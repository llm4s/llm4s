package org.llm4s.config

import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.llm4s.llmconnect.config.{
  AnthropicConfig,
  AzureConfig,
  CohereConfig,
  DeepSeekConfig,
  GeminiConfig,
  MistralConfig,
  OllamaConfig,
  OpenAIConfig,
  ProviderConfig,
  ZaiConfig
}
import org.llm4s.types.Result

class ProviderConfigLegacyReadSpec extends AnyWordSpec with Matchers:

  private def loadLegacyProviderConfig(providerAndModel: String): Result[ProviderConfig] =
    val hocon =
      s"""
         |llm4s {
         |  llm {
         |    model = "$providerAndModel"
         |  }
         |  openai {
         |    apiKey = "sk-test"
         |    baseUrl = "https://api.openai.com/v1"
         |    organization = "org-demo"
         |  }
         |  azure {
         |    endpoint = "https://my-resource.openai.azure.com/openai/deployments/gpt-4o"
         |    apiKey = "azure-key"
         |    apiVersion = "2024-02-01"
         |  }
         |  anthropic {
         |    apiKey = "sk-ant-test"
         |    baseUrl = "https://api.anthropic.com"
         |  }
         |  ollama {
         |    baseUrl = "http://localhost:11434"
         |  }
         |  gemini {
         |    apiKey = "google-key"
         |    baseUrl = "https://generativelanguage.googleapis.com/v1beta"
         |  }
         |  deepseek {
         |    apiKey = "deepseek-key"
         |    baseUrl = "https://api.deepseek.com"
         |  }
         |  cohere {
         |    apiKey = "cohere-key"
         |    baseUrl = "https://api.cohere.com"
         |  }
         |  mistral {
         |    apiKey = "mistral-key"
         |    baseUrl = "https://api.mistral.ai"
         |  }
         |  zai {
         |    apiKey = "zai-key"
         |    baseUrl = "https://api.z.ai/api/paas/v4"
         |  }
         |}
         |""".stripMargin

    ProviderConfigLoader.load(ConfigSource.string(hocon))

  private def loadLegacyProviderConfigWithoutOpenAI(providerAndModel: String): Result[ProviderConfig] =
    val hocon =
      s"""
         |llm4s {
         |  llm {
         |    model = "$providerAndModel"
         |  }
         |  azure {
         |    endpoint = "https://my-resource.openai.azure.com/openai/deployments/gpt-4o"
         |    apiKey = "azure-key"
         |    apiVersion = "2024-02-01"
         |  }
         |  anthropic {
         |    apiKey = "sk-ant-test"
         |    baseUrl = "https://api.anthropic.com"
         |  }
         |  ollama {
         |    baseUrl = "http://localhost:11434"
         |  }
         |  gemini {
         |    apiKey = "google-key"
         |    baseUrl = "https://generativelanguage.googleapis.com/v1beta"
         |  }
         |  deepseek {
         |    apiKey = "deepseek-key"
         |    baseUrl = "https://api.deepseek.com"
         |  }
         |  cohere {
         |    apiKey = "cohere-key"
         |    baseUrl = "https://api.cohere.com"
         |  }
         |  mistral {
         |    apiKey = "mistral-key"
         |    baseUrl = "https://api.mistral.ai"
         |  }
         |  zai {
         |    apiKey = "zai-key"
         |    baseUrl = "https://api.z.ai/api/paas/v4"
         |  }
         |}
         |""".stripMargin

    ProviderConfigLoader.load(ConfigSource.string(hocon))

  private def loadLegacyProviderConfigWithOpenAIMissingApiKey(providerAndModel: String): Result[ProviderConfig] =
    val hocon =
      s"""
         |llm4s {
         |  llm {
         |    model = "$providerAndModel"
         |  }
         |  openai {
         |    baseUrl = "https://api.openai.com/v1"
         |    organization = "org-demo"
         |  }
         |  azure {
         |    endpoint = "https://my-resource.openai.azure.com/openai/deployments/gpt-4o"
         |    apiKey = "azure-key"
         |    apiVersion = "2024-02-01"
         |  }
         |  anthropic {
         |    apiKey = "sk-ant-test"
         |    baseUrl = "https://api.anthropic.com"
         |  }
         |  ollama {
         |    baseUrl = "http://localhost:11434"
         |  }
         |  gemini {
         |    apiKey = "google-key"
         |    baseUrl = "https://generativelanguage.googleapis.com/v1beta"
         |  }
         |  deepseek {
         |    apiKey = "deepseek-key"
         |    baseUrl = "https://api.deepseek.com"
         |  }
         |  cohere {
         |    apiKey = "cohere-key"
         |    baseUrl = "https://api.cohere.com"
         |  }
         |  mistral {
         |    apiKey = "mistral-key"
         |    baseUrl = "https://api.mistral.ai"
         |  }
         |  zai {
         |    apiKey = "zai-key"
         |    baseUrl = "https://api.z.ai/api/paas/v4"
         |  }
         |}
         |""".stripMargin

    ProviderConfigLoader.load(ConfigSource.string(hocon))

  "ProviderConfigLoader legacy config reading" should {

    "keep supporting the legacy OpenAI llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("openai/gpt-4o-mini")

      result match
        case Right(cfg: OpenAIConfig) =>
          cfg.model shouldBe "gpt-4o-mini"
          cfg.apiKey shouldBe "sk-test"
          cfg.baseUrl shouldBe "https://api.openai.com/v1"
          cfg.organization shouldBe Some("org-demo")
        case Right(other) =>
          fail(s"Expected OpenAIConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected OpenAIConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Azure llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("azure/gpt-4o")

      result match
        case Right(cfg: AzureConfig) =>
          cfg.model shouldBe "gpt-4o"
          cfg.endpoint shouldBe "https://my-resource.openai.azure.com/openai/deployments/gpt-4o"
          cfg.apiKey shouldBe "azure-key"
          cfg.apiVersion shouldBe "2024-02-01"
        case Right(other) =>
          fail(s"Expected AzureConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected AzureConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Anthropic llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("anthropic/claude-sonnet-4-20250514")

      result match
        case Right(cfg: AnthropicConfig) =>
          cfg.model shouldBe "claude-sonnet-4-20250514"
          cfg.apiKey shouldBe "sk-ant-test"
          cfg.baseUrl shouldBe "https://api.anthropic.com"
        case Right(other) =>
          fail(s"Expected AnthropicConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected AnthropicConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Ollama llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("ollama/llama3:latest")

      result match
        case Right(cfg: OllamaConfig) =>
          cfg.model shouldBe "llama3:latest"
          cfg.baseUrl shouldBe "http://localhost:11434"
        case Right(other) =>
          fail(s"Expected OllamaConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected OllamaConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Gemini llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("gemini/gemini-2.5-flash")

      result match
        case Right(cfg: GeminiConfig) =>
          cfg.model shouldBe "gemini-2.5-flash"
          cfg.apiKey shouldBe "google-key"
          cfg.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
        case Right(other) =>
          fail(s"Expected GeminiConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected GeminiConfig, got error: ${err.message}")
    }

    "keep supporting the legacy DeepSeek llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("deepseek/deepseek-chat")

      result match
        case Right(cfg: DeepSeekConfig) =>
          cfg.model shouldBe "deepseek-chat"
          cfg.apiKey shouldBe "deepseek-key"
          cfg.baseUrl shouldBe "https://api.deepseek.com"
        case Right(other) =>
          fail(s"Expected DeepSeekConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected DeepSeekConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Cohere llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("cohere/command-r-plus")

      result match
        case Right(cfg: CohereConfig) =>
          cfg.model shouldBe "command-r-plus"
          cfg.apiKey shouldBe "cohere-key"
          cfg.baseUrl shouldBe "https://api.cohere.com"
        case Right(other) =>
          fail(s"Expected CohereConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected CohereConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Mistral llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("mistral/mistral-large-latest")

      result match
        case Right(cfg: MistralConfig) =>
          cfg.model shouldBe "mistral-large-latest"
          cfg.apiKey shouldBe "mistral-key"
          cfg.baseUrl shouldBe "https://api.mistral.ai"
        case Right(other) =>
          fail(s"Expected MistralConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected MistralConfig, got error: ${err.message}")
    }

    "keep supporting the legacy Z.ai llm.model provider selection shape" in {
      val result = loadLegacyProviderConfig("zai/GLM-4.7")

      result match
        case Right(cfg: ZaiConfig) =>
          cfg.model shouldBe "GLM-4.7"
          cfg.apiKey shouldBe "zai-key"
          cfg.baseUrl shouldBe "https://api.z.ai/api/paas/v4"
        case Right(other) =>
          fail(s"Expected ZaiConfig, got ${other.getClass.getSimpleName}: $other")
        case Left(err) =>
          fail(s"Expected ZaiConfig, got error: ${err.message}")
    }

    "fail clearly when the legacy llm.model has an unknown provider prefix" in {
      val result = loadLegacyProviderConfig("moonbeam/gpt-4o-mini")

      result match
        case Left(err) =>
          err.message should include("Unknown provider")
          err.message should include("moonbeam")
        case Right(cfg) =>
          fail(s"Expected unknown legacy provider error, got config: $cfg")
    }

    "fail clearly when the selected legacy provider section is missing" in {
      val result = loadLegacyProviderConfigWithoutOpenAI("openai/gpt-4o-mini")

      result match
        case Left(err) =>
          err.message should include("OpenAI provider selected")
          err.message should include("llm4s.openai section is missing")
        case Right(cfg) =>
          fail(s"Expected missing OpenAI section error, got config: $cfg")
    }

    "fail clearly when the selected OpenAI legacy provider is missing its API key" in {
      val result = loadLegacyProviderConfigWithOpenAIMissingApiKey("openai/gpt-4o-mini")

      result match
        case Left(err) =>
          err.message should include("Missing OpenAI API key")
          err.message should include("OPENAI_API_KEY")
        case Right(cfg) =>
          fail(s"Expected missing OpenAI API key error, got config: $cfg")
    }
  }
