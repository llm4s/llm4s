package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.MistralConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for MistralConfig context-window fallback mappings,
 * fromValues validation, and toString redaction.
 */
class MistralConfigSpec extends AnyFlatSpec with Matchers {

  // ============ fromValues validation ============

  "MistralConfig.fromValues" should "reject an empty API key" in {
    an[IllegalArgumentException] should be thrownBy {
      MistralConfig.fromValues("mistral-small-latest", apiKey = "   ", baseUrl = "https://api.mistral.ai")
    }
  }

  it should "reject an empty base URL" in {
    an[IllegalArgumentException] should be thrownBy {
      MistralConfig.fromValues("mistral-small-latest", apiKey = "key", baseUrl = "  ")
    }
  }

  it should "produce a valid config with non-empty inputs" in {
    val cfg = MistralConfig.fromValues("mistral-small-latest", apiKey = "sk-test", baseUrl = "https://api.mistral.ai")
    cfg.apiKey shouldBe "sk-test"
    cfg.model shouldBe "mistral-small-latest"
    cfg.baseUrl shouldBe "https://api.mistral.ai"
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  // ============ Context-window fallback mappings ============

  "MistralConfig context window fallback" should "return conservative fallback from registry for mistral-small models" in {
    // 1. Lookup the canonical model to get the source of truth
    val canonical = org.llm4s.model.ModelRegistry
      .lookup("mistral-small-latest")
      .getOrElse(fail("mistral-small-latest not found in registry"))

    // 2. Validate fallback matches the canonical truth
    val maxTokens = canonical.contextWindow.getOrElse(fail("contextWindow not defined for mistral-small-latest"))
    val cfg =
      MistralConfig.fromValues("mistral-small-latest", apiKey = "key", baseUrl = MistralConfig.DEFAULT_BASE_URL)
    cfg.contextWindow should be <= maxTokens
  }

  it should "return conservative fallback from registry for codestral models" in {
    // 1. Lookup the canonical model to get the source of truth
    val canonical = org.llm4s.model.ModelRegistry
      .lookup("mistral/codestral-latest")
      .getOrElse(fail("mistral/codestral-latest not found in registry"))

    val maxTokens = canonical.contextWindow.getOrElse(fail("contextWindow not defined for codestral-latest"))
    val cfg = MistralConfig.fromValues("codestral-latest", apiKey = "key", baseUrl = MistralConfig.DEFAULT_BASE_URL)
    cfg.contextWindow should be <= maxTokens
  }

  it should "return default 128000 for unknown model names" in {
    val cfg =
      MistralConfig.fromValues("some-unknown-model-xyz", apiKey = "key", baseUrl = MistralConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  // ============ toString redaction ============

  "MistralConfig.toString" should "redact the API key" in {
    val cfg = MistralConfig(
      apiKey = "sk-secret-key-12345",
      model = "mistral-small-latest",
      baseUrl = "https://api.mistral.ai",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val s = cfg.toString
    (s should not).include("sk-secret-key-12345")
    s should include("model=mistral-small-latest")
    s should include("MistralConfig")
  }

  // ============ DEFAULT_BASE_URL ============

  "MistralConfig.DEFAULT_BASE_URL" should "point to Mistral AI API" in {
    MistralConfig.DEFAULT_BASE_URL shouldBe "https://api.mistral.ai"
  }
}
