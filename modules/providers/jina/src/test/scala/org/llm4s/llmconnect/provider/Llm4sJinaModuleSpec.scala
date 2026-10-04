package org.llm4s.llmconnect.provider

import org.llm4s.config.JinaConfigKeys
import org.llm4s.llmconnect.config.ModelDimensionRegistry
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-jina` registers itself, and only itself: the services entry is found, the descriptor
 * arrives, nothing else supplies `jina`, and a config selecting it builds a provider. The checks
 * are `llm4s-provider-testkit`'s, the ones a provider module outside this repository uses.
 */
class Llm4sJinaModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  "the llm4s-jina services entry" should {

    "be discovered under its id, the only supplier of jina, and registrable explicitly" in {
      assertModule(new Llm4sJinaModule)
    }

    "contribute no chat provider, so jina is not a chat id" in {
      new Llm4sJinaModule().chatProviders shouldBe empty
      ProviderRegistry.default.find(ProviderId("jina")) shouldBe None
    }
  }

  "the llm4s-jina reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind JINA_API_KEY to llm4s.credentials.jina.apiKey" in {
      JinaEmbeddingProvider.configSpec.apiKeyEnv shouldBe Seq(JinaConfigKeys.JINA_API_KEY)
      assertEmbeddingCredentialBindings(JinaEmbeddingProvider, "jina-embeddings-v3")
    }

    "build a Jina embedding provider from the selected block, as an application does" in {
      val (_, config) = ProviderTestConfig
        .loadEmbeddings("""llm4s.embeddings.model = "jina/jina-embeddings-v3"""", Map("JINA_API_KEY" -> "jina-test"))
        .fold(error => fail(error.message), identity)

      config.model shouldBe "jina-embeddings-v3"
      config.apiKey shouldBe "jina-test"
      config.baseUrl shouldBe "https://api.jina.ai/v1"
      assertBuildsEmbeddingProvider(JinaEmbeddingProvider, config)
    }

    "refuse to load without an API key" in {
      val error = ProviderTestConfig
        .loadEmbeddings("""llm4s.embeddings.model = "jina/jina-embeddings-v3"""", Map.empty)
        .left
        .toOption
        .get
      error.message should include(JinaConfigKeys.JINA_API_KEY)
    }

    "honour JINA_EMBEDDING_BASE_URL" in {
      val (_, config) = ProviderTestConfig
        .loadEmbeddings(
          """llm4s.embeddings.model = "jina/jina-embeddings-v3"""",
          Map("JINA_API_KEY" -> "k", "JINA_EMBEDDING_BASE_URL" -> "http://proxy.local/v1")
        )
        .fold(error => fail(error.message), identity)

      config.baseUrl shouldBe "http://proxy.local/v1"
    }
  }

  "the llm4s-jina model dimensions" should {

    "answer through ModelDimensionRegistry" in {
      given ProviderRegistry = ProviderRegistry.default
      ModelDimensionRegistry.getDimension("jina", "jina-embeddings-v3") shouldBe Right(1024)
      JinaEmbeddingProvider.modelDimensions.values.foreach(_ should be > 0)
    }
  }
