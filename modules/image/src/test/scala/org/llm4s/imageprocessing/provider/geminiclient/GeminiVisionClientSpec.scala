package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.error.NetworkError
import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart, StreamingHttpResponse }
import org.llm4s.imageprocessing._
import org.llm4s.types.Result
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.llm4s.media.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.awt.image.BufferedImage
import java.awt.Color
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

/** Test double for the HTTP layer: every request gets the same canned outcome. */
class FixedHttpClient(outcome: Result[HttpResponse]) extends Llm4sHttpClient {
  override def get(u: String, h: Map[String, String], p: Map[String, String], t: FiniteDuration)          = outcome
  override def post(u: String, h: Map[String, String], b: String, t: FiniteDuration)                      = outcome
  override def postBytes(u: String, h: Map[String, String], d: Array[Byte], t: FiniteDuration)            = outcome
  override def postMultipart(u: String, h: Map[String, String], p: Seq[MultipartPart], t: FiniteDuration) = outcome
  override def put(u: String, h: Map[String, String], b: String, t: FiniteDuration)                       = outcome
  override def delete(u: String, h: Map[String, String], t: FiniteDuration)                               = outcome
  override def postRaw(u: String, h: Map[String, String], b: String, t: FiniteDuration) =
    outcome.map(r => HttpRawResponse(r.statusCode, r.body.getBytes, r.headers))
  override def postStream(u: String, h: Map[String, String], b: String, t: FiniteDuration) =
    outcome.map(r => StreamingHttpResponse(r.statusCode, new java.io.ByteArrayInputStream(r.body.getBytes), r.headers))
}

/** A [[GeminiVisionClient]] whose HTTP layer answers with a canned outcome. */
class MockGeminiVisionClient(config: GeminiVisionConfig, mockResponse: Try[(Int, String)])
    extends GeminiVisionClient(
      config,
      new FixedHttpClient(
        mockResponse.toEither.left.map(e => NetworkError(e.getMessage, Some(e), "test")).map { case (c, b) =>
          HttpResponse(c, b)
        }
      )
    )

class GeminiVisionClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val testConfig = GeminiVisionConfig(
    apiKey = "test-key",
    model = "gemini-3.6-flash",
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
    new GeminiVisionClient(testConfig).detectMediaType("a.png").mimeType shouldBe "image/png"
  }

  it should "detect JPEG for .jpg" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.jpg").mimeType shouldBe "image/jpeg"
  }

  it should "detect JPEG for .jpeg" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.jpeg").mimeType shouldBe "image/jpeg"
  }

  it should "detect GIF" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.gif").mimeType shouldBe "image/gif"
  }

  it should "detect WEBP" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.webp").mimeType shouldBe "image/webp"
  }

  it should "detect BMP" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.bmp").mimeType shouldBe "image/bmp"
  }

  it should "detect TIFF for .tiff" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.tiff").mimeType shouldBe "image/tiff"
  }

  it should "detect TIFF for .tif" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.tif").mimeType shouldBe "image/tiff"
  }

  it should "default to JPEG for unknown extension" in {
    new GeminiVisionClient(testConfig).detectMediaType("a.xyz").mimeType shouldBe "image/jpeg"
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
    result.foreach(r => r.text shouldBe Some("hello"))
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

  it should "return Left, not a made-up description, for a malformed 200 body" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, malformedJson)))
    client.analyzeImage(tempImageFile.toString, None).isLeft shouldBe true
  }

  it should "return Left for an empty parts array" in {
    val client = new MockGeminiVisionClient(testConfig, Success((200, emptyResponseJson)))
    client.analyzeImage(tempImageFile.toString, None).isLeft shouldBe true
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
    val result = client.convertFormat(tempImageFile.toString, MediaType.Jpeg)
    result.isRight shouldBe true
    result.foreach(_.format shouldBe MediaType.Jpeg)
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
