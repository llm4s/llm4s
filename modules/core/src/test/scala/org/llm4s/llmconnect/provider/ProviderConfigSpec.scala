package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // ================================= OPENAI CONFIG =================================

  test("OpenAIConfig.fromValues creates config with correct model") {
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "test-key",
        organization = Some("test-org"),
        baseUrl = "https://api.openai.com/v1"
      )
      .value

    config.model shouldBe "gpt-4o"
    config.apiKey shouldBe "test-key"
    config.organization shouldBe Some("test-org")
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4o") {
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "test-key",
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value

    config.contextWindow shouldBe 128000
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4") {
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4",
        apiKey = "test-key",
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value

    config.contextWindow shouldBe 8192
  }

  test("OpenAIConfig.fromValues fails for empty apiKey") {
    OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "",
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("OpenAIConfig.fromValues fails for empty baseUrl") {
    OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "test-key",
        organization = None,
        baseUrl = ""
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  // ================================= ANTHROPIC CONFIG =================================

  test("AnthropicConfig.fromValues creates config with correct model") {
    val config = AnthropicConfig
      .fromValues(
        modelName = "claude-3-sonnet-20240229",
        apiKey = "test-key",
        baseUrl = "https://api.anthropic.com"
      )
      .value

    config.model shouldBe "claude-3-sonnet-20240229"
    config.apiKey shouldBe "test-key"
  }

  test("AnthropicConfig.fromValues sets large context window for claude-3") {
    val config = AnthropicConfig
      .fromValues(
        modelName = "claude-3-opus-20240229",
        apiKey = "test-key",
        baseUrl = "https://api.anthropic.com"
      )
      .value

    config.contextWindow shouldBe 200000
  }

  test("AnthropicConfig.fromValues fails for empty apiKey") {
    AnthropicConfig
      .fromValues(
        modelName = "claude-3-sonnet",
        apiKey = "",
        baseUrl = "https://api.anthropic.com"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  // ================================= GEMINI CONFIG =================================

  test("GeminiConfig.fromValues appends v1beta when baseUrl is the API host root") {
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-1.5-flash",
        apiKey = "test-key",
        baseUrl = "https://generativelanguage.googleapis.com"
      )
      .value

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  test("GeminiConfig.fromValues preserves explicit versioned baseUrl") {
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-1.5-flash",
        apiKey = "test-key",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
      )
      .value

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  // ================================= AZURE CONFIG =================================

  test("AzureConfig.fromValues creates config with correct model") {
    val config = AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "https://my-resource.openai.azure.com",
        apiKey = "test-key",
        apiVersion = "2024-02-15-preview"
      )
      .value

    config.model shouldBe "gpt-4o"
    config.endpoint shouldBe "https://my-resource.openai.azure.com"
    config.apiVersion shouldBe "2024-02-15-preview"
  }

  test("AzureConfig.fromValues fails for empty endpoint") {
    AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "",
        apiKey = "test-key",
        apiVersion = "2024-02-15-preview"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  // ================================= ZAI CONFIG =================================

  test("ZaiConfig.fromValues creates config with correct model") {
    val config = ZaiConfig
      .fromValues(
        modelName = "GLM-4.7",
        apiKey = "test-key",
        baseUrl = "https://api.z.ai/api/paas/v4"
      )
      .value

    config.model shouldBe "GLM-4.7"
    config.apiKey shouldBe "test-key"
    config.baseUrl shouldBe "https://api.z.ai/api/paas/v4"
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.7") {
    val config = ZaiConfig
      .fromValues(
        modelName = "GLM-4.7",
        apiKey = "test-key",
        baseUrl = "https://api.z.ai/api/paas/v4"
      )
      .value

    config.contextWindow shouldBe 200000
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.5-air") {
    val config = ZaiConfig
      .fromValues(
        modelName = "GLM-4.5-air",
        apiKey = "test-key",
        baseUrl = "https://api.z.ai/api/paas/v4"
      )
      .value

    config.contextWindow shouldBe 128000
  }

  test("ZaiConfig.fromValues fails for empty apiKey") {
    ZaiConfig
      .fromValues(
        modelName = "GLM-4.7",
        apiKey = "",
        baseUrl = "https://api.z.ai/api/paas/v4"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("ZaiConfig.fromValues fails for empty baseUrl") {
    ZaiConfig
      .fromValues(
        modelName = "GLM-4.7",
        apiKey = "test-key",
        baseUrl = ""
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("ZaiConfig.fromValues sets reserveCompletion for all models") {
    val config = ZaiConfig.fromValues("GLM-4.7", "test-key", "https://api.z.ai/api/paas/v4").value
    config.reserveCompletion should be > 0
  }

  // ================================= PROVIDER CONFIG TRAIT =================================

  test("All config types implement ProviderConfig trait") {
    val openai: ProviderConfig = OpenAIConfig.fromValues("gpt-4o", "key", None, "https://api.openai.com/v1").value
    val anthropic: ProviderConfig =
      AnthropicConfig.fromValues("claude-3-sonnet", "key", "https://api.anthropic.com").value
    val azure: ProviderConfig =
      AzureConfig.fromValues("gpt-4o", "https://azure.openai.com", "key", "2024-02-15").value
    val zai: ProviderConfig =
      ZaiConfig.fromValues("GLM-4.7", "key", "https://api.z.ai/api/paas/v4").value

    openai.model shouldBe "gpt-4o"
    anthropic.model shouldBe "claude-3-sonnet"
    azure.model shouldBe "gpt-4o"
    zai.model shouldBe "GLM-4.7"
  }

  // ============================ fromValues VALIDATION ============================

  test("fromValues names the provider and the field, and reports the field as missing") {
    val error = OpenAIConfig.fromValues("gpt-4o", "   ", None, "https://api.openai.com/v1").left.value

    error.message shouldBe "OpenAI apiKey must be non-empty"
    error match {
      case ConfigurationError(_, missingKeys) => missingKeys shouldBe List("apiKey")
      case other                              => fail(s"expected a ConfigurationError, got $other")
    }
  }

  test("fromValues reports the first blank field when several are blank") {
    AnthropicConfig.fromValues("claude-3", "", "").left.value.message shouldBe "Anthropic apiKey must be non-empty"
  }

  test("every fromValues factory returns a Left for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "Azure endpoint"      -> AzureConfig.fromValues("gpt-4o", " ", "key", "2024-02-15"),
      "Azure apiKey"        -> AzureConfig.fromValues("gpt-4o", "https://azure.example", " ", "2024-02-15"),
      "Anthropic baseUrl"   -> AnthropicConfig.fromValues("claude-3", "key", " "),
      "Gemini apiKey"       -> GeminiConfig.fromValues("gemini-2.0-flash", " ", "https://example.invalid"),
      "Gemini baseUrl"      -> GeminiConfig.fromValues("gemini-2.0-flash", "key", " "),
      "DeepSeek apiKey"     -> DeepSeekConfig.fromValues("deepseek-chat", " ", DeepSeekConfig.DEFAULT_BASE_URL),
      "DeepSeek baseUrl"    -> DeepSeekConfig.fromValues("deepseek-chat", "key", " "),
      "Cohere apiKey"       -> CohereConfig.fromValues("command-r", " ", CohereConfig.DEFAULT_BASE_URL),
      "Cohere baseUrl"      -> CohereConfig.fromValues("command-r", "key", " "),
      "Mistral apiKey"      -> MistralConfig.fromValues("mistral-small-latest", " ", MistralConfig.DEFAULT_BASE_URL),
      "Vertex AI projectId" -> VertexAIConfig.fromValues("gemini-2.0-flash", projectId = " "),
      "Vertex AI location"  -> VertexAIConfig.fromValues("gemini-2.0-flash", projectId = "p", location = " ")
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }

  test("VertexAIConfig.fromValues builds a config for a non-blank project and location") {
    val config = VertexAIConfig.fromValues("gemini-2.0-flash", projectId = "my-project").value

    config.projectId shouldBe "my-project"
    config.location shouldBe VertexAIConfig.DEFAULT_LOCATION
  }
}
