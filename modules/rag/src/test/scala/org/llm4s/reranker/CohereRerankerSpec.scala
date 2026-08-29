package org.llm4s.reranker

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CohereRerankerSpec extends AnyFlatSpec with Matchers {

  "RerankRequest" should "create with query and documents" in {
    val request = RerankRequest(
      query = "What is Scala?",
      documents = Seq("Scala is a programming language", "Python is also popular")
    )

    request.query shouldBe "What is Scala?"
    request.documents should have size 2
    request.topK shouldBe None
    request.returnDocuments shouldBe true
  }

  it should "support topK parameter" in {
    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2", "doc3"),
      topK = Some(2)
    )

    request.topK shouldBe Some(2)
  }

  "RerankResponse" should "store results and metadata" in {
    val results = Seq(
      RerankResult(index = 0, score = 0.95, document = "doc1"),
      RerankResult(index = 2, score = 0.80, document = "doc3")
    )

    val response = RerankResponse(
      results = results,
      metadata = Map("provider" -> "cohere", "model" -> "rerank-english-v3.0")
    )

    response.results should have size 2
    response.results.head.score shouldBe 0.95
    response.metadata("provider") shouldBe "cohere"
  }

  "RerankResult" should "preserve index, score, and document" in {
    val result = RerankResult(
      index = 1,
      score = 0.87,
      document = "Test document content"
    )

    result.index shouldBe 1
    result.score shouldBe 0.87
    result.document shouldBe "Test document content"
  }

  "RerankProviderConfig" should "store configuration" in {
    val config = RerankProviderConfig(
      baseUrl = "https://api.cohere.com",
      apiKey = "test-key",
      model = "rerank-english-v3.0"
    )

    config.baseUrl shouldBe "https://api.cohere.com"
    config.apiKey shouldBe "test-key"
    config.model shouldBe "rerank-english-v3.0"
  }

  "CohereReranker" should "have correct default values" in {
    CohereReranker.DEFAULT_BASE_URL shouldBe "https://api.cohere.com"
    CohereReranker.DEFAULT_MODEL shouldBe "rerank-english-v3.0"
  }

  it should "create from individual parameters" in {
    val reranker = CohereReranker("test-api-key")

    reranker shouldBe a[CohereReranker]
  }

  it should "create from config" in {
    val config = RerankProviderConfig(
      baseUrl = "https://custom.api.com",
      apiKey = "test-key",
      model = "custom-model"
    )

    val reranker = CohereReranker(config)

    reranker shouldBe a[CohereReranker]
  }

  "RerankerFactory" should "create Cohere reranker" in {
    val reranker = RerankerFactory.cohere("test-api-key")

    reranker shouldBe a[Reranker]
  }

  it should "parse backend from string" in {
    RerankerFactory.Backend.fromString("cohere") shouldBe Some(RerankerFactory.Backend.Cohere)
    RerankerFactory.Backend.fromString("COHERE") shouldBe Some(RerankerFactory.Backend.Cohere)
    RerankerFactory.Backend.fromString("llm") shouldBe Some(RerankerFactory.Backend.LLM)
    RerankerFactory.Backend.fromString("none") shouldBe Some(RerankerFactory.Backend.None)
    RerankerFactory.Backend.fromString("") shouldBe Some(RerankerFactory.Backend.None)
    RerankerFactory.Backend.fromString("unknown") shouldBe None
  }

  "RerankerFactory.passthrough" should "preserve original order with decreasing scores" in {
    val request = RerankRequest(
      query = "test",
      documents = Seq("first", "second", "third")
    )

    val response = RerankerFactory.passthrough.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results

    results should have size 3
    results(0).index shouldBe 0
    results(1).index shouldBe 1
    results(2).index shouldBe 2

    // Scores should be decreasing
    results(0).score should be > results(1).score
    results(1).score should be > results(2).score

    // Documents should be preserved
    results(0).document shouldBe "first"
    results(1).document shouldBe "second"
    results(2).document shouldBe "third"
  }

  it should "respect topK parameter" in {
    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2", "doc3", "doc4", "doc5"),
      topK = Some(2)
    )

    val response = RerankerFactory.passthrough.rerank(request)

    response.isRight shouldBe true
    response.toOption.get.results should have size 2
  }

  "RerankError" should "extend LLMError" in {
    val error = RerankError(
      code = Some("401"),
      message = "Invalid API key",
      provider = "cohere"
    )

    error shouldBe a[org.llm4s.error.LLMError]
    error.code shouldBe Some("401")
    error.message shouldBe "Invalid API key"
    error.context("provider") shouldBe "cohere"
  }
}
