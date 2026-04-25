package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigSpec extends AnyFunSuite with Matchers {

  // ================================= OPENAI CONFIG =================================

  test("OpenAIConfig.fromValues creates config with correct model") {
    val config = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "test-key",
      organization = Some("test-org"),
      baseUrl = "https://api.openai.com/v1"
    )

    config.model shouldBe "gpt-4o"
    config.apiKey shouldBe "test-key"
    config.organization shouldBe Some("test-org")
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4o") {
    val config = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "test-key",
      organization = None,
      baseUrl = "https://api.openai.com/v1"
    )

    config.contextWindow shouldBe 128000
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4") {
    val config = OpenAIConfig.fromValues(
      modelName = "gpt-4",
      apiKey = "test-key",
      organization = None,
      baseUrl = "https://api.openai.com/v1"
    )

    config.contextWindow shouldBe 8192
  }

  test("OpenAIConfig.fromValues throws for empty apiKey") {
    an[IllegalArgumentException] should be thrownBy {
      OpenAIConfig.fromValues(
        modelName = "gpt-4o",
        apiKey = "",
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
    }
  }

  test("OpenAIConfig.fromValues throws for empty baseUrl") {
    an[IllegalArgumentException] should be thrownBy {
      OpenAIConfig.fromValues(
        modelName = "gpt-4o",
        apiKey = "test-key",
        organization = None,
        baseUrl = ""
      )
    }
  }

  // ================================= ANTHROPIC CONFIG =================================

  test("AnthropicConfig.fromValues creates config with correct model") {
    val config = AnthropicConfig.fromValues(
      modelName = "claude-3-sonnet-20240229",
      apiKey = "test-key",
      baseUrl = "https://api.anthropic.com"
    )

    config.model shouldBe "claude-3-sonnet-20240229"
    config.apiKey shouldBe "test-key"
  }

  test("AnthropicConfig.fromValues sets large context window for claude-3") {
    val config = AnthropicConfig.fromValues(
      modelName = "claude-3-opus-20240229",
      apiKey = "test-key",
      baseUrl = "https://api.anthropic.com"
    )

    config.contextWindow shouldBe 200000
  }

  test("AnthropicConfig.fromValues throws for empty apiKey") {
    an[IllegalArgumentException] should be thrownBy {
      AnthropicConfig.fromValues(
        modelName = "claude-3-sonnet",
        apiKey = "",
        baseUrl = "https://api.anthropic.com"
      )
    }
  }

  // ================================= GEMINI CONFIG =================================

  test("GeminiConfig.fromValues appends v1beta when baseUrl is the API host root") {
    val config = GeminiConfig.fromValues(
      modelName = "gemini-1.5-flash",
      apiKey = "test-key",
      baseUrl = "https://generativelanguage.googleapis.com"
    )

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  test("GeminiConfig.fromValues preserves explicit versioned baseUrl") {
    val config = GeminiConfig.fromValues(
      modelName = "gemini-1.5-flash",
      apiKey = "test-key",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    )

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  // ================================= AZURE CONFIG =================================

  test("AzureConfig.fromValues creates config with correct model") {
    val config = AzureConfig.fromValues(
      modelName = "gpt-4o",
      endpoint = "https://my-resource.openai.azure.com",
      apiKey = "test-key",
      apiVersion = "2024-02-15-preview"
    )

    config.model shouldBe "gpt-4o"
    config.endpoint shouldBe "https://my-resource.openai.azure.com"
    config.apiVersion shouldBe "2024-02-15-preview"
  }

  test("AzureConfig.fromValues throws for empty endpoint") {
    an[IllegalArgumentException] should be thrownBy {
      AzureConfig.fromValues(
        modelName = "gpt-4o",
        endpoint = "",
        apiKey = "test-key",
        apiVersion = "2024-02-15-preview"
      )
    }
  }

  // ================================= OLLAMA CONFIG =================================

  test("OllamaConfig.fromValues creates config with correct model") {
    val config = OllamaConfig.fromValues(
      modelName = "llama3",
      baseUrl = "http://localhost:11434"
    )

    config.model shouldBe "llama3"
    config.baseUrl shouldBe "http://localhost:11434"
  }

  test("OllamaConfig.fromValues sets correct context window for llama2") {
    val config = OllamaConfig.fromValues(
      modelName = "llama2",
      baseUrl = "http://localhost:11434"
    )

    config.contextWindow shouldBe 4096
  }

  test("OllamaConfig.fromValues sets context window for mistral") {
    val config = OllamaConfig.fromValues(
      modelName = "mistral",
      baseUrl = "http://localhost:11434"
    )

    // Context window may come from ModelRegistry or fallback logic
    config.contextWindow should be > 0
  }

  test("OllamaConfig.fromValues throws for empty baseUrl") {
    an[IllegalArgumentException] should be thrownBy {
      OllamaConfig.fromValues(
        modelName = "llama3",
        baseUrl = ""
      )
    }
  }

  test("OllamaConfig.fromValues sets reserveCompletion for all models") {
    val config = OllamaConfig.fromValues("llama3", "http://localhost:11434")
    // reserveCompletion may come from ModelRegistry or fallback logic
    config.reserveCompletion should be > 0
  }

  // ================================= ZAI CONFIG =================================

  test("ZaiConfig.fromValues creates config with correct model") {
    val config = ZaiConfig.fromValues(
      modelName = "GLM-4.7",
      apiKey = "test-key",
      baseUrl = "https://api.z.ai/api/paas/v4"
    )

    config.model shouldBe "GLM-4.7"
    config.apiKey shouldBe "test-key"
    config.baseUrl shouldBe "https://api.z.ai/api/paas/v4"
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.7") {
    val config = ZaiConfig.fromValues(
      modelName = "GLM-4.7",
      apiKey = "test-key",
      baseUrl = "https://api.z.ai/api/paas/v4"
    )

    config.contextWindow shouldBe 128000
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.5-air") {
    val config = ZaiConfig.fromValues(
      modelName = "GLM-4.5-air",
      apiKey = "test-key",
      baseUrl = "https://api.z.ai/api/paas/v4"
    )

    config.contextWindow shouldBe 32000
  }

  test("ZaiConfig.fromValues throws for empty apiKey") {
    an[IllegalArgumentException] should be thrownBy {
      ZaiConfig.fromValues(
        modelName = "GLM-4.7",
        apiKey = "",
        baseUrl = "https://api.z.ai/api/paas/v4"
      )
    }
  }

  test("ZaiConfig.fromValues throws for empty baseUrl") {
    an[IllegalArgumentException] should be thrownBy {
      ZaiConfig.fromValues(
        modelName = "GLM-4.7",
        apiKey = "test-key",
        baseUrl = ""
      )
    }
  }

  test("ZaiConfig.fromValues sets reserveCompletion for all models") {
    val config = ZaiConfig.fromValues("GLM-4.7", "test-key", "https://api.z.ai/api/paas/v4")
    config.reserveCompletion should be > 0
  }

  // ================================= PROVIDER CONFIG TRAIT =================================

  test("All config types implement ProviderConfig trait") {
    val openai: ProviderConfig = OpenAIConfig.fromValues("gpt-4o", "key", None, "https://api.openai.com/v1")
    val anthropic: ProviderConfig =
      AnthropicConfig.fromValues("claude-3-sonnet", "key", "https://api.anthropic.com")
    val ollama: ProviderConfig = OllamaConfig.fromValues("llama3", "http://localhost:11434")
    val azure: ProviderConfig =
      AzureConfig.fromValues("gpt-4o", "https://azure.openai.com", "key", "2024-02-15")
    val zai: ProviderConfig =
      ZaiConfig.fromValues("GLM-4.7", "key", "https://api.z.ai/api/paas/v4")

    openai.model shouldBe "gpt-4o"
    anthropic.model shouldBe "claude-3-sonnet"
    ollama.model shouldBe "llama3"
    azure.model shouldBe "gpt-4o"
    zai.model shouldBe "GLM-4.7"
  }
}
