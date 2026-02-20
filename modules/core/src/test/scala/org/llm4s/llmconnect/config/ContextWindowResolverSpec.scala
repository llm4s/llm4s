package org.llm4s.llmconnect.config

import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

/**
 * Unit tests for ContextWindowResolver.
 *
 * Verifies both the registry hit path (metadata used directly) and the
 * registry miss path (fallbackResolver invoked).
 */
class ContextWindowResolverSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = ModelRegistry.reset()

  "ContextWindowResolver.resolve" should "use fallback when model not in registry" in {
    val fallback = (_: String) => (9999, 8888)
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("openai"),
      modelName = "nonexistent-model-xyz-12345",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 9999
    rc shouldBe 8888
  }

  it should "use fallback with multiple lookup providers when model not in registry" in {
    val fallback = (_: String) => (50000, 2000)
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("azure", "openai"),
      modelName = "another-unknown-model-999",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 50000
    rc shouldBe 2000
  }

  it should "use fallback when lookupProviders is empty and model not in registry" in {
    val fallback = (_: String) => (1000, 500)
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq.empty,
      modelName = "nonexistent-model-xyz-12345",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 1000
    rc shouldBe 500
  }

  it should "use registry metadata when model is registered (hit path)" in {
    val testModel = ModelMetadata(
      modelId = "test-provider/test-resolver-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(77000),
      maxOutputTokens = Some(3500),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )
    ModelRegistry.register(testModel)

    val fallback = (_: String) => (9999, 9999) // should not be reached
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("test-provider"),
      modelName = "test-resolver-model",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 77000
    rc shouldBe 3500
  }

  it should "use defaultContextWindow and defaultReserve when registry hit has no token data" in {
    val testModel = ModelMetadata(
      modelId = "test-provider/test-no-tokens-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )
    ModelRegistry.register(testModel)

    val fallback = (_: String) => (9999, 9999) // should not be reached
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("test-provider"),
      modelName = "test-no-tokens-model",
      defaultContextWindow = 12345,
      defaultReserve = 6789,
      fallbackResolver = fallback
    )
    cw shouldBe 12345
    rc shouldBe 6789
  }
}
