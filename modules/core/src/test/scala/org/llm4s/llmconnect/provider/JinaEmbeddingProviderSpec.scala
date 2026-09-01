package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import org.slf4j.LoggerFactory

class JinaEmbeddingProviderSpec extends AnyFlatSpec with Matchers with MockFactory {

  override def withFixture(test: NoArgTest): Outcome = {
    val logger = LoggerFactory
      .getLogger("org.llm4s.llmconnect.provider.JinaEmbeddingProvider$$anon$1")
      .asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try super.withFixture(test)
    finally logger.setLevel(previous)
  }

  private val cfg = EmbeddingProviderConfig(
    baseUrl = "https://api.jina.ai/v1",
    model = "jina-embeddings-v3",
    apiKey = "test-key"
  )
  private val modelCfg = EmbeddingModelConfig("jina-embeddings-v3", 1024)
  private val req      = EmbeddingRequest(Seq("hello", "world"), modelCfg)

  private def httpOk(body: String): HttpResponse               = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int, body: String): HttpResponse = HttpResponse(status, body, Map.empty)

  "JinaEmbeddingProvider" should "parse a successful embedding response with two vectors" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body =
      """{"data":[{"embedding":[0.1,0.2,0.3]},{"embedding":[0.4,0.5,0.6]}]}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings should have size 2
    resp.embeddings(0) shouldBe Vector(0.1, 0.2, 0.3)
    resp.embeddings(1) shouldBe Vector(0.4, 0.5, 0.6)
  }

  it should "return embeddings in the correct order for multiple inputs" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body =
      """{"data":[{"embedding":[1.0]},{"embedding":[2.0]},{"embedding":[3.0]}]}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val multiReq = EmbeddingRequest(Seq("a", "b", "c"), modelCfg)
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(multiReq)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings(0)(0) shouldBe 1.0
    resp.embeddings(1)(0) shouldBe 2.0
    resp.embeddings(2)(0) shouldBe 3.0
  }

  it should "include model metadata in the response" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"data":[{"embedding":[0.1]},{"embedding":[0.2]}]}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isRight shouldBe true
    result.toOption.get.metadata("provider") shouldBe "jina"
    result.toOption.get.metadata("model") shouldBe "jina-embeddings-v3"
    result.toOption.get.metadata("count") shouldBe "2"
  }

  it should "include task parameter in outbound request payload" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"data":[{"embedding":[0.1]},{"embedding":[0.2]}]}"""

    var capturedPayload: String = ""
    (mockHttp.post _).when(*, *, *, *).onCall { (_, _, payload, _) =>
      capturedPayload = payload
      httpOk(body)
    }

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isRight shouldBe true
    capturedPayload should include(""""task":"retrieval.passage"""")
  }

  it should "return EmbeddingError with code '429' on HTTP 429" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(429, "Rate limit exceeded"))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe Some("429")
    result.left.toOption.get.context.get("provider") shouldBe Some("jina")
  }

  it should "return EmbeddingError with code '401' on HTTP 401" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(401, "Unauthorized"))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe Some("401")
    result.left.toOption.get.context.get("provider") shouldBe Some("jina")
  }

  it should "return EmbeddingError with code '500' on HTTP 500" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(500, "Internal Server Error"))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe Some("500")
  }

  it should "return EmbeddingError on malformed JSON response" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpOk("not-json{{{"))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.context.get("provider") shouldBe Some("jina")
  }

  it should "return EmbeddingError on missing 'data' field in JSON" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpOk("""{"result": []}"""))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
  }
}
