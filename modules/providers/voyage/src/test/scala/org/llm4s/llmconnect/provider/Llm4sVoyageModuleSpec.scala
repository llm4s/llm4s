package org.llm4s.llmconnect.provider

import org.llm4s.config.VoyageConfigKeys
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-voyage` registers itself, and only itself.
 *
 * Voyage was the embedding half of core's `BuiltinProviders` until it moved here (#1132); the
 * part only a carved module has to prove is that depending on it is enough - the services
 * entry is found and the descriptor arrives - and that nothing else supplies it. The checks are
 * `llm4s-provider-testkit`'s, the ones a provider module outside this repository uses.
 */
class Llm4sVoyageModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  "the llm4s-voyage services entry" should {

    "be discovered under its id and alias, the only supplier of voyage, and registrable explicitly" in {
      assertModule(new Llm4sVoyageModule)
    }

    "contribute no chat provider, so voyage is not a chat id" in {
      new Llm4sVoyageModule().chatProviders shouldBe empty
      ProviderRegistry.default.find(ProviderId("voyage")) shouldBe None
    }
  }

  "the llm4s-voyage reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind VOYAGE_API_KEY to llm4s.credentials.voyage.apiKey" in {
      VoyageAIEmbeddingProvider.configSpec.apiKeyEnv shouldBe Seq(VoyageConfigKeys.VOYAGE_API_KEY)
      assertEmbeddingCredentialBindings(VoyageAIEmbeddingProvider, "voyage-3")
    }

    "give the voyageai alias the voyage key" in {
      CredentialsRoundTrip.embeddingsKey("voyageai", "voyage-3", Map("VOYAGE_API_KEY" -> "pa-shared")) shouldBe
        Right("pa-shared")
    }

    "build a Voyage embedding provider from the selected block, as an application does" in {
      val (_, config) = ProviderTestConfig
        .loadEmbeddings("""llm4s.embeddings.model = "voyage/voyage-3"""", Map("VOYAGE_API_KEY" -> "pa-test"))
        .fold(error => fail(error.message), identity)

      config.model shouldBe "voyage-3"
      config.apiKey shouldBe "pa-test"
      assertBuildsEmbeddingProvider(VoyageAIEmbeddingProvider, config)
    }
  }
