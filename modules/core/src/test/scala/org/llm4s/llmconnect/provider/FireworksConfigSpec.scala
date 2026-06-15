package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, FireworksConfig }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for FireworksConfig case class and companion object.
 */
class FireworksConfigSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given resolver: ContextWindowResolver = ContextWindowResolver(mrs)

  private val defaultModel = "accounts/fireworks/models/llama-v3p1-8b-instruct"

  // ==========================================================================
  // DEFAULT_BASE_URL
  // ==========================================================================

  "FireworksConfig.DEFAULT_BASE_URL" should "be the official Fireworks AI inference endpoint" in {
    FireworksConfig.DEFAULT_BASE_URL shouldBe "https://api.fireworks.ai/inference/v1"
  }

  // ==========================================================================
  // provider field
  // ==========================================================================

  "FireworksConfig" should "have provider kind Fireworks" in {
    val cfg = FireworksConfig(
      apiKey = "fw-test",
      model = defaultModel,
      baseUrl = FireworksConfig.DEFAULT_BASE_URL,
      contextWindow = 131072,
      reserveCompletion = 4096
    )
    cfg.provider shouldBe ProviderKind.Fireworks
  }

  // ==========================================================================
  // toString — API key redaction
  // ==========================================================================

  it should "redact the apiKey in toString" in {
    val cfg = FireworksConfig(
      apiKey = "fw-super-secret-key",
      model = defaultModel,
      baseUrl = FireworksConfig.DEFAULT_BASE_URL,
      contextWindow = 131072,
      reserveCompletion = 4096
    )
    val str = cfg.toString
    str should not include "fw-super-secret-key"
    str should include("FireworksConfig")
    str should include(defaultModel)
  }

  // ==========================================================================
  // fromValues — normal construction
  // ==========================================================================

  "FireworksConfig.fromValues" should "create config with sensible defaults for llama model" in {
    val cfg = FireworksConfig.fromValues(
      modelName = "accounts/fireworks/models/llama-v3p1-8b-instruct",
      apiKey = "fw-key",
      baseUrl = FireworksConfig.DEFAULT_BASE_URL
    )
    cfg.apiKey shouldBe "fw-key"
    cfg.model shouldBe "accounts/fireworks/models/llama-v3p1-8b-instruct"
    cfg.baseUrl shouldBe FireworksConfig.DEFAULT_BASE_URL
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "create config for mixtral model" in {
    val cfg = FireworksConfig.fromValues(
      modelName = "accounts/fireworks/models/mixtral-8x7b-instruct",
      apiKey = "fw-key",
      baseUrl = FireworksConfig.DEFAULT_BASE_URL
    )
    cfg.model shouldBe "accounts/fireworks/models/mixtral-8x7b-instruct"
    cfg.contextWindow should be > 0
  }

  it should "create config for firefunction-v2 model" in {
    val cfg = FireworksConfig.fromValues(
      modelName = "accounts/fireworks/models/firefunction-v2",
      apiKey = "fw-key",
      baseUrl = FireworksConfig.DEFAULT_BASE_URL
    )
    cfg.model shouldBe "accounts/fireworks/models/firefunction-v2"
    cfg.contextWindow should be > 0
  }

  it should "use fallback context window for unknown models" in {
    val cfg = FireworksConfig.fromValues(
      modelName = "accounts/fireworks/models/unknown-model-xyz",
      apiKey = "fw-key",
      baseUrl = FireworksConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "throw when apiKey is empty" in {
    an[IllegalArgumentException] should be thrownBy {
      FireworksConfig.fromValues(
        modelName = defaultModel,
        apiKey = "",
        baseUrl = FireworksConfig.DEFAULT_BASE_URL
      )
    }
  }

  it should "throw when apiKey is blank" in {
    an[IllegalArgumentException] should be thrownBy {
      FireworksConfig.fromValues(
        modelName = defaultModel,
        apiKey = "   ",
        baseUrl = FireworksConfig.DEFAULT_BASE_URL
      )
    }
  }

  it should "throw when baseUrl is empty" in {
    an[IllegalArgumentException] should be thrownBy {
      FireworksConfig.fromValues(
        modelName = defaultModel,
        apiKey = "fw-key",
        baseUrl = ""
      )
    }
  }

  it should "throw when baseUrl is blank" in {
    an[IllegalArgumentException] should be thrownBy {
      FireworksConfig.fromValues(
        modelName = defaultModel,
        apiKey = "fw-key",
        baseUrl = "   "
      )
    }
  }
}
