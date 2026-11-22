package org.llm4s.rag.loader

import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class UrlLoaderSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // SSRF Protection
  // ==========================================================================

  "UrlLoader" should "reject blocked URLs before making requests" in {
    val loader = UrlLoader("http://169.254.169.254/latest/meta-data/")
    val result = loader.load().next()

    result shouldBe a[LoadResult.Failure]

    result match {
      case LoadResult.Failure(_, error, _, _) =>
        error.message should include("blocked range")
      case _ => fail("Expected a failure for blocked URL")
    }
  }

  it should "name the document a failed load is about, so RAG.sync keeps it" in {
    val url = "http://169.254.169.254/latest/meta-data/"

    UrlLoader(url).load().next().asInstanceOf[LoadResult.Failure].documentId shouldBe Some(url)
  }

  it should "reject localhost URLs" in {
    val loader = UrlLoader("http://127.0.0.1/secret")
    val result = loader.load().next()

    result shouldBe a[LoadResult.Failure]
  }

  // ==========================================================================
  // Factory Methods
  // ==========================================================================

  "UrlLoader.apply(url)" should "create loader with single URL" in {
    val loader = UrlLoader("http://example.com")

    loader.urls shouldBe Seq("http://example.com")
  }

  "UrlLoader.apply(urls*)" should "create loader with multiple URLs" in {
    val loader = UrlLoader("http://a.com", "http://b.com", "http://c.com")

    loader.urls should have size 3
  }

  "UrlLoader.withAuth" should "set authorization header" in {
    val loader = UrlLoader.withAuth(Seq("http://example.com"), Secret("my-token"))

    loader.headers should contain("Authorization" -> "Bearer my-token")
    loader.urls shouldBe Seq("http://example.com")
  }

  "UrlLoader.withUserAgent" should "set user agent header" in {
    val loader = UrlLoader.withUserAgent(Seq("http://example.com"), "CustomBot/1.0")

    loader.headers should contain("User-Agent" -> "CustomBot/1.0")
  }

  // ==========================================================================
  // Fluent API
  // ==========================================================================

  "UrlLoader.withUrl" should "add a URL" in {
    val loader = UrlLoader("http://a.com").withUrl("http://b.com")

    loader.urls shouldBe Seq("http://a.com", "http://b.com")
  }

  "UrlLoader.withHeaders" should "add headers" in {
    val loader = UrlLoader("http://a.com")
      .withHeaders(Map("Accept" -> "text/html", "X-Custom" -> "value"))

    loader.headers should contain("Accept" -> "text/html")
    loader.headers should contain("X-Custom" -> "value")
  }

  it should "merge with existing headers" in {
    val loader = UrlLoader
      .withAuth(Seq("http://a.com"), Secret("token"))
      .withHeaders(Map("Accept" -> "text/html"))

    loader.headers should contain("Authorization" -> "Bearer token")
    loader.headers should contain("Accept" -> "text/html")
  }

  "UrlLoader.withTimeout" should "set timeout" in {
    val loader = UrlLoader("http://a.com").withTimeout(5000.millis)

    loader.timeout shouldBe 5000.millis
  }

  "UrlLoader.withRetries" should "set retry count" in {
    val loader = UrlLoader("http://a.com").withRetries(5)

    loader.retryCount shouldBe 5
  }

  // ==========================================================================
  // estimatedCount and description
  // ==========================================================================

  "UrlLoader.estimatedCount" should "return the number of URLs" in {
    val loader = UrlLoader("http://a.com", "http://b.com")

    loader.estimatedCount shouldBe Some(2)
  }

  "UrlLoader.description" should "include URL count" in {
    val loader = UrlLoader("http://a.com", "http://b.com", "http://c.com")

    loader.description should include("3 URLs")
  }

  // ==========================================================================
  // Default values
  // ==========================================================================

  "UrlLoader defaults" should "have sensible values" in {
    val loader = UrlLoader("http://a.com")

    loader.timeout shouldBe 30000.millis
    loader.retryCount shouldBe 2
    loader.headers shouldBe empty
    loader.metadata shouldBe empty
  }

  // ==========================================================================
  // Multiple blocked URLs
  // ==========================================================================

  "UrlLoader with multiple URLs" should "produce results for each URL" in {
    val loader = UrlLoader(
      "http://169.254.169.254/meta",
      "http://10.0.0.1/internal"
    )

    val results = loader.load().toList
    results should have size 2
    results.foreach(_ shouldBe a[LoadResult.Failure])
  }

  it should "mask credential-named headers in its toString (#1932)" in {
    val loader = UrlLoader(
      urls = Seq("https://docs.example.com/page"),
      headers = Map("Authorization" -> "Bearer opaque-loader-token-0001", "Accept" -> "text/html")
    )
    val shown = loader.toString
    (shown should not).include("opaque-loader-token-0001")
    shown should include("Authorization -> ***")
    shown should include("Accept -> text/html")
    shown should include("https://docs.example.com/page")
  }

  // ==========================================================================
  // URL policy (#2724)
  // ==========================================================================

  import LoopbackHttpServer.*

  "UrlLoader with a UrlPolicy" should "load from a host the policy allows, at the address it returned" in {
    val page = Reply(
      200,
      "# Title",
      Map("Content-Type" -> "text/markdown", "ETag" -> "\"e1\"", "Last-Modified" -> "Wed, 21 Oct 2015 07:28:00 GMT")
    )
    withServer("/doc.md" -> page) { server =>
      val url    = s"${server.url("docs.test")}/doc.md"
      val policy = loopbackPolicy.withResolver(fakeDns("docs.test"))
      val result = UrlLoader(url).withUrlPolicy(policy).withHeaders(Map("X-Key" -> "k")).load().next()

      val doc = result.asInstanceOf[LoadResult.Success].document
      doc.content shouldBe "# Title"
      doc.metadata("content_type") shouldBe "text/markdown"
      doc.metadata("etag") shouldBe "\"e1\""
      doc.metadata("lastModified") shouldBe "1445412480000"
      doc.version.flatMap(_.timestamp) shouldBe Some(1445412480000L)
      doc.hints shouldBe Some(DocumentHints.markdown)

      val seen = server.requests.head
      seen.host shouldBe Some(s"docs.test:${server.port}")
      seen.userAgent shouldBe Some("LLM4S-RAG/1.0")
      seen.headers.get("x-key") shouldBe Some("k")
    }
  }

  it should "send the caller's User-Agent instead of its own" in {
    withServer("/p" -> Reply(200, "x")) { server =>
      UrlLoader.withUserAgent(Seq(s"${server.baseUrl}/p"), "mine/2").withUrlPolicy(loopbackPolicy).load().next()
      server.requests.map(_.userAgent) shouldBe Seq(Some("mine/2"))
    }
  }

  it should "fail a non-200 answer, naming the document" in {
    withServer() { server =>
      val url    = s"${server.baseUrl}/gone"
      val result = UrlLoader(url).withUrlPolicy(loopbackPolicy).load().next().asInstanceOf[LoadResult.Failure]
      result.error.message should startWith("HTTP 404")
      result.documentId shouldBe Some(url)
    }
  }

  it should "check every redirect hop against the policy" in {
    withServer("/start" -> redirect("http://other.test/secret")) { server =>
      val policy = loopbackPolicy.withResolver(fakeDns("docs.test", "other.test")).withAllowedHosts(Set("docs.test"))
      val result =
        UrlLoader(s"${server.url("docs.test")}/start").withUrlPolicy(policy).load().next()
      result.asInstanceOf[LoadResult.Failure].error.message should include("Redirect blocked by SSRF protection")
      server.paths shouldBe Seq("/start")
    }
  }

  it should "refuse a loopback server under the default policy, without a request" in {
    withServer("/p" -> Reply(200, "x")) { server =>
      UrlLoader(s"${server.baseUrl}/p").load().next() shouldBe a[LoadResult.Failure]
      server.requests shouldBe empty
    }
  }
}
