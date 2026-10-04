package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.llm4s.imageprocessing.provider.geminiclient.GeminiVisionClient
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Path, Paths }

/**
 * Cloud smoke test for [[GeminiVisionClient]] against the real Gemini API.
 *
 * The client's request building, response parsing and error paths are covered without a
 * network by the unit specs in `llm4s-image` (`GeminiVisionClientSpec`); this suite only
 * checks what a stub cannot: that the real endpoint accepts the request the client builds.
 *
 * Requires: `GOOGLE_API_KEY` (or `GEMINI_API_KEY`) environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * Assertions are structural; Gemini's description is never compared against expected text.
 */
@Cloud
class GeminiVisionClientTest extends AnyFlatSpec with Matchers with EitherValues {

  private val apiKey: Option[String] =
    Option(System.getenv("GOOGLE_API_KEY"))
      .filter(_.nonEmpty)
      .orElse(Option(System.getenv("GEMINI_API_KEY")).filter(_.nonEmpty))

  private val Model = "gemini-2.0-flash"

  /** The 64x64 PNG fixture in `modules/it/src/test/resources`. */
  private def testImage: Path = Paths.get(getClass.getResource("/test-image.png").toURI)

  /** What the client returns as the description when the reply body cannot be parsed. */
  private val UnparsedReply = "Could not parse response from Gemini Vision API"

  "GeminiVisionClient" should "analyze an image with the real Gemini API" in {
    Tier.require(apiKey.isDefined, "GOOGLE_API_KEY (or GEMINI_API_KEY) not set")

    val client = new GeminiVisionClient(GeminiVisionConfig(apiKey = apiKey.get, model = Model))

    val analysis =
      client.analyzeImage(testImage.toString, Some("Describe this image in one sentence")).value

    // A reply the client could not parse becomes a fixed fallback description.
    analysis.description should not be UnparsedReply
  }

  it should "return Left for an invalid API key" in {
    val client = new GeminiVisionClient(GeminiVisionConfig(apiKey = "invalid-key-for-testing", model = Model))

    val result = client.analyzeImage(testImage.toString, None)

    result.isLeft shouldBe true
  }
}
