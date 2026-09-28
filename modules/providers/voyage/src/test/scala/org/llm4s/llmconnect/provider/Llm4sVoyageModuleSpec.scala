package org.llm4s.llmconnect.provider

import org.llm4s.config.{ CredentialsRoundTrip, VoyageConfigKeys }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-voyage` registers itself, and only itself.
 *
 * Voyage was the embedding half of core's `BuiltinProviders` until it moved here (#1132); the
 * part only a carved module has to prove is that depending on it is enough - the services
 * entry is found and the descriptor arrives - and that nothing else supplies it.
 */
class Llm4sVoyageModuleSpec extends AnyWordSpec with Matchers:

  "the llm4s-voyage services entry" should {

    "be discovered, contributing the Voyage embedding provider under its id and alias" in {
      val registry = ProviderRegistry.discover()

      registry.resolveEmbedding(ProviderId("voyage")) shouldBe Right(VoyageAIEmbeddingProvider)
      registry.canonicalEmbeddingId("voyageai") shouldBe ProviderId("voyage")
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sVoyageModule].getName)
    }

    "contribute no chat provider, so voyage is not a chat id" in {
      new Llm4sVoyageModule().chatProviders shouldBe empty
      ProviderRegistry.default.find(ProviderId("voyage")) shouldBe None
    }

    "be the only module that supplies voyage" in {
      ProviderRegistry.default.report.modules
        .filter(_.embeddingProviderIds.contains("voyage"))
        .map(_.moduleClass) shouldBe Seq(classOf[Llm4sVoyageModule].getName)
    }

    "be registrable explicitly where discovery cannot run" in {
      ProviderRegistry.ofModules(new Llm4sVoyageModule).resolveEmbedding(ProviderId("voyage")) shouldBe
        Right(VoyageAIEmbeddingProvider)
    }
  }

  "the llm4s-voyage reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind VOYAGE_API_KEY to llm4s.credentials.voyage.apiKey" in {
      VoyageAIEmbeddingProvider.configSpec.apiKeyEnv shouldBe Seq(VoyageConfigKeys.VOYAGE_API_KEY)
      CredentialsRoundTrip.embeddingBindings(VoyageAIEmbeddingProvider, "voyage-3") shouldBe
        Map("VOYAGE_API_KEY" -> Right("key-from-VOYAGE_API_KEY"))
    }

    "give the voyageai alias the voyage key" in {
      CredentialsRoundTrip.embeddingsKey("voyageai", "voyage-3", Map("VOYAGE_API_KEY" -> "pa-shared")) shouldBe
        Right("pa-shared")
    }
  }
