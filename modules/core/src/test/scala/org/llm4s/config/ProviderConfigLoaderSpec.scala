package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config._
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * Comprehensive unit tests for ProviderConfigLoader validation and parsing.
 *
 * These tests use ConfigSource.string() to provide deterministic HOCON input
 * without relying on environment variables or external configuration files.
 */
class ProviderConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  // --------------------------------------------------------------------------
  // OpenAI Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for OpenAI" should {

    "successfully load valid OpenAI config with explicit prefix" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    apiKey = "sk-test-12345"
          |    baseUrl = "https://api.openai.com/v1"
          |    organization = "org-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[OpenAIConfig]
      val openai = cfg.asInstanceOf[OpenAIConfig]
      openai.model shouldBe "gpt-4o"
      openai.apiKey shouldBe "sk-test-12345"
      openai.baseUrl shouldBe "https://api.openai.com/v1"
      openai.organization shouldBe Some("org-test")
    }

    "successfully load OpenAI config without organization (optional field)" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o-mini" }
          |  openai {
          |    apiKey = "sk-test"
          |    baseUrl = "https://api.openai.com/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[OpenAIConfig]
      cfg.organization shouldBe None
    }

    "fail with clear error when OpenAI API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    baseUrl = "https://api.openai.com/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error shouldBe a[ConfigurationError]
      error.message should include("Missing OpenAI API key")
      error.message should include("OPENAI_API_KEY")
    }

    "fail with clear error when OpenAI API key is empty string" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    apiKey = "   "
          |    baseUrl = "https://api.openai.com/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Missing OpenAI API key")
    }

    "fail with clear error when openai section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("OpenAI provider selected")
      error.message should include("llm4s.openai section is missing")
    }

    "use default base URL when baseUrl is not provided" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[OpenAIConfig]
      cfg.baseUrl shouldBe DefaultConfig.DEFAULT_OPENAI_BASE_URL
    }
  }

  // --------------------------------------------------------------------------
  // Azure Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Azure" should {

    "successfully load valid Azure config" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "azure/gpt-4o" }
          |  azure {
          |    endpoint = "https://my-resource.openai.azure.com"
          |    apiKey = "azure-key-12345"
          |    apiVersion = "2024-02-01"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[AzureConfig]
      val azure = cfg.asInstanceOf[AzureConfig]
      azure.model shouldBe "gpt-4o"
      azure.endpoint shouldBe "https://my-resource.openai.azure.com"
      azure.apiKey shouldBe "azure-key-12345"
      azure.apiVersion shouldBe "2024-02-01"
    }

    "use default API version when not provided" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "azure/gpt-4o" }
          |  azure {
          |    endpoint = "https://my-resource.openai.azure.com"
          |    apiKey = "azure-key"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[AzureConfig]
      cfg.apiVersion shouldBe DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW
    }

    "fail with clear error when Azure endpoint is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "azure/gpt-4o" }
          |  azure {
          |    apiKey = "azure-key"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Azure endpoint")
      error.message should include("AZURE_API_BASE")
    }

    "fail with clear error when Azure API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "azure/gpt-4o" }
          |  azure {
          |    endpoint = "https://my-resource.openai.azure.com"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Azure API key")
      error.message should include("AZURE_API_KEY")
    }

    "fail when azure section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "azure/gpt-4o" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Azure provider selected")
      result.left.value.message should include("llm4s.azure section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Anthropic Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Anthropic" should {

    "successfully load valid Anthropic config" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "anthropic/claude-3-5-sonnet-latest" }
          |  anthropic {
          |    apiKey = "sk-ant-api03-test"
          |    baseUrl = "https://api.anthropic.com"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[AnthropicConfig]
      val anthropic = cfg.asInstanceOf[AnthropicConfig]
      anthropic.model shouldBe "claude-3-5-sonnet-latest"
      anthropic.apiKey shouldBe "sk-ant-api03-test"
      anthropic.baseUrl shouldBe "https://api.anthropic.com"
    }

    "use default base URL when not provided" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "anthropic/claude-3-opus-20240229" }
          |  anthropic {
          |    apiKey = "sk-ant-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[AnthropicConfig]
      cfg.baseUrl shouldBe DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL
    }

    "fail with clear error when Anthropic API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "anthropic/claude-3-opus-20240229" }
          |  anthropic {
          |    baseUrl = "https://api.anthropic.com"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Anthropic API key")
      error.message should include("ANTHROPIC_API_KEY")
    }

    "fail when anthropic section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "anthropic/claude-3-opus" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Anthropic provider selected")
      result.left.value.message should include("llm4s.anthropic section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Ollama Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Ollama" should {

    "successfully load valid Ollama config" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "ollama/llama3.2" }
          |  ollama {
          |    baseUrl = "http://localhost:11434"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[OllamaConfig]
      val ollama = cfg.asInstanceOf[OllamaConfig]
      ollama.model shouldBe "llama3.2"
      ollama.baseUrl shouldBe "http://localhost:11434"
    }

    "fail with clear error when Ollama base URL is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "ollama/llama3.2" }
          |  ollama {
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Ollama base URL")
      error.message should include("OLLAMA_BASE_URL")
    }

    "fail when ollama section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "ollama/llama3" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Ollama provider selected")
      result.left.value.message should include("llm4s.ollama section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Gemini Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Gemini" should {

    "successfully load valid Gemini config with gemini prefix" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "gemini/gemini-2.0-flash" }
          |  gemini {
          |    apiKey = "AIza-test-key"
          |    baseUrl = "https://generativelanguage.googleapis.com/v1beta"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[GeminiConfig]
      val gemini = cfg.asInstanceOf[GeminiConfig]
      gemini.model shouldBe "gemini-2.0-flash"
      gemini.apiKey shouldBe "AIza-test-key"
    }

    "successfully load Gemini config with google prefix alias" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "google/gemini-1.5-pro" }
          |  gemini {
          |    apiKey = "AIza-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[GeminiConfig]
      cfg.model shouldBe "gemini-1.5-pro"
    }

    "use default base URL when not provided" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "gemini/gemini-2.0-flash" }
          |  gemini {
          |    apiKey = "AIza-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[GeminiConfig]
      cfg.baseUrl shouldBe DefaultConfig.DEFAULT_GEMINI_BASE_URL
    }

    "fail with clear error when Gemini API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "gemini/gemini-2.0-flash" }
          |  gemini {
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Gemini API key")
      error.message should include("GOOGLE_API_KEY")
    }

    "fail when gemini section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "gemini/gemini-2.0-flash" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Gemini provider selected")
      result.left.value.message should include("llm4s.gemini section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Z.ai Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Z.ai" should {

    "successfully load valid Z.ai config" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "zai/zai-model-v1" }
          |  zai {
          |    apiKey = "zai-key-test"
          |    baseUrl = "https://api.z.ai/api/paas/v4"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[ZaiConfig]
      val zai = cfg.asInstanceOf[ZaiConfig]
      zai.model shouldBe "zai-model-v1"
      zai.apiKey shouldBe "zai-key-test"
    }

    "fail with clear error when Z.ai API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "zai/zai-model" }
          |  zai {
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing Z.ai API key")
      error.message should include("ZAI_API_KEY")
    }

    "fail when zai section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "zai/zai-model" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Z.ai provider selected")
      result.left.value.message should include("llm4s.zai section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Cohere Provider Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader for Cohere" should {

    "successfully load valid Cohere config with cohere prefix" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "cohere/command-r-08-2024" }
          |  cohere {
          |    apiKey = "cohere-test-key"
          |    baseUrl = "https://api.cohere.com"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value
      cfg shouldBe a[CohereConfig]
      val cohere = cfg.asInstanceOf[CohereConfig]
      cohere.model shouldBe "command-r-08-2024"
      cohere.apiKey shouldBe "cohere-test-key"
      cohere.baseUrl shouldBe "https://api.cohere.com"
    }

    "fail with clear error when Cohere API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "cohere/command-r-08-2024" }
          |  cohere {
          |    baseUrl = "https://api.cohere.com"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error shouldBe a[ConfigurationError]
      error.message should include("Missing Cohere API key")
      error.message should include("COHERE_API_KEY")
    }

    "use default base URL when not provided" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "cohere/command-r-08-2024" }
          |  cohere {
          |    apiKey = "cohere-test-key"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[CohereConfig]
      cfg.baseUrl shouldBe "https://api.cohere.com"
    }

    "fail when cohere section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "cohere/command-r" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Cohere provider selected")
      result.left.value.message should include("llm4s.cohere section is missing")
    }
  }

  // --------------------------------------------------------------------------
  // Model Spec Validation Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader model spec validation" should {

    "fail with clear error when model is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  llm { }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing model spec")
      error.message should include("LLM_MODEL")
    }

    "fail with clear error when model is empty string" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "  " }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Missing model spec")
    }

    "fail with clear error for unknown provider prefix" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "unknownprovider/some-model" }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Unknown provider prefix")
      error.message should include("unknownprovider")
    }

    "support OpenRouter via openai prefix with custom baseUrl" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openrouter/meta-llama/llama-3.3-70b" }
          |  openai {
          |    apiKey = "sk-or-test"
          |    baseUrl = "https://openrouter.ai/api/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[OpenAIConfig]
      cfg.model shouldBe "meta-llama/llama-3.3-70b"
      cfg.baseUrl shouldBe "https://openrouter.ai/api/v1"
    }

    "infer OpenRouter provider from baseUrl when no explicit prefix" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "gpt-4o" }
          |  openai {
          |    apiKey = "sk-or-test"
          |    baseUrl = "https://openrouter.ai/api/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      // Should succeed and use the OpenAI-compatible path (openrouter)
      result.isRight shouldBe true
      val cfg = result.value.asInstanceOf[OpenAIConfig]
      cfg.model shouldBe "gpt-4o"
    }
  }

  // --------------------------------------------------------------------------
  // Malformed Configuration Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader with malformed config" should {

    "fail gracefully when llm4s root is missing" in {
      val hocon =
        """
          |someOtherConfig {
          |  value = "test"
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Failed to load llm4s provider config via PureConfig")
    }

    "fail gracefully when llm section has wrong structure" in {
      val hocon =
        """
          |llm4s {
          |  llm = "invalid-scalar-value"
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Failed to load llm4s provider config via PureConfig")
    }

    "preserve error context from PureConfig when parsing fails" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai = "this should be an object not a string"
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("PureConfig")
    }
  }

  // --------------------------------------------------------------------------
  // loadOpenAISharedApiKey Tests
  // --------------------------------------------------------------------------

  "ProviderConfigLoader.loadOpenAISharedApiKey" should {

    "successfully extract OpenAI API key from config" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    apiKey = "sk-shared-key-123"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.loadOpenAISharedApiKey(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value shouldBe "sk-shared-key-123"
    }

    "fail with clear error when API key is missing for shared key lookup" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    baseUrl = "https://api.openai.com/v1"
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.loadOpenAISharedApiKey(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing OpenAI API key")
    }

    "trim whitespace from API key" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai {
          |    apiKey = "  sk-trimmed  "
          |  }
          |}
          |""".stripMargin

      val result = ProviderConfigLoader.loadOpenAISharedApiKey(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value shouldBe "sk-trimmed"
    }
  }
}
