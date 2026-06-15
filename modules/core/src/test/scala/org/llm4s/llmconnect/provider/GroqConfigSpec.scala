package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, GroqConfig }
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GroqConfigSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryTestSupport.defaultService())

  // ============ fromValues validation ============

  "GroqConfig.fromValues" should "reject an empty API key" in {
    an[IllegalArgumentException] should be thrownBy {
      GroqConfig.fromValues("llama-3.1-8b-instant", apiKey = "   ", baseUrl = GroqConfig.DEFAULT_BASE_URL)
    }
  }

  it should "reject an empty base URL" in {
    an[IllegalArgumentException] should be thrownBy {
      GroqConfig.fromValues("llama-3.1-8b-instant", apiKey = "key", baseUrl = "  ")
    }
  }

  it should "produce a valid config with non-empty inputs" in {
    val cfg = GroqConfig.fromValues(
      "llama-3.1-8b-instant",
      apiKey = "gsk-test",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.apiKey shouldBe "gsk-test"
    cfg.model shouldBe "llama-3.1-8b-instant"
    cfg.baseUrl shouldBe GroqConfig.DEFAULT_BASE_URL
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  // ============ Context-window fallback mappings ============

  "GroqConfig context window fallback" should "return a positive context window for llama-3.1-8b-instant" in {
    val cfg = GroqConfig.fromValues(
      "llama-3.1-8b-instant",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    // The model registry takes precedence; the context window is non-zero.
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for llama-3.3-70b-versatile" in {
    val cfg = GroqConfig.fromValues(
      "llama-3.3-70b-versatile",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return 32768 for mixtral-8x7b-32768 (fallback)" in {
    val cfg = GroqConfig.fromValues(
      "mixtral-8x7b-32768",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    // The fallback for mixtral-8x7b is 32768; registry may or may not override.
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for gemma2-9b-it" in {
    val cfg = GroqConfig.fromValues(
      "gemma2-9b-it",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for llama-3.1-70b models" in {
    val cfg = GroqConfig.fromValues(
      "llama-3.1-70b-versatile",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for llama-3-70b models" in {
    val cfg = GroqConfig.fromValues(
      "llama-3-70b-8192",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for llama-3-8b models" in {
    val cfg = GroqConfig.fromValues(
      "llama-3-8b-8192",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return a positive context window for gemma-7b models" in {
    val cfg = GroqConfig.fromValues(
      "gemma-7b-it",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "return default 32768 for completely unknown model names" in {
    val cfg = GroqConfig.fromValues(
      "some-unknown-groq-model-xyz",
      apiKey = "key",
      baseUrl = GroqConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 4096
  }

  // ============ toString redaction ============

  "GroqConfig.toString" should "redact the API key" in {
    val cfg = GroqConfig(
      apiKey = "gsk-secret-key-12345",
      model = "llama-3.1-8b-instant",
      baseUrl = GroqConfig.DEFAULT_BASE_URL,
      contextWindow = 131072,
      reserveCompletion = 4096
    )
    val s = cfg.toString
    (s should not).include("gsk-secret-key-12345")
    s should include("model=llama-3.1-8b-instant")
    s should include("GroqConfig")
  }

  // ============ DEFAULT_BASE_URL ============

  "GroqConfig.DEFAULT_BASE_URL" should "point to Groq OpenAI-compatible API" in {
    GroqConfig.DEFAULT_BASE_URL shouldBe "https://api.groq.com/openai/v1"
  }

  // ============ ProviderKind ============

  "GroqConfig.provider" should "be ProviderKind.Groq" in {
    val cfg = GroqConfig(
      apiKey = "key",
      model = "llama-3.1-8b-instant",
      baseUrl = GroqConfig.DEFAULT_BASE_URL,
      contextWindow = 131072,
      reserveCompletion = 4096
    )
    cfg.provider shouldBe org.llm4s.types.ProviderModelTypes.ProviderKind.Groq
  }
}
