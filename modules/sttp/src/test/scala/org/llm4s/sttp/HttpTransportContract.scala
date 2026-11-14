package org.llm4s.sttp

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ NetworkError, TimeoutError, ValidationError }
import org.llm4s.http.{ Llm4sHttpClient, MultipartPart }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, ServerSocket }
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.concurrent.duration.*

/**
 * What every [[org.llm4s.http.Llm4sHttpClient]] a provider client is handed must do, run against a local server:
 * statuses (a non-2xx is a `Right`), request and response headers, query parameters and bodies, raw bytes, streamed
 * chunks delivered as they arrive, timeouts, refused connections, invalid URLs and multipart. Two specs mix it in: one
 * against `Llm4sHttpClient.create()`, one against [[SttpLlm4sHttpClient]], so the adapter is held to the default
 * client's behaviour (#2341).
 */
trait HttpTransportContract extends AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  /** How the suite names the client under test. */
  protected def transportName: String

  /** The client under test; closed after the suite. */
  protected def newClient(): Llm4sHttpClient

  private lazy val client                = newClient()
  private val executor                   = Executors.newCachedThreadPool()
  private val server                     = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private lazy val baseUrl               = s"http://127.0.0.1:${server.getAddress.getPort}"
  @volatile private var firstChunkRead   = new CountDownLatch(1)
  @volatile private var lastRequestQuery = ""

  extension [A](result: Result[A])
    private def ok: A =
      result.fold(err => fail(s"expected a response, got ${err.getClass.getSimpleName}: ${err.message}"), identity)

  private def respond(ex: HttpExchange, status: Int, body: Array[Byte], headers: (String, String)*): Unit = {
    headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    ex.sendResponseHeaders(status, if body.isEmpty then -1L else body.length.toLong)
    ex.getResponseBody.write(body)
    ex.close()
  }

  override def beforeAll(): Unit = {
    // Echoes the method, the body, the X-Probe request header and the Content-Type, and answers two Set-Cookie headers.
    server.createContext(
      "/echo",
      ex => {
        lastRequestQuery = Option(ex.getRequestURI.getRawQuery).getOrElse("")
        val body  = new String(ex.getRequestBody.readAllBytes(), UTF_8)
        val probe = Option(ex.getRequestHeaders.getFirst("X-Probe")).getOrElse("")
        val ct    = Option(ex.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
        respond(
          ex,
          200,
          s"method=${ex.getRequestMethod}|probe=$probe|content-type=$ct|body=$body".getBytes(UTF_8),
          "X-Mixed-Case" -> "v",
          "Set-Cookie"   -> "a=1",
          "Set-Cookie"   -> "b=2"
        )
      }
    )
    server.createContext(
      "/status",
      ex => {
        val code = ex.getRequestURI.getPath.stripPrefix("/status/").toInt
        ex.getRequestBody.readAllBytes()
        respond(ex, code, s"""{"error":"status $code"}""".getBytes(UTF_8), "X-Request-Id" -> s"req-$code")
      }
    )
    server.createContext(
      "/binary",
      ex => {
        ex.getRequestBody.readAllBytes()
        respond(ex, 200, Array[Byte](0, -1, -2, 127, -128, 10))
      }
    )
    // Two chunks; the second is written only once the client has read the first, so a client that buffered the whole
    // body before returning would never see it.
    server.createContext(
      "/stream",
      ex => {
        ex.getRequestBody.readAllBytes()
        ex.getResponseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        val out = ex.getResponseBody
        out.write("data: one\n\n".getBytes(UTF_8))
        out.flush()
        firstChunkRead.await(10, TimeUnit.SECONDS)
        out.write("data: two\n\n".getBytes(UTF_8))
        out.close()
      }
    )
    server.createContext(
      "/slow",
      ex => {
        Thread.sleep(3000)
        respond(ex, 200, "late".getBytes(UTF_8))
      }
    )
    server.setExecutor(executor)
    server.start()
  }

  override def afterAll(): Unit = {
    client.close()
    server.stop(0)
    executor.shutdownNow()
  }

  private def freePortUrl(): String = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    s"http://127.0.0.1:$port/nobody"
  }

  // ---- statuses

  transportName should "return a 2xx response with its status and body" in {
    val response = client.post(s"$baseUrl/echo", body = "hi").ok
    response.statusCode shouldBe 200
    response.body should include("body=hi")
  }

  it should "return a non-2xx status as a Right, with its body and headers, for every method" in {
    Seq(400, 401, 404, 429, 500, 503).foreach { code =>
      val url = s"$baseUrl/status/$code"
      Seq(
        client.get(url).ok,
        client.post(url, body = "{}").ok,
        client.postBytes(url, data = "x".getBytes(UTF_8)).ok,
        client.put(url, body = "{}").ok,
        client.delete(url).ok
      ).foreach { response =>
        response.statusCode shouldBe code
        response.body shouldBe s"""{"error":"status $code"}"""
        response.header("x-request-id") shouldBe Some(s"req-$code")
      }
      client.postRaw(url).ok.statusCode shouldBe code
      val streamed = client.postStream(url).ok
      streamed.statusCode shouldBe code
      new String(streamed.body.readAllBytes(), UTF_8) shouldBe s"""{"error":"status $code"}"""
      streamed.body.close()
    }
  }

  // ---- headers, methods, params, bodies

  it should "send the request headers and key the response headers in lower case, keeping every value" in {
    val response = client.post(s"$baseUrl/echo", headers = Map("X-Probe" -> "p-1"), body = "b").ok
    response.body should include("probe=p-1")
    response.headers.get("x-mixed-case") shouldBe Some(Seq("v"))
    response.headers.get("set-cookie").map(_.toSet) shouldBe Some(Set("a=1", "b=2"))
    response.header("X-MIXED-CASE") shouldBe Some("v")
  }

  it should "send each method with its body" in {
    client.get(s"$baseUrl/echo").ok.body should startWith("method=GET|")
    client.post(s"$baseUrl/echo", body = "p").ok.body should (startWith("method=POST|").and(endWith("body=p")))
    client.put(s"$baseUrl/echo", body = "u").ok.body should (startWith("method=PUT|").and(endWith("body=u")))
    client.delete(s"$baseUrl/echo").ok.body should startWith("method=DELETE|")
    client.postBytes(s"$baseUrl/echo", data = "bytes".getBytes(UTF_8)).ok.body should endWith("body=bytes")
  }

  it should "encode the query parameters of a GET" in {
    client.get(s"$baseUrl/echo", params = Map("q" -> "a b&c")).ok.statusCode shouldBe 200
    lastRequestQuery should (include("q=a+b%26c").or(include("q=a%20b%26c")))
  }

  it should "keep a UTF-8 body intact both ways" in {
    client
      .post(s"$baseUrl/echo", headers = Map("Content-Type" -> "text/plain; charset=utf-8"), body = "héllo ✓")
      .ok
      .body should endWith("body=héllo ✓")
  }

  it should "return a raw body byte for byte" in {
    client.postRaw(s"$baseUrl/binary", body = "x").ok.body.toSeq shouldBe Seq[Byte](0, -1, -2, 127, -128, 10)
  }

  // ---- streaming

  it should "hand back a streamed body chunk by chunk, before the server has finished" in {
    firstChunkRead = new CountDownLatch(1)
    val response = client.postStream(s"$baseUrl/stream", body = "{}").ok
    response.statusCode shouldBe 200
    response.header("content-type") shouldBe Some("text/event-stream")
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body, UTF_8))
    reader.readLine() shouldBe "data: one"
    firstChunkRead.countDown()
    reader.readLine() shouldBe ""
    reader.readLine() shouldBe "data: two"
    reader.close()
  }

  // ---- timeouts and transport failures

  it should "return Left(TimeoutError) when the server is slower than the timeout" in {
    client.post(s"$baseUrl/slow", body = "{}", timeout = 200.millis).left.toOption.get shouldBe a[TimeoutError]
    client.get(s"$baseUrl/slow", timeout = 200.millis).left.toOption.get shouldBe a[TimeoutError]
  }

  it should "return Left(TimeoutError) from postStream when no response arrives in time" in {
    client.postStream(s"$baseUrl/slow", body = "{}", timeout = 200.millis).left.toOption.get shouldBe a[TimeoutError]
  }

  it should "return Left(NetworkError) for every method when the connection is refused" in {
    val url = freePortUrl()
    Seq(
      client.get(url),
      client.post(url),
      client.postBytes(url),
      client.postMultipart(url, parts = Seq(MultipartPart.TextField("a", "b"))),
      client.put(url),
      client.delete(url),
      client.postRaw(url),
      client.postStream(url)
    ).foreach(_.left.toOption.get shouldBe a[NetworkError])
  }

  it should "return Left(ValidationError) for an invalid URL" in {
    client.post("not a url").left.toOption.get shouldBe a[ValidationError]
  }

  // ---- multipart

  it should "send text and file parts as multipart/form-data" in {
    val file = Files.createTempFile("llm4s-contract", ".bin")
    Files.write(file, "file-content".getBytes(UTF_8))
    val response = client
      .postMultipart(
        s"$baseUrl/echo",
        parts = Seq(MultipartPart.TextField("model", "whisper-1"), MultipartPart.FilePart("file", file, "audio.wav"))
      )
      .ok
    Files.deleteIfExists(file)
    val body = response.body
    body should include("content-type=multipart/form-data; boundary=")
    val boundary = body.split("boundary=", 2)(1).takeWhile(_ != '|')
    body should include(s"--$boundary")
    body should include("""name="model"""")
    body should include("whisper-1")
    body should include("""name="file"; filename="audio.wav"""")
    body.linesIterator.map(_.trim).toSeq should contain("file-content")
    body should include(s"--$boundary--")
  }

  it should "return Left(ValidationError) when a multipart file cannot be read" in {
    val missing = Files.createTempDirectory("llm4s-contract").resolve("missing.bin")
    client
      .postMultipart(s"$baseUrl/echo", parts = Seq(MultipartPart.FilePart("file", missing, "missing.bin")))
      .left
      .toOption
      .get shouldBe a[ValidationError]
  }
}
