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
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.OpenAI, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("OpenRouter provider with OpenAIConfig returns OpenRouterClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "openrouter/test-model",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.OpenRouter, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenRouterClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Azure provider with AzureConfig returns OpenAIClient (Azure-backed)") {
    val cfg: ProviderConfig = AzureConfig(
      endpoint = "https://example.azure.com",
      apiKey = "key",
      model = "gpt-4o",
      apiVersion = "V2025_01_01_PREVIEW",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.Azure, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Anthropic provider with AnthropicConfig returns AnthropicClient") {
    val cfg: ProviderConfig = AnthropicConfig(
      apiKey = "key",
      model = "claude-3-sonnet",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.Anthropic, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Ollama provider with OllamaConfig returns OllamaClient") {
    val cfg: ProviderConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OllamaClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("OpenAI provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = AnthropicConfig(
      apiKey = "key",
      model = "claude-3-sonnet",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )

    val res = LLMConnect.getClient(LLMProvider.OpenAI, wrongCfg)
    res.isLeft shouldBe true
  }

  test("OpenRouter provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = AzureConfig(
      endpoint = "https://example.azure.com",
      apiKey = "key",
      model = "gpt-4o",
      apiVersion = "V2025_01_01_PREVIEW",
      contextWindow = 128000,
      reserveCompletion = 4096
    )

    val res = LLMConnect.getClient(LLMProvider.OpenRouter, wrongCfg)
    res.isLeft shouldBe true
  }

  test("Azure provider with non-AzureConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )

    val res = LLMConnect.getClient(LLMProvider.Azure, wrongCfg)
    res.isLeft shouldBe true
  }
}
