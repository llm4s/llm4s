package org.llm4s.llmconnect.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderTimeoutConfigSpec extends AnyFunSuite with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  test("OpenAIConfig has default timeout of 30000ms") {
    val config = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "test-key",
      organization = None,
      baseUrl = "https://api.openai.com/v1"
    )
    config.timeoutMs shouldBe 30000
  }

  test("OpenAIConfig accepts custom timeout") {
    val config = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "test-key",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      timeoutMs = 5000
    )
    config.timeoutMs shouldBe 5000
  }

  test("AzureConfig has default timeout of 30000ms") {
    val config = AzureConfig.fromValues(
      modelName = "gpt-4o",
      endpoint = "https://test.openai.azure.com",
      apiKey = "test-key",
      apiVersion = "2025-01-01-preview"
    )
    config.timeoutMs shouldBe 30000
  }

  test("AzureConfig accepts custom timeout") {
    val config = AzureConfig.fromValues(
      modelName = "gpt-4o",
      endpoint = "https://test.openai.azure.com",
      apiKey = "test-key",
      apiVersion = "2025-01-01-preview",
      timeoutMs = 15000
    )
    config.timeoutMs shouldBe 15000
  }

  test("AnthropicConfig has default timeout of 30000ms") {
    val config = AnthropicConfig.fromValues(
      modelName = "claude-sonnet-4-5-latest",
      apiKey = "test-key",
      baseUrl = "https://api.anthropic.com"
    )
    config.timeoutMs shouldBe 30000
  }

  test("AnthropicConfig accepts custom timeout") {
    val config = AnthropicConfig.fromValues(
      modelName = "claude-sonnet-4-5-latest",
      apiKey = "test-key",
      baseUrl = "https://api.anthropic.com",
      timeoutMs = 60000
    )
    config.timeoutMs shouldBe 60000
  }

  test("OllamaConfig has default timeout of 30000ms") {
    val config = OllamaConfig.fromValues(
      modelName = "llama3",
      baseUrl = "http://localhost:11434"
    )
    config.timeoutMs shouldBe 30000
  }

  test("OllamaConfig accepts custom timeout of 1ms (extreme case)") {
    val config = OllamaConfig.fromValues(
      modelName = "llama3",
      baseUrl = "http://localhost:11434",
      timeoutMs = 1
    )
    config.timeoutMs shouldBe 1
  }

  test("GeminiConfig has default timeout of 30000ms") {
    val config = GeminiConfig.fromValues(
      modelName = "gemini-2.0-flash",
      apiKey = "test-key",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    )
    config.timeoutMs shouldBe 30000
  }

  test("GeminiConfig accepts custom timeout") {
    val config = GeminiConfig.fromValues(
      modelName = "gemini-2.0-flash",
      apiKey = "test-key",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      timeoutMs = 45000
    )
    config.timeoutMs shouldBe 45000
  }

  test("MistralConfig has default timeout of 30000ms") {
    val config = MistralConfig.fromValues(
      modelName = "mistral-large-latest",
      apiKey = "test-key",
      baseUrl = "https://api.mistral.ai"
    )
    config.timeoutMs shouldBe 30000
  }

  test("MistralConfig accepts custom timeout") {
    val config = MistralConfig.fromValues(
      modelName = "mistral-large-latest",
      apiKey = "test-key",
      baseUrl = "https://api.mistral.ai",
      timeoutMs = 35000
    )
    config.timeoutMs shouldBe 35000
  }

  test("CohereConfig has default timeout of 30000ms") {
    val config = CohereConfig.fromValues(
      modelName = "command-r-plus",
      apiKey = "test-key",
      baseUrl = "https://api.cohere.com"
    )
    config.timeoutMs shouldBe 30000
  }

  test("CohereConfig accepts custom timeout") {
    val config = CohereConfig.fromValues(
      modelName = "command-r-plus",
      apiKey = "test-key",
      baseUrl = "https://api.cohere.com",
      timeoutMs = 25000
    )
    config.timeoutMs shouldBe 25000
  }

  test("DeepSeekConfig has default timeout of 30000ms") {
    val config = DeepSeekConfig.fromValues(
      modelName = "deepseek-chat",
      apiKey = "test-key",
      baseUrl = "https://api.deepseek.com"
    )
    config.timeoutMs shouldBe 30000
  }

  test("DeepSeekConfig accepts custom timeout") {
    val config = DeepSeekConfig.fromValues(
      modelName = "deepseek-chat",
      apiKey = "test-key",
      baseUrl = "https://api.deepseek.com",
      timeoutMs = 20000
    )
    config.timeoutMs shouldBe 20000
  }

  test("ZaiConfig has default timeout of 30000ms") {
    val config = ZaiConfig.fromValues(
      modelName = "GLM-4.7",
      apiKey = "test-key",
      baseUrl = "https://api.z.ai/api/paas/v4"
    )
    config.timeoutMs shouldBe 30000
  }

  test("ZaiConfig accepts custom timeout") {
    val config = ZaiConfig.fromValues(
      modelName = "GLM-4.7",
      apiKey = "test-key",
      baseUrl = "https://api.z.ai/api/paas/v4",
      timeoutMs = 40000
    )
    config.timeoutMs shouldBe 40000
  }

  test("Provider config with 1ms timeout should be configurable (extreme case for timeout enforcement)") {
    // This test verifies that a very short timeout can be configured
    // In practice, a 1ms timeout would cause HTTP requests to timeout immediately
    // returning a Left (error) instead of hanging
    val config = OllamaConfig.fromValues(
      modelName = "llama3",
      baseUrl = "http://localhost:11434",
      timeoutMs = 1
    )
    config.timeoutMs shouldBe 1
    // In actual usage, clients would use this timeout value and fail fast
    // rather than hanging indefinitely
  }
}
