package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.awt.image.BufferedImage
import java.awt.Color
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.util.{ Failure, Success, Try }

/** Test double that overrides the HTTP layer. */
class MockGeminiVisionClient(
  config: GeminiVisionConfig,
  mockResponse: Try[(Int, String)]
) extends GeminiVisionClient(config) {
  override protected def sendHttpRequest(
    url: String,
    requestBodyJson: String,
    timeoutSeconds: Int
  ): Try[(Int, String)] = mockResponse
}

class GeminiVisionClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val testConfig = GeminiVisionConfig(
    apiKey = "test-key",
    model = "gemini-1.5-flash",
    connectTimeoutSeconds = 5,
    requestTimeoutSeconds = 10
  )

  // Valid JSON response from Gemini with 'person' and 'tree' keywords for tag/object extraction
  private val validGeminiJson =
    """{"candidates":[{"content":{"parts":[{"text":"A person standing near a tree outdoors. The image contains text says \"hello\"."}]}}]}"""

  private val validGeminiJsonNoTextMatch =
    """{"candidates":[{"content":{"parts":[{"text":"A person with a cat and a dog near a building."}]}}]}"""

  private val errorJson =
    """{"error":{"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""

  private val errorJsonNoStatus =
    """{"error":{"message":"Bad request"}}"""

  private val malformedJson = "not json at all {{{{"

  private val emptyResponseJson =
    """{"candidates":[{"content":{"parts":[]}}]}"""

  var tempImageFile: java.nio.file.Path = _

  override def beforeEach(): Unit = {
    tempImageFile = Files.createTempFile("gemini-test", ".png")
    val img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
    val g2d = img.createGraphics()
    g2d.setColor(Color.BLUE)
    g2d.fillRect(0, 0, 32, 32)
    g2d.dispose()
    ImageIO.write(img, "png", tempImageFile.toFile)
    ()
  }

  override def afterEach(): Unit = {
    Files.deleteIfExists(tempImageFile)
    ()
  }

  // ---- encodeImageToBase64 ----

  "GeminiVisionClient.encodeImageToBase64" should "succeed for a real image file" in {
    val client = new GeminiVisionClient(testConfig)
    val result = client.encodeImageToBase64(tempImageFile.toString)
    result.isSuccess shouldBe true
    result.foreach { b64 =>
      b64 should not be empty
      b64.matches("^[A-Za-z0-9+/]*={0,2}$") shouldBe true
    }
  }

  it should "fail for a non-existent file" in {
    val client = new GeminiVisionClient(testConfig)
    client.encodeImageToBase64("/no/such/file.png").isFailure shouldBe true
  }

  // ---- detectMediaType ----

  "GeminiVisionClient.detectMediaType" should "detect PNG" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.png").value shouldBe "image/png"
  }

  it should "detect JPEG for .jpg" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.jpg").value shouldBe "image/jpeg"
  }

  it should "detect JPEG for .jpeg" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.jpeg").value shouldBe "image/jpeg"
  }

  it should "detect GIF" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.gif").value shouldBe "image/gif"
  }

  it should "detect WEBP" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.webp").value shouldBe "image/webp"
  }

  it should "detect BMP" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.bmp").value shouldBe "image/bmp"
  }

  it should "detect TIFF for .tiff" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.tiff").value shouldBe "image/tiff"
  }

  it should "detect TIFF for .tif" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.tif").value shouldBe "image/tiff"
  }

  it should "default to JPEG for unknown extension" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.xyz").value shouldBe "image/jpeg"
  }

  // ---- analyzeImage — happy path ----

  "GeminiVisionClient.analyzeImage" should "return Right with description on 200 response" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach { r =>
      r.description should include("person")
      r.confidence shouldBe 0.85
    }
  }

  it should "extract tags from the description text" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach { r =>
      r.tags should contain("person")
      r.tags should contain("tree")
    }
  }

  it should "extract detected objects from the description text" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach { r =>
      r.objects.map(_.label) should contain("person")
      r.objects.map(_.label) should contain("tree")
    }
  }

  it should "extract text from response using 'says' pattern" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach { r =>
      r.text shouldBe Some("hello")
    }
  }

  it should "return None for text when no text pattern is found" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJsonNoTextMatch)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach(_.text shouldBe None)
  }

  it should "use the default prompt when none is provided" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    client.analyzeImage(tempImageFile.toString, None).isRight shouldBe true
  }

  it should "use a custom prompt when one is provided" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    client.analyzeImage(tempImageFile.toString, Some("What is in this image?")).isRight shouldBe true
  }

  it should "handle malformed JSON response gracefully (fallback description)" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, malformedJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach(_.description should not be empty)
  }

  it should "handle empty parts array in response (fallback description)" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, emptyResponseJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isRight shouldBe true
    result.foreach(_.description should not be empty)
  }

  // ---- analyzeImage — error paths ----

  it should "return Left on 401 Unauthorized with JSON error body" in {
    val client = new MockGeminiVisionClient(testConfig, Success((401, errorJson)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left on 401 with error JSON missing status field" in {
    val client = new MockGeminiVisionClient(testConfig, Success((401, errorJsonNoStatus)))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left on 500 server error" in {
    val client = new MockGeminiVisionClient(testConfig, Success((500, "Internal Server Error")))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left on 400 with non-JSON response body" in {
    val client = new MockGeminiVisionClient(testConfig, Success((400, "Bad Request plain text")))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left on network failure" in {
    val client = new MockGeminiVisionClient(testConfig, Failure(new java.io.IOException("Connection refused")))
    val result = client.analyzeImage(tempImageFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "return Left when image file does not exist" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, validGeminiJson)))
    client.analyzeImage("/no/such/file.png", None).isLeft shouldBe true
  }

  // ---- delegation methods ----

  "GeminiVisionClient.preprocessImage" should "delegate to the local processor" in {
    val client = new GeminiVisionClient(testConfig)
    val result = client.preprocessImage(tempImageFile.toString, List(ImageOperation.Resize(16, 16)))
    result.isRight shouldBe true
    result.foreach { p =>
      p.width shouldBe 16
      p.height shouldBe 16
    }
  }

  "GeminiVisionClient.convertFormat" should "delegate to the local processor" in {
    val client = new GeminiVisionClient(testConfig)
    val result = client.convertFormat(tempImageFile.toString, ImageFormat.JPEG)
    result.isRight shouldBe true
    result.foreach(_.format shouldBe ImageFormat.JPEG)
  }

  "GeminiVisionClient.resizeImage" should "delegate to the local processor" in {
    val client = new GeminiVisionClient(testConfig)
    val result = client.resizeImage(tempImageFile.toString, 16, 16, maintainAspectRatio = false)
    result.isRight shouldBe true
    result.foreach { p =>
      p.width shouldBe 16
      p.height shouldBe 16
    }
  }

  it should "maintain aspect ratio when requested" in {
    val client = new GeminiVisionClient(testConfig)
    val result = client.resizeImage(tempImageFile.toString, 16, 16, maintainAspectRatio = true)
    result.isRight shouldBe true
  }
}
