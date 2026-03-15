package org.llm4s.llmconnect.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests provider config fallback branches for patch coverage.
 *
 * Uses model names not in ModelRegistry to trigger fallbackResolver path.
 * Model names use "patch-cov-" prefix to ensure registry miss.
 */
class ProviderConfigFallbackSpec extends AnyFlatSpec with Matchers {

  val azureEndpoint = "https://azure.example.com"
  val azureVersion  = "2024-02-15"
  val apiKey        = "sk-test"
  val baseUrl       = "https://api.example.com"

  "OpenAIConfig fallback" should "return 128000 for gpt-4o-like model" in {
    val cfg = OpenAIConfig.fromValues("patch-cov-gpt-4o", apiKey, None, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = OpenAIConfig.fromValues("patch-cov-unknown", apiKey, None, baseUrl)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "AzureConfig fallback" should "return 128000 for gpt-4o-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4o", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for gpt-4-turbo-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4-turbo", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for gpt-4-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 16384 for gpt-3.5-turbo-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-3.5-turbo", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for o1-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-o1-mini", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = AzureConfig.fromValues("patch-cov-unknown", azureEndpoint, apiKey, azureVersion)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "AnthropicConfig fallback" should "return 200000 for claude-3-like model" in {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-3", apiKey, baseUrl)
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 200000 for claude-3.5-like model" in {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-3.5-sonnet", apiKey, baseUrl)
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 100000 for claude-instant-like model" in {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-instant", apiKey, baseUrl)
    cfg.contextWindow shouldBe 100000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 200000 for unknown model" in {
    val cfg = AnthropicConfig.fromValues("patch-cov-unknown", apiKey, baseUrl)
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  "GeminiConfig fallback" should "return 1048576 for gemini-2-like model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-2", apiKey, baseUrl)
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 1048576 for gemini-1.5-like model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-1.5-pro", apiKey, baseUrl)
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 32768 for gemini-1.0-like model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-1.0", apiKey, baseUrl)
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 1048576 for gemini-pro-like model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-pro", apiKey, baseUrl)
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 1048576 for gemini-flash-like model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-flash", apiKey, baseUrl)
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 1048576 for unknown model" in {
    val cfg = GeminiConfig.fromValues("patch-cov-unknown", apiKey, baseUrl)
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  "DeepSeekConfig fallback" should "return 128000 for unregistered model (default branch)" in {
    val cfg = DeepSeekConfig.fromValues("patch-cov-deepseek-reasoner", apiKey, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 128000 for unknown model (default branch)" in {
    val cfg = DeepSeekConfig.fromValues("patch-cov-unknown", apiKey, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }

  "OllamaConfig fallback" should "return 4096 for llama2-like model" in {
    val cfg = OllamaConfig.fromValues("patch-cov-llama2", baseUrl)
    cfg.contextWindow shouldBe 4096
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for llama3-like model" in {
    val cfg = OllamaConfig.fromValues("patch-cov-llama3", baseUrl)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 16384 for codellama-like model" in {
    val cfg = OllamaConfig.fromValues("patch-cov-codellama", baseUrl)
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 32768 for mistral-like model" in {
    val cfg = OllamaConfig.fromValues("patch-cov-mistral", baseUrl)
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = OllamaConfig.fromValues("patch-cov-unknown", baseUrl)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "ZaiConfig fallback" should "return 128000 for GLM-4.7-like model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-GLM-4.7", apiKey, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 32000 for GLM-4.5-like model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-GLM-4.5", apiKey, baseUrl)
    cfg.contextWindow shouldBe 32000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for unknown model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-unknown", apiKey, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  "CohereConfig fallback" should "return 128000 for any unregistered model" in {
    val cfg = CohereConfig.fromValues("patch-cov-unknown", apiKey, baseUrl)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }
}
