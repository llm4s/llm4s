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

class CohereEmbeddingProviderSpec extends AnyFlatSpec with Matchers with MockFactory {

  override def withFixture(test: NoArgTest): Outcome = {
    val logger = LoggerFactory
      .getLogger("org.llm4s.llmconnect.provider.CohereEmbeddingProvider$$anon$1")
      .asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try super.withFixture(test)
    finally logger.setLevel(previous)
  }

  private val cfg = EmbeddingProviderConfig(
    baseUrl = "http://cohere-test",
    model = "embed-multilingual-v3.0",
    apiKey = "test-key"
  )
  private val modelCfg = EmbeddingModelConfig("embed-multilingual-v3.0", 1024)
  private val req      = EmbeddingRequest(Seq("hello"), modelCfg)

  private def httpOk(body: String): HttpResponse               = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int, body: String): HttpResponse = HttpResponse(status, body, Map.empty)

  "CohereEmbeddingProvider" should "parse a successful single-text embedding response" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = "{" + "\"embeddings\": [{\"embedding\": [0.1, 0.2, 0.3]}] }"
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val provider = CohereEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings should have size 1
    resp.embeddings.head shouldBe Vector(0.1, 0.2, 0.3)
  }

  it should "parse a successful batch embedding response" in {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = "{\"embeddings\":[{\"embedding\":[1.0]},{\"embedding\":[2.0]},{\"embedding\":[3.0]}]}"
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val multiReq = EmbeddingRequest(Seq("a", "b", "c"), modelCfg)
    val provider = CohereEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(multiReq)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings(0)(0) shouldBe 1.0
    resp.embeddings(1)(0) shouldBe 2.0
    resp.embeddings(2)(0) shouldBe 3.0
  }

  it should "return RateLimitError on HTTP 429" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(429, "Too many requests"))

    val provider = CohereEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ConfigurationError on HTTP 401" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(401, "Unauthorized"))

    val provider = CohereEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
  }
}
