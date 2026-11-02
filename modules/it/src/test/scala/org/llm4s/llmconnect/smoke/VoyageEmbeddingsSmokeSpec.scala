package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, VoyageAIEmbeddingProvider }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Voyage AI embeddings (`llm4s-voyage`).
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `VOYAGE_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class VoyageEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private val voyageApiKey: Option[String] = Option(System.getenv("VOYAGE_API_KEY")).filter(_.nonEmpty)

  private val Model              = "voyage-3.5-lite"
  private val ExpectedDimensions = VoyageAIEmbeddingProvider.modelDimensions(Model)

  private def provider(apiKey: String): EmbeddingProvider =
    VoyageAIEmbeddingProvider.fromConfig(
      EmbeddingProviderConfig(baseUrl = "https://api.voyageai.com/v1", model = Model, apiKey = apiKey)
    )

  private def request(purpose: InputPurpose, texts: String*): EmbeddingRequest =
    EmbeddingRequest(texts, EmbeddingModelConfig(Model, ExpectedDimensions), purpose)

  "Voyage AI embeddings" should "embed 3 documents in one request" in {
    Tier.require(voyageApiKey.isDefined, "VOYAGE_API_KEY not set")

    val result = provider(voyageApiKey.get).embed(
      request(
        InputPurpose.Document,
        "The quick brown fox jumps over the lazy dog.",
        "Machine learning is transforming enterprise software.",
        "Voyage AI provides embedding models for retrieval."
      )
    )

    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.embeddings should have size 3
    response.embeddings.foreach(_ should have size ExpectedDimensions)
  }

  it should "embed a query with input_type query" in {
    Tier.require(voyageApiKey.isDefined, "VOYAGE_API_KEY not set")

    val result = provider(voyageApiKey.get).embed(request(InputPurpose.Query, "What is enterprise RAG?"))

    withClue(s"Embedding a query failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.embeddings should have size 1
    response.embeddings.head should have size ExpectedDimensions
  }

  it should "return a 401 error for an invalid API key" in {
    val result = provider("voyage-invalid-key-for-smoke-test").embed(request(InputPurpose.Document, "test"))

    result.isLeft shouldBe true
    result.swap.toOption.get.code shouldBe Some("401")
  }
}
