package org.llm4s.llmconnect

import org.scalatest.EitherValues
import org.llm4s.config.ConfigKeys._
import org.llm4s.config.DefaultConfig._
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigConstructionTest extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  test("OpenAIConfig.load returns Right on success") {
    val cfg = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "sk-test",
        organization = None,
        baseUrl = DEFAULT_OPENAI_BASE_URL
      )
      .value
    cfg.model shouldBe "gpt-4o"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe DEFAULT_OPENAI_BASE_URL
  }

  test("OpenAIConfig.load returns Left when api key missing") {
    val res =
      OpenAIConfig.fromValues("gpt-4o", "", None, DEFAULT_OPENAI_BASE_URL)
    res.isLeft shouldBe true
  }

  test("AzureConfig.load returns Right with defaults when version missing") {
    val cfg = AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "https://example.azure.com",
        apiKey = "test-key",
        apiVersion = AZURE_API_VERSION
      )
      .value
    cfg.endpoint shouldBe "https://example.azure.com"
    cfg.apiKey shouldBe "test-key"
    cfg.apiVersion.nonEmpty shouldBe true
  }

  test("AzureConfig.load returns Left when endpoint missing") {
    val res = AzureConfig.fromValues(
      modelName = "gpt-4o",
      endpoint = "",
      apiKey = "test-key",
      apiVersion = AZURE_API_VERSION
    )
    res.isLeft shouldBe true
  }

  test("AnthropicConfig.load returns Left when api key missing") {
    val res =
      AnthropicConfig.fromValues("claude-3", "", "https://api.anthropic.com")
    res.isLeft shouldBe true
  }

  test("OpenAIConfig.fromValues constructs an OpenAI config") {
    val openAi = OpenAIConfig.fromValues("gpt-4o", "sk", None, DEFAULT_OPENAI_BASE_URL).value
    openAi shouldBe a[OpenAIConfig]
  }

  test("OpenAIConfig.fromValues preserves an OpenRouter base URL") {
    val cfg = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "sk",
        organization = None,
        baseUrl = "https://openrouter.ai/api/v1"
      )
      .value
    cfg.baseUrl should include("openrouter.ai")
  }
}
