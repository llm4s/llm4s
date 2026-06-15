package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, XAIConfig }
import org.llm4s.types.ProviderModelTypes.ProviderKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Unit tests for [[XAIConfig]] — validates config construction, defaults,
 * validation, and the ProviderKind assignment.
 */
class XAIConfigSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // ==========================================================================
  // XAIConfig.fromValues — happy paths
  // ==========================================================================

  "XAIConfig.fromValues" should "construct config with correct defaults" in {
    val cfg = XAIConfig.fromValues(
      modelName = "grok-beta",
      apiKey = "xai-test-key",
      baseUrl = XAIConfig.DEFAULT_BASE_URL,
    )
    cfg.model shouldBe "grok-beta"
    cfg.apiKey shouldBe "xai-test-key"
    cfg.baseUrl shouldBe "https://api.x.ai/v1"
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
    cfg.provider shouldBe ProviderKind.XAI
  }

  it should "set provider kind to XAI" in {
    val cfg = XAIConfig.fromValues("grok-2-latest", "xai-key", XAIConfig.DEFAULT_BASE_URL)
    cfg.provider shouldBe ProviderKind.XAI
  }

  it should "use a custom base URL when provided" in {
    val customUrl = "https://custom.x.ai/v1"
    val cfg       = XAIConfig.fromValues("grok-beta", "xai-key", customUrl)
    cfg.baseUrl shouldBe customUrl
  }

  it should "accept grok-2-vision-latest" in {
    val cfg = XAIConfig.fromValues("grok-2-vision-latest", "xai-key", XAIConfig.DEFAULT_BASE_URL)
    cfg.model shouldBe "grok-2-vision-latest"
    cfg.contextWindow should be > 0
  }

  it should "accept grok-2-latest" in {
    val cfg = XAIConfig.fromValues("grok-2-latest", "xai-key", XAIConfig.DEFAULT_BASE_URL)
    cfg.model shouldBe "grok-2-latest"
  }

  it should "resolve a large context window for grok-2 variants" in {
    val cfg = XAIConfig.fromValues("grok-2-latest", "xai-key", XAIConfig.DEFAULT_BASE_URL)
    cfg.contextWindow shouldBe 131072
  }

  // ==========================================================================
  // XAIConfig.fromValues — validation failures
  // ==========================================================================

  it should "fail when apiKey is empty" in {
    val result = Try(XAIConfig.fromValues("grok-beta", "", XAIConfig.DEFAULT_BASE_URL))
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("apiKey")
  }

  it should "fail when apiKey is blank" in {
    val result = Try(XAIConfig.fromValues("grok-beta", "   ", XAIConfig.DEFAULT_BASE_URL))
    result.isFailure shouldBe true
  }

  it should "fail when baseUrl is empty" in {
    val result = Try(XAIConfig.fromValues("grok-beta", "xai-key", ""))
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("baseUrl")
  }

  it should "fail when baseUrl is blank" in {
    val result = Try(XAIConfig.fromValues("grok-beta", "xai-key", "   "))
    result.isFailure shouldBe true
  }

  // ==========================================================================
  // XAIConfig case class — toString redacts apiKey
  // ==========================================================================

  "XAIConfig.toString" should "redact the API key" in {
    val cfg = XAIConfig(
      apiKey = "xai-super-secret-key-12345",
      model = "grok-beta",
      baseUrl = XAIConfig.DEFAULT_BASE_URL,
      contextWindow = 131072,
      reserveCompletion = 4096,
    )
    val str = cfg.toString
    str should not include "xai-super-secret-key-12345"
    str should include("grok-beta")
    str should include("https://api.x.ai/v1")
  }

  // ==========================================================================
  // XAIConfig.DEFAULT_BASE_URL
  // ==========================================================================

  "XAIConfig.DEFAULT_BASE_URL" should "point to xAI v1 endpoint" in {
    XAIConfig.DEFAULT_BASE_URL shouldBe "https://api.x.ai/v1"
  }
}
