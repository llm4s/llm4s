package org.llm4s.sc2

import org.llm4s.llmconnect.config.OpenAIConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for OpenAIConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 * Same logic as sc3.OpenAIConfigCrossTest; behavior verified across both versions (positive and negative cases).
 */
class OpenAIConfigCrossTest extends AnyFlatSpec with Matchers {

  "OpenAIConfig.fromValues" should "build config with expected model, baseUrl and organization" in {
    val config = OpenAIConfig.fromValues("gpt-4o", "sk-test-key", Some("org-1"), "https://api.openai.com/v1")
    config.model shouldBe "gpt-4o"
    config.baseUrl shouldBe "https://api.openai.com/v1"
    config.organization shouldBe Some("org-1")
    config.apiKey shouldBe "sk-test-key"
  }

  it should "accept None for organization" in {
    val config = OpenAIConfig.fromValues("gpt-3.5-turbo", "sk-key", None, "https://api.openai.com/v1")
    config.model shouldBe "gpt-3.5-turbo"
    config.organization shouldBe None
  }

  it should "set contextWindow and reserveCompletion from model (registry or fallback) for gpt-4o" in {
    val config = OpenAIConfig.fromValues("gpt-4o", "sk-key", None, "https://api.openai.com/v1")
    config.model shouldBe "gpt-4o"
    config.contextWindow should be > 0
    config.reserveCompletion should be > 0
  }

  it should "set contextWindow and reserveCompletion for gpt-3.5-turbo" in {
    val config = OpenAIConfig.fromValues("gpt-3.5-turbo", "sk-key", None, "https://api.openai.com/v1")
    config.model shouldBe "gpt-3.5-turbo"
    config.contextWindow should be > 0
    config.reserveCompletion should be > 0
  }

  it should "use default context window for unknown model name" in {
    val config = OpenAIConfig.fromValues("unknown-model", "sk-key", None, "https://api.openai.com/v1")
    config.model shouldBe "unknown-model"
    config.contextWindow shouldBe 8192
    config.reserveCompletion shouldBe 4096
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      OpenAIConfig.fromValues("gpt-4", "", None, "https://api.openai.com/v1")
    }.getMessage should include("apiKey")
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      OpenAIConfig.fromValues("gpt-4", "sk-key", None, "  ")
    }.getMessage should include("baseUrl")
  }
}
