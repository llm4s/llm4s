package org.llm4s.llmconnect.provider

import org.llm4s.config.CredentialsRoundTrip
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-ollama` registers itself, and what it registers works.
 *
 * This is Ollama's row of core's `BuiltinProvidersSpec`, which it left with the
 * provider (#1132), plus the part that only a carved module has to prove: that
 * depending on it is enough - the services entry is found and both halves arrive.
 */
class Llm4sOllamaModuleSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  private val section = NamedProviderConfig(
    provider = OllamaProvider.id,
    model = ModelName("llama3.1"),
    baseUrl = Some(BaseUrl("http://localhost:11434")),
    apiKey = None,
    organization = None,
    endpoint = None,
    apiVersion = None
  )

  "the llm4s-ollama services entry" should {

    "be discovered, contributing both halves under the id ollama" in {
      val registry = ProviderRegistry.discover()

      registry.get(ProviderId("ollama")) shouldBe Right(OllamaProvider)
      registry.findEmbedding(ProviderId("ollama")) shouldBe Some(OllamaEmbeddingProvider)
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sOllamaModule].getName)
    }

    "be the only module that supplies ollama, for chat or embeddings" in {
      // Core held these in `BuiltinProviders` until #1132 deleted it; nothing but this
      // module may supply them now.
      val modules = ProviderRegistry.default.report.modules
      Seq("ollama").foreach { id =>
        modules.filter(_.providerIds.contains(id)).map(_.moduleClass) shouldBe Seq(classOf[Llm4sOllamaModule].getName)
      }
      Seq("ollama").foreach { id =>
        modules.filter(_.embeddingProviderIds.contains(id)).map(_.moduleClass) shouldBe
          Seq(classOf[Llm4sOllamaModule].getName)
      }
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.ofModules(new Llm4sOllamaModule)

      registry.get(ProviderId("ollama")) shouldBe Right(OllamaProvider)
      registry.findEmbedding(ProviderId("ollama")) shouldBe Some(OllamaEmbeddingProvider)
    }
  }

  "OllamaProvider" should {

    "build an OllamaConfig and an OllamaClient from a config section" in {
      val result =
        OllamaProvider.buildConfig("test-instance", section).flatMap { config =>
          config.getClass.getSimpleName shouldBe "OllamaConfig"
          OllamaProvider.buildClient(config, LlmClientOptions.default)
        }

      result.map(_.getClass.getSimpleName) shouldBe Right("OllamaClient")
    }

    "refuse a config belonging to another provider" in {
      val foreign = FixtureChatConfig("k", "fixture-model")

      OllamaProvider.buildClient(foreign, LlmClientOptions.default) match
        case Left(error)   => error.message should include("Invalid config type FixtureChatConfig for provider ollama")
        case Right(client) => fail(s"ollama accepted a FixtureChatConfig and built $client")
    }

    "declare streaming and a model lister" in {
      OllamaProvider.features.streaming shouldBe true
      OllamaProvider.modelLister shouldBe defined
    }
  }

  "the llm4s-ollama reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind no key, since Ollama takes none" in {
      OllamaProvider.configSpec.apiKeyEnv shouldBe empty
      OllamaEmbeddingProvider.configSpec.apiKeyEnv shouldBe empty
      CredentialsRoundTrip.chatSectionKey("ollama", Map.empty, """baseUrl = "http://localhost:11434"""") shouldBe
        Right(None)
    }
  }
