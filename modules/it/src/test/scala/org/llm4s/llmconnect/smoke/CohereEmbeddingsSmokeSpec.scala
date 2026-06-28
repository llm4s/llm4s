package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.provider.CohereEmbeddingProvider
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for Cohere embeddings. Skips if COHERE_API_KEY is not set.
 */
class CohereEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get

  private val apiKey: Option[String] = Option(System.getenv("COHERE_API_KEY")).filter(_.nonEmpty)

  "Cohere embeddings" should "embed three sentences with embed-multilingual-v3.0" in {
    assume(apiKey.isDefined, "COHERE_API_KEY not set")

    val cfg = EmbeddingProviderConfig(baseUrl = "https://api.cohere.com", model = "embed-multilingual-v3.0", apiKey = apiKey.get)
    val provider = CohereEmbeddingProvider.fromConfig(cfg)

    val modelCfg = EmbeddingModelConfig("embed-multilingual-v3.0", 1024)
    val req = EmbeddingRequest(Seq("Hello world", "Bonjour le monde", "Hola mundo"), modelCfg)

    val result = provider.embed(req)
    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val resp = result.toOption.get
    resp.embeddings should not be empty
    val dim = resp.embeddings.head.length
    resp.embeddings.foreach(v => v.length shouldBe dim)
  }
}
