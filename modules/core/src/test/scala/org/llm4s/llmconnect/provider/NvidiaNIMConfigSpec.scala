package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, NvidiaNIMConfig }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NvidiaNIMConfigSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // =========================================================================
  // fromValues validation
  // =========================================================================

  "NvidiaNIMConfig.fromValues" should "reject an empty base URL" in {
    an[IllegalArgumentException] should be thrownBy {
      NvidiaNIMConfig.fromValues("meta/llama-3.1-8b-instruct", apiKey = "nvapi-key", baseUrl = "  ")
    }
  }

  it should "produce a valid config with cloud API key" in {
    val cfg = NvidiaNIMConfig.fromValues(
      modelName = "meta/llama-3.1-8b-instruct",
      apiKey = "nvapi-test-key",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.apiKey shouldBe "nvapi-test-key"
    cfg.model shouldBe "meta/llama-3.1-8b-instruct"
    cfg.baseUrl shouldBe NvidiaNIMConfig.DEFAULT_BASE_URL
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "produce a valid config with empty API key (on-premise mode)" in {
    val cfg = NvidiaNIMConfig.fromValues(
      modelName = "meta/llama-3.1-8b-instruct",
      apiKey = "",
      baseUrl = "http://nim-server:8000/v1"
    )
    cfg.apiKey shouldBe ""
    cfg.baseUrl shouldBe "http://nim-server:8000/v1"
    cfg.contextWindow should be > 0
  }

  it should "trim trailing whitespace from baseUrl" in {
    val cfg = NvidiaNIMConfig.fromValues(
      modelName = "meta/llama-3.1-8b-instruct",
      apiKey = "",
      baseUrl = "  http://nim-server:8000/v1  "
    )
    cfg.baseUrl shouldBe "http://nim-server:8000/v1"
  }

  // =========================================================================
  // Context-window fallback mappings
  // =========================================================================

  "NvidiaNIMConfig context window fallback" should "return 128000 for llama-3.1-8b" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "meta/llama-3.1-8b-instruct",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 128000
  }

  it should "return 128000 for llama-3.1-70b" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "meta/llama-3.1-70b-instruct",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 128000
  }

  it should "return 32768 for mistral-7b" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "mistralai/mistral-7b-instruct-v0.3",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 32768
  }

  it should "return 4096 for nemotron-4-340b" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "nvidia/nemotron-4-340b-instruct",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 4096
  }

  it should "return 16384 for codellama models" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "meta/codellama-70b",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 16384
  }

  it should "return default 128000 for unknown model names" in {
    val cfg = NvidiaNIMConfig.fromValues(
      "some-unknown-model-xyz",
      apiKey = "",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL
    )
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  // =========================================================================
  // DEFAULT_BASE_URL and DEFAULT_ON_PREMISE_BASE_URL
  // =========================================================================

  "NvidiaNIMConfig.DEFAULT_BASE_URL" should "point to NVIDIA cloud API" in {
    NvidiaNIMConfig.DEFAULT_BASE_URL shouldBe "https://integrate.api.nvidia.com/v1"
  }

  "NvidiaNIMConfig.DEFAULT_ON_PREMISE_BASE_URL" should "point to localhost NIM container" in {
    NvidiaNIMConfig.DEFAULT_ON_PREMISE_BASE_URL shouldBe "http://localhost:8000/v1"
  }

  // =========================================================================
  // toString redaction
  // =========================================================================

  "NvidiaNIMConfig.toString" should "redact the API key" in {
    val cfg = NvidiaNIMConfig(
      apiKey = "nvapi-secret-key-12345",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL,
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val s = cfg.toString
    (s should not).include("nvapi-secret-key-12345")
    s should include("model=meta/llama-3.1-8b-instruct")
    s should include("NvidiaNIMConfig")
  }

  it should "not expose empty API key in toString" in {
    val cfg = NvidiaNIMConfig(
      apiKey = "",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = "http://nim-server:8000/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val s = cfg.toString
    s should include("NvidiaNIMConfig")
    s should include("model=meta/llama-3.1-8b-instruct")
  }

  // =========================================================================
  // ProviderKind
  // =========================================================================

  "NvidiaNIMConfig" should "have NvidiaNIM as provider kind" in {
    import org.llm4s.types.ProviderModelTypes.ProviderKind
    val cfg = NvidiaNIMConfig(
      apiKey = "key",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = NvidiaNIMConfig.DEFAULT_BASE_URL,
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    cfg.provider shouldBe ProviderKind.NvidiaNIM
  }
}
