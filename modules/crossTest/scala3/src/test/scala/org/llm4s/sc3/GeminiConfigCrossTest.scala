package org.llm4s.sc3

import org.llm4s.llmconnect.config.GeminiConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for GeminiConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 */
class GeminiConfigCrossTest extends AnyFlatSpec with Matchers {

  "GeminiConfig.fromValues" should "build config with expected model and baseUrl" in {
    val config = GeminiConfig.fromValues("gemini-2.0-flash", "gemini-key", "https://generativelanguage.googleapis.com")
    config.model shouldBe "gemini-2.0-flash"
    config.baseUrl shouldBe "https://generativelanguage.googleapis.com"
    config.apiKey shouldBe "gemini-key"
  }

  it should "set contextWindow and reserveCompletion from model fallback for gemini-2" in {
    val config = GeminiConfig.fromValues("gemini-2.0-flash", "key", "https://generativelanguage.googleapis.com")
    config.contextWindow shouldBe 1048576
    config.reserveCompletion shouldBe 8192
  }

  it should "use default context window for unknown model name" in {
    val config = GeminiConfig.fromValues("custom-gemini", "key", "https://generativelanguage.googleapis.com")
    config.model shouldBe "custom-gemini"
    config.contextWindow shouldBe 1048576
    config.reserveCompletion shouldBe 8192
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      GeminiConfig.fromValues("gemini-2", "", "https://generativelanguage.googleapis.com")
    }.getMessage should include("apiKey")
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      GeminiConfig.fromValues("gemini-2", "key", "  ")
    }.getMessage should include("baseUrl")
  }
}
