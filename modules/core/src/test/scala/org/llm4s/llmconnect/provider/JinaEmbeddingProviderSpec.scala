package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, Image, MultimediaEmbeddingRequest }
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

class JinaEmbeddingProviderSpec extends AnyFlatSpec with Matchers {

  // Suppress noisy provider logs during tests
  override def withFixture(test: NoArgTest): Outcome = {
    val loggerName = "org.llm4s.llmconnect.provider.JinaEmbeddingProvider$$anon$1"
    val logger     = org.slf4j.LoggerFactory.getLogger(loggerName).asInstanceOf[LBLogger]
    val previous   = logger.getLevel
    logger.setLevel(Level.OFF)
    try super.withFixture(test)
    finally logger.setLevel(previous)
  }

  // ---- test fixtures -------------------------------------------------------

  private val cfg = EmbeddingProviderConfig(
    baseUrl = "http://jina-test",
    model = "jina-embeddings-v3",
    apiKey = "jina-test-key"
  )

  private val modelCfg = EmbeddingModelConfig("jina-embeddings-v3", 1024)
  private val req      = EmbeddingRequest(Seq("hello", "world"), modelCfg)

  private def httpOk(body: String): HttpResponse               = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int, body: String): HttpResponse = HttpResponse(status, body, Map.empty)

  private def embeddingBody(vectors: Seq[Seq[Double]]): String = {
    val dataItems = vectors
      .map { v =>
        val floats = v.mkString("[", ",", "]")
        s"""{"embedding":$floats}"""
      }
      .mkString("[", ",", "]")
    s"""{"data":$dataItems}"""
  }

  // ---- happy-path tests ----------------------------------------------------

  "JinaEmbeddingProvider" should "embed a single text and return the correct vector" in {
    val body     = embeddingBody(Seq(Seq(0.1, 0.2, 0.3)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val singleReq = EmbeddingRequest(Seq("hello"), modelCfg)
    val provider  = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result    = provider.embed(singleReq)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings should have size 1
    resp.embeddings(0) shouldBe Vector(0.1, 0.2, 0.3)
  }

  it should "embed a batch of texts and return all vectors in order" in {
    val body     = embeddingBody(Seq(Seq(1.0), Seq(2.0), Seq(3.0)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val multiReq = EmbeddingRequest(Seq("a", "b", "c"), modelCfg)
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(multiReq)

    result.isRight shouldBe true
    val resp = result.toOption.get
    resp.embeddings should have size 3
    resp.embeddings(0)(0) shouldBe 1.0
    resp.embeddings(1)(0) shouldBe 2.0
    resp.embeddings(2)(0) shouldBe 3.0
  }

  it should "include correct metadata in the response" in {
    val body     = embeddingBody(Seq(Seq(0.5), Seq(0.6)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isRight shouldBe true
    val meta = result.toOption.get.metadata
    meta("provider") shouldBe "jina"
    meta("model") shouldBe "jina-embeddings-v3"
    meta("task") shouldBe JinaEmbeddingProvider.DefaultTask
    meta("count") shouldBe "2"
  }

  // ---- task parameter tests ------------------------------------------------

  it should "send the default task (text-matching) when no task suffix is in the model name" in {
    val body     = embeddingBody(Seq(Seq(0.1, 0.2)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    provider.embed(req)

    val sentBody = mockHttp.lastBody.get
    val json     = read(sentBody)
    json("task").str shouldBe "text-matching"
  }

  it should "send retrieval.passage task when encoded in the model name" in {
    val passageCfg  = EmbeddingProviderConfig("http://jina-test", "jina-embeddings-v3", "jina-test-key")
    val passageModel = EmbeddingModelConfig("jina-embeddings-v3::retrieval.passage", 1024)
    val passageReq   = EmbeddingRequest(Seq("long document text"), passageModel)

    val body     = embeddingBody(Seq(Seq(0.9, 0.8)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(passageCfg, mockHttp)
    val result   = provider.embed(passageReq)

    result.isRight shouldBe true
    val sentBody = mockHttp.lastBody.get
    val json     = read(sentBody)
    json("task").str shouldBe "retrieval.passage"
    json("model").str shouldBe "jina-embeddings-v3"
  }

  it should "send retrieval.query task when encoded in the model name" in {
    val queryModel = EmbeddingModelConfig("jina-embeddings-v3::retrieval.query", 1024)
    val queryReq   = EmbeddingRequest(Seq("search query"), queryModel)

    val body     = embeddingBody(Seq(Seq(0.3, 0.4)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(queryReq)

    result.isRight shouldBe true
    val sentBody = mockHttp.lastBody.get
    val json     = read(sentBody)
    json("task").str shouldBe "retrieval.query"
  }

  it should "send the correct URL with Bearer auth header" in {
    val body     = embeddingBody(Seq(Seq(0.1)))
    val mockHttp = new MockHttpClient(httpOk(body))

    val singleReq = EmbeddingRequest(Seq("test"), modelCfg)
    JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(singleReq)

    mockHttp.lastUrl.get shouldBe "http://jina-test/v1/embeddings"
    mockHttp.lastHeaders.get("Authorization") shouldBe "Bearer jina-test-key"
    mockHttp.lastHeaders.get("Content-Type") shouldBe "application/json"
  }

  it should "send the input texts in the request body" in {
    val body     = embeddingBody(Seq(Seq(0.1), Seq(0.2)))
    val mockHttp = new MockHttpClient(httpOk(body))

    JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(req)

    val json = read(mockHttp.lastBody.get)
    json("input").arr.map(_.str).toSeq shouldBe Seq("hello", "world")
  }

  // ---- error handling tests ------------------------------------------------

  it should "return EmbeddingError with code 401 on HTTP 401" in {
    val mockHttp = new MockHttpClient(httpErr(401, "Unauthorized"))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.code shouldBe Some("401")
    err.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError with code 429 on HTTP 429 (rate limit)" in {
    val mockHttp = new MockHttpClient(httpErr(429, "Rate limit exceeded"))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.code shouldBe Some("429")
    err.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError with code 500 on HTTP 500" in {
    val mockHttp = new MockHttpClient(httpErr(500, "Internal Server Error"))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.code shouldBe Some("500")
    err.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError on malformed JSON response" in {
    val mockHttp = new MockHttpClient(httpOk("not-valid-json{{{"))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError on missing 'data' field in JSON" in {
    val mockHttp = new MockHttpClient(httpOk("""{"result": []}"""))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)
    val result   = provider.embed(req)

    result.isLeft shouldBe true
    result.left.toOption.get.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError on network failure" in {
    val failingHttp = new FailingHttpClient(new java.io.IOException("connection refused"))
    val provider    = JinaEmbeddingProvider.forTest(cfg, failingHttp)
    val result      = provider.embed(req)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.code shouldBe None
    err.message should include("connection refused")
    err.context("provider") shouldBe "jina"
  }

  it should "return EmbeddingError for interrupted request" in {
    val interruptedException = new InterruptedException("interrupted")
    val failingHttp          = new FailingHttpClient(interruptedException)
    val provider             = JinaEmbeddingProvider.forTest(cfg, failingHttp)
    val result               = provider.embed(req)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.message should include("interrupted")
    err.context("provider") shouldBe "jina"
  }

  // ---- task extraction helpers ---------------------------------------------

  "JinaEmbeddingProvider.extractTask" should "return default task for plain model name" in {
    JinaEmbeddingProvider.extractTask("jina-embeddings-v3") shouldBe JinaEmbeddingProvider.DefaultTask
  }

  it should "extract retrieval.passage from model::task encoding" in {
    JinaEmbeddingProvider.extractTask("jina-embeddings-v3::retrieval.passage") shouldBe "retrieval.passage"
  }

  it should "extract retrieval.query from model::task encoding" in {
    JinaEmbeddingProvider.extractTask("jina-embeddings-v3::retrieval.query") shouldBe "retrieval.query"
  }

  it should "extract text-matching from model::task encoding" in {
    JinaEmbeddingProvider.extractTask("jina-embeddings-v3::text-matching") shouldBe "text-matching"
  }

  "JinaEmbeddingProvider.stripTask" should "return model name unchanged when no task suffix" in {
    JinaEmbeddingProvider.stripTask("jina-embeddings-v3") shouldBe "jina-embeddings-v3"
  }

  it should "strip the task suffix and return only the model name" in {
    JinaEmbeddingProvider.stripTask("jina-embeddings-v3::retrieval.passage") shouldBe "jina-embeddings-v3"
  }

  // ---- multimodal default --------------------------------------------------

  it should "return 501 Not Implemented for multimodal embed by default" in {
    val body     = embeddingBody(Seq(Seq(0.1)))
    val mockHttp = new MockHttpClient(httpOk(body))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp)

    val multiReq = MultimediaEmbeddingRequest(
      inputs = Seq.empty,
      model = modelCfg,
      modality = Image
    )
    val result = provider.embedMultimodal(multiReq)

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe Some("501")
  }
}
