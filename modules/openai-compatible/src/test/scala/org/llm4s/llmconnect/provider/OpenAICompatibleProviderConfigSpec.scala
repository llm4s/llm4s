package org.llm4s.llmconnect.provider

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

  test("fromValues reports the first blank field when several are blank") {
    DeepSeekConfig.fromValues("deepseek-chat", "", "").left.value.message shouldBe "DeepSeek apiKey must be non-empty"
  }

  test("every fromValues factory returns a Left for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "DeepSeek apiKey"           -> DeepSeekConfig.fromValues("deepseek-chat", " ", DeepSeekConfig.DEFAULT_BASE_URL),
      "DeepSeek baseUrl"          -> DeepSeekConfig.fromValues("deepseek-chat", "key", " "),
      "OpenAI-compatible model"   -> OpenAICompatibleConfig.fromValues(" ", "http://localhost:8000/v1"),
      "OpenAI-compatible baseUrl" -> OpenAICompatibleConfig.fromValues("m", " ")
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }

  // ============================ OpenAICompatibleConfig ============================

  test("OpenAICompatibleConfig.fromValues needs no API key, and treats a blank one as none") {
    OpenAICompatibleConfig.fromValues("m", "http://localhost:8000/v1").value.apiKey shouldBe None
    OpenAICompatibleConfig.fromValues("m", "http://localhost:8000/v1", apiKey = Some("  ")).value.apiKey shouldBe None
    OpenAICompatibleConfig.fromValues("m", "http://h/v1", apiKey = Some(" k ")).value.apiKey shouldBe Some("k")
  }

  test("OpenAICompatibleConfig.fromValues defaults the window and reserve conservatively") {
    val cfg = OpenAICompatibleConfig.fromValues("m", "http://localhost:8000/v1/").value
    cfg.contextWindow shouldBe OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW
    cfg.reserveCompletion shouldBe OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION
    cfg.baseUrl shouldBe "http://localhost:8000/v1"
  }

  test("OpenAICompatibleConfig.fromValues keeps the default reserve below a small configured window") {
    OpenAICompatibleConfig
      .fromValues("m", "http://h/v1", contextWindow = Some(4096))
      .value
      .reserveCompletion shouldBe 1024
  }

  test("OpenAICompatibleConfig.fromValues takes a configured window, reserve and headers") {
    val cfg = OpenAICompatibleConfig
      .fromValues("m", "http://h/v1", None, Some(131072), Some(8192), Map("X-Team" -> "search"))
      .value
    (cfg.contextWindow, cfg.reserveCompletion, cfg.headers) shouldBe (131072, 8192, Map("X-Team" -> "search"))
  }

  test("OpenAICompatibleConfig.fromValues rejects a non-positive window or an impossible reserve") {
    OpenAICompatibleConfig
      .fromValues("m", "http://h/v1", contextWindow = Some(0))
      .left
      .value
      .message should include("contextWindow must be positive")
    OpenAICompatibleConfig
      .fromValues("m", "http://h/v1", contextWindow = Some(1000), reserveCompletion = Some(1000))
      .left
      .value
      .message should include("reserveCompletion must be at least 0 and less than contextWindow (1000)")
    OpenAICompatibleConfig
      .fromValues("m", "http://h/v1", reserveCompletion = Some(-1))
      .isLeft shouldBe true
  }
}
