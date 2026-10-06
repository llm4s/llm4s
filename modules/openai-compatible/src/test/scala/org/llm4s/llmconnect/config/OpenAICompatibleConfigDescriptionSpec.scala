package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `providerId`, `endpointUrl` and `withModel` for the configs `llm4s-openai-compatible`
 * holds - the rows of core's `ProviderConfigDescriptionSpec` that moved here with them
 * (#1132), plus the generic `OpenAICompatibleConfig`. `MistralConfig` and `CohereConfig` were the
 * last rows there; with them gone core builds no config, and its spec went too. `OpenAIConfig` is here because it
 * lives in this module - OpenRouter builds one - and `llm4s-openai` takes it from here.
 */
class OpenAICompatibleConfigDescriptionSpec extends AnyWordSpec with Matchers:

  private val openai   = OpenAIConfig("k", "gpt-4o", None, "https://api.openai.com/v1", 128000, 4096)
  private val zai      = ZaiConfig("k", "GLM-4.7", ZaiConfig.DEFAULT_BASE_URL, 200000, 4096)
  private val deepseek = DeepSeekConfig("k", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)
  private val mistral  = MistralConfig("k", "mistral-large-latest", MistralConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val cohere   = CohereConfig("k", "command-r", CohereConfig.DEFAULT_BASE_URL, 128000, 4096)
  private val generic =
    OpenAICompatibleConfig("m", "http://localhost:8000/v1", Some("k"), 32768, 4096, Map("X-Team" -> "search"))

  private val all: Seq[ProviderConfig] = Seq(openai, zai, deepseek, mistral, cohere, generic)

  "ProviderConfig.providerId" should {
    "name each provider in its canonical spelling" in {
      openai.providerId shouldBe ProviderId("openai")
      openai.copy(baseUrl = "https://openrouter.ai/api/v1").providerId shouldBe ProviderId("openrouter")
      zai.providerId shouldBe ProviderId("zai")
      deepseek.providerId shouldBe ProviderId("deepseek")
      mistral.providerId shouldBe ProviderId("mistral")
      cohere.providerId shouldBe ProviderId("cohere")
      generic.providerId shouldBe ProviderId("openai-compatible")
    }

    "be distinct across the configs this module builds" in {
      all.map(_.providerId.asString).distinct.size shouldBe all.size
    }
  }

  "ProviderConfig.endpointUrl" should {
    "return the URL the config will actually contact" in {
      openai.endpointUrl shouldBe Some("https://api.openai.com/v1")
      zai.endpointUrl shouldBe Some(ZaiConfig.DEFAULT_BASE_URL)
      deepseek.endpointUrl shouldBe Some(DeepSeekConfig.DEFAULT_BASE_URL)
      mistral.endpointUrl shouldBe Some(MistralConfig.DEFAULT_BASE_URL)
      cohere.endpointUrl shouldBe Some(CohereConfig.DEFAULT_BASE_URL)
      generic.endpointUrl shouldBe Some("http://localhost:8000/v1")
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
      deepseek.withModel("m").asInstanceOf[DeepSeekConfig].apiKey shouldBe deepseek.apiKey
      generic.withModel("m2").asInstanceOf[OpenAICompatibleConfig].headers shouldBe generic.headers
    }
  }

  "toString" should {
    "redact the API key and header values" in {
      val shown = generic.withApiKey("sk-secret").withHeaders(Map("X-Token" -> "tok-secret")).toString
      (shown should not).include("sk-secret")
      (shown should not).include("tok-secret")
      shown should include("X-Token")
      (deepseek.copy(apiKey = "ds-secret").toString should not).include("ds-secret")
    }
  }

  "OpenAICompatibleConfig's two-argument apply and setters" should {
    "build the same config as the defaulted apply, for Java and Kotlin callers" in {
      val built = OpenAICompatibleConfig("m", "http://localhost:8000/v1")
        .withApiKey("k")
        .withContextWindow(32768)
        .withReserveCompletion(4096)
        .withHeader("X-Team", "search")
      built shouldBe generic
      OpenAICompatibleConfig("m", "http://localhost:8000/v1") shouldBe
        OpenAICompatibleConfig("m", "http://localhost:8000/v1", apiKey = None)
      built.withStreamUsage(false).streamUsage shouldBe false
      built.withApiKey(None).apiKey shouldBe None
      built.withBaseUrl("http://other/v1").baseUrl shouldBe "http://other/v1"
    }
  }
