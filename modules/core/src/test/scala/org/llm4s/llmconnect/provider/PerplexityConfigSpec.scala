package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, PerplexityConfig }
import org.llm4s.types.ProviderModelTypes.ProviderKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PerplexityConfigSpec extends AnyFlatSpec with Matchers:

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // ============ fromValues validation ============

  "PerplexityConfig.fromValues" should "reject an empty API key" in {
    an[IllegalArgumentException] should be thrownBy {
      PerplexityConfig.fromValues("sonar", apiKey = "   ", baseUrl = "https://api.perplexity.ai")
    }
  }

  it should "reject an empty base URL" in {
    an[IllegalArgumentException] should be thrownBy {
      PerplexityConfig.fromValues("sonar", apiKey = "pplx-key", baseUrl = "  ")
    }
  }

  it should "produce a valid config with non-empty inputs" in {
    val cfg = PerplexityConfig.fromValues("sonar", apiKey = "pplx-test", baseUrl = "https://api.perplexity.ai")
    cfg.apiKey shouldBe "pplx-test"
    cfg.model shouldBe "sonar"
    cfg.baseUrl shouldBe "https://api.perplexity.ai"
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "resolve context window for sonar model" in {
    val cfg = PerplexityConfig.fromValues("sonar", apiKey = "key", baseUrl = PerplexityConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "resolve context window for sonar-pro model" in {
    val cfg = PerplexityConfig.fromValues("sonar-pro", apiKey = "key", baseUrl = PerplexityConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "resolve context window for sonar-reasoning model" in {
    val cfg =
      PerplexityConfig.fromValues("sonar-reasoning", apiKey = "key", baseUrl = PerplexityConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "resolve context window for sonar-reasoning-pro model" in {
    val cfg =
      PerplexityConfig.fromValues("sonar-reasoning-pro", apiKey = "key", baseUrl = PerplexityConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return default context window for unknown model names" in {
    val cfg =
      PerplexityConfig.fromValues(
        "unknown-perplexity-model",
        apiKey = "key",
        baseUrl = PerplexityConfig.DEFAULT_BASE_URL
      )
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  // ============ provider kind ============

  "PerplexityConfig" should "have Perplexity as the provider kind" in {
    val cfg = PerplexityConfig(
      apiKey = "key",
      model = "sonar",
      baseUrl = "https://api.perplexity.ai",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    cfg.provider shouldBe ProviderKind.Perplexity
  }

  // ============ toString redaction ============

  "PerplexityConfig.toString" should "redact the API key" in {
    val cfg = PerplexityConfig(
      apiKey = "pplx-secret-key-12345",
      model = "sonar",
      baseUrl = "https://api.perplexity.ai",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val s = cfg.toString
    (s should not).include("pplx-secret-key-12345")
    s should include("model=sonar")
    s should include("PerplexityConfig")
    s should include("baseUrl=https://api.perplexity.ai")
  }

  // ============ DEFAULT_BASE_URL ============

  "PerplexityConfig.DEFAULT_BASE_URL" should "point to Perplexity AI API" in {
    PerplexityConfig.DEFAULT_BASE_URL shouldBe "https://api.perplexity.ai"
  }
