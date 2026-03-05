package org.llm4s.llmconnect.config

import org.llm4s.config.Llm4sConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProviderConfigLoaderIntegrationSpec extends AnyWordSpec with Matchers {
  "Llm4sConfig.provider" should {
    "load OpenAI config from llm4s.* and defaults" in {
      // application.conf provides llm4s.llm.model and llm4s.openai.apiKey
      val prov = Llm4sConfig.provider().fold(err => fail(err.toString), identity)
      prov match {
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o" // model part from openai/gpt-4o
          openai.apiKey shouldBe "test-key"
          openai.baseUrl should startWith("https://api.openai.com/")
        case other => fail(s"Expected OpenAIConfig, got $other")
      }
    }

    "load Mistral config using system properties over defaults" in {
      System.setProperty("llm4s.llm.model", "mistral/mistral-small-latest")
      System.setProperty("llm4s.mistral.apiKey", "sys-test-key")
      com.typesafe.config.ConfigFactory.invalidateCaches()

      try {
        val prov = Llm4sConfig.provider().fold(err => fail(err.toString), identity)
        prov match {
          case mistral: MistralConfig =>
            mistral.model shouldBe "mistral-small-latest"
            mistral.apiKey shouldBe "sys-test-key"
            mistral.baseUrl should startWith("https://api.mistral.ai")
          case other => fail(s"Expected MistralConfig, got $other")
        }
      } finally {
        System.clearProperty("llm4s.llm.model")
        System.clearProperty("llm4s.mistral.apiKey")
        com.typesafe.config.ConfigFactory.invalidateCaches()
      }
    }
  }
}
