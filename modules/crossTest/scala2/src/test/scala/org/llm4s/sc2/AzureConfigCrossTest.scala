package org.llm4s.sc2

import org.llm4s.llmconnect.config.AzureConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for AzureConfig parsing.
 * Verifies that provider config built from values behaves identically in Scala 2.13 and 3.x.
 * No network, no env — deterministic fromValues only.
 */
class AzureConfigCrossTest extends AnyFlatSpec with Matchers {

  "AzureConfig.fromValues" should "build config with expected model, endpoint and apiVersion" in {
    val config = AzureConfig.fromValues(
      "gpt-4o",
      "https://my-resource.openai.azure.com",
      "azure-key",
      "2024-02-15-preview"
    )
    config.model shouldBe "gpt-4o"
    config.endpoint shouldBe "https://my-resource.openai.azure.com"
    config.apiKey shouldBe "azure-key"
    config.apiVersion shouldBe "2024-02-15-preview"
  }

  it should "set contextWindow and reserveCompletion from model (registry or fallback) for gpt-4o" in {
    val config = AzureConfig.fromValues("gpt-4o", "https://x.openai.azure.com", "key", "2024-02-15")
    config.model shouldBe "gpt-4o"
    config.contextWindow should be > 0
    config.reserveCompletion should be > 0
  }

  it should "use default context window for unknown model name" in {
    val config = AzureConfig.fromValues("custom-deployment", "https://x.openai.azure.com", "key", "2024-02-15")
    config.model shouldBe "custom-deployment"
    config.contextWindow shouldBe 8192
    config.reserveCompletion shouldBe 4096
  }

  it should "throw if endpoint is empty" in {
    intercept[IllegalArgumentException] {
      AzureConfig.fromValues("gpt-4", "", "key", "2024-02-15")
    }.getMessage should include("endpoint")
  }

  it should "throw if apiKey is empty" in {
    intercept[IllegalArgumentException] {
      AzureConfig.fromValues("gpt-4", "https://x.openai.azure.com", "  ", "2024-02-15")
    }.getMessage should include("apiKey")
  }
}
