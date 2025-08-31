package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.provider.LLMProvider

class LLMConnectProviderTypeSafetyTest extends AnyFunSuite with Matchers {

  test("OpenAI provider with OpenAIConfig returns OpenAIClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1"
    )
    val client = LLMConnect.getClient(LLMProvider.OpenAI, cfg)
    client.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("OpenRouter provider with OpenAIConfig returns OpenRouterClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "openrouter/test-model",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1"
    )
    val client = LLMConnect.getClient(LLMProvider.OpenRouter, cfg)
    client.getClass.getSimpleName shouldBe "OpenRouterClient"
  }

  test("Azure provider with AzureConfig returns OpenAIClient (Azure-backed)") {
    val cfg: ProviderConfig = AzureConfig(
      endpoint = "https://example.azure.com",
      apiKey = "key",
      model = "gpt-4o",
      apiVersion = "V2025_01_01_PREVIEW"
    )
    val client = LLMConnect.getClient(LLMProvider.Azure, cfg)
    client.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("Anthropic provider with AnthropicConfig returns AnthropicClient") {
    val cfg: ProviderConfig = AnthropicConfig(
      apiKey = "key",
      model = "claude-3-sonnet",
      baseUrl = "https://api.anthropic.com"
    )
    val client = LLMConnect.getClient(LLMProvider.Anthropic, cfg)
    client.getClass.getSimpleName shouldBe "AnthropicClient"
  }

  test("Ollama provider with OllamaConfig returns OllamaClient") {
    val cfg: ProviderConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434"
    )
    val client = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    client.getClass.getSimpleName shouldBe "OllamaClient"
  }

  test("OpenAI provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = AnthropicConfig(
      apiKey = "key",
      model = "claude-3-sonnet",
      baseUrl = "https://api.anthropic.com"
    )

    assertThrows[IllegalArgumentException] {
      LLMConnect.getClient(LLMProvider.OpenAI, wrongCfg)
    }
  }

  test("OpenRouter provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = AzureConfig(
      endpoint = "https://example.azure.com",
      apiKey = "key",
      model = "gpt-4o",
      apiVersion = "V2025_01_01_PREVIEW"
    )

    assertThrows[IllegalArgumentException] {
      LLMConnect.getClient(LLMProvider.OpenRouter, wrongCfg)
    }
  }

  test("Azure provider with non-AzureConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1"
    )

    assertThrows[IllegalArgumentException] {
      LLMConnect.getClient(LLMProvider.Azure, wrongCfg)
    }
  }
}
