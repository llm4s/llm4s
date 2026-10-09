package org.llm4s.http

import org.llm4s.error.{ NetworkError, ServiceError, TimeoutError, UnknownError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.http.HttpResponse.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.*

class Llm4sHttpClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: HttpServer = _
  private var baseUrl: String    = _
  private val client             = Llm4sHttpClient.create()
  private val executor           = java.util.concurrent.Executors.newCachedThreadPool()

  extension [A](result: Result[A])
    private def ok: A = result.fold(err => fail(s"Expected a response, got error: ${err.message}"), identity)

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

    // Slow handler — sleeps past the client's timeout before answering
    server.createContext(
      "/slow",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          Thread.sleep(2000)
          val responseBytes = "late".getBytes(StandardCharsets.UTF_8)
          scala.util.Try {
            exchange.sendResponseHeaders(200, responseBytes.length.toLong)
            exchange.getResponseBody.write(responseBytes)
            exchange.getResponseBody.close()
          }
        }
      }
    )

    // Headers handler — answers 429 with Retry-After and a multi-valued header
    server.createContext(
      "/headers",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          exchange.getRequestBody.readAllBytes()
          val responseBytes = "line-1\nline-2\n".getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Retry-After", "7")
          exchange.getResponseHeaders.add("X-Multi", "a")
          exchange.getResponseHeaders.add("X-Multi", "b")
          exchange.sendResponseHeaders(429, responseBytes.length.toLong)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    server.setExecutor(executor)
    server.start()
    baseUrl = s"http://localhost:${server.getAddress.getPort}"
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
    executor.shutdownNow()
  }

  // ============================================================
  // HttpResponse model tests
  // ============================================================

  "HttpResponse" should "have sensible defaults" in {
    val response = HttpResponse(200, "ok")
    response.statusCode shouldBe 200
    response.body shouldBe "ok"
    response.headers shouldBe Map.empty
  }

  it should "convert a successful response to JsonHttpResponse" in {
    val response = HttpResponse(200, """{"ok":true}""", Map("content-type" -> Seq("application/json")))

    val result = response.toJson()

    result match
      case Right(jsonResponse) =>
        jsonResponse.statusCode shouldBe 200
        jsonResponse.body("ok").bool shouldBe true
        jsonResponse.headers shouldBe response.headers
      case Left(err) =>
        fail(s"Expected JSON response, got error: ${err.message}")
  }

  it should "fail clearly when JSON response parsing fails" in {
    val response = HttpResponse(200, """not-json""")

    val result = response.toJson()

    result match
      case Left(err: ValidationError) =>
        err.field shouldBe "responseBody"
      case Left(err) =>
        fail(s"Expected ValidationError, got: ${err.message}")
      case Right(jsonResponse) =>
        fail(s"Expected JSON parsing to fail, got: $jsonResponse")
  }

  it should "reject non-success responses via ensureSuccess" in {
    val response = HttpResponse(404, "missing")

    val result = response.ensureSuccess("test-provider")

    result match
      case Left(err: ServiceError) =>
        err.httpStatus shouldBe 404
        err.provider shouldBe "test-provider"
      case Left(err) =>
        fail(s"Expected ServiceError, got: ${err.message}")
      case Right(ok) =>
        fail(s"Expected failed response, got: $ok")
  }

  it should "redact credentials echoed in the body it puts into the ServiceError (#1674)" in {
    val message =
      HttpResponse(401, org.llm4s.testutil.EchoedCredentials.Text)
        .ensureSuccess("test-provider")
        .left
        .toOption
        .get
        .message
    message should include("[REDACTED]")
    org.llm4s.testutil.EchoedCredentials.leaked(message) shouldBe empty
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
    instance.getClass.getSimpleName shouldBe "JdkHttpClient"
  }

  "Llm4sHttpClient.close" should "shut the JDK client down, so a later request is a Left, not an exception" in {
    val closing = Llm4sHttpClient.create()
    closing.get(s"$baseUrl/echo").ok.statusCode shouldBe 200
    closing.close()
    closing.get(s"$baseUrl/echo").isLeft shouldBe true
  }

  "Llm4sHttpClient.create(connectTimeout)" should "return a working client" in {
    val withConnectTimeout = Llm4sHttpClient.create(connectTimeout = 5.seconds)
    withConnectTimeout.get(s"$baseUrl/echo").ok.statusCode shouldBe 200
  }

  // ============================================================
  // GET tests
  // ============================================================

  "JdkHttpClient.get" should "send a GET request and return response" in {
    val response = client.get(s"$baseUrl/echo").ok
    response.statusCode shouldBe 200
    response.body should startWith("method=GET")
  }

  it should "return a Right for successful requests" in {
    val result = client.get(s"$baseUrl/echo")

    result match
      case Right(response) =>
        response.statusCode shouldBe 200
        response.body should startWith("method=GET")
      case Left(err) =>
        fail(s"Expected successful Result, got error: ${err.message}")
  }

  it should "pass custom headers" in {
    val response = client
      .get(
        s"$baseUrl/echo",
        headers = Map("User-Agent" -> "llm4s-test/1.0")
      )
      .ok
    response.body should include("user-agent=llm4s-test/1.0")
  }

  it should "encode and append query params" in {
    val response = client
      .get(
        s"$baseUrl/params",
        params = Map("q" -> "hello world", "count" -> "5")
      )
      .ok
    response.body should include("q=hello+world")
    response.body should include("count=5")
  }

  it should "append query params to URL that already has params" in {
    val response = client
      .get(
        s"$baseUrl/params?existing=true",
        params = Map("extra" -> "yes")
      )
      .ok
    response.body should include("existing=true")
    response.body should include("extra=yes")
  }

  it should "return response headers with lowercase keys" in {
    val response = client.get(s"$baseUrl/echo").ok
    response.headers.get("x-custom-header") shouldBe Some(Seq("test-value"))
  }

  it should "return Left from a failing test double rather than throwing" in {
    val failing = new FailingHttpClient(new java.net.ConnectException("refused"))

    failing.get("http://localhost:1/unreachable") match
      case Left(err: NetworkError) => err.message should include("connection failed")
      case other                   => fail(s"Expected NetworkError, got: $other")
  }

  // ============================================================
  // POST tests
  // ============================================================

  "JdkHttpClient.post" should "send a POST request with string body" in {
    val response = client
      .post(
        s"$baseUrl/echo",
        headers = Map("Content-Type" -> "application/json"),
        body = """{"key":"value"}"""
      )
      .ok
    response.statusCode shouldBe 200
    response.body should include("method=POST")
    response.body should include("""body={"key":"value"}""")
  }

  // ============================================================
  // POST bytes tests
  // ============================================================

  "JdkHttpClient.postBytes" should "send a POST request with byte array body" in {
    val data     = "binary-content".getBytes(StandardCharsets.UTF_8)
    val response = client.postBytes(s"$baseUrl/echo", data = data).ok
    response.statusCode shouldBe 200
    response.body should include("method=POST")
    response.body should include("body=binary-content")
  }

  // ============================================================
  // PUT tests
  // ============================================================

  "JdkHttpClient.put" should "send a PUT request with string body" in {
    val response = client
      .put(
        s"$baseUrl/echo",
        headers = Map("Content-Type" -> "application/json"),
        body = """{"updated":true}"""
      )
      .ok
    response.statusCode shouldBe 200
    response.body should include("method=PUT")
    response.body should include("""body={"updated":true}""")
  }

  // ============================================================
  // DELETE tests
  // ============================================================

  "JdkHttpClient.delete" should "send a DELETE request" in {
    val response = client.delete(s"$baseUrl/echo").ok
    response.statusCode shouldBe 200
    response.body should include("method=DELETE")
  }

  // ============================================================
  // postRaw tests
  // ============================================================

  "JdkHttpClient.postRaw" should "return an HttpRawResponse with the correct status code" in {
    val response = client.postRaw(s"$baseUrl/binary", body = "{}").ok
    response.statusCode shouldBe 200
  }

  it should "return body as exact raw bytes without charset corruption" in {
    val response = client.postRaw(s"$baseUrl/binary", body = "{}").ok
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
    val response = client
      .postRaw(
        s"$baseUrl/echo",
        headers = Map("Content-Type" -> "application/json"),
        body = """{"ping":"pong"}"""
      )
      .ok
    response.statusCode shouldBe 200
    // /echo returns a UTF-8 text response — decode it to verify the body was sent
    new String(response.body, StandardCharsets.UTF_8) should include("""body={"ping":"pong"}""")
  }

  it should "return the correct status code for non-2xx responses" in {
    val response = client.postRaw(s"$baseUrl/status/429", body = "").ok
    response.statusCode shouldBe 429
  }

  // ============================================================
  // Non-2xx status code tests
  // ============================================================

  "JdkHttpClient" should "not throw on 404 status" in {
    val response = client.get(s"$baseUrl/status/404").ok
    response.statusCode shouldBe 404
    response.body shouldBe "status=404"
  }

  it should "not throw on 500 status" in {
    val response = client.get(s"$baseUrl/status/500").ok
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
    val response = client.postMultipart(s"$baseUrl/multipart", parts = parts).ok
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
      val response = client.postMultipart(s"$baseUrl/multipart", parts = parts).ok
      response.statusCode shouldBe 200
      response.body should include("content-type=multipart/form-data; boundary=")
    } finally Files.deleteIfExists(tempFile)
  }

  // ============================================================
  // Response headers on raw and streaming responses
  // ============================================================

  "JdkHttpClient.postRaw" should "carry response headers, looked up case-insensitively" in {
    val response = client.postRaw(s"$baseUrl/headers", body = "{}").ok
    response.statusCode shouldBe 429
    response.headers.get("retry-after") shouldBe Some(Seq("7"))
    response.header("Retry-After") shouldBe Some("7")
    response.header("RETRY-AFTER") shouldBe Some("7")
    response.headers.get("x-multi").map(_.toSet) shouldBe Some(Set("a", "b"))
    response.header("X-Absent") shouldBe None
  }

  "JdkHttpClient.postStream" should "return the status, headers and a readable body" in {
    val response = client.postStream(s"$baseUrl/headers", body = "{}").ok
    val body =
      scala.util.Using.resource(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8))
    response.statusCode shouldBe 429
    response.header("retry-after") shouldBe Some("7")
    body shouldBe "line-1\nline-2\n"
  }

  "HttpHeaders.first" should "match names case-insensitively whatever the stored case" in {
    val headers = Map("Retry-After" -> Seq("1", "2"), "x-empty" -> Seq.empty)
    HttpHeaders.first(headers, "retry-after") shouldBe Some("1")
    HttpHeaders.first(headers, "Retry-After") shouldBe Some("1")
    HttpHeaders.first(headers, "x-empty") shouldBe None
    HttpHeaders.first(headers, "missing") shouldBe None
    HttpResponse(200, "", headers).header("RETRY-after") shouldBe Some("1")
    JsonHttpResponse(200, ujson.Null, headers).header("retry-after") shouldBe Some("1")
  }

  // ============================================================
  // Transport failures are a Left, never a throw
  // ============================================================

  private def unusedPort(): Int =
    scala.util.Using.resource(new java.net.ServerSocket(0))(_.getLocalPort)

  "JdkHttpClient" should "return Left(NetworkError) when the connection is refused" in {
    val url = s"http://localhost:${unusedPort()}/nothing?key=secret-value"
    client.get(url) match
      case Left(err: NetworkError) =>
        err.endpoint should startWith("http://localhost:")
        (err.message should not).include("secret-value")
        (err.endpoint should not).include("secret-value")
      case other => fail(s"Expected NetworkError, got: $other")
  }

  it should "return Left for every method when the connection is refused" in {
    val url = s"http://localhost:${unusedPort()}/nothing"
    client.post(url).isLeft shouldBe true
    client.postBytes(url).isLeft shouldBe true
    client.put(url).isLeft shouldBe true
    client.delete(url).isLeft shouldBe true
    client.postRaw(url).isLeft shouldBe true
    client.postStream(url).isLeft shouldBe true
    client.postMultipart(url, parts = Seq(MultipartPart.TextField("a", "b"))).isLeft shouldBe true
  }

  it should "return Left(TimeoutError) when the server is slower than the timeout" in {
    client.get(s"$baseUrl/slow", timeout = 200.millis) match
      case Left(err: TimeoutError) =>
        err.timeoutDuration shouldBe 200.millis
        err.operation shouldBe "http.GET"
      case other => fail(s"Expected TimeoutError, got: $other")
  }

  it should "return Left(TimeoutError) from postStream when no response arrives in time" in {
    client.postStream(s"$baseUrl/slow", body = "{}", timeout = 200.millis) match
      case Left(_: TimeoutError) => succeed
      case other                 => fail(s"Expected TimeoutError, got: $other")
  }

  it should "return Left(ValidationError) for an invalid URL" in {
    client.get("not a url") match
      case Left(err: ValidationError) => err.field shouldBe "request"
      case other                      => fail(s"Expected ValidationError, got: $other")
  }

  it should "return Left(ValidationError) for an unsupported scheme or a non-positive timeout" in {
    client.post("ftp://example.com/x").left.toOption.get shouldBe a[ValidationError]
    client.get(s"$baseUrl/echo", timeout = Duration.Zero).left.toOption.get shouldBe a[ValidationError]
  }

  it should "return Left(ValidationError) when a multipart file cannot be read" in {
    val missing = java.nio.file.Paths.get("/definitely/not/here.bin")
    client.postMultipart(s"$baseUrl/multipart", parts = Seq(MultipartPart.FilePart("f", missing, "here.bin"))) match
      case Left(err: ValidationError) => err.field shouldBe "parts"
      case other                      => fail(s"Expected ValidationError, got: $other")
  }

  it should "return Left(CancelledError) and keep the interrupt flag when interrupted" in {
    Thread.currentThread().interrupt()
    val result      = client.get(s"$baseUrl/slow", timeout = 5.seconds)
    val interrupted = Thread.interrupted() // reads and clears the flag
    result match
      case Left(err: org.llm4s.error.CancelledError) => err.operation shouldBe "http.GET"
      case other                                     => fail(s"Expected CancelledError, got: $other")
    interrupted shouldBe true
  }

  it should "return Left(CancelledError) when interrupted while the request is in flight on a virtual thread" in {
    @volatile var outcome: Option[(Result[HttpResponse], Boolean)] = None
    val worker = Thread.ofVirtual().start { () =>
      val r = client.get(s"$baseUrl/slow", timeout = 30.seconds)
      outcome = Some(r -> Thread.currentThread().isInterrupted)
    }
    Thread.sleep(200)
    worker.interrupt()
    worker.join(5000)
    worker.isAlive shouldBe false
    val (result, flag) = outcome.get
    result.left.toOption.get shouldBe a[org.llm4s.error.CancelledError]
    flag shouldBe true
  }

  "HttpFailures.streamReadError" should "classify a body read failure as the transport would" in {
    val url = "https://api.example.com/v1/stream?key=secret"
    HttpFailures.streamReadError(new java.io.IOException("Connection reset"), url, 3.seconds) match {
      case e: org.llm4s.error.NetworkError =>
        e.message should include("Connection reset")
        (e.message should not).include("secret")
        org.llm4s.error.LLMError.isRecoverable(e) shouldBe true
      case other => fail(s"expected NetworkError, got $other")
    }
    HttpFailures.streamReadError(new java.net.SocketTimeoutException("read timed out"), url, 3.seconds) shouldBe a[
      org.llm4s.error.TimeoutError
    ]
    HttpFailures.streamReadError(new java.io.IOException(new InterruptedException), url, 3.seconds) shouldBe a[
      org.llm4s.error.CancelledError
    ]
    Thread.interrupted() shouldBe false // mapping classifies; it never sets the flag
    // Not an I/O failure (e.g. a malformed chunk): the default mapping, unchanged
    HttpFailures.streamReadError(new IllegalStateException("bad chunk"), url, 3.seconds) shouldBe a[
      org.llm4s.error.UnknownError
    ]
  }

  it should "keep the flag of a reading thread that was interrupted" in {
    // On a virtual thread an interrupt closes the socket: the read fails with a plain I/O error
    // and the JDK leaves the flag set, which is what makes it a cancellation
    @volatile var outcome: Option[(org.llm4s.error.LLMError, Boolean)] = None
    val reader = Thread.ofVirtual().start { () =>
      Thread.currentThread().interrupt()
      val error =
        HttpFailures.streamReadError(new java.net.SocketException("Closed by interrupt"), "http://x", 3.seconds)
      outcome = Some(error -> Thread.currentThread().isInterrupted)
    }
    reader.join(5000)
    val (error, flag) = outcome.get
    error shouldBe a[org.llm4s.error.CancelledError]
    flag shouldBe true
  }

  "HttpFailures.toLLMError" should "map each transport failure to its error type" in {
    val url               = "https://user:pw@api.example.com:8443/v1/x?key=secret#frag"
    def map(t: Throwable) = HttpFailures.toLLMError(t, "POST", url, 3.seconds)

    map(new java.net.http.HttpConnectTimeoutException("ct")) shouldBe a[TimeoutError]
    map(new java.net.http.HttpTimeoutException("t")) shouldBe a[TimeoutError]
    map(new java.net.SocketTimeoutException("st")) shouldBe a[TimeoutError]
    map(new InterruptedException("i")) shouldBe a[org.llm4s.error.CancelledError]
    Thread.interrupted() shouldBe false // mapping classifies; it never sets the flag
    map(new IllegalArgumentException("bad")) shouldBe a[ValidationError]
    map(new java.net.ConnectException("refused")) shouldBe a[NetworkError]
    map(new java.net.UnknownHostException("nohost")) shouldBe a[NetworkError]
    map(new java.io.IOException("reset")) shouldBe a[NetworkError]
    map(new RuntimeException()) shouldBe a[UnknownError]

    map(new java.io.IOException("reset")) match
      case err: NetworkError => err.endpoint shouldBe "https://api.example.com:8443/v1/x"
      case other             => fail(s"Expected NetworkError, got: $other")
  }

  "HttpFailures.safeEndpoint" should "fall back to stripping the query from an unparseable URL" in {
    HttpFailures.safeEndpoint("not a url?key=secret") shouldBe "not a url"
    HttpFailures.safeEndpoint("http://h/p?q=1") shouldBe "http://h/p"
  }
}
