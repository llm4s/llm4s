package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `ModelPricing` is growth-prone (#2356): built through `apply`, changed through `with*`. */
class ModelPricingSettersSpec extends AnyFlatSpec with Matchers {

  private val all = ModelPricing(
    inputCostPerToken = Some(1.0),
    outputCostPerToken = Some(2.0),
    cacheCreationInputTokenCost = Some(3.0),
    cacheReadInputTokenCost = Some(4.0),
    inputCostPerTokenBatches = Some(5.0),
    outputCostPerTokenBatches = Some(6.0),
    inputCostPerTokenPriority = Some(7.0),
    outputCostPerTokenPriority = Some(8.0),
    outputCostPerReasoningToken = Some(9.0),
    inputCostPerAudioToken = Some(10.0),
    outputCostPerAudioToken = Some(11.0),
    inputCostPerImage = Some(12.0),
    outputCostPerImage = Some(13.0),
    inputCostPerPixel = Some(14.0),
    outputCostPerPixel = Some(15.0)
  )

  "ModelPricing" should "default every price to None" in {
    val empty = ModelPricing()
    empty.productIterator.toList should contain only None
    empty.estimateCost(1, 1) shouldBe None
  }

  it should "set each price from a value" in {
    val built = ModelPricing()
      .withInputCostPerToken(1.0)
      .withOutputCostPerToken(2.0)
      .withCacheCreationInputTokenCost(3.0)
      .withCacheReadInputTokenCost(4.0)
      .withInputCostPerTokenBatches(5.0)
      .withOutputCostPerTokenBatches(6.0)
      .withInputCostPerTokenPriority(7.0)
      .withOutputCostPerTokenPriority(8.0)
      .withOutputCostPerReasoningToken(9.0)
      .withInputCostPerAudioToken(10.0)
      .withOutputCostPerAudioToken(11.0)
      .withInputCostPerImage(12.0)
      .withOutputCostPerImage(13.0)
      .withInputCostPerPixel(14.0)
      .withOutputCostPerPixel(15.0)

    built shouldBe all
    built.estimateCost(10, 10) shouldBe Some(30.0)
  }

  it should "clear each price with None" in {
    val cleared = all
      .withInputCostPerToken(None)
      .withOutputCostPerToken(None)
      .withCacheCreationInputTokenCost(None)
      .withCacheReadInputTokenCost(None)
      .withInputCostPerTokenBatches(None)
      .withOutputCostPerTokenBatches(None)
      .withInputCostPerTokenPriority(None)
      .withOutputCostPerTokenPriority(None)
      .withOutputCostPerReasoningToken(None)
      .withInputCostPerAudioToken(None)
      .withOutputCostPerAudioToken(None)
      .withInputCostPerImage(None)
      .withOutputCostPerImage(None)
      .withInputCostPerPixel(None)
      .withOutputCostPerPixel(None)

    cleared shouldBe ModelPricing()
  }
}
