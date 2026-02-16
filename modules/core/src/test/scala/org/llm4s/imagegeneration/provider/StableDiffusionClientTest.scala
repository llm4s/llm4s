package org.llm4s.imagegeneration.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.llm4s.imagegeneration._
import java.nio.file.{ Files, Paths }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import requests.Response
import java.nio.charset.StandardCharsets

class StableDiffusionClientTest extends AnyFunSuite with Matchers with ScalaFutures {

  val config = StableDiffusionConfig("http://localhost:7860")

  // Mock HttpClient to control responses
  class MockHttpClient(response: Try[Response]) extends HttpClient {
    var lastUrl: String                  = _
    var lastHeaders: Map[String, String] = _
    var lastData: String                 = _

    override def post(url: String, headers: Map[String, String], data: String, timeout: Int): Try[Response] = {
      lastUrl = url
      lastHeaders = headers
      lastData = data
      response
    }

    override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): Try[Response] =
      ???

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      data: requests.MultiPart,
      timeout: Int
    ): Try[Response] = ???

    override def get(url: String, headers: Map[String, String], timeout: Int): Try[Response] = {
      lastUrl = url
      lastHeaders = headers
      response
    }
  }

  // Helper to create a dummy response
  def createResponse(statusCode: Int, json: String): Response =
    Response(
      url = "http://localhost",
      statusCode = statusCode,
      statusMessage = "OK",
      headers = Map.empty,
      data = new geny.Bytes(json.getBytes(StandardCharsets.UTF_8)),
      history = None
    )

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
    // Create dummy files
    val tempImage = Files.createTempFile("test-image", ".png")
    Files.write(tempImage, "dummy-image-data".getBytes)
    val tempMask = Files.createTempFile("test-mask", ".png")
    Files.write(tempMask, "dummy-mask-data".getBytes)

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
    val tempImage = Files.createTempFile("test-image", ".png")
    Files.write(tempImage, "dummy-data".getBytes)

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
    val tempImage = Files.createTempFile("test-image", ".png")
    Files.write(tempImage, "dummy-data".getBytes)

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
    val tempImage = Files.createTempFile("test-image", ".png")
    Files.write(tempImage, "dummy".getBytes)
    try
      whenReady(client.editImageAsync(tempImage, "prompt")) { result =>
        result.isRight shouldBe true
        httpClient.lastUrl should endWith("/img2img")
      }
    finally
      Files.deleteIfExists(tempImage)
  }
}
