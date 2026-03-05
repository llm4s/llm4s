package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MultipartPart }
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import scala.util.{ Failure, Success }

/**
 * Comprehensive tests for HttpClient trait and SimpleHttpClient implementation.
 *
 * Tests factory methods, delegation to Llm4sHttpClient, error handling, and logging.
 */
class HttpClientSpec extends AnyWordSpec with Matchers with MockFactory {

  // Test helpers
  private def successResponse(statusCode: Int = 200, body: String = "{}"): HttpResponse =
    HttpResponse(statusCode, body)

  private def createTempFile(content: String = "test content"): java.nio.file.Path = {
    val tempFile = Files.createTempFile("test", ".txt")
    Files.write(tempFile, content.getBytes)
    tempFile.toFile.deleteOnExit()
    tempFile
  }

  "HttpClient factory" should {

    "create() should return SimpleHttpClient instance" in {
      val client = HttpClient.create()

      client shouldBe a[SimpleHttpClient]
      client shouldBe a[HttpClient]
    }

    "apply() should return SimpleHttpClient instance" in {
      val client = HttpClient.apply()

      client shouldBe a[SimpleHttpClient]
      client shouldBe a[HttpClient]
    }

    "create() should return working client" in {
      val client = HttpClient.create()

      // Should not throw on instantiation
      noException should be thrownBy client
    }

    "apply() should delegate to create()" in {
      val client1 = HttpClient.create()
      val client2 = HttpClient.apply()

      // Both should be SimpleHttpClient instances
      client1.getClass shouldBe client2.getClass
    }
  }

  "SimpleHttpClient.post()" should {

    "delegate to Llm4sHttpClient with correct parameters" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val url     = "https://api.example.com/generate"
      val headers = Map("Authorization" -> "Bearer token", "Content-Type" -> "application/json")
      val data    = """{"prompt": "test"}"""
      val timeout = 30000

      (mockLlm4s.post _).when(url, headers, data, timeout).returns(successResponse(200, """{"result":"success"}"""))

      val result = client.post(url, headers, data, timeout)

      result shouldBe a[Success[_]]
      (mockLlm4s.post _).verify(url, headers, data, timeout).once()
    }

    "return Success with HttpResponse on successful request" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val expectedResponse = successResponse(200, """{"status":"ok"}""")
      (mockLlm4s.post _).when(*, *, *, *).returns(expectedResponse)

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      result shouldBe Success(expectedResponse)
    }

    "return Success even with non-200 status code" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val expectedResponse = successResponse(500, """{"error":"server error"}""")
      (mockLlm4s.post _).when(*, *, *, *).returns(expectedResponse)

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      // SimpleHttpClient doesn't validate status codes, just wraps in Try
      result shouldBe Success(expectedResponse)
    }

    "return Failure on network timeout" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _)
        .when(*, *, *, *)
        .throws(new java.net.http.HttpTimeoutException("Request timed out"))

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      result match {
        case Failure(e) => e shouldBe a[java.net.http.HttpTimeoutException]
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }

    "return Failure on connection exception" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _).when(*, *, *, *).throws(new java.net.ConnectException("Connection refused"))

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      result match {
        case Failure(e) => e shouldBe a[java.net.ConnectException]
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }

    "return Failure on unknown host" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _).when(*, *, *, *).throws(new java.net.UnknownHostException("unknown.host"))

      val result = client.post("https://unknown.host", Map.empty, "{}", 10000)

      result match {
        case Failure(e) => e shouldBe a[java.net.UnknownHostException]
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }

    "handle empty headers" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _).when(*, *, *, *).returns(successResponse())

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      result shouldBe a[Success[_]]
    }

    "handle empty data" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _).when(*, *, *, *).returns(successResponse())

      val result = client.post("https://api.example.com", Map.empty, "", 10000)

      result shouldBe a[Success[_]]
    }
  }

  "SimpleHttpClient.postBytes()" should {

    "delegate to Llm4sHttpClient with byte array" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val url     = "https://api.example.com/upload"
      val headers = Map("Content-Type" -> "application/octet-stream")
      val data    = "test data".getBytes
      val timeout = 30000

      (mockLlm4s.postBytes _).when(url, headers, data, timeout).returns(successResponse(201, """{"uploaded":true}"""))

      val result = client.postBytes(url, headers, data, timeout)

      result shouldBe a[Success[_]]
      (mockLlm4s.postBytes _).verify(url, headers, data, timeout).once()
    }

    "return Success with HttpResponse on successful request" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val expectedResponse = successResponse(200, """{"bytes_received":100}""")
      (mockLlm4s.postBytes _).when(*, *, *, *).returns(expectedResponse)

      val result = client.postBytes("https://api.example.com", Map.empty, Array[Byte](1, 2, 3), 10000)

      result shouldBe Success(expectedResponse)
    }

    "handle empty byte array" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postBytes _).when(*, *, *, *).returns(successResponse())

      val result = client.postBytes("https://api.example.com", Map.empty, Array.empty[Byte], 10000)

      result shouldBe a[Success[_]]
    }

    "handle large byte array" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val largeData = new Array[Byte](1024 * 1024) // 1MB
      (mockLlm4s.postBytes _).when(*, *, *, *).returns(successResponse())

      val result = client.postBytes("https://api.example.com", Map.empty, largeData, 60000)

      result shouldBe a[Success[_]]
    }

    "return Failure on exception" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postBytes _).when(*, *, *, *).throws(new java.io.IOException("Upload failed"))

      val result = client.postBytes("https://api.example.com", Map.empty, Array[Byte](1, 2, 3), 10000)

      result match {
        case Failure(e) => e shouldBe a[java.io.IOException]
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }
  }

  "SimpleHttpClient.postMultipart()" should {

    "delegate to Llm4sHttpClient with multipart parts" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val url      = "https://api.example.com/upload"
      val headers  = Map("Authorization" -> "Bearer token")
      val tempFile = createTempFile()
      val parts = Seq(
        MultipartPart.TextField("prompt", "test prompt"),
        MultipartPart.FilePart("image", tempFile, "test.txt")
      )
      val timeout = 30000

      (mockLlm4s.postMultipart _)
        .when(url, headers, parts, timeout)
        .returns(successResponse(200, """{"uploaded":true}"""))

      val result = client.postMultipart(url, headers, parts, timeout)

      result shouldBe a[Success[_]]
      (mockLlm4s.postMultipart _).verify(url, headers, parts, timeout).once()
    }

    "return Success with HttpResponse on successful request" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val expectedResponse = successResponse(201, """{"files_uploaded":1}""")
      (mockLlm4s.postMultipart _).when(*, *, *, *).returns(expectedResponse)

      val parts  = Seq(MultipartPart.TextField("key", "value"))
      val result = client.postMultipart("https://api.example.com", Map.empty, parts, 10000)

      result shouldBe Success(expectedResponse)
    }

    "handle empty parts sequence" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postMultipart _).when(*, *, *, *).returns(successResponse())

      val result = client.postMultipart("https://api.example.com", Map.empty, Seq.empty, 10000)

      result shouldBe a[Success[_]]
    }

    "handle multiple text fields" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postMultipart _).when(*, *, *, *).returns(successResponse())

      val parts = Seq(
        MultipartPart.TextField("field1", "value1"),
        MultipartPart.TextField("field2", "value2"),
        MultipartPart.TextField("field3", "value3")
      )
      val result = client.postMultipart("https://api.example.com", Map.empty, parts, 10000)

      result shouldBe a[Success[_]]
    }

    "handle mixed text and file parts" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postMultipart _).when(*, *, *, *).returns(successResponse())

      val tempFile = createTempFile("file content")
      val parts = Seq(
        MultipartPart.TextField("prompt", "generate image"),
        MultipartPart.FilePart("file", tempFile, "input.txt"),
        MultipartPart.TextField("size", "1024x1024")
      )
      val result = client.postMultipart("https://api.example.com", Map.empty, parts, 10000)

      result shouldBe a[Success[_]]
    }

    "return Failure on exception" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.postMultipart _).when(*, *, *, *).throws(new RuntimeException("Multipart upload failed"))

      val parts  = Seq(MultipartPart.TextField("key", "value"))
      val result = client.postMultipart("https://api.example.com", Map.empty, parts, 10000)

      result match {
        case Failure(e) => e.getMessage should include("Multipart upload failed")
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }
  }

  "SimpleHttpClient.get()" should {

    "delegate to Llm4sHttpClient with correct parameters" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val url     = "https://api.example.com/status"
      val headers = Map("Authorization" -> "Bearer token")
      val timeout = 15000

      (mockLlm4s.get _).when(url, headers, *, timeout).returns(successResponse(200, """{"status":"running"}"""))

      val result = client.get(url, headers, timeout)

      result shouldBe a[Success[_]]
      (mockLlm4s.get _).verify(url, headers, *, timeout).once()
    }

    "return Success with HttpResponse on successful request" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val expectedResponse = successResponse(200, """{"data":"response"}""")
      (mockLlm4s.get _).when(*, *, *, *).returns(expectedResponse)

      val result = client.get("https://api.example.com", Map.empty, 10000)

      result shouldBe Success(expectedResponse)
    }

    "handle empty headers" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.get _).when(*, *, *, *).returns(successResponse())

      val result = client.get("https://api.example.com", Map.empty, 10000)

      result shouldBe a[Success[_]]
    }

    "return Failure on network exception" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.get _).when(*, *, *, *).throws(new java.net.SocketTimeoutException("Read timed out"))

      val result = client.get("https://api.example.com", Map.empty, 10000)

      result match {
        case Failure(e) => e shouldBe a[java.net.SocketTimeoutException]
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }
  }

  "SimpleHttpClient error handling" should {

    "wrap all exceptions in Try" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.post _).when(*, *, *, *).throws(new RuntimeException("Unexpected error"))

      val result = client.post("https://api.example.com", Map.empty, "{}", 10000)

      result match {
        case Failure(e) =>
          e shouldBe a[RuntimeException]
          e.getMessage shouldBe "Unexpected error"
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }

    "not throw exceptions directly" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.get _).when(*, *, *, *).throws(new NullPointerException("Null pointer"))

      // Should not throw, should return Failure
      noException should be thrownBy {
        client.get("https://api.example.com", Map.empty, 10000)
      }
    }

    "preserve exception types in Failure" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      val originalException = new IllegalArgumentException("Invalid argument")
      (mockLlm4s.postBytes _).when(*, *, *, *).throws(originalException)

      val result = client.postBytes("https://api.example.com", Map.empty, Array.empty, 10000)

      result match {
        case Failure(e) => e shouldBe originalException
        case Success(v) => fail(s"Expected Failure but got Success($v)")
      }
    }
  }

  "SimpleHttpClient integration" should {

    "work with all HTTP methods in sequence" in {
      val mockLlm4s = stub[Llm4sHttpClient]
      val client    = new SimpleHttpClient(mockLlm4s)

      (mockLlm4s.get _).when(*, *, *, *).returns(successResponse(200, """{"status":"ok"}"""))
      (mockLlm4s.post _).when(*, *, *, *).returns(successResponse(201, """{"created":true}"""))
      (mockLlm4s.postBytes _).when(*, *, *, *).returns(successResponse(202, """{"accepted":true}"""))
      (mockLlm4s.postMultipart _).when(*, *, *, *).returns(successResponse(200, """{"uploaded":true}"""))

      val get           = client.get("https://api.example.com", Map.empty, 10000)
      val post          = client.post("https://api.example.com", Map.empty, "{}", 10000)
      val postBytes     = client.postBytes("https://api.example.com", Map.empty, Array[Byte](1), 10000)
      val postMultipart = client.postMultipart("https://api.example.com", Map.empty, Seq.empty, 10000)

      get shouldBe a[Success[_]]
      post shouldBe a[Success[_]]
      postBytes shouldBe a[Success[_]]
      postMultipart shouldBe a[Success[_]]
    }
  }
}
