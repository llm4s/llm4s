package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelPricingSpec extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────
  // ModelPricing.fromJson – parsing
  // ─────────────────────────────────────────────────────────────

  "ModelPricing.fromJson" should "parse all pricing fields from JSON" in {
    val json = ujson.Obj(
      "input_cost_per_token"            -> 2.5e-6,
      "output_cost_per_token"           -> 1.0e-5,
      "cache_creation_input_token_cost" -> 3.75e-6,
      "cache_read_input_token_cost"     -> 0.3e-6,
      "input_cost_per_token_batches"    -> 1.25e-6,
      "output_cost_per_token_batches"   -> 5.0e-6,
      "input_cost_per_token_priority"   -> 5.0e-6,
      "output_cost_per_token_priority"  -> 2.0e-5,
      "output_cost_per_reasoning_token" -> 1.5e-5,
      "input_cost_per_audio_token"      -> 6.25e-6,
      "output_cost_per_audio_token"     -> 1.2e-5,
      "input_cost_per_image"            -> 1.0e-3,
      "output_cost_per_image"           -> 2.0e-3,
      "input_cost_per_pixel"            -> 1.0e-8,
      "output_cost_per_pixel"           -> 2.0e-8
    )

    val pricing = ModelPricing.fromJson(json)

    pricing.inputCostPerToken shouldBe Some(2.5e-6)
    pricing.outputCostPerToken shouldBe Some(1.0e-5)
    pricing.cacheCreationInputTokenCost shouldBe Some(3.75e-6)
    pricing.cacheReadInputTokenCost shouldBe Some(0.3e-6)
    pricing.inputCostPerTokenBatches shouldBe Some(1.25e-6)
    pricing.outputCostPerTokenBatches shouldBe Some(5.0e-6)
    pricing.inputCostPerTokenPriority shouldBe Some(5.0e-6)
    pricing.outputCostPerTokenPriority shouldBe Some(2.0e-5)
    pricing.outputCostPerReasoningToken shouldBe Some(1.5e-5)
    pricing.inputCostPerAudioToken shouldBe Some(6.25e-6)
    pricing.outputCostPerAudioToken shouldBe Some(1.2e-5)
    pricing.inputCostPerImage shouldBe Some(1.0e-3)
    pricing.outputCostPerImage shouldBe Some(2.0e-3)
    pricing.inputCostPerPixel shouldBe Some(1.0e-8)
    pricing.outputCostPerPixel shouldBe Some(2.0e-8)
  }

  it should "return None for all fields when JSON object is empty" in {
    val pricing = ModelPricing.fromJson(ujson.Obj())

    pricing.inputCostPerToken shouldBe None
    pricing.outputCostPerToken shouldBe None
    pricing.cacheCreationInputTokenCost shouldBe None
    pricing.cacheReadInputTokenCost shouldBe None
    pricing.inputCostPerTokenBatches shouldBe None
    pricing.outputCostPerTokenBatches shouldBe None
    pricing.inputCostPerTokenPriority shouldBe None
    pricing.outputCostPerTokenPriority shouldBe None
    pricing.outputCostPerReasoningToken shouldBe None
    pricing.inputCostPerAudioToken shouldBe None
    pricing.outputCostPerAudioToken shouldBe None
    pricing.inputCostPerImage shouldBe None
    pricing.outputCostPerImage shouldBe None
    pricing.inputCostPerPixel shouldBe None
    pricing.outputCostPerPixel shouldBe None
  }

  it should "return None for fields that are explicitly null in JSON" in {
    val json = ujson.Obj(
      "input_cost_per_token"            -> ujson.Null,
      "output_cost_per_token"           -> ujson.Null,
      "cache_creation_input_token_cost" -> ujson.Null,
      "cache_read_input_token_cost"     -> ujson.Null
    )

    val pricing = ModelPricing.fromJson(json)

    pricing.inputCostPerToken shouldBe None
    pricing.outputCostPerToken shouldBe None
    pricing.cacheCreationInputTokenCost shouldBe None
    pricing.cacheReadInputTokenCost shouldBe None
  }

  it should "parse only the subset of fields that are present" in {
    val json = ujson.Obj(
      "input_cost_per_token"  -> 1.0e-6,
      "output_cost_per_token" -> 3.0e-6
    )

    val pricing = ModelPricing.fromJson(json)

    pricing.inputCostPerToken shouldBe Some(1.0e-6)
    pricing.outputCostPerToken shouldBe Some(3.0e-6)
    pricing.cacheCreationInputTokenCost shouldBe None
    pricing.cacheReadInputTokenCost shouldBe None
    pricing.inputCostPerTokenBatches shouldBe None
    pricing.inputCostPerImage shouldBe None
  }

  // ─────────────────────────────────────────────────────────────
  // ModelPricing.estimateCost
  // ─────────────────────────────────────────────────────────────

  "ModelPricing.estimateCost" should "calculate cost correctly with both rates present" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1.0e-5)
    )
    // 1000 * 2.5e-6 = 0.0025 ; 500 * 1e-5 = 0.005 ; total = 0.0075
    pricing.estimateCost(1000, 500) shouldBe Some(0.0075)
  }

  it should "return None when inputCostPerToken is absent" in {
    val pricing = ModelPricing(outputCostPerToken = Some(1.0e-5))
    pricing.estimateCost(1000, 500) shouldBe None
  }

  it should "return None when outputCostPerToken is absent" in {
    val pricing = ModelPricing(inputCostPerToken = Some(2.5e-6))
    pricing.estimateCost(1000, 500) shouldBe None
  }

  it should "return None when both rates are absent" in {
    ModelPricing().estimateCost(1000, 500) shouldBe None
  }

  it should "return zero cost when both token counts are zero" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1.0e-5)
    )
    pricing.estimateCost(0, 0) shouldBe Some(0.0)
  }

  it should "handle large token counts without overflow" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(1.0e-6),
      outputCostPerToken = Some(4.0e-6)
    )
    // 1_000_000 input + 250_000 output
    val expected = (1_000_000 * 1.0e-6) + (250_000 * 4.0e-6) // = 1.0 + 1.0 = 2.0
    pricing.estimateCost(1_000_000, 250_000) shouldBe Some(expected)
  }

  // ─────────────────────────────────────────────────────────────
  // ModelPricing.estimateCostWithCaching
  // ─────────────────────────────────────────────────────────────

  "ModelPricing.estimateCostWithCaching" should "calculate cost correctly with all three rates present" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1.0e-5),
      cacheReadInputTokenCost = Some(0.25e-6)
    )
    // (1000 * 2.5e-6) + (5000 * 0.25e-6) + (500 * 1e-5)
    // = 0.0025 + 0.00125 + 0.005 = 0.00875
    pricing.estimateCostWithCaching(1000, 5000, 500) shouldBe Some(0.00875)
  }

  it should "return None when cacheReadInputTokenCost is absent" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1.0e-5)
      // cacheReadInputTokenCost intentionally missing
    )
    pricing.estimateCostWithCaching(1000, 5000, 500) shouldBe None
  }

  it should "return None when inputCostPerToken is absent" in {
    val pricing = ModelPricing(
      outputCostPerToken = Some(1.0e-5),
      cacheReadInputTokenCost = Some(0.25e-6)
    )
    pricing.estimateCostWithCaching(1000, 5000, 500) shouldBe None
  }

  it should "return None when outputCostPerToken is absent" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      cacheReadInputTokenCost = Some(0.25e-6)
    )
    pricing.estimateCostWithCaching(1000, 5000, 500) shouldBe None
  }

  it should "return None when all three rates are absent" in {
    ModelPricing().estimateCostWithCaching(1000, 5000, 500) shouldBe None
  }

  it should "return zero when all token counts are zero" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1.0e-5),
      cacheReadInputTokenCost = Some(0.25e-6)
    )
    pricing.estimateCostWithCaching(0, 0, 0) shouldBe Some(0.0)
  }

  // ─────────────────────────────────────────────────────────────
  // Specialist pricing field round-trips
  // ─────────────────────────────────────────────────────────────

  "ModelPricing" should "expose batch pricing fields" in {
    val pricing = ModelPricing(
      inputCostPerTokenBatches = Some(1.25e-6),
      outputCostPerTokenBatches = Some(5.0e-6)
    )
    pricing.inputCostPerTokenBatches shouldBe Some(1.25e-6)
    pricing.outputCostPerTokenBatches shouldBe Some(5.0e-6)
  }

  it should "expose priority pricing fields" in {
    val pricing = ModelPricing(
      inputCostPerTokenPriority = Some(5.0e-6),
      outputCostPerTokenPriority = Some(2.0e-5)
    )
    pricing.inputCostPerTokenPriority shouldBe Some(5.0e-6)
    pricing.outputCostPerTokenPriority shouldBe Some(2.0e-5)
  }

  it should "expose audio and image pricing fields" in {
    val pricing = ModelPricing(
      inputCostPerAudioToken = Some(6.25e-6),
      outputCostPerAudioToken = Some(1.2e-5),
      inputCostPerImage = Some(1.0e-3),
      outputCostPerImage = Some(2.0e-3),
      inputCostPerPixel = Some(1.0e-8),
      outputCostPerPixel = Some(2.0e-8)
    )
    pricing.inputCostPerAudioToken shouldBe Some(6.25e-6)
    pricing.outputCostPerAudioToken shouldBe Some(1.2e-5)
    pricing.inputCostPerImage shouldBe Some(1.0e-3)
    pricing.outputCostPerImage shouldBe Some(2.0e-3)
    pricing.inputCostPerPixel shouldBe Some(1.0e-8)
    pricing.outputCostPerPixel shouldBe Some(2.0e-8)
  }

  it should "expose reasoning token cost field" in {
    val pricing = ModelPricing(outputCostPerReasoningToken = Some(1.5e-5))
    pricing.outputCostPerReasoningToken shouldBe Some(1.5e-5)
  }

  it should "expose cache creation cost field" in {
    val pricing = ModelPricing(
      cacheCreationInputTokenCost = Some(3.75e-6),
      cacheReadInputTokenCost = Some(0.3e-6)
    )
    pricing.cacheCreationInputTokenCost shouldBe Some(3.75e-6)
    pricing.cacheReadInputTokenCost shouldBe Some(0.3e-6)
  }
}
