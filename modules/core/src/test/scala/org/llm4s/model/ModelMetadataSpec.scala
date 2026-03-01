package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelMetadataSpec extends AnyFlatSpec with Matchers {

  "ModelMetadata" should "parse from JSON correctly" in {
    val json = ujson.Obj(
      "litellm_provider"          -> "openai",
      "mode"                      -> "chat",
      "max_input_tokens"          -> 128000,
      "max_output_tokens"         -> 16384,
      "input_cost_per_token"      -> 2.5e-6,
      "output_cost_per_token"     -> 1e-5,
      "supports_function_calling" -> true,
      "supports_vision"           -> true
    )

    val result = ModelMetadata.fromJson("gpt-4o", json)

    result.isRight shouldBe true
    val metadata = result.getOrElse(fail("Failed to parse metadata"))

    metadata.modelId shouldBe "gpt-4o"
    metadata.provider shouldBe "openai"
    metadata.mode shouldBe ModelMode.Chat
    metadata.maxInputTokens shouldBe Some(128000)
    metadata.maxOutputTokens shouldBe Some(16384)
    metadata.inputCostPerToken shouldBe Some(2.5e-6)
    metadata.outputCostPerToken shouldBe Some(1e-5)
  }

  it should "handle missing optional fields" in {
    val json = ujson.Obj(
      "litellm_provider" -> "custom",
      "mode"             -> "chat"
    )

    val result = ModelMetadata.fromJson("custom-model", json)

    result.isRight shouldBe true
    val metadata = result.getOrElse(fail("Failed to parse metadata"))

    metadata.modelId shouldBe "custom-model"
    metadata.provider shouldBe "custom"
    metadata.maxInputTokens shouldBe None
    metadata.maxOutputTokens shouldBe None
  }

  it should "correctly identify context window" in {
    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(100000),
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.contextWindow shouldBe Some(100000)
  }

  it should "fall back to maxOutputTokens for context window" in {
    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.contextWindow shouldBe Some(4096)
  }

  it should "check capabilities correctly" in {
    val capabilities = ModelCapabilities(
      supportsFunctionCalling = Some(true),
      supportsVision = Some(true),
      supportsPromptCaching = Some(false)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(100000),
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = capabilities,
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.supports("function_calling") shouldBe true
    metadata.supports("tools") shouldBe true // alias
    metadata.supports("vision") shouldBe true
    metadata.supports("images") shouldBe true // alias
    metadata.supports("caching") shouldBe false
    metadata.supports("unsupported") shouldBe false
  }

  it should "detect deprecated models" in {
    val pastDate   = java.time.LocalDate.now().minusDays(1).toString
    val futureDate = java.time.LocalDate.now().plusDays(1).toString

    val deprecatedModel = ModelMetadata(
      modelId = "deprecated-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = Some(pastDate)
    )

    val currentModel = ModelMetadata(
      modelId = "current-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = Some(futureDate)
    )

    deprecatedModel.isDeprecated shouldBe true
    currentModel.isDeprecated shouldBe false
  }

  it should "generate a readable description" in {
    val metadata = ModelMetadata(
      modelId = "gpt-4o",
      provider = "openai",
      mode = ModelMode.Chat,
      maxInputTokens = Some(128000),
      maxOutputTokens = Some(16384),
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1e-5),
      capabilities = ModelCapabilities(
        supportsFunctionCalling = Some(true),
        supportsVision = Some(true)
      ),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    val description = metadata.description
    description should include("gpt-4o")
    description should include("openai")
    description should include("chat")
    description should include("128K")
  }

  "ModelMode" should "parse from string correctly" in {
    ModelMode.fromString("chat") shouldBe ModelMode.Chat
    ModelMode.fromString("embedding") shouldBe ModelMode.Embedding
    ModelMode.fromString("image_generation") shouldBe ModelMode.ImageGeneration
    ModelMode.fromString("unknown_mode") shouldBe ModelMode.Unknown
  }

  "ModelPricing" should "estimate cost correctly" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1e-5)
    )

    val cost = pricing.estimateCost(inputTokens = 1000, outputTokens = 500)
    cost shouldBe Some(0.0075) // (1000 * 2.5e-6) + (500 * 1e-5) = 0.0025 + 0.005 = 0.0075
  }

  it should "return None for missing pricing data" in {
    val pricing = ModelPricing()

    pricing.estimateCost(1000, 500) shouldBe None
  }
}
