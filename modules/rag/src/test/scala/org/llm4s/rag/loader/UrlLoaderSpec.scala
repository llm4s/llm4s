package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UrlLoaderSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // SSRF Protection
  // ==========================================================================

  "UrlLoader" should "reject blocked URLs before making requests" in {
    val loader = UrlLoader("http://169.254.169.254/latest/meta-data/")
    val result = loader.load().next()

    result shouldBe a[LoadResult.Failure]

    result match {
      case LoadResult.Failure(_, error, _) =>
        error.message should include("blocked range")
      case _ => fail("Expected a failure for blocked URL")
    }
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
    val loader = UrlLoader.withAuth(Seq("http://example.com"), "my-token")

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
      .withAuth(Seq("http://a.com"), "token")
      .withHeaders(Map("Accept" -> "text/html"))

    loader.headers should contain("Authorization" -> "Bearer token")
    loader.headers should contain("Accept" -> "text/html")
  }

  "UrlLoader.withTimeout" should "set timeout" in {
    val loader = UrlLoader("http://a.com").withTimeout(5000)

    loader.timeoutMs shouldBe 5000
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

    loader.timeoutMs shouldBe 30000
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
}
