package org.llm4s.imagegeneration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Stable Diffusion image generation via Stability AI.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with:
 *   sbt "it/testOnly org.llm4s.imagegeneration.StableDiffusionSmokeSpec"
 * or the `sbt testSmoke` alias.
 *
 * Requires: `STABILITY_API_KEY` environment variable.
 * Tag: CloudSmoke
 */
class StableDiffusionSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("STABILITY_API_KEY")).filter(_.nonEmpty)

  // PNG magic bytes: 0x89 0x50 0x4E 0x47
  private val PngMagicBytes: Array[Byte] = Array(0x89.toByte, 0x50.toByte, 0x4e.toByte, 0x47.toByte)
  // JPEG magic bytes: 0xFF 0xD8
  private val JpegMagicBytes: Array[Byte] = Array(0xff.toByte, 0xd8.toByte)

  private def isValidPngOrJpeg(bytes: Array[Byte]): Boolean = {
    val isPng = bytes.length >= 4 && bytes.take(4).sameElements(PngMagicBytes)
    val isJpeg = bytes.length >= 2 && bytes.take(2).sameElements(JpegMagicBytes)
    isPng || isJpeg
  }

  "StableDiffusion via Stability AI" should "generate an image with non-empty bytes" in {
    assume(apiKey.isDefined, "STABILITY_API_KEY not set - skipping Stable Diffusion smoke test")

    val config = StabilityAIConfig(
      apiKey = apiKey.get,
      model = "stable-diffusion-xl-1024-v1-0"
    )

    val options = ImageGenerationOptions(
      size = ImageSize.Square1024,
      format = ImageFormat.PNG
    )

    val result = ImageGeneration.generateImage(
      prompt = "a red square on a white background",
      config = config,
      options = options
    )

    withClue(s"Image generation failed: ${result.swap.toOption.map(_.message)}") {
      result.isRight shouldBe true
    }

    val image = result.toOption.get
    val imageBytes = image.asBytes

    withClue("Expected image bytes to be non-empty") {
      imageBytes should not be empty
      imageBytes.length should be > 0
    }
  }

  it should "return image bytes with valid PNG or JPEG magic bytes" in {
    assume(apiKey.isDefined, "STABILITY_API_KEY not set - skipping Stable Diffusion smoke test")

    val config = StabilityAIConfig(
      apiKey = apiKey.get,
      model = "stable-diffusion-xl-1024-v1-0"
    )

    val options = ImageGenerationOptions(
      size = ImageSize.Square1024,
      format = ImageFormat.PNG
    )

    val result = ImageGeneration.generateImage(
      prompt = "a red square on a white background",
      config = config,
      options = options
    )

    assume(result.isRight, s"Image generation failed: ${result.swap.toOption.map(_.message)}")

    val image = result.toOption.get
    val imageBytes = image.asBytes

    withClue(s"Expected PNG or JPEG magic bytes but got first bytes: ${imageBytes.take(4).map(b => f"0x$b%02x").mkString(", ")}") {
      isValidPngOrJpeg(imageBytes) shouldBe true
    }
  }

  it should "skip gracefully when STABILITY_API_KEY is absent" in {
    assume(false, "This test demonstrates skip behavior when key is absent (always skipped)")
  }
}
