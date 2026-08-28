package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.llm4s.imageprocessing.provider.geminiclient.GeminiVisionClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.awt.image.BufferedImage
import java.awt.Color
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Integration smoke test for GeminiVisionClient.
 *
 * Unit-style tests (no API key required) verify structural behaviour and error
 * handling in line with [[OpenAIVisionClientTest]] and [[AnthropicVisionClientTest]].
 *
 * The cloud smoke test ("analyze image with real Gemini API") requires the
 * `GOOGLE_API_KEY` environment variable to be set.  It is skipped automatically
 * when the key is absent so that `sbt test` stays fast.
 *
 * Run the cloud smoke test with:
 * {{{
 *   GOOGLE_API_KEY=<key> sbt testSmoke
 * }}}
 */
class GeminiVisionClientTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // Store as val so we never call System.getenv in test logic.
  private val googleApiKey: Option[String] =
    Option(System.getenv("GOOGLE_API_KEY")).filter(_.nonEmpty)
      .orElse(Option(System.getenv("GEMINI_API_KEY")).filter(_.nonEmpty))

  var tempFile: java.nio.file.Path = _
  var config: GeminiVisionConfig   = _

  override def beforeEach(): Unit = {
    tempFile = Files.createTempFile("gemini-test", ".png")
    config = GeminiVisionConfig(
      apiKey = "test-api-key",
      model  = "gemini-1.5-flash"
    )

    val testImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
    val g2d       = testImage.createGraphics()
    g2d.setColor(Color.RED)
    g2d.fillRect(0, 0, 64, 64)
    g2d.setColor(Color.BLUE)
    g2d.fillRect(16, 16, 32, 32)
    g2d.dispose()
    ImageIO.write(testImage, "png", tempFile.toFile)
  }

  override def afterEach(): Unit = {
    Files.deleteIfExists(tempFile)
    ()
  }

  // ---- unit-style tests (no real API key required) ----

  "GeminiVisionClient" should "encode image to base64 successfully" in {
    val client = new GeminiVisionClient(config)

    val result = client.encodeImageToBase64(tempFile.toString)
    result.isSuccess shouldBe true

    result.foreach { base64 =>
      base64 should not be empty
      base64.matches("^[A-Za-z0-9+/]*={0,2}$") shouldBe true
    }
  }

  it should "fail to encode a non-existent image" in {
    val client = new GeminiVisionClient(config)

    val result = client.encodeImageToBase64("/nonexistent/file.png")
    result.isFailure shouldBe true
  }

  it should "detect media type correctly for common extensions" in {
    val client = new GeminiVisionClient(config)

    client.detectMediaType("test.jpg").value  shouldBe "image/jpeg"
    client.detectMediaType("test.jpeg").value shouldBe "image/jpeg"
    client.detectMediaType("test.png").value  shouldBe "image/png"
    client.detectMediaType("test.gif").value  shouldBe "image/gif"
    client.detectMediaType("test.webp").value shouldBe "image/webp"
    client.detectMediaType("test.bmp").value  shouldBe "image/bmp"
    client.detectMediaType("test.tiff").value shouldBe "image/tiff"
    client.detectMediaType("test.tif").value  shouldBe "image/tiff"
    // Unknown extensions fall back to JPEG
    client.detectMediaType("test.unknown").value shouldBe "image/jpeg"
  }

  it should "return Left when analyzeImage is called with an invalid API key" in {
    val client = new GeminiVisionClient(config)

    val result = client.analyzeImage(tempFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left when analyzeImage is called with a custom prompt and invalid API key" in {
    val client = new GeminiVisionClient(config)

    val result = client.analyzeImage(tempFile.toString, Some("Describe this image in one sentence"))
    result.isLeft shouldBe true
  }

  it should "return Left when analyzeImage is called on a non-existent file" in {
    val client = new GeminiVisionClient(config)

    val result = client.analyzeImage("/nonexistent/file.png", None)
    result.isLeft shouldBe true
  }

  it should "return Left when analyzeImage is called on an invalid (non-image) file" in {
    val client   = new GeminiVisionClient(config)
    val textFile = Files.createTempFile("not-an-image", ".txt")
    try {
      Files.write(textFile, "This is not an image".getBytes)

      val result = client.analyzeImage(textFile.toString, None)
      result.isLeft shouldBe true
    } finally Files.deleteIfExists(textFile)
  }

  it should "delegate preprocessing to the local processor" in {
    val client = new GeminiVisionClient(config)

    val result = client.preprocessImage(tempFile.toString, List(ImageOperation.Resize(32, 32)))
    result.isRight shouldBe true
    result.foreach { processed =>
      processed.width  shouldBe 32
      processed.height shouldBe 32
      processed.metadata.operations should contain(ImageOperation.Resize(32, 32))
    }
  }

  it should "delegate format conversion to the local processor" in {
    val client = new GeminiVisionClient(config)

    val result = client.convertFormat(tempFile.toString, ImageFormat.JPEG)
    result.isRight shouldBe true
    result.foreach(_.format shouldBe ImageFormat.JPEG)
  }

  it should "delegate resizing to the local processor" in {
    val client = new GeminiVisionClient(config)

    val result = client.resizeImage(tempFile.toString, 32, 32, maintainAspectRatio = false)
    result.isRight shouldBe true
    result.foreach { processed =>
      processed.width  shouldBe 32
      processed.height shouldBe 32
    }
  }

  it should "implement the ImageProcessingClient trait" in {
    val client: ImageProcessingClient = new GeminiVisionClient(config)
    client should not be null
  }

  // ---- cloud smoke test (requires GOOGLE_API_KEY) ----

  it should "analyze image with real Gemini API and return a non-empty description" in {
    assume(googleApiKey.isDefined, "GOOGLE_API_KEY (or GEMINI_API_KEY) not set — skipping cloud smoke test")

    val smokeConfig = GeminiVisionConfig(
      apiKey = googleApiKey.get,
      model  = "gemini-1.5-flash"
    )
    val client = new GeminiVisionClient(smokeConfig)

    // Use the shared resource image for the real call
    val resourcePath = getClass.getResource("/test-image.png")
    assume(resourcePath != null, "test-image.png resource not found")

    val result = client.analyzeImage(
      imagePath = resourcePath.getPath,
      prompt    = Some("Describe this image in one sentence")
    )

    withClue(s"analyzeImage failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.foreach { analysis =>
      analysis.description should not be empty
    }
  }
}
