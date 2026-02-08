package org.llm4s.sc2

import org.llm4s.llmconnect.config.DeepSeekConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for DeepSeekConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 */
class DeepSeekConfigCrossTest extends AnyFlatSpec with Matchers {

  "DeepSeekConfig.fromValues" should "build config with expected model and baseUrl" in {
    val config = DeepSeekConfig.fromValues("deepseek-chat", "ds-key", DeepSeekConfig.DEFAULT_BASE_URL)
    config.model shouldBe "deepseek-chat"
    config.baseUrl shouldBe DeepSeekConfig.DEFAULT_BASE_URL
    config.apiKey shouldBe "ds-key"
  }

  it should "set contextWindow and reserveCompletion for deepseek-chat" in {
    val config = DeepSeekConfig.fromValues("deepseek-chat", "key", "https://api.deepseek.com")
    config.model shouldBe "deepseek-chat"
    config.contextWindow should be > 0
    config.reserveCompletion should be > 0
  }

  it should "use default context window for unknown model name" in {
    val config = DeepSeekConfig.fromValues("custom-model", "key", "https://api.deepseek.com")
    config.model shouldBe "custom-model"
    config.contextWindow shouldBe 128000
    config.reserveCompletion shouldBe 8192
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      DeepSeekConfig.fromValues("deepseek-chat", "", DeepSeekConfig.DEFAULT_BASE_URL)
    }.getMessage should include("apiKey")
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      DeepSeekConfig.fromValues("deepseek-chat", "key", "  ")
    }.getMessage should include("baseUrl")
  }
}
