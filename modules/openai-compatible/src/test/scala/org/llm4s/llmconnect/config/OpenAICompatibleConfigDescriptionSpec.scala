package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `providerId`, `endpointUrl` and `withModel` for the configs `llm4s-openai-compatible`
 * holds - the rows of core's `ProviderConfigDescriptionSpec` that moved here with them
 * (#1132), plus the generic `OpenAICompatibleConfig`.
 */
class OpenAICompatibleConfigDescriptionSpec extends AnyWordSpec with Matchers:

  private val zai      = ZaiConfig("k", "GLM-4.7", ZaiConfig.DEFAULT_BASE_URL, 200000, 4096)
  private val deepseek = DeepSeekConfig("k", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)
  private val generic =
    OpenAICompatibleConfig("m", "http://localhost:8000/v1", Some("k"), 32768, 4096, Map("X-Team" -> "search"))

  private val all: Seq[ProviderConfig] = Seq(zai, deepseek, generic)

  "ProviderConfig.providerId" should {
    "name each provider in its canonical spelling" in {
      zai.providerId shouldBe ProviderId("zai")
      deepseek.providerId shouldBe ProviderId("deepseek")
      generic.providerId shouldBe ProviderId("openai-compatible")
    }

    "be distinct across the configs this module builds" in {
      all.map(_.providerId.asString).distinct.size shouldBe all.size
    }
  }

  "ProviderConfig.endpointUrl" should {
    "return the URL the config will actually contact" in {
      zai.endpointUrl shouldBe Some(ZaiConfig.DEFAULT_BASE_URL)
      deepseek.endpointUrl shouldBe Some(DeepSeekConfig.DEFAULT_BASE_URL)
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
      deepseek.withModel("m").asInstanceOf[DeepSeekConfig].apiKey shouldBe deepseek.apiKey
      generic.withModel("m2").asInstanceOf[OpenAICompatibleConfig].headers shouldBe generic.headers
    }
  }

  "toString" should {
    "redact the API key and header values" in {
      val shown = generic.copy(apiKey = Some("sk-secret"), headers = Map("X-Token" -> "tok-secret")).toString
      (shown should not).include("sk-secret")
      (shown should not).include("tok-secret")
      shown should include("X-Token")
      (deepseek.copy(apiKey = "ds-secret").toString should not).include("ds-secret")
    }
  }
