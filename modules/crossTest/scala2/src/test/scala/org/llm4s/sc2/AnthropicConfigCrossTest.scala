package org.llm4s.sc2

import org.llm4s.llmconnect.config.AnthropicConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for AnthropicConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 */
class AnthropicConfigCrossTest extends AnyFlatSpec with Matchers {

  "AnthropicConfig.fromValues" should "build config with expected model and baseUrl" in {
    val config = AnthropicConfig.fromValues("claude-3-5-sonnet-20241022", "sk-ant-key", "https://api.anthropic.com")
    config.model shouldBe "claude-3-5-sonnet-20241022"
    config.baseUrl shouldBe "https://api.anthropic.com"
    config.apiKey shouldBe "sk-ant-key"
  }

  it should "set contextWindow and reserveCompletion from model fallback for claude-3" in {
    val config = AnthropicConfig.fromValues("claude-3-opus", "sk-key", "https://api.anthropic.com")
    config.contextWindow shouldBe 200000
    config.reserveCompletion shouldBe 4096
  }

  it should "use default context window for unknown model name" in {
    val config = AnthropicConfig.fromValues("custom-claude", "sk-key", "https://api.anthropic.com")
    config.model shouldBe "custom-claude"
    config.contextWindow shouldBe 200000
    config.reserveCompletion shouldBe 4096
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      AnthropicConfig.fromValues("claude-3", "", "https://api.anthropic.com")
    }.getMessage should include("apiKey")
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      AnthropicConfig.fromValues("claude-3", "sk-key", "  ")
    }.getMessage should include("baseUrl")
  }
}
