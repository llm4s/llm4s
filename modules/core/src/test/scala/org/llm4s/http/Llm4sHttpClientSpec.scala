package org.llm4s.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class Llm4sHttpClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: HttpServer = _
  private var baseUrl: String    = _
  private val client             = new JdkHttpClient()

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress(0), 0)

    // Echo handler — returns method, headers, and body in the response
    server.createContext(
      "/echo",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val method      = exchange.getRequestMethod
          val body        = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val userAgent   = Option(exchange.getRequestHeaders.getFirst("User-Agent")).getOrElse("")
          val contentType = Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")

          val responseBody  = s"method=$method|body=$body|user-agent=$userAgent|content-type=$contentType"
          val responseBytes = responseBody.getBytes(StandardCharsets.UTF_8)

          exchange.getResponseHeaders.add("X-Custom-Header", "test-value")
          exchange.sendResponseHeaders(200, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    // Query params handler — echoes back the query string
    server.createContext(
      "/params",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val query         = Option(exchange.getRequestURI.getQuery).getOrElse("")
          val responseBytes = query.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(200, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    // Status handler — returns the status code from the path (e.g. /status/404)
    server.createContext(
      "/status",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val code          = exchange.getRequestURI.getPath.stripPrefix("/status/").toInt
          val responseBytes = s"status=$code".getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(code, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    // Binary handler — echoes raw bytes that include invalid UTF-8 sequences.
    // Used to verify postRaw preserves every byte without charset corruption.
    server.createContext(
      "/binary",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          // JPEG magic bytes + high bytes that are invalid UTF-8 — would be corrupted
          // by BodyHandlers.ofString() + .getBytes(ISO_8859_1)
          val responseBytes = Array[Byte](
            0xff.toByte,
            0xd8.toByte,
            0xff.toByte, // JPEG SOI marker
            0xe0.toByte,
            0x00.toByte,
            0x10.toByte, // APP0 marker
            0x80.toByte,
            0xc0.toByte,
            0xfe.toByte // more high bytes (invalid UTF-8)
          )
          exchange.sendResponseHeaders(200, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    // Multipart handler — echoes content-type header to verify boundary
    server.createContext(
      "/multipart",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val contentType = Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
          val bodyBytes   = exchange.getRequestBody.readAllBytes()
          val bodyStr     = new String(bodyBytes, StandardCharsets.UTF_8)

          val responseBody =
            s"content-type=$contentType|body-length=${bodyBytes.length}|body-contains-boundary=${bodyStr.contains("--")}"
          val responseBytes = responseBody.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(200, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    server.setExecutor(null)
    server.start()
    baseUrl = s"http://localhost:${server.getAddress.getPort}"
  }

  override def afterAll(): Unit =
    if (server != null) server.stop(0)

  // ============================================================
  // HttpResponse model tests
  // ============================================================

  "HttpResponse" should "have sensible defaults" in {
    val response = HttpResponse(200, "ok")
    response.statusCode shouldBe 200
    response.body shouldBe "ok"
    response.headers shouldBe Map.empty
  }

  it should "store headers" in {
    val headers  = Map("content-type" -> Seq("application/json"))
    val response = HttpResponse(200, "{}", headers)
    response.headers("content-type") shouldBe Seq("application/json")
  }

  "HttpRawResponse" should "hold status code and raw bytes" in {
    val bytes    = Array[Byte](0xff.toByte, 0xd8.toByte, 0x00.toByte)
    val response = HttpRawResponse(200, bytes)
    response.statusCode shouldBe 200
    response.body shouldEqual bytes
  }

  // ============================================================
  // MultipartPart model tests
  // ============================================================

  "MultipartPart.TextField" should "hold name and value" in {
    val field = MultipartPart.TextField("key", "value")
    field.name shouldBe "key"
    field.value shouldBe "value"
  }

  "MultipartPart.FilePart" should "hold name, path, and filename" in {
    val path = java.nio.file.Paths.get("/tmp/test.txt")
    val part = MultipartPart.FilePart("file", path, "test.txt")
    part.name shouldBe "file"
    part.path shouldBe path
    part.filename shouldBe "test.txt"
  }

  // ============================================================
  // Llm4sHttpClient.create() factory test
  // ============================================================

  "Llm4sHttpClient.create()" should "return a JdkHttpClient instance" in {
    val instance = Llm4sHttpClient.create()
    instance shouldBe a[JdkHttpClient]
  }

  // ============================================================
  // GET tests
  // ============================================================

  "JdkHttpClient.get" should "send a GET request and return response" in {
    val response = client.get(s"$baseUrl/echo")
    response.statusCode shouldBe 200
    response.body should startWith("method=GET")
  }

  it should "pass custom headers" in {
    val response = client.get(
      s"$baseUrl/echo",
      headers = Map("User-Agent" -> "llm4s-test/1.0")
    )
    response.body should include("user-agent=llm4s-test/1.0")
  }

  it should "encode and append query params" in {
    val response = client.get(
      s"$baseUrl/params",
      params = Map("q" -> "hello world", "count" -> "5")
    )
    response.body should include("q=hello+world")
    response.body should include("count=5")
  }

  it should "append query params to URL that already has params" in {
    val response = client.get(
      s"$baseUrl/params?existing=true",
      params = Map("extra" -> "yes")
    )
    response.body should include("existing=true")
    response.body should include("extra=yes")
  }

  it should "return response headers with lowercase keys" in {
    val response = client.get(s"$baseUrl/echo")
    response.headers.get("x-custom-header") shouldBe Some(Seq("test-value"))
  }

  // ============================================================
  // POST tests
  // ============================================================

  "JdkHttpClient.post" should "send a POST request with string body" in {
    val response = client.post(
      s"$baseUrl/echo",
      headers = Map("Content-Type" -> "application/json"),
      body = """{"key":"value"}"""
    )
    response.statusCode shouldBe 200
    response.body should include("method=POST")
    response.body should include("""body={"key":"value"}""")
  }

  // ============================================================
  // POST bytes tests
  // ============================================================

  "JdkHttpClient.postBytes" should "send a POST request with byte array body" in {
    val data     = "binary-content".getBytes(StandardCharsets.UTF_8)
    val response = client.postBytes(s"$baseUrl/echo", data = data)
    response.statusCode shouldBe 200
    response.body should include("method=POST")
    response.body should include("body=binary-content")
  }

  // ============================================================
  // PUT tests
  // ============================================================

  "JdkHttpClient.put" should "send a PUT request with string body" in {
    val response = client.put(
      s"$baseUrl/echo",
      headers = Map("Content-Type" -> "application/json"),
      body = """{"updated":true}"""
    )
    response.statusCode shouldBe 200
    response.body should include("method=PUT")
    response.body should include("""body={"updated":true}""")
  }

  // ============================================================
  // DELETE tests
  // ============================================================

  "JdkHttpClient.delete" should "send a DELETE request" in {
    val response = client.delete(s"$baseUrl/echo")
    response.statusCode shouldBe 200
    response.body should include("method=DELETE")
  }

  // ============================================================
  // postRaw tests
  // ============================================================

  "JdkHttpClient.postRaw" should "return an HttpRawResponse with the correct status code" in {
    val response = client.postRaw(s"$baseUrl/binary", body = "{}")
    response.statusCode shouldBe 200
  }

  it should "return body as exact raw bytes without charset corruption" in {
    val response = client.postRaw(s"$baseUrl/binary", body = "{}")
    // These bytes contain invalid UTF-8 sequences (0xFF, 0xD8, 0x80, 0xC0, 0xFE).
    // BodyHandlers.ofString() would replace them with U+FFFD then ISO_8859_1 would
    // map U+FFFD to '?' (0x3F), corrupting the payload.
    // postRaw must return the exact wire bytes.
    val expected = Array[Byte](
      0xff.toByte,
      0xd8.toByte,
      0xff.toByte,
      0xe0.toByte,
      0x00.toByte,
      0x10.toByte,
      0x80.toByte,
      0xc0.toByte,
      0xfe.toByte
    )
    response.body shouldEqual expected
  }

  it should "send the POST body to the server" in {
    val response = client.postRaw(
      s"$baseUrl/echo",
      headers = Map("Content-Type" -> "application/json"),
      body = """{"ping":"pong"}"""
    )
    response.statusCode shouldBe 200
    // /echo returns a UTF-8 text response — decode it to verify the body was sent
    new String(response.body, StandardCharsets.UTF_8) should include("""body={"ping":"pong"}""")
  }

  it should "return the correct status code for non-2xx responses" in {
    val response = client.postRaw(s"$baseUrl/status/429", body = "")
    response.statusCode shouldBe 429
  }

  // ============================================================
  // Non-2xx status code tests
  // ============================================================

  "JdkHttpClient" should "not throw on 404 status" in {
    val response = client.get(s"$baseUrl/status/404")
    response.statusCode shouldBe 404
    response.body shouldBe "status=404"
  }

  it should "not throw on 500 status" in {
    val response = client.get(s"$baseUrl/status/500")
    response.statusCode shouldBe 500
    response.body shouldBe "status=500"
  }

  // ============================================================
  // Multipart tests
  // ============================================================

  "JdkHttpClient.postMultipart" should "send multipart form data with text fields" in {
    val parts = Seq(
      MultipartPart.TextField("name", "test"),
      MultipartPart.TextField("value", "hello")
    )
    val response = client.postMultipart(s"$baseUrl/multipart", parts = parts)
    response.statusCode shouldBe 200
    response.body should include("content-type=multipart/form-data; boundary=")
    response.body should include("body-contains-boundary=true")
  }

  it should "send multipart form data with file parts" in {
    val tempFile = Files.createTempFile("llm4s-test-", ".txt")
    Files.write(tempFile, "file-content".getBytes(StandardCharsets.UTF_8))

    try {
      val parts = Seq(
        MultipartPart.TextField("prompt", "describe this"),
        MultipartPart.FilePart("file", tempFile, "test.txt")
      )
      val response = client.postMultipart(s"$baseUrl/multipart", parts = parts)
      response.statusCode shouldBe 200
      response.body should include("content-type=multipart/form-data; boundary=")
    } finally
      Files.deleteIfExists(tempFile)
  }
}
