package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `fromValues` for the configs `llm4s-openai-compatible` holds. The DeepSeek cases are core's
 * `ProviderConfigSpec` cases, moved here with `DeepSeekConfig` (#1132); the rest cover the
 * generic `OpenAICompatibleConfig`.
 */
class OpenAICompatibleProviderConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // ============================ fromValues VALIDATION ============================

  test("fromValues names the provider and the field, and reports the field as missing") {
    val error =
      OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("   "), BaseUrl("https://api.openai.com/v1")).left.value

    error.message shouldBe "OpenAI apiKey must be non-empty"
    error match {
      case ConfigurationError(_, missingKeys) => missingKeys shouldBe List("apiKey")
      case other                              => fail(s"expected a ConfigurationError, got $other")
    }
  }

  test("fromValues reports the first blank field when several are blank") {
    DeepSeekConfig
      .fromValues(ModelName("deepseek-chat"), Secret(""), BaseUrl(""))
      .left
      .value
      .message shouldBe "DeepSeek apiKey must be non-empty"
  }

  test("every fromValues factory returns a Left for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "DeepSeek apiKey" -> DeepSeekConfig
        .fromValues(ModelName("deepseek-chat"), Secret(" "), DeepSeekConfig.DEFAULT_BASE_URL),
      "DeepSeek baseUrl" -> DeepSeekConfig.fromValues(ModelName("deepseek-chat"), Secret("key"), BaseUrl(" ")),
      "Mistral apiKey" -> MistralConfig
        .fromValues(ModelName("mistral-small-latest"), Secret(" "), MistralConfig.DEFAULT_BASE_URL),
      "Cohere apiKey"  -> CohereConfig.fromValues(ModelName("command-r"), Secret(" "), CohereConfig.DEFAULT_BASE_URL),
      "Cohere baseUrl" -> CohereConfig.fromValues(ModelName("command-r"), Secret("key"), BaseUrl(" ")),
      "OpenAI-compatible model" -> OpenAICompatibleConfig
        .fromValues(ModelName(" "), BaseUrl("http://localhost:8000/v1")),
      "OpenAI-compatible baseUrl" -> OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl(" "))
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }

  // ================================= OPENAI CONFIG =================================

  test("OpenAIConfig.fromValues creates config with correct model") {
    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName("gpt-4o"),
        apiKey = Secret("test-key"),
        organization = Some("test-org"),
        baseUrl = BaseUrl("https://api.openai.com/v1")
      )
      .value

    config.model.asString shouldBe "gpt-4o"
    config.apiKey.reveal shouldBe "test-key"
    config.organization shouldBe Some("test-org")
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4o") {
    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName("gpt-4o"),
        apiKey = Secret("test-key"),
        organization = None,
        baseUrl = BaseUrl("https://api.openai.com/v1")
      )
      .value

    config.contextWindow shouldBe 128000
  }

  test("OpenAIConfig.fromValues sets correct context window for gpt-4") {
    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName("gpt-4"),
        apiKey = Secret("test-key"),
        organization = None,
        baseUrl = BaseUrl("https://api.openai.com/v1")
      )
      .value

    config.contextWindow shouldBe 8192
  }

  test("OpenAIConfig.fromValues fails for empty apiKey") {
    OpenAIConfig
      .fromValues(
        modelName = ModelName("gpt-4o"),
        apiKey = Secret(""),
        organization = None,
        baseUrl = BaseUrl("https://api.openai.com/v1")
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("OpenAIConfig.fromValues fails for empty baseUrl") {
    OpenAIConfig
      .fromValues(
        modelName = ModelName("gpt-4o"),
        apiKey = Secret("test-key"),
        organization = None,
        baseUrl = BaseUrl("")
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  // ================================= ZAI CONFIG =================================

  test("ZaiConfig.fromValues creates config with correct model") {
    val config = ZaiConfig
      .fromValues(
        modelName = ModelName("GLM-4.7"),
        apiKey = Secret("test-key"),
        baseUrl = BaseUrl("https://api.z.ai/api/paas/v4")
      )
      .value

    config.model.asString shouldBe "GLM-4.7"
    config.apiKey.reveal shouldBe "test-key"
    config.baseUrl shouldBe BaseUrl("https://api.z.ai/api/paas/v4")
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.7") {
    val config = ZaiConfig
      .fromValues(
        modelName = ModelName("GLM-4.7"),
        apiKey = Secret("test-key"),
        baseUrl = BaseUrl("https://api.z.ai/api/paas/v4")
      )
      .value

    config.contextWindow shouldBe 200000
  }

  test("ZaiConfig.fromValues sets correct context window for GLM-4.5-air") {
    val config = ZaiConfig
      .fromValues(
        modelName = ModelName("GLM-4.5-air"),
        apiKey = Secret("test-key"),
        baseUrl = BaseUrl("https://api.z.ai/api/paas/v4")
      )
      .value

    config.contextWindow shouldBe 128000
  }

  test("ZaiConfig.fromValues fails for empty apiKey") {
    ZaiConfig
      .fromValues(
        modelName = ModelName("GLM-4.7"),
        apiKey = Secret(""),
        baseUrl = BaseUrl("https://api.z.ai/api/paas/v4")
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("ZaiConfig.fromValues fails for empty baseUrl") {
    ZaiConfig
      .fromValues(
        modelName = ModelName("GLM-4.7"),
        apiKey = Secret("test-key"),
        baseUrl = BaseUrl("")
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("ZaiConfig.fromValues sets reserveCompletion for all models") {
    val config =
      ZaiConfig.fromValues(ModelName("GLM-4.7"), Secret("test-key"), BaseUrl("https://api.z.ai/api/paas/v4")).value
    config.reserveCompletion should be > 0
  }

  // ================================= PROVIDER CONFIG TRAIT =================================

  test("All config types implement ProviderConfig trait") {
    val openai: ProviderConfig =
      OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("key"), BaseUrl("https://api.openai.com/v1")).value
    val zai: ProviderConfig =
      ZaiConfig.fromValues(ModelName("GLM-4.7"), Secret("key"), BaseUrl("https://api.z.ai/api/paas/v4")).value

    openai.model.asString shouldBe "gpt-4o"
    zai.model.asString shouldBe "GLM-4.7"
  }

  // ============================ OpenAICompatibleConfig ============================

  test("OpenAICompatibleConfig.fromValues needs no API key, and treats a blank one as none") {
    OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl("http://localhost:8000/v1")).value.apiKey shouldBe None
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://localhost:8000/v1"), apiKey = Some(Secret("  ")))
      .value
      .apiKey shouldBe None
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), apiKey = Some(Secret(" k ")))
      .value
      .apiKey shouldBe Some(
      Secret("k")
    )
  }

  test("OpenAICompatibleConfig.fromValues defaults the window and reserve conservatively") {
    val cfg = OpenAICompatibleConfig.fromValues(ModelName("m"), BaseUrl("http://localhost:8000/v1/")).value
    cfg.contextWindow shouldBe OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW
    cfg.reserveCompletion shouldBe OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION
    cfg.baseUrl shouldBe BaseUrl("http://localhost:8000/v1")
  }

  test("OpenAICompatibleConfig.fromValues keeps the default reserve below a small configured window") {
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), contextWindow = Some(4096))
      .value
      .reserveCompletion shouldBe 1024
  }

  test("OpenAICompatibleConfig.fromValues takes a configured window, reserve and headers") {
    val cfg = OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), None, Some(131072), Some(8192), Map("X-Team" -> "search"))
      .value
    (cfg.contextWindow, cfg.reserveCompletion, cfg.headers) shouldBe (131072, 8192, Map("X-Team" -> "search"))
  }

  test("OpenAICompatibleConfig.fromValues rejects a non-positive window or an impossible reserve") {
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), contextWindow = Some(0))
      .left
      .value
      .message should include("contextWindow must be positive")
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), contextWindow = Some(1000), reserveCompletion = Some(1000))
      .left
      .value
      .message should include("reserveCompletion must be at least 0 and less than contextWindow (1000)")
    OpenAICompatibleConfig
      .fromValues(ModelName("m"), BaseUrl("http://h/v1"), reserveCompletion = Some(-1))
      .isLeft shouldBe true
  }
}
