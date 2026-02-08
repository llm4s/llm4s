package org.llm4s.sc2

import org.llm4s.llmconnect.config.ZaiConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for ZaiConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 */
class ZaiConfigCrossTest extends AnyFlatSpec with Matchers {

  "ZaiConfig.fromValues" should "build config with expected model, apiKey and baseUrl" in {
    val config = ZaiConfig.fromValues("GLM-4.7", "zai-key", "https://api.z.ai/api/paas/v4")
    config.model shouldBe "GLM-4.7"
    config.baseUrl shouldBe "https://api.z.ai/api/paas/v4"
    config.apiKey shouldBe "zai-key"
  }

  it should "set contextWindow and reserveCompletion from model fallback for GLM-4.7" in {
    val config = ZaiConfig.fromValues("GLM-4.7", "key", ZaiConfig.DEFAULT_BASE_URL)
    config.contextWindow shouldBe 128000
    config.reserveCompletion shouldBe 4096
  }

  it should "use default context window for unknown model name" in {
    val config = ZaiConfig.fromValues("custom-model", "key", "https://api.z.ai/v1")
    config.model shouldBe "custom-model"
    config.contextWindow shouldBe 128000
    config.reserveCompletion shouldBe 4096
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      ZaiConfig.fromValues("GLM-4.7", "", "https://api.z.ai/v1")
    }.getMessage should include("apiKey")
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      ZaiConfig.fromValues("GLM-4.7", "key", "  ")
    }.getMessage should include("baseUrl")
  }
}
