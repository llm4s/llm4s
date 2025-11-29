package org.llm4s.llmconnect

import org.llm4s.config.ConfigKeys._
import org.llm4s.config.DefaultConfig._
import scala.util.Try
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigLoaderTest extends AnyFunSuite with Matchers {

  test("OpenAIConfig.load returns Right on success") {
    val cfg = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "sk-test",
      organization = None,
      baseUrl = DEFAULT_OPENAI_BASE_URL
    )
    cfg.model shouldBe "gpt-4o"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe DEFAULT_OPENAI_BASE_URL
  }

  test("OpenAIConfig.load returns Left when api key missing") {
    val res =
      Try(OpenAIConfig.fromValues("gpt-4o", "", None, DEFAULT_OPENAI_BASE_URL)).toEither
    res.isLeft shouldBe true
  }

  test("AzureConfig.load returns Right with defaults when version missing") {
    val cfg = AzureConfig.fromValues(
      modelName = "gpt-4o",
      endpoint = "https://example.azure.com",
      apiKey = "test-key",
      apiVersion = AZURE_API_VERSION
    )
    cfg.endpoint shouldBe "https://example.azure.com"
    cfg.apiKey shouldBe "test-key"
    cfg.apiVersion.nonEmpty shouldBe true
  }

  test("AzureConfig.load returns Left when endpoint missing") {
    val res = Try(
      AzureConfig.fromValues(
        modelName = "gpt-4o",
        endpoint = "",
        apiKey = "test-key",
        apiVersion = AZURE_API_VERSION
      )
    ).toEither
    res.isLeft shouldBe true
  }

  test("AnthropicConfig.load returns Left when api key missing") {
    val res =
      Try(AnthropicConfig.fromValues("claude-3", "", "https://api.anthropic.com")).toEither
    res.isLeft shouldBe true
  }

  test("OllamaConfig.load returns Left when base url missing") {
    val res = Try(OllamaConfig.fromValues("llama3", "")).toEither
    res.isLeft shouldBe true
  }

  test("ProviderConfigLoader.from handles provider prefixes and inference") {
    val openAi = OpenAIConfig.fromValues("gpt-4o", "sk", None, DEFAULT_OPENAI_BASE_URL)
    openAi shouldBe a[OpenAIConfig]
  }

  test("ProviderConfigLoader.from respects OpenRouter base URL inference") {
    val cfg = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "sk",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1"
    )
    cfg.baseUrl should include("openrouter.ai")
  }
}
