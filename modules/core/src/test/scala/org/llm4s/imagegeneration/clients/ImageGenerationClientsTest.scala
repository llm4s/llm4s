package org.llm4s.imagegeneration.clients

import org.llm4s.imagegeneration._
import org.llm4s.imagegeneration.provider._
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import requests.Response

import org.scalatest.concurrent.ScalaFutures
import scala.util.Success
import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global

class ImageGenerationClientsTest
    extends AnyFunSuite
    with Matchers
    with MockFactory
    with EitherValues
    with ScalaFutures {

  // Common test data
  val prompt            = "a beautiful sunset"
  val mockResponseBytes = """{"data":[{"b64_json":"base64data","url":null}],"created":1234567890}""".getBytes

  // Response helpers
  def createResponse(statusCode: Int, body: String): Response = Response(
    url = "http://test",
    statusCode = statusCode,
    statusMessage = if (statusCode == 200) "OK" else "Error",
    data = new geny.Bytes(body.getBytes),
    headers = Map.empty,
    history = None
  )

  val mockSuccessResponse = createResponse(200, """{"data":[{"b64_json":"base64data"}],"created":1234567890}""")
  val mockErrorResponse   = createResponse(400, """{"error":{"message":"Invalid request"}}""")

  // ==========================================
  // OpenAI Client Tests
  // ==========================================

  test("OpenAIImageClient should generate single image") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val responseBody = """{
      "created": 1234567890,
      "data": [{"b64_json": "base64data"}]
    }"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val result = client.generateImage(prompt)
    result.isRight shouldBe true
    result.value.data shouldBe "base64data"
  }

  test("OpenAIImageClient should generate multiple images") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val responseBody = """{
      "created": 1234567890,
      "data": [
        {"b64_json": "img1"},
        {"b64_json": "img2"}
      ]
    }"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val result = client.generateImages(prompt, 2)
    result.isRight shouldBe true
    result.value.length shouldBe 2
    result.value(0).data shouldBe "img1"
    result.value(1).data shouldBe "img2"
  }

  test("OpenAIImageClient should parse URL response format") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val responseBody = """{
      "created": 1234567890,
      "data": [{"url": "http://image.url"}]
    }"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val options = ImageGenerationOptions(responseFormat = Some("url"))
    val result  = client.generateImage(prompt, options)

    result.isRight shouldBe true
    result.value.url shouldBe Some("http://image.url")
    result.value.data shouldBe empty
  }

  test("OpenAIImageClient should handle API errors") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(mockErrorResponse))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[ValidationError]
  }

  test("OpenAIImageClient should handle 401 Unauthorized") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(401, "Unauthorized")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[AuthenticationError]
  }

  test("OpenAIImageClient should handle 429 Rate Limit") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(429, "Rate limit")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[RateLimitError]
  }

  test("OpenAIImageClient should validate prompt") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val result = client.generateImage("")
    result.isLeft shouldBe true
    result.left.value shouldBe a[ValidationError]
  }

  test("OpenAIImageClient should validate count") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val result = client.generateImages(prompt, 11) // Max is 10 for dall-e-2
    result.isLeft shouldBe true
    result.left.value shouldBe a[ValidationError]
  }

  test("OpenAIImageClient health check should return healthy") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.get _).when(*, *, *).returns(Success(createResponse(200, "{}")))

    val result = client.health()
    result.isRight shouldBe true
    result.value.status shouldBe HealthStatus.Healthy
  }

  test("OpenAIImageClient should support editImage") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val responseBody = """{
      "created": 1234567890,
      "data": [{"b64_json": "edited_image_base64"}]
    }"""
    (mockHttpClient.postMultipart _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val tempFile = File.createTempFile("test", ".png")
    try {
      val result = client.editImage(tempFile.toPath, "edit prompt")
      result.isRight shouldBe true
      result.value.head.data shouldBe "edited_image_base64"
    } finally
      tempFile.delete()
  }

  test("OpenAIImageClient should handle malformed JSON response") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "{invalid-json")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[UnknownError]
  }

  test("OpenAIImageClient should handle empty response body") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[UnknownError]
  }

  test("OpenAIImageClient should support async methods") {
    val mockHttpClient = stub[HttpClient]
    val config         = OpenAIConfig(apiKey = "test-key")
    val client         = new OpenAIImageClient(config, mockHttpClient)

    val responseBody = """{"created": 1234567890, "data": [{"b64_json": "img1"}]}"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    whenReady(client.generateImageAsync(prompt)) { result =>
      result.isRight shouldBe true
      result.value.data shouldBe "img1"
    }

    whenReady(client.generateImagesAsync(prompt, 1)) { result =>
      result.isRight shouldBe true
      result.value.length shouldBe 1
    }

    val tempFile = File.createTempFile("test", ".png")
    try {
      val editResponseBody = """{"created": 1234567890, "data": [{"b64_json": "edited"}]}"""
      (mockHttpClient.postMultipart _).when(*, *, *, *).returns(Success(createResponse(200, editResponseBody)))
      whenReady(client.editImageAsync(tempFile.toPath, "edit")) { result =>
        result.isRight shouldBe true
        result.value.head.data shouldBe "edited"
      }
    } finally tempFile.delete()
  }

  // ==========================================
  // HuggingFace Client Tests
  // ==========================================

  test("HuggingFaceClient should generate single image") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "imagebytes")))

    val result = client.generateImage(prompt)
    result.isRight shouldBe true
    result.value.data should not be empty
  }

  test("HuggingFaceClient should generate multiple images (looping)") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "imagebytes")))

    val result = client.generateImages(prompt, 2)
    result.isRight shouldBe true
    result.value.length shouldBe 2
  }

  test("HuggingFaceClient should map API error") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(mockErrorResponse))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
  }

  test("HuggingFaceClient should handle 401 Unauthorized") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(401, "Unauthorized")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[AuthenticationError]
  }

  test("HuggingFaceClient should handle 429 Rate Limit") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(429, "Rate limit")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[RateLimitError]
  }

  test("HuggingFaceClient health check should return healthy") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.get _).when(*, *, *).returns(Success(createResponse(200, "{}")))

    val result = client.health()
    result.isRight shouldBe true
    result.value.status shouldBe HealthStatus.Healthy
  }

  test("HuggingFaceClient should support async methods") {
    val mockHttpClient = stub[HttpClient]
    val config         = HuggingFaceConfig(apiKey = "test-key")
    val client         = new HuggingFaceClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "imagebytes")))

    whenReady(client.generateImageAsync(prompt)) { result =>
      result.isRight shouldBe true
      result.value.data should not be empty
    }

    whenReady(client.generateImagesAsync(prompt, 1)) { result =>
      result.isRight shouldBe true
      result.value.length shouldBe 1
    }

    val tempFile = File.createTempFile("test", ".png")
    try
      whenReady(client.editImageAsync(tempFile.toPath, "edit")) { result =>
        result.isLeft shouldBe true
        result.left.value shouldBe a[UnsupportedOperation]
      }
    finally tempFile.delete()
  }

  // ==========================================
  // Stable Diffusion Client Tests
  // ==========================================

  test("StableDiffusionClient should generate single image") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    val responseBody = """{"images": ["base64data"], "parameters": {}, "info": ""}"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val result = client.generateImage(prompt)
    result.isRight shouldBe true
    result.value.data shouldBe "base64data"
  }

  test("StableDiffusionClient should generate multiple images") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    val responseBody = """{"images": ["img1", "img2"], "parameters": {}, "info": ""}"""
    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, responseBody)))

    val result = client.generateImages(prompt, 2)
    result.isRight shouldBe true
    result.value.length shouldBe 2
  }

  test("StableDiffusionClient should map API error") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(mockErrorResponse))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
  }

  test("StableDiffusionClient should handle 401 Unauthorized") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(401, "Unauthorized")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[AuthenticationError]
  }

  test("StableDiffusionClient should handle 429 Rate Limit") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(429, "Rate limit")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[RateLimitError]
  }

  test("StableDiffusionClient should handle malformed JSON response") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    (mockHttpClient.post _).when(*, *, *, *).returns(Success(createResponse(200, "{invalid")))

    val result = client.generateImage(prompt)
    result.isLeft shouldBe true
    result.left.value shouldBe a[UnknownError]
  }

  test("StableDiffusionClient health check should return healthy") {
    val mockHttpClient = stub[HttpClient]
    val config         = StableDiffusionConfig(baseUrl = "http://localhost:7860")
    val client         = new StableDiffusionClient(config, mockHttpClient)

    (mockHttpClient.get _).when(*, *, *).returns(Success(createResponse(200, "{}")))

    val result = client.health()
    result.isRight shouldBe true
    result.value.status shouldBe HealthStatus.Healthy
  }

  // ==========================================
  // HttpClient Tests
  // ==========================================

  test("HttpClient should return failure on exception") {
    val client = new SimpleHttpClient()
    val result = client.post("http://0.0.0.0:0/invalid", Map.empty, "", 100)
    result.isFailure shouldBe true
  }
}
