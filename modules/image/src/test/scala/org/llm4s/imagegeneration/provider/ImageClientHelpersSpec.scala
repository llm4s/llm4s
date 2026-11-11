package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.{ HuggingFaceConfig, ImageValidationError }
import org.llm4s.media.MediaType
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration
import scala.util.{ Success, Try }

/**
 * Pins the small public helpers of [[OpenAIImageClient]] and [[HuggingFaceClient]]: the
 * OpenAI format lookups (filtered to the formats the images API can return), the DALL-E
 * deprecation warning, and the HuggingFace prompt and count validators.
 */
class ImageClientHelpersSpec extends AnyFlatSpec with Matchers {

  /** Records every request; any call at all means validation let the input through. */
  final private class RecordingHttpClient extends HttpClient {
    var requests: Int = 0

    private def record(): Try[HttpResponse] = {
      requests += 1
      Success(HttpResponse(200, ""))
    }

    override def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration) =
      record()

    override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: FiniteDuration) =
      record()

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ) = record()

    override def get(url: String, headers: Map[String, String], timeout: FiniteDuration) = record()

    override def postRaw(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration) = {
      requests += 1
      scala.util.Failure(new UnsupportedOperationException("not expected in this test"))
    }
  }

  private def huggingFace(http: HttpClient = new RecordingHttpClient()): HuggingFaceClient =
    new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("test-token")), http)

  private val emptyPrompt = Left(ImageValidationError("Prompt cannot be empty"))
  private val badCount    = Left(ImageValidationError("Count must be between 1 and 4 for HuggingFace"))

  "OpenAIImageClient.outputFormatToImageFormat" should "map the formats the images API returns" in {
    OpenAIImageClient.outputFormatToImageFormat("png") shouldBe Some(MediaType.Png)
    OpenAIImageClient.outputFormatToImageFormat("jpeg") shouldBe Some(MediaType.Jpeg)
    OpenAIImageClient.outputFormatToImageFormat("jpg") shouldBe Some(MediaType.Jpeg)
  }

  it should "trim, lower-case and drop a leading dot before the lookup" in {
    OpenAIImageClient.outputFormatToImageFormat(" .WEBP ") shouldBe Some(MediaType.WebP)
  }

  it should "drop an image format MediaType knows but the OpenAI API never returns" in {
    MediaType.imageFromExtension("gif") shouldBe Some(MediaType.Gif)
    OpenAIImageClient.outputFormatToImageFormat("gif") shouldBe None
  }

  it should "return None for an unknown format" in {
    OpenAIImageClient.outputFormatToImageFormat("bogus") shouldBe None
  }

  "OpenAIImageClient.urlToImageFormat" should "read a supported format from the URL's extension" in {
    OpenAIImageClient.urlToImageFormat("https://x/y.webp") shouldBe Some(MediaType.WebP)
  }

  it should "drop an unsupported image format and a URL without an extension" in {
    OpenAIImageClient.urlToImageFormat("https://x/y.gif") shouldBe None
    OpenAIImageClient.urlToImageFormat("https://x/y") shouldBe None
  }

  "OpenAIImageClient.deprecationWarningMessage" should "name the model, removal date, replacements and the deprecations page" in {
    OpenAIImageClient.deprecationWarningMessage("dall-e-3") shouldBe
      "dall-e-3 is deprecated and scheduled for removal on 2026-05-12. " +
      "Migrate to gpt-image-1, gpt-image-1-mini, gpt-image-1.5. " +
      "See https://platform.openai.com/docs/deprecations."
  }

  "HuggingFaceClient.validatePrompt" should "reject an empty or blank prompt" in {
    huggingFace().validatePrompt("") shouldBe emptyPrompt
    huggingFace().validatePrompt("   ") shouldBe emptyPrompt
  }

  it should "return a non-blank prompt unchanged" in {
    huggingFace().validatePrompt("cat") shouldBe Right("cat")
  }

  "HuggingFaceClient.validateCount" should "reject counts outside 1 to 4" in {
    for count <- Seq(0, -1, 5) do withClue(s"count=$count: ")(huggingFace().validateCount(count) shouldBe badCount)
  }

  it should "accept the bounds 1 and 4" in {
    huggingFace().validateCount(1) shouldBe Right(1)
    huggingFace().validateCount(4) shouldBe Right(4)
  }

  "HuggingFaceClient.generateImages" should "reject a blank prompt before sending any request" in {
    val http = new RecordingHttpClient()

    huggingFace(http).generateImages("  ", 1) shouldBe emptyPrompt
    http.requests shouldBe 0
  }

  it should "reject an out-of-range count before sending any request" in {
    val http = new RecordingHttpClient()

    huggingFace(http).generateImages("a red fox", 5) shouldBe badCount
    http.requests shouldBe 0
  }
}
