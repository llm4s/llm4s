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
 * core is checked here, and a new one must be added.
 */
class ProviderConfigDescriptionSpec extends AnyWordSpec with Matchers:

  private val openai    = OpenAIConfig("k", "gpt-4o", None, "https://api.openai.com/v1", 128000, 4096)
  private val azure     = AzureConfig("https://x.openai.azure.com", "k", "gpt-4o", "2025-01-01-preview", 128000, 4096)
  private val anthropic = AnthropicConfig("k", "claude-sonnet-4-5", "https://api.anthropic.com", 200000, 4096)
  private val ollama    = OllamaConfig("llama3", "http://localhost:11434", 8192, 4096)
  private val zai       = ZaiConfig("k", "GLM-4.7", ZaiConfig.DEFAULT_BASE_URL, 200000, 4096)
  private val gemini    = GeminiConfig("k", "gemini-2.0-flash", "https://x.invalid/v1beta", 1048576, 8192)
  private val deepseek  = DeepSeekConfig("k", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)
  private val cohere    = CohereConfig("k", "command-r", CohereConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val mistral   = MistralConfig("k", "mistral-large-latest", MistralConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val vertexai  = VertexAIConfig("proj", "us-central1", "gemini-2.0-flash", None, 1048576, 8192)

  private val all: Seq[ProviderConfig] =
    Seq(openai, azure, anthropic, ollama, zai, gemini, deepseek, cohere, mistral, vertexai)

  "ProviderConfig.providerId" should {
    "name each provider in its canonical spelling" in {
      openai.providerId shouldBe ProviderId("openai")
      azure.providerId shouldBe ProviderId("azure")
      anthropic.providerId shouldBe ProviderId("anthropic")
      ollama.providerId shouldBe ProviderId("ollama")
      zai.providerId shouldBe ProviderId("zai")
      gemini.providerId shouldBe ProviderId("gemini")
      deepseek.providerId shouldBe ProviderId("deepseek")
      cohere.providerId shouldBe ProviderId("cohere")
      mistral.providerId shouldBe ProviderId("mistral")
      vertexai.providerId shouldBe ProviderId("vertexai")
    }

    "be distinct across the configs core builds" in {
      all.map(_.providerId.asString).distinct.size shouldBe all.size
    }
  }

  "ProviderConfig.endpointUrl" should {
    "return the URL the config will actually contact" in {
      openai.endpointUrl shouldBe Some("https://api.openai.com/v1")
      // Azure's endpoint field, not a baseUrl - the distinction the old match existed to make.
      azure.endpointUrl shouldBe Some("https://x.openai.azure.com")
      anthropic.endpointUrl shouldBe Some("https://api.anthropic.com")
      ollama.endpointUrl shouldBe Some("http://localhost:11434")
      zai.endpointUrl shouldBe Some(ZaiConfig.DEFAULT_BASE_URL)
      gemini.endpointUrl shouldBe Some("https://x.invalid/v1beta")
      deepseek.endpointUrl shouldBe Some(DeepSeekConfig.DEFAULT_BASE_URL)
      cohere.endpointUrl shouldBe Some(CohereConfig.DEFAULT_BASE_URL)
      mistral.endpointUrl shouldBe Some(MistralConfig.DEFAULT_BASE_URL)
      // Vertex derives its URL from the location; the old ConfigPolicy match returned None here.
      vertexai.endpointUrl shouldBe Some("https://us-central1-aiplatform.googleapis.com/v1")
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
      azure.withModel("m").asInstanceOf[AzureConfig].apiVersion shouldBe azure.apiVersion
      openai.withModel("m").asInstanceOf[OpenAIConfig].apiKey shouldBe openai.apiKey
      vertexai.withModel("m").asInstanceOf[VertexAIConfig].projectId shouldBe vertexai.projectId
    }
  }
