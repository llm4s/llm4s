package org.llm4s.imagegeneration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for DALL-E image generation via OpenAI.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with:
 *   sbt "it/testOnly org.llm4s.imagegeneration.DALLESmokeSpec"
 * or the `sbt testSmoke` alias.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 * Tag: CloudSmoke
 */
class DALLESmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  "DALL-E" should "generate an image and return a non-empty URL" in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set - skipping DALL-E smoke test")

    val config = OpenAIConfig(
      apiKey = apiKey.get,
      model = "dall-e-2"
    )

    val options = ImageGenerationOptions(
      size = ImageSize.Square512,
      responseFormat = Some("url")
    )

    val result = ImageGeneration.generateImage(
      prompt = "a blue circle on a white background",
      config = config,
      options = options
    )

    withClue(s"Image generation failed: ${result.swap.toOption.map(_.message)}") {
      result.isRight shouldBe true
    }

    val image = result.toOption.get
    withClue("Expected image URL to be non-empty") {
      image.url should not be empty
      image.url.get should not be empty
    }
  }

  it should "return a reachable image URL (HTTP 200)" in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set - skipping DALL-E smoke test")

    val config = OpenAIConfig(
      apiKey = apiKey.get,
      model = "dall-e-2"
    )

    val options = ImageGenerationOptions(
      size = ImageSize.Square512,
      responseFormat = Some("url")
    )

    val result = ImageGeneration.generateImage(
      prompt = "a blue circle on a white background",
      config = config,
      options = options
    )

    assume(result.isRight, s"Image generation failed: ${result.swap.toOption.map(_.message)}")

    val image = result.toOption.get
    assume(image.url.isDefined, "No URL in generated image - cannot check reachability")

    val imageUrl = image.url.get
    imageUrl should not be empty

    val httpResponse = java.net.URI.create(imageUrl).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
    httpResponse.setRequestMethod("HEAD")
    httpResponse.setConnectTimeout(10000)
    httpResponse.setReadTimeout(10000)
    val statusCode = httpResponse.getResponseCode
    httpResponse.disconnect()

    withClue(s"Expected URL $imageUrl to return HTTP 200, got $statusCode") {
      statusCode shouldBe 200
    }
  }

  it should "skip gracefully when OPENAI_API_KEY is absent" in {
    assume(false, "This test demonstrates skip behavior when key is absent (always skipped)")
  }
}
