package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Covers the self-describing members `ProviderConfig` gained in #1131 PR 1.
 *
 * `providerId`, `endpointUrl` and `withModel` replaced four exhaustive matches
 * over the (formerly `sealed`) config hierarchy - in `ConfigPolicy`,
 * `PrometheusMetricsExample` and `ProviderSetupRuntime`. Losing `sealed` means
 * the compiler no longer checks that a new subtype has been considered
 * everywhere, so this spec is what keeps that guarantee: every config built into
 * core is checked here, and a new one must be added. A config that leaves core
 * takes its checks with it - `OllamaConfigSpec` in `llm4s-ollama`, `GeminiConfigSpec`
 * in `llm4s-gemini`, `AnthropicConfigSpec` in `llm4s-anthropic`, `AzureConfigSpec` in
 * `llm4s-openai`, `DeepSeekConfig` in `llm4s-openai-compatible`'s `OpenAICompatibleConfigDescriptionSpec`.
 * `OpenAIConfig` stays in core, and is checked here, because OpenRouter
 * shares it.
 */
class ProviderConfigDescriptionSpec extends AnyWordSpec with Matchers:

  private val openai  = OpenAIConfig("k", "gpt-4o", None, "https://api.openai.com/v1", 128000, 4096)
  private val zai     = ZaiConfig("k", "GLM-4.7", ZaiConfig.DEFAULT_BASE_URL, 200000, 4096)
  private val cohere  = CohereConfig("k", "command-r", CohereConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val mistral = MistralConfig("k", "mistral-large-latest", MistralConfig.DEFAULT_BASE_URL, 128000, 4096)

  private val all: Seq[ProviderConfig] =
    Seq(openai, zai, cohere, mistral)

  "ProviderConfig.providerId" should {
    "name each provider in its canonical spelling" in {
      openai.providerId shouldBe ProviderId("openai")
      zai.providerId shouldBe ProviderId("zai")
      cohere.providerId shouldBe ProviderId("cohere")
      mistral.providerId shouldBe ProviderId("mistral")
    }

    "be distinct across the configs core builds" in {
      all.map(_.providerId.asString).distinct.size shouldBe all.size
    }
  }

  "ProviderConfig.endpointUrl" should {
    "return the URL the config will actually contact" in {
      openai.endpointUrl shouldBe Some("https://api.openai.com/v1")
      zai.endpointUrl shouldBe Some(ZaiConfig.DEFAULT_BASE_URL)
      cohere.endpointUrl shouldBe Some(CohereConfig.DEFAULT_BASE_URL)
      mistral.endpointUrl shouldBe Some(MistralConfig.DEFAULT_BASE_URL)
    }
  }

  "ProviderConfig.withModel" should {
    "change only the model, preserving type and provider" in {
      all.foreach { config =>
        val renamed = config.withModel("some-other-model")
        renamed.model shouldBe "some-other-model"
        renamed.providerId shouldBe config.providerId
        renamed.getClass shouldBe config.getClass
        renamed.endpointUrl shouldBe config.endpointUrl
        renamed.contextWindow shouldBe config.contextWindow
        renamed.reserveCompletion shouldBe config.reserveCompletion
      }
    }

    "leave provider-specific fields untouched" in {
      openai.withModel("m").asInstanceOf[OpenAIConfig].apiKey shouldBe openai.apiKey
    }
  }
