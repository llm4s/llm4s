package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ ImageGenerationOptions, ImageSize }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HuggingClientPayloadSpec extends AnyFlatSpec with Matchers {

  private def fullOptions: ImageGenerationOptions =
    ImageGenerationOptions(
      seed = Some(42L),
      guidanceScale = 9.25,
      inferenceSteps = 35,
      negativePrompt = Some("blurry, low quality")
    )

  "HuggingClientPayload.apply" should "put the prompt in inputs" in {
    HuggingClientPayload("a red fox", fullOptions).inputs shouldBe "a red fox"
  }

  it should "map each option into its own parameter" in {
    val parameters = HuggingClientPayload("a red fox", fullOptions).parameters

    parameters.guidance_scale shouldBe 9.25
    parameters.num_inference_steps shouldBe 35
    parameters.negative_prompt shouldBe Some("blurry, low quality")
    parameters.seed shouldBe Some(42L)
  }

  it should "use the default options: 7.5 guidance, 20 steps, 512x512, no negative prompt and no seed" in {
    val parameters = HuggingClientPayload("a red fox", ImageGenerationOptions()).parameters

    parameters shouldBe Parameters(
      guidance_scale = 7.5,
      num_inference_steps = 20,
      negative_prompt = None,
      width = Some(512),
      height = Some(512),
      seed = None
    )
  }

  it should "send no width or height for ImageSize.Auto" in {
    val parameters = HuggingClientPayload("p", ImageGenerationOptions(size = ImageSize.Auto)).parameters

    parameters.width shouldBe None
    parameters.height shouldBe None
  }

  it should "carry a negative prompt with quotes and non-ASCII text through unchanged" in {
    val negative = "no \"text\", no café 日本語 😀"
    val options  = ImageGenerationOptions(negativePrompt = Some(negative))

    HuggingClientPayload("p", options).parameters.negative_prompt shouldBe Some(negative)
  }

  "HuggingClientPayload serialisation" should "round-trip a payload with every optional field" in {
    val payload = HuggingClientPayload("a red fox", fullOptions)

    upickle.default.read[HuggingClientPayload](upickle.default.write(payload)) shouldBe payload
  }

  it should "round-trip a payload without optional fields" in {
    val payload = HuggingClientPayload("a red fox", ImageGenerationOptions())

    upickle.default.read[HuggingClientPayload](upickle.default.write(payload)) shouldBe payload
  }

  it should "round-trip a payload with only a seed" in {
    val payload = HuggingClientPayload("p", ImageGenerationOptions(seed = Some(Long.MaxValue)))

    upickle.default.read[HuggingClientPayload](upickle.default.write(payload)) shouldBe payload
  }

  it should "round-trip a payload whose negative prompt has quotes and non-ASCII text" in {
    val negative = "no \"text\", no café 日本語 😀"
    val payload  = HuggingClientPayload("say \"hi\"", ImageGenerationOptions(negativePrompt = Some(negative)))

    upickle.default.read[HuggingClientPayload](upickle.default.write(payload)) shouldBe payload
  }
}
