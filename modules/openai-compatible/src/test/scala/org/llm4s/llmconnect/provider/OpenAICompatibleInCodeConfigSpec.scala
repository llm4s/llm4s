package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.llmconnect.config.{
  CohereConfig,
  DeepSeekConfig,
  MistralConfig,
  OpenAICompatibleConfig,
  OpenAIConfig,
  ProviderConfig,
  XaiConfig,
  ZaiConfig
}
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai-compatible` builds its chat clients from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class OpenAICompatibleInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sOpenAICompatibleModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuilds(config: ProviderConfig, provider: String): Unit =
    config.providerId shouldBe ProviderId(provider)
    val client = LLMConnect.getClient(config).value
    val window = client.contextWindow
    client.close()
    window shouldBe config.contextWindow

  "llm4s-openai-compatible, configured in code" should {
    "build a generic OpenAI-compatible client from a model and base URL, with no key" in {
      assertBuilds(
        OpenAICompatibleConfig.fromValues(ModelName("qwen2.5"), BaseUrl("http://localhost:8000/v1")).value,
        "openai-compatible"
      )
    }

    "build a DeepSeek client from a model, key and base URL" in {
      assertBuilds(
        DeepSeekConfig.fromValues(ModelName("deepseek-chat"), Secret("ds-test"), DeepSeekConfig.DEFAULT_BASE_URL).value,
        "deepseek"
      )
    }

    "build a Z.ai client from a model, key and base URL" in {
      assertBuilds(
        ZaiConfig.fromValues(ModelName("GLM-4.7"), Secret("zai-test"), ZaiConfig.DEFAULT_BASE_URL).value,
        "zai"
      )
    }

    "build an OpenRouter client from an OpenAI config with an OpenRouter base URL" in {
      assertBuilds(
        OpenAIConfig
          .fromValues(ModelName("openai/gpt-4o-mini"), Secret("or-test"), OpenRouterProvider.DEFAULT_BASE_URL)
          .value,
        "openrouter"
      )
    }

    "build a Mistral client from a model, key and base URL" in {
      assertBuilds(
        MistralConfig
          .fromValues(ModelName("mistral-large-latest"), Secret("mi-test"), MistralConfig.DEFAULT_BASE_URL)
          .value,
        "mistral"
      )
    }

    "build a Cohere chat client from a model, key and base URL" in {
      assertBuilds(
        CohereConfig.fromValues(ModelName("command-a-03-2025"), Secret("co-test"), CohereConfig.DEFAULT_BASE_URL).value,
        "cohere"
      )
    }

    "build an xAI client from a model, key and base URL" in {
      assertBuilds(
        XaiConfig.fromValues(ModelName("grok-4"), Secret("xai-test"), XaiConfig.DEFAULT_BASE_URL).value,
        "xai"
      )
    }
  }
