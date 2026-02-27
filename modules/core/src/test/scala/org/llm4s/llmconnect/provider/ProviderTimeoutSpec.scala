package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

/**
 * Tests that HTTP timeout values flow correctly from config to provider clients,
 * replacing the previous hardcoded constants.
 *
 * Each test uses the lightweight approach of verifying that:
 *  1. Default timeout values exactly match the previously-hardcoded values
 *     (backward-compatibility guarantee).
 *  2. Custom timeout values can be set and are reflected in the config.
 */
class ProviderTimeoutSpec extends AnyFunSuite with Matchers {

  // ============================================================
  // CohereConfig
  // ============================================================

  test("CohereConfig has a 2-minute requestTimeout default") {
    val cfg = CohereConfig.fromValues("command-r-plus", "key", "https://api.cohere.com")
    cfg.requestTimeout shouldBe 2.minutes
  }

  test("CohereConfig has a 10-minute streamTimeout default") {
    val cfg = CohereConfig.fromValues("command-r-plus", "key", "https://api.cohere.com")
    cfg.streamTimeout shouldBe 10.minutes
  }

  test("CohereConfig accepts a custom requestTimeout") {
    val cfg = CohereConfig(
      apiKey = "key",
      model = "command-r-plus",
      baseUrl = "https://api.cohere.com",
      contextWindow = 128000,
      reserveCompletion = 4096,
      requestTimeout = 30.seconds,
      streamTimeout = 5.minutes
    )
    cfg.requestTimeout shouldBe 30.seconds
    cfg.streamTimeout shouldBe 5.minutes
  }

  // ============================================================
  // GeminiConfig
  // ============================================================

  test("GeminiConfig has a 2-minute requestTimeout default") {
    val cfg = GeminiConfig.fromValues("gemini-2.0-flash", "key", "https://generativelanguage.googleapis.com/v1beta")
    cfg.requestTimeout shouldBe 2.minutes
  }

  test("GeminiConfig has a 10-minute streamTimeout default") {
    val cfg = GeminiConfig.fromValues("gemini-2.0-flash", "key", "https://generativelanguage.googleapis.com/v1beta")
    cfg.streamTimeout shouldBe 10.minutes
  }

  test("GeminiConfig accepts a custom streamTimeout") {
    val cfg = GeminiConfig(
      apiKey = "key",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192,
      requestTimeout = 1.minute,
      streamTimeout = 20.minutes
    )
    cfg.streamTimeout shouldBe 20.minutes
  }

  // ============================================================
  // OllamaConfig
  // ============================================================

  test("OllamaConfig has a 2-minute requestTimeout default") {
    val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434")
    cfg.requestTimeout shouldBe 2.minutes
  }

  test("OllamaConfig has a 10-minute streamTimeout default") {
    val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434")
    cfg.streamTimeout shouldBe 10.minutes
  }

  test("OllamaConfig accepts custom timeouts") {
    val cfg = OllamaConfig(
      model = "llama3",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096,
      requestTimeout = 90.seconds,
      streamTimeout = 15.minutes
    )
    cfg.requestTimeout shouldBe 90.seconds
    cfg.streamTimeout shouldBe 15.minutes
  }

  // ============================================================
  // DeepSeekConfig
  // ============================================================

  test("DeepSeekConfig has a 5-minute requestTimeout default") {
    val cfg = DeepSeekConfig.fromValues("deepseek-chat", "key", "https://api.deepseek.com")
    cfg.requestTimeout shouldBe 5.minutes
  }

  test("DeepSeekConfig has a 10-minute streamTimeout default") {
    val cfg = DeepSeekConfig.fromValues("deepseek-chat", "key", "https://api.deepseek.com")
    cfg.streamTimeout shouldBe 10.minutes
  }

  // ============================================================
  // ZaiConfig
  // ============================================================

  test("ZaiConfig has a 5-minute requestTimeout default") {
    val cfg = ZaiConfig.fromValues("GLM-4.7", "key", "https://api.z.ai/api/paas/v4")
    cfg.requestTimeout shouldBe 5.minutes
  }

  test("ZaiConfig has a 10-minute streamTimeout default") {
    val cfg = ZaiConfig.fromValues("GLM-4.7", "key", "https://api.z.ai/api/paas/v4")
    cfg.streamTimeout shouldBe 10.minutes
  }

  // ============================================================
  // OpenAIConfig
  // ============================================================

  test("OpenAIConfig has a 2-minute requestTimeout default") {
    val cfg = OpenAIConfig.fromValues("gpt-4o", "key", None, "https://api.openai.com/v1")
    cfg.requestTimeout shouldBe 2.minutes
  }

  test("OpenAIConfig accepts a custom requestTimeout for OpenRouter use-case") {
    val cfg = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1",
      contextWindow = 128000,
      reserveCompletion = 4096,
      requestTimeout = 3.minutes,
      streamTimeout = 15.minutes
    )
    cfg.requestTimeout shouldBe 3.minutes
    cfg.streamTimeout shouldBe 15.minutes
  }

  // ============================================================
  // AnthropicConfig
  // ============================================================

  test("AnthropicConfig has a 2-minute requestTimeout default") {
    val cfg = AnthropicConfig.fromValues("claude-3-sonnet-20240229", "key", "https://api.anthropic.com")
    cfg.requestTimeout shouldBe 2.minutes
  }

  test("AnthropicConfig has a 10-minute streamTimeout default") {
    val cfg = AnthropicConfig.fromValues("claude-3-sonnet-20240229", "key", "https://api.anthropic.com")
    cfg.streamTimeout shouldBe 10.minutes
  }

  // ============================================================
  // AzureConfig
  // ============================================================

  test("AzureConfig has a 2-minute requestTimeout default") {
    val cfg = AzureConfig.fromValues("gpt-4o", "https://my-resource.openai.azure.com", "key", "2024-02-15-preview")
    cfg.requestTimeout shouldBe 2.minutes
  }

  // ============================================================
  // ProviderConfig trait
  // ============================================================

  test("All ProviderConfig subtypes expose requestTimeout and streamTimeout") {
    val configs: Seq[ProviderConfig] = Seq(
      OpenAIConfig.fromValues("gpt-4o", "key", None, "https://api.openai.com/v1"),
      AnthropicConfig.fromValues("claude-3-sonnet-20240229", "key", "https://api.anthropic.com"),
      OllamaConfig.fromValues("llama3", "http://localhost:11434"),
      GeminiConfig.fromValues("gemini-2.0-flash", "key", "https://generativelanguage.googleapis.com/v1beta"),
      DeepSeekConfig.fromValues("deepseek-chat", "key", "https://api.deepseek.com"),
      ZaiConfig.fromValues("GLM-4.7", "key", "https://api.z.ai/api/paas/v4"),
      CohereConfig.fromValues("command-r-plus", "key", "https://api.cohere.com"),
      AzureConfig.fromValues("gpt-4o", "https://my.openai.azure.com", "key", "2024-02-15-preview")
    )

    configs.foreach { cfg =>
      cfg.requestTimeout.toSeconds should be > 0L
      cfg.streamTimeout.toSeconds should be > 0L
      cfg.streamTimeout.toMillis should be >= cfg.requestTimeout.toMillis
    }
  }
}
