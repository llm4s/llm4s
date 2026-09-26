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
 * `llm4s-openai`, and `OpenAIConfig`, `DeepSeekConfig` and `ZaiConfig` in
 * `llm4s-openai-compatible`'s `OpenAICompatibleConfigDescriptionSpec`.
 */
class ProviderConfigDescriptionSpec extends AnyWordSpec with Matchers:

  private val cohere  = CohereConfig("k", "command-r", CohereConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val mistral = MistralConfig("k", "mistral-large-latest", MistralConfig.DEFAULT_BASE_URL, 128000, 4096)

  private val all: Seq[ProviderConfig] =
    Seq(cohere, mistral)

  "ProviderConfig.providerId" should {
    "name each provider in its canonical spelling" in {
      cohere.providerId shouldBe ProviderId("cohere")
      mistral.providerId shouldBe ProviderId("mistral")
    }

    "be distinct across the configs core builds" in {
      all.map(_.providerId.asString).distinct.size shouldBe all.size
    }
  }

  "ProviderConfig.endpointUrl" should {
    "return the URL the config will actually contact" in {
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
  }
