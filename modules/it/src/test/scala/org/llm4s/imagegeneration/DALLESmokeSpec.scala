package org.llm4s.imagegeneration

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for DALL-E image generation via OpenAI.
 *
 * Lives in the integration-test module so default `sbt test` stays fast.
 * Run it with `sbt testSmoke`.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class DALLESmokeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  "DALL-E" should "generate an image whose returned URL is reachable" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val result = ImageGeneration.generateImage(
      prompt = "a blue circle on a white background",
      config = OpenAIConfig(apiKey = apiKey.get, model = "dall-e-2"),
      options = ImageGenerationOptions(size = ImageSize.Square512, responseFormat = Some("url"))
    )

    withClue(s"Image generation failed: ${result.swap.toOption.map(_.message)}") {
      result.isRight shouldBe true
    }

    val imageUrl = result.value.url.value

    val connection = java.net.URI.create(imageUrl).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
    connection.setRequestMethod("HEAD")
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(10000)
    val statusCode =
      try connection.getResponseCode
      finally connection.disconnect()

    withClue(s"Expected the generated image URL to return HTTP 200, got $statusCode") {
      statusCode shouldBe 200
    }
  }
}
