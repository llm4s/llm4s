package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver, DeepSeekConfig }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-anthropic` registers itself, and what it registers works.
 *
 * This is Anthropic's row of core's `BuiltinProvidersSpec`, which left with the provider
 * (#1132), plus the part that only a carved module has to prove: that depending on it is
 * enough - the services entry is found and the descriptor arrives.
 */
class Llm4sAnthropicModuleSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** A section carrying every field the provider asks for. */
  private val section: NamedProviderConfig =
    NamedProviderConfig(
      provider = AnthropicProvider.id,
      model = ModelName("claude-sonnet-4-5"),
      baseUrl = AnthropicProvider.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

  "the llm4s-anthropic services entry" should {

    "be discovered, contributing anthropic" in {
      val registry = ProviderRegistry.discover()

      registry.get(ProviderId("anthropic")) shouldBe Right(AnthropicProvider)
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sAnthropicModule].getName)
    }

    "contribute no embedding provider" in {
      ProviderRegistry.default.findEmbedding(ProviderId("anthropic")) shouldBe None
    }

    "not be part of core's built-in set, which no longer ships Anthropic" in {
      ProviderRegistry.builtin.find(ProviderId("anthropic")) shouldBe None
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.builtin.withModule(new Llm4sAnthropicModule)

      registry.get(ProviderId("anthropic")) shouldBe Right(AnthropicProvider)
    }
  }

  "the llm4s-anthropic provider" should {

    "default its base URL to the Anthropic API" in {
      AnthropicProvider.configSpec.defaultBaseUrl shouldBe Some(AnthropicConfig.DEFAULT_BASE_URL)
    }

    "build an AnthropicConfig and an AnthropicClient from a config section" in {
      val result =
        AnthropicProvider.buildConfig("test-instance", section).flatMap { config =>
          config.getClass.getSimpleName shouldBe "AnthropicConfig"
          AnthropicProvider.buildClient(config, LlmClientOptions.default)
        }

      result.map(_.getClass.getSimpleName) shouldBe Right("AnthropicClient")
    }

    "refuse a config belonging to another provider" in {
      val foreign = DeepSeekConfig("k", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)

      AnthropicProvider.buildClient(foreign, LlmClientOptions.default) match
        case Left(error)   => error.message should include("Invalid config type DeepSeekConfig for provider anthropic")
        case Right(client) => fail(s"anthropic accepted a DeepSeekConfig and built $client")
    }

    "declare streaming and a model lister" in {
      AnthropicProvider.features.streaming shouldBe true
      AnthropicProvider.modelLister shouldBe defined
    }
  }
