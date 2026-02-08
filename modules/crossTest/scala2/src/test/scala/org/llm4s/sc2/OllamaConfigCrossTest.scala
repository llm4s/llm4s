package org.llm4s.sc2

import org.llm4s.llmconnect.config.OllamaConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for OllamaConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env, no config files — deterministic fromValues only.
 * Same logic as sc3; includes positive (valid fromValues) and negative (empty baseUrl throws) cases.
 */
class OllamaConfigCrossTest extends AnyFlatSpec with Matchers {

  "OllamaConfig.fromValues" should "build config with expected model and baseUrl" in {
    val config = OllamaConfig.fromValues("llama2", "http://localhost:11434")
    config.model shouldBe "llama2"
    config.baseUrl shouldBe "http://localhost:11434"
  }

  it should "set contextWindow and reserveCompletion from model fallback" in {
    val config = OllamaConfig.fromValues("llama2", "http://localhost:11434")
    config.contextWindow shouldBe 4096
    config.reserveCompletion shouldBe 4096
  }

  it should "use default context window for unknown model name" in {
    val config = OllamaConfig.fromValues("custom-model", "http://127.0.0.1:11434")
    config.model shouldBe "custom-model"
    config.baseUrl shouldBe "http://127.0.0.1:11434"
    config.contextWindow shouldBe 8192
    config.reserveCompletion shouldBe 4096
  }

  it should "throw if baseUrl is empty" in {
    intercept[IllegalArgumentException] {
      OllamaConfig.fromValues("llama2", "  ")
    }.getMessage should include("baseUrl")
  }
}

