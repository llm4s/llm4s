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

  // ============================ fromValues VALIDATION ============================

  test("fromValues names the provider and the field, and reports the field as missing") {
    val error = OpenAIConfig.fromValues("gpt-4o", "   ", None, "https://api.openai.com/v1").left.value

    error.message shouldBe "OpenAI apiKey must be non-empty"
    error match {
      case ConfigurationError(_, missingKeys) => missingKeys shouldBe List("apiKey")
      case other                              => fail(s"expected a ConfigurationError, got $other")
    }
  }

  // The DeepSeek fromValues cases moved to llm4s-openai-compatible's
  // OpenAICompatibleProviderConfigSpec with DeepSeekConfig (#1132).

  test("every fromValues factory returns a Left for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "Cohere apiKey"  -> CohereConfig.fromValues("command-r", " ", CohereConfig.DEFAULT_BASE_URL),
      "Cohere baseUrl" -> CohereConfig.fromValues("command-r", "key", " "),
      "Mistral apiKey" -> MistralConfig.fromValues("mistral-small-latest", " ", MistralConfig.DEFAULT_BASE_URL)
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }
}
