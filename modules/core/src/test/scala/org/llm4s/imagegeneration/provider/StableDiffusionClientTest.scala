package org.llm4s.imagegeneration.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.llm4s.imagegeneration._
import org.llm4s.http.{ HttpResponse, MultipartPart }
import java.nio.file.{ Files, Paths }
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }

class StableDiffusionClientTest extends AnyFunSuite with Matchers with ScalaFutures {

  val config = StableDiffusionConfig("http://localhost:7860")

  // Mock HttpClient to control responses
  class MockHttpClient(response: Try[HttpResponse]) extends HttpClient {
    var lastUrl: String                  = _
    var lastHeaders: Map[String, String] = _
    var lastData: String                 = _

    override def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[HttpResponse] = {
      lastUrl = url
      lastHeaders = headers
      lastData = data
      response
    }

    override def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: Int
    ): Try[HttpResponse] = ???

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: Int
    ): Try[HttpResponse] = ???

    override def get(url: String, headers: Map[String, String], timeout: Int): Try[HttpResponse] = {
      lastUrl = url
      lastHeaders = headers
      response
    }

    override def postRaw(url: String, headers: Map[String, String], data: String, timeout: Int) = ???
  }

  // Helper to create a dummy response
  def createResponse(statusCode: Int, body: String): HttpResponse =
    HttpResponse(statusCode = statusCode, body = body)

  test("health check returns Healthy when service responds with 200") {
    val mockResponse = createResponse(200, "{}")
    val httpClient   = new MockHttpClient(Success(mockResponse))
    val client       = new StableDiffusionClient(config, httpClient)

    val result = client.health()
    result.map(_.status) shouldBe Right(HealthStatus.Healthy)
    result.map(_.message) shouldBe Right("Stable Diffusion service is responding")
    httpClient.lastUrl should endWith("/sdapi/v1/options")
  }

  test("health check returns Degraded when service responds with non-200") {
    val mockResponse = createResponse(500, "Internal Server Error")
    val httpClient   = new MockHttpClient(Success(mockResponse))
    val client       = new StableDiffusionClient(config, httpClient)

    val result = client.health()
    result.map(_.status) shouldBe Right(HealthStatus.Degraded)
  }

  test("health check returns ServiceError when request fails") {
    val httpClient = new MockHttpClient(Failure(new Exception("Connection refused")))
    val client     = new StableDiffusionClient(config, httpClient)

    val result = client.health()
    result should matchPattern { case Left(ServiceError(_, _)) => }
  }

  test("editImage fails if image path is invalid") {
    val client = new StableDiffusionClient(config, new MockHttpClient(Success(createResponse(200, "{}"))))
    val result = client.editImage(Paths.get("non-existent-file.png"), "prompt")
    result should matchPattern { case Left(ValidationError(_)) => }
  }

  test("editImage success flow") {
    val tempImage = createTempPng("test-image", 64, 64)
    val tempMask  = createTempPng("test-mask", 64, 64)

    try {
      val successJson  = """{"images": ["base64encodedimage"]}"""
      val mockResponse = createResponse(200, successJson)
      val httpClient   = new MockHttpClient(Success(mockResponse))
      val client       = new StableDiffusionClient(config, httpClient)

      val result = client.editImage(tempImage, "test prompt", Some(tempMask))
      result.isRight shouldBe true
      result.map(images => images.head.data shouldBe "base64encodedimage")

      httpClient.lastUrl should endWith("/sdapi/v1/img2img")
      // Verify payload contains prompt (checking serialization)
      httpClient.lastData should include("test prompt")

    } finally {
      Files.deleteIfExists(tempImage)
      Files.deleteIfExists(tempMask)
    }
  }

  test("editImage handles API error (non-200)") {
    val tempImage = createTempPng("test-image", 64, 64)

    try {
      val mockResponse = createResponse(500, "Error")
      val httpClient   = new MockHttpClient(Success(mockResponse))
      val client       = new StableDiffusionClient(config, httpClient)

      val result = client.editImage(tempImage, "prompt")
      result should matchPattern { case Left(ServiceError(_, 500)) => }
    } finally
      Files.deleteIfExists(tempImage)
  }

  test("editImage handles connection error") {
    val tempImage = createTempPng("test-image", 64, 64)

    try {
      val httpClient = new MockHttpClient(Failure(new Exception("Connection refused")))
      val client     = new StableDiffusionClient(config, httpClient)

      val result = client.editImage(tempImage, "prompt")
      result should matchPattern { case Left(UnknownError(_)) => }
    } finally
      Files.deleteIfExists(tempImage)
  }

  test("generateImages handles connection error") {
    val httpClient = new MockHttpClient(Failure(new Exception("Connection refused")))
    val client     = new StableDiffusionClient(config, httpClient)

    val result = client.generateImages("prompt", 1)
    result should matchPattern { case Left(UnknownError(_)) => }
  }

  test("async methods delegate to sync methods") {
    val successJson  = """{"images": ["img1"]}"""
    val mockResponse = createResponse(200, successJson)
    val httpClient   = new MockHttpClient(Success(mockResponse))
    val client       = new StableDiffusionClient(config, httpClient)

    // generateImageAsync
    whenReady(client.generateImageAsync("prompt")) { result =>
      result.isRight shouldBe true
      httpClient.lastUrl should endWith("/txt2img")
    }

    // generateImagesAsync
    whenReady(client.generateImagesAsync("prompt", 2)) { result =>
      result.isRight shouldBe true
      httpClient.lastUrl should endWith("/txt2img")
    }

    // editImageAsync
    val tempImage = createTempPng("test-image", 64, 64)
    try
      whenReady(client.editImageAsync(tempImage, "prompt")) { result =>
        result.isRight shouldBe true
        httpClient.lastUrl should endWith("/img2img")
      }
    finally
      Files.deleteIfExists(tempImage)
  }

  private def createTempPng(prefix: String, width: Int, height: Int) = {
    val path  = Files.createTempFile(prefix, ".png")
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    ImageIO.write(image, "png", path.toFile)
    path
  }
}
