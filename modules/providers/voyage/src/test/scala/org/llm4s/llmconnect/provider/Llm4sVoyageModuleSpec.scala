package org.llm4s.llmconnect.provider

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
