package org.llm4s.llmconnect

import org.llm4s.config.ConfigKeys._
import org.llm4s.config.ConfigReader
import org.llm4s.config.DefaultConfig._
import org.llm4s.error.NotFoundError
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigLoaderTest extends AnyFunSuite with Matchers {

  test("OpenAIConfig.load returns Right on success") {
    val config = ConfigReader(
      Map(
        OPENAI_API_KEY -> "sk-test"
      )
    )

    val res = OpenAIConfig("gpt-4o", config)
    res.isRight shouldBe true
    val cfg = res.toOption.get
    cfg.model shouldBe "gpt-4o"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe DEFAULT_OPENAI_BASE_URL
  }

  test("OpenAIConfig.load returns Left when api key missing") {
    val reader = ConfigReader(Map.empty)
    val res    = OpenAIConfig("gpt-4o", reader)
    res.isLeft shouldBe true
    res.fold(_ shouldBe a[NotFoundError], _ => fail())
  }

  test("AzureConfig.load returns Right with defaults when version missing") {
    val config = ConfigReader(
      Map(
        AZURE_API_BASE -> "https://example.azure.com",
        AZURE_API_KEY  -> "test-key"
      )
    )

    val res = AzureConfig("gpt-4o", config)
    res.isRight shouldBe true
    val cfg = res.toOption.get
    cfg.endpoint shouldBe "https://example.azure.com"
    cfg.apiKey shouldBe "test-key"
    cfg.apiVersion.nonEmpty shouldBe true
  }

  test("AzureConfig.load returns Left when endpoint missing") {
    val config = ConfigReader(
      Map(
        AZURE_API_KEY -> "test-key"
      )
    )

    AzureConfig("gpt-4o", config).isLeft shouldBe true
  }

  test("AnthropicConfig.load returns Left when api key missing") {
    val config = ConfigReader(Map.empty)
    AnthropicConfig("claude-3", config).isLeft shouldBe true
  }

  test("OllamaConfig.load returns Left when base url missing") {
    val config = ConfigReader(Map.empty)
    OllamaConfig("llama3", config).isLeft shouldBe true
  }

  test("ProviderConfigLoader.from handles provider prefixes and inference") {
    val reader = ConfigReader(
      Map(
        OPENAI_API_KEY  -> "sk",
        OPENAI_BASE_URL -> DEFAULT_OPENAI_BASE_URL
      )
    )

    val r1 = ProviderConfigLoader("openai/gpt-4o", reader)
    r1.isRight shouldBe true
    r1.toOption.get shouldBe a[OpenAIConfig]

    val r2 = ProviderConfigLoader("gpt-4o", reader) // infer openai
    r2.isRight shouldBe true
    r2.toOption.get shouldBe a[OpenAIConfig]
  }

  test("ProviderConfigLoader.from returns Left on unknown prefix") {
    val reader = ConfigReader(Map.empty)
    val res    = ProviderConfigLoader("invalid/gpt", reader)
    res.isLeft shouldBe true
  }

  test("ProviderConfigLoader.from respects OpenRouter base URL inference") {
    val reader = ConfigReader(
      Map(
        OPENAI_API_KEY  -> "sk",
        OPENAI_BASE_URL -> "https://openrouter.ai/api/v1"
      )
    )

    // No prefix but OpenRouter base URL should infer openrouter
    val res = ProviderConfigLoader("gpt-4o", reader)
    res.isRight shouldBe true
    val cfg = res.toOption.get
    cfg shouldBe a[OpenAIConfig]
    cfg.asInstanceOf[OpenAIConfig].baseUrl should include("openrouter.ai")
  }
}
