package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.JinaEmbeddingProvider
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
class JinaEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("JINA_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): EmbeddingProviderConfig =
    EmbeddingProviderConfig(
      baseUrl = "https://api.jina.ai/v1",
      model = "jina-embeddings-v3",
      apiKey = key
    )

  "Jina Embeddings" should "embed three test sentences" in {
    assume(apiKey.isDefined, "JINA_API_KEY not set")

    val cfg      = config(apiKey.get)
    val provider = JinaEmbeddingProvider.fromConfig(cfg)
    val modelCfg = EmbeddingModelConfig("jina-embeddings-v3", 1024)
    val request  = EmbeddingRequest(
      Seq(
        "The quick brown fox jumps over the lazy dog",
        "Hello world from Jina embeddings",
        "This is a test sentence"
      ),
      modelCfg
    )

    val result = provider.embed(request)

    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val response = result.toOption.get
    response.embeddings should have size 3
    response.embeddings.foreach { embedding =>
      withClue(s"Vector dimension should be 1024 but got ${embedding.size}") {
        embedding should have size 1024
      }
      embedding.foreach { value =>
        value shouldBe a[Double]
      }
    }
  }
}
