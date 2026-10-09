package org.llm4s.imagegeneration

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for Stable Diffusion image generation via Stability AI.
 *
 * Lives in the integration-test module so default `sbt test` stays fast.
 * Run it with `sbt testSmoke`.
 *
 * Requires: `STABILITY_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class StableDiffusionSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val apiKey: Option[String] = Option(System.getenv("STABILITY_API_KEY")).filter(_.nonEmpty)

  private val PngMagicBytes: Seq[Byte]  = Seq(0x89, 0x50, 0x4e, 0x47).map(_.toByte)
  private val JpegMagicBytes: Seq[Byte] = Seq(0xff, 0xd8).map(_.toByte)

  "StableDiffusion via Stability AI" should "generate an image with valid PNG or JPEG bytes" in {
    Tier.require(apiKey.isDefined, "STABILITY_API_KEY not set")

    val result = ImageGeneration.generateImage(
      prompt = "a red square on a white background",
      config = StabilityAIConfig(apiKey = apiKey.get, model = "stable-diffusion-xl-1024-v1-0"),
      options = ImageGenerationOptions(size = ImageSize.Square1024)
    )

    withClue(s"Image generation failed: ${result.swap.toOption.map(_.message)}") {
      result.isRight shouldBe true
    }

    val bytes   = result.value.asBytes.toSeq
    val isPng   = bytes.startsWith(PngMagicBytes)
    val isJpeg  = bytes.startsWith(JpegMagicBytes)
    val leading = bytes.take(4).map(b => f"0x$b%02x").mkString(", ")

    withClue(s"Expected PNG or JPEG magic bytes but got leading bytes: $leading") {
      (isPng || isJpeg) shouldBe true
    }
  }

  it should "reject an invalid API key" in {
    val result = ImageGeneration.generateImage(
      prompt = "a red square on a white background",
      config = StabilityAIConfig(apiKey = "sk-invalid-key-for-testing", model = "stable-diffusion-xl-1024-v1-0"),
      options = ImageGenerationOptions(size = ImageSize.Square1024)
    )

    // The client maps 401 to ImageAuthenticationError; Stability may answer a bad key with 403 too.
    result.left.value match {
      case _: ImageAuthenticationError               => succeed
      case ImageServiceError(_, code) if code == 403 => succeed
      case other                                     => fail(s"Expected an authentication failure, got $other")
    }
  }
}
