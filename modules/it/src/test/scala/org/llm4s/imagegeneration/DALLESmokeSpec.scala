package org.llm4s.imagegeneration

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for OpenAI image generation.
 *
 * `dall-e-2` and `dall-e-3` were removed from the OpenAI API on 2026-05-12 (the client logs
 * that when either is configured), so this uses `gpt-image-1`, the migration target. GPT Image
 * models always answer with base64 data, never a URL, and reject a `response_format`
 * parameter, so the assertion is on the decoded bytes.
 *
 * Lives in the integration-test module so default `sbt test` stays fast.
 * Run it with `sbt testSmoke`.
 *
 * Requires: `OPENAI_API_KEY` environment variable (the organisation must be verified for
 * `gpt-image-1`). Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class DALLESmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  private val PngMagicBytes: Seq[Byte] = Seq(0x89, 0x50, 0x4e, 0x47).map(_.toByte)

  "OpenAI image generation" should "generate an image with valid PNG bytes" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val result = ImageGeneration.generateImage(
      prompt = "a blue circle on a white background",
      config = OpenAIConfig(apiKey = apiKey.get, model = "gpt-image-1"),
      // Lowest quality and the smallest size: the cheapest call that still proves the path.
      options = ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("low"))
    )

    withClue(s"Image generation failed: ${result.swap.toOption.map(_.message)}") {
      result.isRight shouldBe true
    }

    val bytes   = result.value.asBytes.toSeq
    val leading = bytes.take(4).map(b => f"0x$b%02x").mkString(", ")

    withClue(s"Expected PNG magic bytes but got leading bytes: $leading") {
      bytes.startsWith(PngMagicBytes) shouldBe true
    }
  }

  it should "reject an invalid API key with AuthenticationError" in {
    val result = ImageGeneration.generateImage(
      prompt = "a blue circle on a white background",
      config = OpenAIConfig(apiKey = "sk-invalid-key-for-testing", model = "gpt-image-1"),
      options = ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("low"))
    )

    result.left.value shouldBe an[AuthenticationError]
  }
}
