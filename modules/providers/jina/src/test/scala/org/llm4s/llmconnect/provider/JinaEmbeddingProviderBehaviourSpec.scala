package org.llm4s.llmconnect.provider

import org.llm4s.error.NetworkError
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient, MultipartPart }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/**
 * Behaviour the happy-path spec does not pin: exact request JSON per task, URL joining, the full
 * status-code table, degenerate inputs, secret handling, and the real HTTP wire (a local stub
 * server, no network).
 */
class JinaEmbeddingProviderBehaviourSpec extends AnyFlatSpec with Matchers {

  private val modelCfg  = EmbeddingModelConfig("jina-embeddings-v3", 1024)
  private val secretKey = "jina-SECRET-key-123"

  private def cfg(base: String = "http://jina-test") =
    EmbeddingProviderConfig(baseUrl = base, model = "jina-embeddings-v3", apiKey = secretKey)

  private def ok(vectors: Seq[Seq[Double]]): HttpResponse = {
    val items = vectors.zipWithIndex.map { case (v, i) =>
      s"""{"object":"embedding","index":$i,"embedding":${v.mkString("[", ",", "]")}}"""
    }
    HttpResponse(
      200,
      s"""{"data":${items.mkString("[", ",", "]")},"usage":{"total_tokens":3,"prompt_tokens":3}}""",
      Map.empty
    )
  }

  private def embed(http: Llm4sHttpClient, texts: Seq[String], task: JinaTask = JinaTask.default) =
    JinaEmbeddingProvider.forTest(cfg(), http, task).embed(EmbeddingRequest(texts, modelCfg))

  "the request body" should "match the golden JSON for every task" in {
    JinaTask.values.foreach { task =>
      val http = new MockHttpClient(ok(Seq(Seq(1.0), Seq(2.0))))
      embed(http, Seq("a \"q\"\n", "ü"), task)
      withClue(task.toString) {
        read(http.lastBody.get) shouldBe read(
          s"""{"input":["a \\"q\\"\\n","ü"],"model":"jina-embeddings-v3","task":"${task.wireName}"}"""
        )
      }
    }
  }

  it should "send exactly input, model and task (no dimensions field)" in {
    val http = new MockHttpClient(ok(Seq(Seq(1.0))))
    embed(http, Seq("a"))
    read(http.lastBody.get).obj.keySet shouldBe Set("input", "model", "task")
  }

  // Per api.jina.ai/openapi.json: EmbeddingsV2Request has no `task`; ClipV2Request.task is the
  // constant `retrieval.query`; EmbeddingsV4Request.task excludes classification and separation.
  private def bodyFor(model: String, task: JinaTask) = {
    val http = new MockHttpClient(ok(Seq(Seq(1.0))))
    val res = JinaEmbeddingProvider
      .forTest(cfg(), http, task)
      .embed(EmbeddingRequest(Seq("a"), EmbeddingModelConfig(model, 1024)))
    (res, http.lastBody.map(read(_)))
  }

  "the task field" should "be omitted for jina-embeddings-v2 models, whose schema has none" in {
    val (res, body) = bodyFor("jina-embeddings-v2-base-en", JinaTask.RetrievalPassage)
    res.isRight shouldBe true
    body.get.obj.keySet shouldBe Set("input", "model")
    res.toOption.get.metadata.contains("task") shouldBe false
  }

  it should "be sent for jina-clip-v2 only as retrieval.query, and omitted for documents" in {
    bodyFor("jina-clip-v2", JinaTask.RetrievalQuery)._2.get("task").str shouldBe "retrieval.query"
    bodyFor("jina-clip-v2", JinaTask.RetrievalPassage)._2.get.obj.keySet shouldBe Set("input", "model")
  }

  it should "be rejected locally for jina-embeddings-v4 when classification or separation" in {
    Seq(JinaTask.Classification, JinaTask.Separation).foreach { t =>
      val http = new MockHttpClient(ok(Seq(Seq(1.0))))
      val res = JinaEmbeddingProvider
        .forTest(cfg(), http, t)
        .embed(EmbeddingRequest(Seq("a"), EmbeddingModelConfig("jina-embeddings-v4", 2048)))
      res.left.toOption.get.message should include(t.wireName)
      http.lastBody shouldBe None
    }
    bodyFor("jina-embeddings-v4", JinaTask.TextMatching)._2.get("task").str shouldBe "text-matching"
  }

  "URL joining" should "yield <base>/embeddings for every base-URL spelling" in {
    val table = Seq(
      "https://api.jina.ai/v1"   -> "https://api.jina.ai/v1/embeddings",
      "https://api.jina.ai/v1/"  -> "https://api.jina.ai/v1/embeddings",
      "http://localhost:8080"    -> "http://localhost:8080/embeddings",
      "http://localhost:8080/"   -> "http://localhost:8080/embeddings",
      "https://proxy.local/a/b/" -> "https://proxy.local/a/b/embeddings"
    )
    table.foreach { case (base, expected) =>
      val http = new MockHttpClient(ok(Seq(Seq(1.0))))
      JinaEmbeddingProvider.forTest(cfg(base), http).embed(EmbeddingRequest(Seq("a"), modelCfg))
      withClue(base)(http.lastUrl.get shouldBe expected)
    }
  }

  "every HTTP status" should "map to an EmbeddingError carrying that status as its code" in {
    val table = Seq(
      400 -> "bad request",
      401 -> "Authentication failed",
      402 -> "payment",
      403 -> "forbidden",
      404 -> "no such model",
      413 -> "too large",
      422 -> "unprocessable",
      429 -> "Rate limit exceeded",
      500 -> "boom",
      502 -> "bad gateway",
      503 -> "unavailable",
      504 -> "timeout"
    )
    table.foreach { case (status, fragment) =>
      val err = embed(new MockHttpClient(HttpResponse(status, fragment, Map.empty)), Seq("a")).left.toOption.get
      withClue(status.toString) {
        err.code shouldBe Some(status.toString)
        err.message should include(fragment)
        err.context("provider") shouldBe "jina"
      }
    }
  }

  it should "treat a 2xx other than 200 as an error rather than parse it" in {
    val r = embed(new MockHttpClient(HttpResponse(202, """{"data":[]}""", Map.empty)), Seq("a"))
    r.left.toOption.get.code shouldBe Some("202")
  }

  it should "truncate an enormous error body" in {
    val err = embed(new MockHttpClient(HttpResponse(500, "x" * 100000, Map.empty)), Seq("a")).left.toOption.get
    err.message.length should be < 4000
  }

  "degenerate input" should "still produce a single well-formed request for an empty list" in {
    val http = new MockHttpClient(HttpResponse(200, """{"data":[]}""", Map.empty))
    embed(http, Seq.empty).toOption.get.embeddings shouldBe empty
    read(http.lastBody.get)("input").arr shouldBe empty
  }

  it should "pass empty and blank strings through unchanged and in position" in {
    val http = new MockHttpClient(ok(Seq(Seq(1.0), Seq(2.0), Seq(3.0))))
    embed(http, Seq("", "   ", "x"))
    read(http.lastBody.get)("input").arr.map(_.str).toSeq shouldBe Seq("", "   ", "x")
  }

  it should "send a very large batch and a very long text as exactly one call, without splitting" in {
    val many = (1 to 2048).map(i => s"t$i")
    val http = new MockHttpClient(ok(many.map(_ => Seq(0.5))))
    embed(http, many).toOption.get.embeddings should have size 2048
    http.postCallCount shouldBe 1

    val long  = "w " * 500000
    val http2 = new MockHttpClient(ok(Seq(Seq(0.5))))
    embed(http2, Seq(long))
    read(http2.lastBody.get)("input")(0).str shouldBe long
    http2.postCallCount shouldBe 1
  }

  "the response" should "keep numeric precision and cope with integer-valued numbers" in {
    val body = """{"data":[{"index":0,"embedding":[1,-2,0.123456789012345,1e-7]}]}"""
    embed(new MockHttpClient(HttpResponse(200, body, Map.empty)), Seq("a")).toOption.get.embeddings shouldBe
      Seq(Vector(1.0, -2.0, 0.123456789012345, 1e-7))
  }

  it should "be a parse error, not an exception, when an embedding holds a non-number" in {
    val body = """{"data":[{"index":0,"embedding":["x"]}]}"""
    embed(new MockHttpClient(HttpResponse(200, body, Map.empty)), Seq("a")).left.toOption.get.message should
      startWith("Parsing error")
  }

  "secrets" should "stay out of the config's toString and of transport errors" in {
    (cfg().toString should not).include(secretKey)
    val failing = new Llm4sHttpClient {
      private def fail[A]: Result[A] = Left(NetworkError("connect timed out", None, "http://jina-test/embeddings"))
      def get(u: String, h: Map[String, String], p: Map[String, String], t: FiniteDuration)          = fail
      def post(u: String, h: Map[String, String], b: String, t: FiniteDuration)                      = fail
      def postBytes(u: String, h: Map[String, String], d: Array[Byte], t: FiniteDuration)            = fail
      def postMultipart(u: String, h: Map[String, String], p: Seq[MultipartPart], t: FiniteDuration) = fail
      def put(u: String, h: Map[String, String], b: String, t: FiniteDuration)                       = fail
      def delete(u: String, h: Map[String, String], t: FiniteDuration)                               = fail
      def postRaw(u: String, h: Map[String, String], b: String, t: FiniteDuration)                   = fail
      def postStream(u: String, h: Map[String, String], b: String, t: FiniteDuration)                = fail
    }
    val err = embed(failing, Seq("a")).left.toOption.get
    (err.toString should not).include(secretKey)
    (err.message should not).include(secretKey)
  }

  "the real HTTP wire" should "post JSON with the Bearer key to /embeddings, with and without a trailing slash" in {
    val seen = new AtomicReference[(String, String, String, String)]()
    LocalProviderTestServer.withServer("/v1/embeddings") { ex =>
      val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      seen.set((ex.getRequestMethod, ex.getRequestURI.getPath, ex.getRequestHeaders.getFirst("Authorization"), body))
      LocalProviderTestServer.sendJsonResponse(ex, 200, ok(Seq(Seq(0.25, 0.5))).body)
    } { base =>
      Seq(s"$base/v1", s"$base/v1/").foreach { b =>
        val r = JinaEmbeddingProvider
          .fromConfig(cfg(b), JinaTask.RetrievalQuery)
          .embed(EmbeddingRequest(Seq("hi"), modelCfg))
        withClue(b)(r.toOption.get.embeddings shouldBe Seq(Vector(0.25, 0.5)))
        val (m, p, auth, body) = seen.get
        m shouldBe "POST"
        p shouldBe "/v1/embeddings"
        auth shouldBe s"Bearer $secretKey"
        read(body)("task").str shouldBe "retrieval.query"
      }
    }
  }

  it should "map a real 429 and a real 500 from the server" in {
    Seq(429 -> "Rate limit exceeded", 500 -> "boom").foreach { case (status, frag) =>
      LocalProviderTestServer.withServer("/v1/embeddings")(ex =>
        LocalProviderTestServer.sendJsonResponse(ex, status, "boom")
      ) { base =>
        val err = JinaEmbeddingProvider
          .fromConfig(cfg(s"$base/v1"))
          .embed(EmbeddingRequest(Seq("hi"), modelCfg))
          .left
          .toOption
          .get
        err.code shouldBe Some(status.toString)
        err.message should include(if (status == 429) frag else "boom")
      }
    }
  }

  it should "return a transport error when nothing listens" in {
    val err = JinaEmbeddingProvider
      .fromConfig(cfg("http://localhost:1/v1"))
      .embed(EmbeddingRequest(Seq("hi"), modelCfg))
      .left
      .toOption
      .get
    err.code shouldBe None
    err.message should startWith("HTTP request failed")
    (err.message should not).include(secretKey)
  }

  "dimension handling" should "pass vectors through even when their length differs from the requested config (documented gap, as Voyage)" in {
    val r = embed(new MockHttpClient(ok(Seq(Seq(1.0, 2.0)))), Seq("a"))
    r.toOption.get.embeddings.head should have size 2 // modelCfg says 1024
  }
}
