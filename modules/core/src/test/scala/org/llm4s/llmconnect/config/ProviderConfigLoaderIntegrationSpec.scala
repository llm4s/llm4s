package org.llm4s.llmconnect.config

import org.llm4s.config.ConfigReader
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProviderConfigLoaderIntegrationSpec extends AnyWordSpec with Matchers {
  "ConfigReader.ProviderConfig" should {
    "load OpenAI config from llm4s.* and defaults" in {
      // application.conf provides llm4s.llm.model and llm4s.openai.apiKey
      val prov = ConfigReader.Provider().fold(err => fail(err.toString), identity)
      prov match {
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o" // model part from openai/gpt-4o
          openai.apiKey shouldBe "test-key"
          openai.baseUrl should startWith("https://api.openai.com/")
        case other => fail(s"Expected OpenAIConfig, got $other")
      }
    }
  }
}
