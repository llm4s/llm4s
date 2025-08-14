package org.llm4s.imageprocessing.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.AnthropicVisionConfig
import org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient
import java.nio.file.Files
import java.awt.image.BufferedImage
import java.awt.Color
import javax.imageio.ImageIO

class AnthropicVisionClientTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  var tempFile: java.nio.file.Path  = _
  var config: AnthropicVisionConfig = _

  override def beforeEach(): Unit = {
    tempFile = Files.createTempFile("test", ".png")
    config = AnthropicVisionConfig(
      apiKey = "test-api-key",
      baseUrl = "https://api.anthropic.com",
      model = "claude-3-sonnet-20240229"
    )

    // Create a test image
    val testImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
    val g2d       = testImage.createGraphics()
    g2d.setColor(Color.RED)
    g2d.fillRect(0, 0, 100, 100)
    g2d.dispose()
    ImageIO.write(testImage, "png", tempFile.toFile)
  }

  override def afterEach(): Unit =
    Files.deleteIfExists(tempFile)

  "AnthropicVisionClient" should "detect media type correctly" in {
    val client = new AnthropicVisionClient(config)

    // Test different file extensions
    client.detectMediaType("test.jpg").value shouldBe "image/jpeg"
    client.detectMediaType("test.jpeg").value shouldBe "image/jpeg"
    client.detectMediaType("test.png").value shouldBe "image/png"
    client.detectMediaType("test.gif").value shouldBe "image/gif"
    client.detectMediaType("test.webp").value shouldBe "image/webp"
    client.detectMediaType("test.bmp").value shouldBe "image/bmp"
    client.detectMediaType("test.tiff").value shouldBe "image/tiff"
    client.detectMediaType("test.tif").value shouldBe "image/tiff"
    client.detectMediaType("test.unknown").value shouldBe "image/jpeg" // Default fallback
  }

  it should "encode image to base64 successfully" in {
    val client = new AnthropicVisionClient(config)

    val result = client.encodeImageToBase64(tempFile.toString)
    result.isSuccess shouldBe true

    result.foreach { base64 =>
      base64 should not be empty
      // Base64 should be valid
      base64.matches("^[A-Za-z0-9+/]*={0,2}$") shouldBe true
    }
  }

  it should "fail to encode non-existent image" in {
    val client = new AnthropicVisionClient(config)

    val result = client.encodeImageToBase64("/nonexistent/file.png")
    result.isFailure shouldBe true
  }

  it should "analyze image with default prompt" in {
    val client = new AnthropicVisionClient(config)

    val result = client.analyzeImage(tempFile.toString, None)
    // Note: This will fail in tests because we don't have a real API key
    // But we can test the error handling
    result.isLeft shouldBe true
  }

  it should "analyze image with custom prompt" in {
    val client = new AnthropicVisionClient(config)

    val result = client.analyzeImage(tempFile.toString, Some("Describe this image in detail"))
    result.isLeft shouldBe true
  }

  it should "extract text from image" in {
    val client = new AnthropicVisionClient(config)

    val result = client.extractText(tempFile.toString)
    result.isLeft shouldBe true
  }

  it should "detect objects in image" in {
    val client = new AnthropicVisionClient(config)

    val result = client.detectObjects(tempFile.toString)
    result.isLeft shouldBe true
  }

  it should "generate tags for image" in {
    val client = new AnthropicVisionClient(config)

    val result = client.generateTags(tempFile.toString)
    result.isLeft shouldBe true
  }

  it should "delegate preprocessing to local processor" in {
    val client = new AnthropicVisionClient(config)

    val result = client.preprocessImage(tempFile.toString, List(ImageOperation.Resize(50, 50)))

    result.isRight shouldBe true
    result.foreach { processedImage =>
      processedImage.width shouldBe 50
      processedImage.height shouldBe 50
      processedImage.metadata.operations should contain(ImageOperation.Resize(50, 50))
    }
  }

  it should "delegate format conversion to local processor" in {
    val client = new AnthropicVisionClient(config)

    val result = client.convertFormat(tempFile.toString, ImageFormat.JPEG)

    result.isRight shouldBe true
    result.foreach(processedImage => processedImage.format shouldBe ImageFormat.JPEG)
  }

  it should "delegate resizing to local processor" in {
    val client = new AnthropicVisionClient(config)

    val result = client.resizeImage(tempFile.toString, 50, 50, maintainAspectRatio = false)

    result.isRight shouldBe true
    result.foreach { processedImage =>
      processedImage.width shouldBe 50
      processedImage.height shouldBe 50
    }
  }

  it should "handle file not found error" in {
    val client = new AnthropicVisionClient(config)

    val result = client.analyzeImage("/nonexistent/file.png", None)
    result.isLeft shouldBe true
  }

  it should "handle invalid image file error" in {
    val client = new AnthropicVisionClient(config)

    // Create a text file instead of image
    val textFile = Files.createTempFile("test", ".txt")
    try {
      Files.write(textFile, "This is not an image".getBytes)

      val result = client.analyzeImage(textFile.toString, None)
      result.isLeft shouldBe true
      result.isLeft shouldBe true
      result.isLeft shouldBe true
    } finally
      Files.deleteIfExists(textFile)
  }

  it should "use correct media type in API call" in {
    val client = new AnthropicVisionClient(config)

    // Test with different file extensions
    val pngFile = Files.createTempFile("test", ".png")
    val jpgFile = Files.createTempFile("test", ".jpg")

    try {
      // Create test images
      val testImage = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
      ImageIO.write(testImage, "png", pngFile.toFile)
      ImageIO.write(testImage, "jpg", jpgFile.toFile)

      // The media type detection should work correctly
      client.detectMediaType(pngFile.toString).value shouldBe "image/png"
      client.detectMediaType(jpgFile.toString).value shouldBe "image/jpeg"
    } finally {
      Files.deleteIfExists(pngFile)
      Files.deleteIfExists(jpgFile)
    }
  }
}
