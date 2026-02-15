package org.llm4s.imagegeneration.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.llm4s.imagegeneration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import requests.Response
import java.nio.charset.StandardCharsets

class VertexAIClientTest extends AnyFunSuite with Matchers with ScalaFutures {

  val config = VertexAIConfig(
    projectId = "test-project",
    location = "us-central1",
    model = "imagegeneration@005",
    accessToken = "test-token"
  )

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
      url = "https://us-central1-aiplatform.googleapis.com",
      statusCode = statusCode,
      statusMessage = "OK",
      headers = Map.empty,
      data = new geny.Bytes(json.trim.getBytes(StandardCharsets.UTF_8)),
      history = None
    )

  test("generateImage validates prompt (empty)") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(200, "{}"))))
    val result = client.generateImage("   ")
    result should matchPattern { case Left(ValidationError(_)) => }
  }

  test("generateImages validates count (too high)") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(200, "{}"))))
    val result = client.generateImages("prompt", 9)
    result should matchPattern { case Left(ValidationError(_)) => }
  }

  test("generateImages builds correct payload") {
    val successJson =
      """{
        |  "predictions": [
        |    {
        |      "bytesBase64Encoded": "base64image"
        |    }
        |  ]
        |}""".stripMargin
    val mockResponse = createResponse(200, successJson)
    val httpClient   = new MockHttpClient(Success(mockResponse))
    val client       = new VertexAIClient(config, httpClient)

    val options = ImageGenerationOptions(
      size = ImageSize.Landscape1536x1024,
      seed = Some(12345L),
      negativePrompt = Some("blurry")
    )

    val result = client.generateImages("a prompt", 1, options)
    result.fold(
      err => fail(s"Expected success but got error: ${err.message}"),
      _ => succeed
    )

    // Verify payload
    val payload = ujson.read(httpClient.lastData)
    payload("instances")(0)("prompt").str shouldBe "a prompt"
    payload("instances")(0)("negativePrompt").str shouldBe "blurry"
    payload("parameters")("sampleCount").num.toInt shouldBe 1
    payload("parameters")("aspectRatio").str shouldBe "16:9"
    // Seed is serialized as a string for safety in JSON
    payload("parameters")("seed").str shouldBe "12345"

    // Verify headers
    httpClient.lastHeaders("Authorization") shouldBe "Bearer test-token"
  }

  test("generateImages handles success response") {
    val successJson =
      """{
        |  "predictions": [
        |    { "bytesBase64Encoded": "img1" },
        |    { "bytesBase64Encoded": "img2" }
        |  ]
        |}""".stripMargin
    val mockResponse = createResponse(200, successJson)
    val client       = new VertexAIClient(config, new MockHttpClient(Success(mockResponse)))

    val result = client.generateImages("prompt", 2)
    val images = result.fold(
      err => fail(s"Expected success but got error: ${err.message}"),
      imgs => imgs
    )

    images should have size 2
    images(0).data shouldBe "img1"
    images(1).data shouldBe "img2"
  }

  test("generateImages handles empty predictions") {
    val json   = """{"predictions": []}"""
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(200, json))))
    val result = client.generateImages("prompt", 1)
    result should matchPattern { case Left(ValidationError(_)) => }
  }

  test("generateImages handles API error (401)") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(401, "Unauthorized"))))
    val result = client.generateImages("prompt", 1)
    result should matchPattern { case Left(AuthenticationError(_)) => }
  }

  test("generateImages handles API error (500)") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(500, "Internal Error"))))
    val result = client.generateImages("prompt", 1)
    result should matchPattern { case Left(ServiceError(_, 500)) => }
  }

  test("generateImages handles connection failure") {
    val client = new VertexAIClient(config, new MockHttpClient(Failure(new Exception("Network error"))))
    val result = client.generateImages("prompt", 1)
    result should matchPattern { case Left(UnknownError(_)) => }
  }

  test("health check handles success") {
    val httpClient = new MockHttpClient(Success(createResponse(200, "{}")))
    val client     = new VertexAIClient(config, httpClient)
    val result     = client.health()
    result.map(_.status) shouldBe Right(HealthStatus.Healthy)
    (httpClient.lastUrl should not).endWith(":predict")
    httpClient.lastUrl should endWith(config.model)
  }

  test("health check handles 401/403 as degraded") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(403, "Forbidden"))))
    val result = client.health()
    result.map(_.status) shouldBe Right(HealthStatus.Degraded)
  }

  test("editImage is not supported") {
    val client = new VertexAIClient(config, new MockHttpClient(Success(createResponse(200, "{}"))))
    val result = client.editImage(java.nio.file.Paths.get("foo"), "prompt")
    result should matchPattern { case Left(ValidationError(_)) => } // "Image editing is not yet supported..."
  }

  test("async methods delegate correctly") {
    val successJson = """{"predictions": [{ "bytesBase64Encoded": "img1" }]}"""
    val httpClient  = new MockHttpClient(Success(createResponse(200, successJson)))
    val client      = new VertexAIClient(config, httpClient)

    whenReady(client.generateImageAsync("prompt")) { result =>
      result.isRight shouldBe true
      httpClient.lastUrl should endWith(":predict")
    }
  }
}
