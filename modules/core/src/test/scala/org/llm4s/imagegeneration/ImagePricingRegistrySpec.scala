package org.llm4s.imagegeneration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImagePricingRegistrySpec extends AnyFlatSpec with Matchers {

  "ImagePricingRegistry.costPerImage" should "return correct price for gpt-image-1 standard config" in {
    ImagePricingRegistry.costPerImage("gpt-image-1", "medium", "1024x1024") shouldBe Some(0.042)
  }

  it should "return correct price for gpt-image-1 high quality large" in {
    ImagePricingRegistry.costPerImage("gpt-image-1", "high", "1536x1024") shouldBe Some(0.250)
  }

  it should "return correct price for dall-e-3 standard" in {
    ImagePricingRegistry.costPerImage("dall-e-3", "standard", "1024x1024") shouldBe Some(0.040)
  }

  it should "return correct price for dall-e-3 hd" in {
    ImagePricingRegistry.costPerImage("dall-e-3", "hd", "1024x1024") shouldBe Some(0.080)
  }

  it should "return correct price for dall-e-2" in {
    ImagePricingRegistry.costPerImage("dall-e-2", "standard", "512x512") shouldBe Some(0.018)
  }

  it should "return None for unknown model" in {
    ImagePricingRegistry.costPerImage("unknown-model", "standard", "1024x1024") shouldBe None
  }

  it should "return None for unknown quality" in {
    ImagePricingRegistry.costPerImage("dall-e-3", "ultra", "1024x1024") shouldBe None
  }

  it should "return None for unknown size" in {
    ImagePricingRegistry.costPerImage("dall-e-3", "standard", "2048x2048") shouldBe None
  }

  "ImagePricingRegistry.costPerImageWithDefaults" should "default quality to standard" in {
    ImagePricingRegistry.costPerImageWithDefaults("dall-e-3", None, ImageSize.Square1024) shouldBe Some(0.040)
  }

  it should "default quality to standard when empty string" in {
    ImagePricingRegistry.costPerImageWithDefaults("dall-e-3", Some(""), ImageSize.Square1024) shouldBe Some(0.040)
  }

  it should "use provided quality when present" in {
    ImagePricingRegistry.costPerImageWithDefaults("dall-e-3", Some("hd"), ImageSize.Square1024) shouldBe Some(0.080)
  }

  it should "handle ImageSize.Auto" in {
    ImagePricingRegistry.costPerImageWithDefaults("gpt-image-1", Some("low"), ImageSize.Auto) shouldBe Some(0.011)
  }

  it should "handle custom sizes by formatting as WxH" in {
    ImagePricingRegistry.costPerImageWithDefaults("dall-e-2", None, ImageSize.Custom(512, 512)) shouldBe Some(0.018)
  }

  "ImagePricingRegistry.estimateCost" should "multiply per-image cost by count" in {
    val singleCost = ImagePricingRegistry.costPerImageWithDefaults("dall-e-3", Some("standard"), ImageSize.Square1024)
    singleCost shouldBe Some(0.040)

    ImagePricingRegistry.estimateCost("dall-e-3", Some("standard"), ImageSize.Square1024, 3) shouldBe Some(0.120)
  }

  it should "return None when pricing is unknown" in {
    ImagePricingRegistry.estimateCost("unknown-model", None, ImageSize.Square1024, 1) shouldBe None
  }

  it should "return Some(0.0) when count is 0" in {
    ImagePricingRegistry.estimateCost("dall-e-3", Some("standard"), ImageSize.Square1024, 0) shouldBe Some(0.0)
  }

  "ImagePricingRegistry.knownModels" should "include expected models" in {
    val models = ImagePricingRegistry.knownModels
    models should contain("gpt-image-1")
    models should contain("dall-e-3")
    models should contain("dall-e-2")
    models should contain("stable-diffusion-xl-1024-v1-0")
  }
}
