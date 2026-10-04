package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, Image, MultimediaEmbeddingRequest }
import org.scalamock.scalatest.MockFactory
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

import scala.concurrent.duration.*

class JinaEmbeddingProviderSpec extends AnyFlatSpec with Matchers with MockFactory {

  // Suppress noisy provider logs during tests
  override def withFixture(test: NoArgTest): Outcome = {
    val loggerName = "org.llm4s.llmconnect.provider.JinaEmbeddingProvider$$anon$1"
    val logger     = org.slf4j.LoggerFactory.getLogger(loggerName).asInstanceOf[LBLogger]
    val previous   = logger.getLevel
    logger.setLevel(Level.OFF)
    try super.withFixture(test)
    finally logger.setLevel(previous)
  }

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
    val dataItems = vectors.map(v => s"""{"embedding":${v.mkString("[", ",", "]")}}""").mkString("[", ",", "]")
    s"""{"data":$dataItems}"""
  }

  "JinaEmbeddingProvider" should "embed a single text and return the correct vector" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1, 0.2, 0.3)))))

    val result = JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(EmbeddingRequest(Seq("hello"), modelCfg))

    result.toOption.get.embeddings shouldBe Seq(Vector(0.1, 0.2, 0.3))
  }

  it should "embed a batch of texts in one call and return all vectors in order" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(1.0), Seq(2.0), Seq(3.0)))))

    val result = JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(EmbeddingRequest(Seq("a", "b", "c"), modelCfg))

    result.toOption.get.embeddings shouldBe Seq(Vector(1.0), Vector(2.0), Vector(3.0))
    mockHttp.postCallCount shouldBe 1
  }

  it should "include provider, model, task and count in the response metadata" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.5), Seq(0.6)))))

    val meta = JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(req).toOption.get.metadata

    meta("provider") shouldBe "jina"
    meta("model") shouldBe "jina-embeddings-v3"
    meta("task") shouldBe "retrieval.passage"
    meta("count") shouldBe "2"
  }

  "the task setting" should "default to retrieval.passage, since indexing is the batch case" in {
    JinaTask.default shouldBe JinaTask.RetrievalPassage
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1), Seq(0.2)))))

    JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(req)

    read(mockHttp.lastBody.get)("task").str shouldBe "retrieval.passage"
  }

  it should "send each task's wire name" in {
    val expected = Map(
      JinaTask.RetrievalQuery   -> "retrieval.query",
      JinaTask.RetrievalPassage -> "retrieval.passage",
      JinaTask.TextMatching     -> "text-matching",
      JinaTask.Classification   -> "classification",
      JinaTask.Separation       -> "separation"
    )
    JinaTask.values.toSet shouldBe expected.keySet
    expected.foreach { case (task, wire) =>
      val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1), Seq(0.2)))))
      JinaEmbeddingProvider.forTest(cfg, mockHttp, task).embed(req)
      withClue(task.toString)(read(mockHttp.lastBody.get)("task").str shouldBe wire)
    }
  }

  it should "reach the response metadata and leave the model name untouched" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.3, 0.4)))))
    val provider = JinaEmbeddingProvider.forTest(cfg, mockHttp, JinaTask.RetrievalQuery)

    val result = provider.embed(EmbeddingRequest(Seq("search query"), modelCfg))

    result.toOption.get.metadata("task") shouldBe "retrieval.query"
    read(mockHttp.lastBody.get)("model").str shouldBe "jina-embeddings-v3"
  }

  "the request" should "post to <baseUrl>/embeddings with a Bearer key" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1)))))

    JinaEmbeddingProvider
      .forTest(cfg.copy(baseUrl = "https://api.jina.ai/v1/"), mockHttp)
      .embed(EmbeddingRequest(Seq("test"), modelCfg))

    mockHttp.lastUrl.get shouldBe "https://api.jina.ai/v1/embeddings"
    mockHttp.lastHeaders.get("Authorization") shouldBe "Bearer jina-test-key"
    mockHttp.lastHeaders.get("Content-Type") shouldBe "application/json"
    mockHttp.lastTimeout shouldBe Some(120.seconds)
  }

  it should "carry the input texts and the model" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1), Seq(0.2)))))

    JinaEmbeddingProvider.forTest(cfg, mockHttp).embed(req)

    val json = read(mockHttp.lastBody.get)
    json("input").arr.map(_.str).toSeq shouldBe Seq("hello", "world")
    json("model").str shouldBe "jina-embeddings-v3"
  }

  "errors" should "be EmbeddingError code 401 on HTTP 401, naming the provider" in {
    val err =
      JinaEmbeddingProvider.forTest(cfg, new MockHttpClient(httpErr(401, "Unauthorized"))).embed(req).left.toOption.get

    err.code shouldBe Some("401")
    err.message should include("Authentication failed")
    err.context("provider") shouldBe "jina"
  }

  it should "be code 429 on HTTP 429 (rate limit)" in {
    val err =
      JinaEmbeddingProvider.forTest(cfg, new MockHttpClient(httpErr(429, "slow down"))).embed(req).left.toOption.get

    err.code shouldBe Some("429")
    err.message should include("Rate limit exceeded")
  }

  it should "be the status code on any other HTTP failure" in {
    val err = JinaEmbeddingProvider.forTest(cfg, new MockHttpClient(httpErr(500, "boom"))).embed(req).left.toOption.get

    err.code shouldBe Some("500")
    err.message shouldBe "boom"
  }

  it should "be a parse error on malformed JSON" in {
    val err =
      JinaEmbeddingProvider.forTest(cfg, new MockHttpClient(httpOk("not-valid-json{{{"))).embed(req).left.toOption.get

    err.message should startWith("Parsing error")
    err.context("provider") shouldBe "jina"
  }

  it should "be a parse error when the data field is missing" in {
    val err =
      JinaEmbeddingProvider.forTest(cfg, new MockHttpClient(httpOk("""{"result": []}"""))).embed(req).left.toOption.get

    err.message should startWith("Parsing error")
  }

  it should "be a returned error, not an exception, on a transport failure" in {
    val http = stub[Llm4sHttpClient]
    (http.post _)
      .when(*, *, *, *)
      .returns(Left(org.llm4s.error.NetworkError("connection refused", None, "http://jina-test/embeddings")))

    val err = JinaEmbeddingProvider.forTest(cfg, http).embed(req).left.toOption.get

    err.code shouldBe None
    err.message should include("connection refused")
    err.context("provider") shouldBe "jina"
  }

  "multimodal embedding" should "be 501 Not Implemented, as Jina text embeddings are text only" in {
    val mockHttp = new MockHttpClient(httpOk(embeddingBody(Seq(Seq(0.1)))))
    val result = JinaEmbeddingProvider
      .forTest(cfg, mockHttp)
      .embedMultimodal(MultimediaEmbeddingRequest(inputs = Seq.empty, model = modelCfg, modality = Image))

    result.left.toOption.get.code shouldBe Some("501")
    mockHttp.postCallCount shouldBe 0
  }
}
