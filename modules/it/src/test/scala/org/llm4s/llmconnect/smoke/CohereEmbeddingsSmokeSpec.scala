package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.{ CohereEmbeddingProvider, CohereInputType, EmbeddingProvider }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Cohere embeddings.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `COHERE_API_KEY` environment variable.
 */
@Cloud
class CohereEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private val cohereApiKey: Option[String] = Option(System.getenv("COHERE_API_KEY")).filter(_.nonEmpty)

  private val Model              = "embed-english-v3.0"
  private val ExpectedDimensions = 1024

  private def provider(apiKey: String, inputType: CohereInputType = CohereInputType.default): EmbeddingProvider =
    CohereEmbeddingProvider.fromConfig(
      EmbeddingProviderConfig(baseUrl = "https://api.cohere.com", model = Model, apiKey = apiKey),
      inputType
    )

  private def request(texts: String*): EmbeddingRequest =
    EmbeddingRequest(texts, EmbeddingModelConfig(Model, ExpectedDimensions))

  "Cohere embeddings" should "embed 3 sentences with embed-english-v3.0" in {
    Tier.require(cohereApiKey.isDefined, "COHERE_API_KEY not set")

    val result = provider(cohereApiKey.get).embed(
      request(
        "The quick brown fox jumps over the lazy dog.",
        "Machine learning is transforming enterprise software.",
        "Cohere provides embedding models for retrieval."
      )
    )

    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.embeddings should have size 3
    response.embeddings.foreach(_ should have size ExpectedDimensions)
    response.metadata("input_type") shouldBe "search_document"
  }

  it should "embed a query with the search_query input type" in {
    Tier.require(cohereApiKey.isDefined, "COHERE_API_KEY not set")

    val result = provider(cohereApiKey.get, CohereInputType.SearchQuery).embed(request("What is enterprise RAG?"))

    withClue(s"Embedding with input type failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.metadata("input_type") shouldBe "search_query"
    response.embeddings should have size 1
    response.embeddings.head should have size ExpectedDimensions
  }

  it should "return a 401 error for an invalid API key" in {
    val result = provider("cohere-invalid-key-for-smoke-test").embed(request("test"))

    result.isLeft shouldBe true
    result.swap.toOption.get.code shouldBe Some("401")
  }
}
