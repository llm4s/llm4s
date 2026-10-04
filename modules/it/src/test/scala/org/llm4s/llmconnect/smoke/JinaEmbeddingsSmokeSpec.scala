package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, JinaEmbeddingProvider, JinaTask }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Jina AI embeddings.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `JINA_API_KEY` environment variable.
 */
@Cloud
class JinaEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private val jinaApiKey: Option[String] = Option(System.getenv("JINA_API_KEY")).filter(_.nonEmpty)

  private val Model              = "jina-embeddings-v3"
  private val ExpectedDimensions = 1024

  private def provider(apiKey: String, task: JinaTask = JinaTask.default): EmbeddingProvider =
    JinaEmbeddingProvider.fromConfig(
      EmbeddingProviderConfig(baseUrl = "https://api.jina.ai/v1", model = Model, apiKey = apiKey),
      task
    )

  private def request(texts: String*): EmbeddingRequest =
    EmbeddingRequest(texts, EmbeddingModelConfig(Model, ExpectedDimensions))

  "Jina AI embeddings" should "embed 3 sentences with jina-embeddings-v3" in {
    Tier.require(jinaApiKey.isDefined, "JINA_API_KEY not set")

    val result = provider(jinaApiKey.get).embed(
      request(
        "The quick brown fox jumps over the lazy dog.",
        "Machine learning is transforming enterprise software.",
        "Jina AI provides state-of-the-art embedding models for RAG."
      )
    )

    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.embeddings should have size 3
    response.embeddings.foreach(_ should have size ExpectedDimensions)
  }

  it should "embed a query with the retrieval.query task" in {
    Tier.require(jinaApiKey.isDefined, "JINA_API_KEY not set")

    val result = provider(jinaApiKey.get, JinaTask.RetrievalQuery).embed(request("What is enterprise RAG?"))

    withClue(s"Embedding with task failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val response = result.toOption.get
    response.metadata("task") shouldBe "retrieval.query"
    response.embeddings should have size 1
    response.embeddings.head should have size ExpectedDimensions
  }

  it should "return a 401 error for an invalid API key" in {
    val result = provider("jina-invalid-key-for-smoke-test").embed(request("test"))

    result.isLeft shouldBe true
    result.swap.toOption.get.code shouldBe Some("401")
  }
}
