package org.llm4s.llmconnect.provider

import org.llm4s.config.CohereEmbeddingConfigKeys
import org.llm4s.llmconnect.config.ModelDimensionRegistry
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-cohere` registers itself, and only itself: the services entry is found, the descriptor
 * arrives, nothing else supplies `cohere`, and a config selecting it builds a provider. The checks
 * are `llm4s-provider-testkit`'s, the ones a provider module outside this repository uses.
 */
class Llm4sCohereModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  "the llm4s-cohere services entry" should {

    "be discovered under its id, the only supplier of cohere, and registrable explicitly" in {
      assertModule(new Llm4sCohereModule)
    }

    "contribute no chat provider: Cohere chat is a dialect in llm4s-openai-compatible" in {
      new Llm4sCohereModule().chatProviders shouldBe empty
      ProviderRegistry.default.find(ProviderId("cohere")) shouldBe None
    }
  }

  "the llm4s-cohere reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind COHERE_API_KEY to llm4s.credentials.cohere.apiKey" in {
      CohereEmbeddingProvider.configSpec.apiKeyEnv shouldBe Seq(CohereEmbeddingConfigKeys.COHERE_API_KEY)
      assertEmbeddingCredentialBindings(CohereEmbeddingProvider, "embed-english-v3.0")
    }

    "build a Cohere embedding provider from the selected block, as an application does" in {
      val (_, config) = ProviderTestConfig
        .loadEmbeddings(
          """llm4s.embeddings.model = "cohere/embed-english-v3.0"""",
          Map("COHERE_API_KEY" -> "cohere-test")
        )
        .fold(error => fail(error.message), identity)

      config.model shouldBe "embed-english-v3.0"
      config.apiKey shouldBe "cohere-test"
      config.baseUrl shouldBe "https://api.cohere.com"
      assertBuildsEmbeddingProvider(CohereEmbeddingProvider, config)
    }

    "refuse to load without an API key, naming the variable" in {
      val error = ProviderTestConfig
        .loadEmbeddings("""llm4s.embeddings.model = "cohere/embed-english-v3.0"""", Map.empty)
        .left
        .toOption
        .get
      error.message should include(CohereEmbeddingConfigKeys.COHERE_API_KEY)
    }

    "honour COHERE_EMBEDDING_BASE_URL and COHERE_EMBEDDING_MODEL" in {
      val (_, config) = ProviderTestConfig
        .loadEmbeddings(
          """llm4s.embeddings.provider = "cohere"""",
          Map(
            "COHERE_API_KEY"            -> "k",
            "COHERE_EMBEDDING_BASE_URL" -> "http://proxy.local",
            "COHERE_EMBEDDING_MODEL"    -> "embed-multilingual-v3.0"
          )
        )
        .fold(error => fail(error.message), identity)

      config.baseUrl shouldBe "http://proxy.local"
      config.model shouldBe "embed-multilingual-v3.0"
    }
  }

  "the llm4s-cohere model dimensions" should {

    "answer through ModelDimensionRegistry" in {
      given ProviderRegistry = ProviderRegistry.default
      ModelDimensionRegistry.getDimension("cohere", "embed-english-v3.0") shouldBe Right(1024)
      ModelDimensionRegistry.getDimension("cohere", "embed-v4.0") shouldBe Right(1536)
      CohereEmbeddingProvider.modelDimensions.values.foreach(_ should be > 0)
    }
  }
