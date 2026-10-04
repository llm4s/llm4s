// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.imageprocessing.provider.geminiclient

import ch.qos.logback.classic.{ Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.error.{ APIError, CancelledError, ConfigurationError, LLMError, NetworkError, TimeoutError }
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Base64
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

object GeminiVisionClientHttpSpec {
  val mimeTable = Seq(
    ("png", ".png", "image/png"),
    ("jpg", ".jpg", "image/jpeg"),
    ("jpg", ".jpeg", "image/jpeg"),
    ("gif", ".gif", "image/gif"),
    ("bmp", ".bmp", "image/bmp"),
    ("png", ".PNG", "image/png"),
    ("jpg", ".JPG", "image/jpeg"),
    ("png", ".dat", "image/jpeg"), // unknown extension falls back to JPEG
    ("png", "", "image/jpeg")      // no extension falls back to JPEG
  )

  val googleError = (code: Int, status: String) =>
    s"""{"error":{"code":$code,"message":"msg for $code","status":"$status"}}"""

  val statuses = Seq(
    (400, "INVALID_ARGUMENT"),
    (401, "UNAUTHENTICATED"),
    (403, "PERMISSION_DENIED"),
    (404, "NOT_FOUND"),
    (429, "RESOURCE_EXHAUSTED"),
    (500, "INTERNAL"),
    (503, "UNAVAILABLE"),
    (504, "DEADLINE_EXCEEDED")
  )

}

/**
 * Drives [[GeminiVisionClient]] against a local stub of the Gemini `generateContent` endpoint,
 * so the request that goes on the wire, and every reply shape the client must survive, are
 * asserted without a network or an API key.
 */
class GeminiVisionClientHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import GeminiVisionClientHttpSpec._

  private val Key = "AIzaSyDUMMY-secret-key-0123456789"

  /** One request as the stub saw it. */
  final private case class Seen(
    method: String,
    rawPath: String,
    rawQuery: Option[String],
    headers: Map[String, String],
    body: String
  )

  /** What the stub answers with. `delayMs` is slept before replying. */
  final private case class Reply(
    status: Int,
    body: String,
    headers: Map[String, String] = Map.empty,
    delayMs: Long = 0
  )

  private var server: HttpServer = _
  private val seen               = new java.util.concurrent.ConcurrentLinkedQueue[Seen]()
  @volatile private var reply    = Reply(200, ok("fine"))
  private val arrived            = new AtomicInteger(0)

  private def ok(text: String): String =
    ujson.write(
      ujson.Obj(
        "candidates" -> ujson.Arr(
          ujson.Obj(
            "content"      -> ujson.Obj("role" -> "model", "parts" -> ujson.Arr(ujson.Obj("text" -> text))),
            "finishReason" -> "STOP"
          )
        )
      )
    )

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/",
      new HttpHandler {
        override def handle(ex: HttpExchange): Unit = {
          val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val hdrs = ex.getRequestHeaders.asScala.map { case (k, v) => k.toLowerCase -> v.asScala.mkString(",") }.toMap
          seen.add(
            Seen(ex.getRequestMethod, ex.getRequestURI.getRawPath, Option(ex.getRequestURI.getRawQuery), hdrs, body)
          )
          arrived.incrementAndGet()
          val r = reply
          try {
            if (r.delayMs > 0) Thread.sleep(r.delayMs)
            val bytes = r.body.getBytes(StandardCharsets.UTF_8)
            r.headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
            ex.sendResponseHeaders(r.status, bytes.length.toLong)
            ex.getResponseBody.write(bytes)
          } catch {
            case _: java.io.IOException | _: InterruptedException => ()
          } finally ex.close()
        }
      }
    )
    server.start()
  }

  override def afterAll(): Unit = server.stop(0)

  private val tmp = ListBuffer.empty[Path]

  override def beforeEach(): Unit = {
    seen.clear()
    arrived.set(0)
    reply = Reply(200, ok("fine"))
  }

  override def afterEach(): Unit = {
    tmp.foreach(p => Files.deleteIfExists(p))
    tmp.clear()
  }

  private def baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/v1beta"

  private def cfg(key: String = Key, model: String = "gemini-test", request: Int = 10) =
    GeminiVisionConfig(
      apiKey = key,
      model = model,
      baseUrl = baseUrl,
      connectTimeoutSeconds = 5,
      requestTimeoutSeconds = request
    )

  private def image(suffix: String, format: String): Path = {
    val img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val g   = img.createGraphics()
    g.setColor(java.awt.Color.RED)
    g.fillRect(0, 0, 8, 8)
    g.dispose()
    val p = Files.createTempFile("gemini-http", suffix)
    tmp += p
    ImageIO.write(img, format, p.toFile) shouldBe true
    p
  }

  private def lastBody: ujson.Value = ujson.read(seen.asScala.toList.last.body)

  private def errString(e: LLMError): String = e.toString + " | " + e.message + " | " + e.formatted

  // ---- golden request ----------------------------------------------------------------

  for ((format, suffix, mime) <- mimeTable)
    "GeminiVisionClient request" should s"send inline base64 data with mimeType $mime for '$suffix' ($format bytes)" in {
      val p = image(suffix, format)
      val r = new GeminiVisionClient(cfg()).analyzeImage(p.toString, Some("What is this?"))
      r.isRight shouldBe true

      seen.size shouldBe 1
      val req = seen.asScala.head
      req.method shouldBe "POST"
      req.rawPath shouldBe "/v1beta/models/gemini-test:generateContent"
      req.headers("content-type") should startWith("application/json")

      // Exact golden shape: one content, text part first, then inlineData with camelCase keys.
      val expected = ujson.Obj(
        "contents" -> ujson.Arr(
          ujson.Obj(
            "parts" -> ujson.Arr(
              ujson.Obj("text" -> "What is this?"),
              ujson.Obj(
                "inlineData" -> ujson.Obj(
                  "mimeType" -> mime,
                  "data"     -> Base64.getEncoder.encodeToString(Files.readAllBytes(p))
                )
              )
            )
          )
        )
      )
      ujson.read(req.body) shouldBe expected
    }

  it should "use the documented default prompt when none is given" in {
    val p = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).isRight shouldBe true
    lastBody("contents")(0)("parts")(0)("text").str should startWith("Analyze this image in detail")
  }

  it should "round-trip a prompt with quotes, newlines and non-ASCII characters" in {
    val p      = image(".png", "png")
    val prompt = "Say \"hi\"\nline2 \\ é中😀"
    new GeminiVisionClient(cfg()).analyzeImage(p.toString, Some(prompt)).isRight shouldBe true
    lastBody("contents")(0)("parts")(0)("text").str shouldBe prompt
  }

  it should "send the model name from the config in the path" in {
    val p = image(".png", "png")
    new GeminiVisionClient(cfg(model = "gemini-9.9-pro")).analyzeImage(p.toString).isRight shouldBe true
    seen.asScala.head.rawPath shouldBe "/v1beta/models/gemini-9.9-pro:generateContent"
  }

  // ---- local-file edge cases -----------------------------------------------------------

  "GeminiVisionClient inputs" should "fail without any HTTP call when the file does not exist" in {
    val r = new GeminiVisionClient(cfg()).analyzeImage("/no/such/file.png")
    r.isLeft shouldBe true
    arrived.get shouldBe 0
  }

  it should "fail without any HTTP call for an empty file" in {
    val p = Files.createTempFile("gemini-empty", ".png")
    tmp += p
    val r = new GeminiVisionClient(cfg()).analyzeImage(p.toString)
    r.isLeft shouldBe true
    arrived.get shouldBe 0
  }

  it should "fail without any HTTP call for a file that is not an image" in {
    val p = Files.createTempFile("gemini-notimage", ".png")
    tmp += p
    Files.write(p, "this is not an image".getBytes(StandardCharsets.UTF_8))
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).isLeft shouldBe true
    arrived.get shouldBe 0
  }

  it should "fail without any HTTP call and name the problem for a blank API key" in {
    val p = image(".png", "png")
    val r = new GeminiVisionClient(cfg(key = "   ")).analyzeImage(p.toString)
    r.left.toOption.get shouldBe a[ConfigurationError]
    arrived.get shouldBe 0
  }

  it should "fail without any HTTP call for a blank model" in {
    val p = image(".png", "png")
    val r = new GeminiVisionClient(cfg(model = "")).analyzeImage(p.toString)
    r.left.toOption.get shouldBe a[ConfigurationError]
    arrived.get shouldBe 0
  }

  it should "leave the source file deletable immediately after the call (no leaked handle)" in {
    val p = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).isRight shouldBe true
    Files.delete(p) // throws on Windows if a handle is still open
    Files.exists(p) shouldBe false
    // and after a failure too
    reply = Reply(500, "boom")
    val q = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(q.toString).isLeft shouldBe true
    Files.delete(q)
  }

  // ---- API key placement and secrecy ---------------------------------------------------

  "GeminiVisionClient API key" should "travel in the x-goog-api-key header and never in the URL" in {
    val p = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).isRight shouldBe true
    val req = seen.asScala.head
    req.headers.get("x-goog-api-key") shouldBe Some(Key)
    req.rawQuery shouldBe None
    (req.rawPath should not).include(Key)
    (req.body should not).include(Key)
  }

  it should "not appear in the error for an HTTP error status" in {
    val p = image(".png", "png")
    reply = Reply(403, """{"error":{"code":403,"message":"denied","status":"PERMISSION_DENIED"}}""")
    val e = new GeminiVisionClient(cfg()).analyzeImage(p.toString).left.toOption.get
    (errString(e) should not).include(Key)
  }

  it should "not appear in the error when the request cannot even be built (illegal URL character in the key)" in {
    // A key with a space makes `URI.create(url-with-?key=...)` throw IllegalArgumentException,
    // whose message quotes the whole URL.
    val spaced = "has space-" + Key
    val p      = image(".png", "png")
    val e      = new GeminiVisionClient(cfg(key = spaced)).analyzeImage(p.toString)
    e.left.foreach(err => (errString(err) should not).include(spaced))
    e.left.foreach(err => (errString(err) should not).include(Key))
  }

  it should "not appear in the error when the connection is refused" in {
    val dead = new java.net.ServerSocket(0)
    val port = dead.getLocalPort
    dead.close()
    val p = image(".png", "png")
    val c = cfg().copy(baseUrl = s"http://127.0.0.1:$port/v1beta")
    val e = new GeminiVisionClient(c).analyzeImage(p.toString).left.toOption.get
    (errString(e) should not).include(Key)
    e shouldBe a[NetworkError]
  }

  it should "not appear in any log record, and not in the config's toString" in {
    val logger   = org.slf4j.LoggerFactory.getLogger("org.llm4s").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    val prevLevel = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.TRACE)
    logger.addAppender(appender)
    try {
      val p = image(".png", "png")
      val c = new GeminiVisionClient(cfg())
      c.analyzeImage(p.toString).isRight shouldBe true
      reply = Reply(500, "oops")
      c.analyzeImage(p.toString).isLeft shouldBe true
      appender.list.asScala.foreach { ev =>
        (ev.getFormattedMessage should not).include(Key)
        Option(ev.getThrowableProxy).foreach(t => (t.getMessage should not).include(Key))
      }
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(prevLevel)
      appender.stop()
    }
    (cfg().toString should not).include(Key)
  }

  // ---- response parsing ----------------------------------------------------------------

  private def candidate(finish: String, parts: ujson.Value*): ujson.Value =
    ujson.Obj(
      "content"      -> ujson.Obj("role" -> "model", "parts" -> ujson.Arr.from(parts)),
      "finishReason" -> finish
    )

  private def text(t: String): ujson.Value = ujson.Obj("text" -> t)

  private def describe(json: ujson.Value, status: Int = 200): Either[LLMError, String] = {
    reply = Reply(status, ujson.write(json))
    val p = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).map(_.description)
  }

  "GeminiVisionClient response" should "concatenate every text part of the first candidate in order" in {
    describe(
      ujson.Obj("candidates" -> ujson.Arr(candidate("STOP", text("Hello "), text("big "), text("world"))))
    ) shouldBe
      Right("Hello big world")
  }

  it should "ignore non-text parts and only use the first candidate" in {
    val json = ujson.Obj(
      "candidates" -> ujson.Arr(
        candidate("STOP", text("A"), ujson.Obj("functionCall" -> ujson.Obj("name" -> "f")), text("B")),
        candidate("STOP", text("SECOND"))
      )
    )
    describe(json) shouldBe Right("AB")
  }

  it should "return the partial text when finishReason is MAX_TOKENS" in {
    describe(ujson.Obj("candidates" -> ujson.Arr(candidate("MAX_TOKENS", text("cut off"))))) shouldBe Right("cut off")
  }

  for (reason <- Seq("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "OTHER", "IMAGE_SAFETY"))
    it should s"fail, naming $reason, when a candidate ends with $reason and carries no text" in {
      val json = ujson.Obj(
        "candidates" -> ujson.Arr(ujson.Obj("finishReason" -> reason, "index" -> 0))
      )
      val r = describe(json)
      r.isLeft shouldBe true
      errString(r.left.toOption.get) should include(reason)
    }

  it should "fail, naming the blockReason, when the prompt was blocked (promptFeedback, no candidates)" in {
    val json = ujson.Obj("promptFeedback" -> ujson.Obj("blockReason" -> "IMAGE_SAFETY"))
    val r    = describe(json)
    r.isLeft shouldBe true
    errString(r.left.toOption.get) should include("IMAGE_SAFETY")
  }

  it should "fail on an empty candidates array" in {
    describe(ujson.Obj("candidates" -> ujson.Arr())).isLeft shouldBe true
  }

  it should "fail when the candidate has no content or empty parts" in {
    describe(ujson.Obj("candidates" -> ujson.Arr(ujson.Obj("finishReason" -> "STOP")))).isLeft shouldBe true
    describe(ujson.Obj("candidates" -> ujson.Arr(candidate("STOP")))).isLeft shouldBe true
  }

  it should "fail on an empty JSON object" in {
    describe(ujson.Obj()).isLeft shouldBe true
  }

  it should "fail, not report success, on a 200 whose body is not JSON" in {
    reply = Reply(200, "<html>proxy login</html>")
    val p = image(".png", "png")
    val r = new GeminiVisionClient(cfg()).analyzeImage(p.toString)
    r.isLeft shouldBe true
  }

  it should "fail on a 200 with an empty body" in {
    reply = Reply(200, "")
    val p = image(".png", "png")
    new GeminiVisionClient(cfg()).analyzeImage(p.toString).isLeft shouldBe true
  }

  it should "carry non-ASCII text through unchanged" in {
    describe(ujson.Obj("candidates" -> ujson.Arr(candidate("STOP", text("café 中文 😀"))))) shouldBe
      Right("café 中文 😀")
  }

  // ---- status code table ---------------------------------------------------------------

  for ((code, st) <- statuses)
    "GeminiVisionClient errors" should s"map HTTP $code to an APIError carrying the status code and Google's message" in {
      reply = Reply(code, googleError(code, st), Map("Retry-After" -> "7"))
      val p = image(".png", "png")
      val e = new GeminiVisionClient(cfg()).analyzeImage(p.toString).left.toOption.get
      e shouldBe a[APIError]
      val api = e.asInstanceOf[APIError]
      api.provider shouldBe "Gemini"
      api.statusCode shouldBe Some(code)
      e.message should include(s"msg for $code")
      e.message should include(st)
      (errString(e) should not).include(Key)
      arrived.get shouldBe 1 // no hidden retries
    }

  it should "map a non-JSON error body without losing the status" in {
    reply = Reply(502, "<html>Bad Gateway</html>")
    val p = image(".png", "png")
    val e = new GeminiVisionClient(cfg()).analyzeImage(p.toString).left.toOption.get
    e.asInstanceOf[APIError].statusCode shouldBe Some(502)
    e.message should include("Bad Gateway")
  }

  it should "truncate a huge error body in the error message" in {
    reply = Reply(500, "x" * 100000)
    val p = image(".png", "png")
    val e = new GeminiVisionClient(cfg()).analyzeImage(p.toString).left.toOption.get
    e.message.length should be < 10000
  }

  it should "report a timeout as a TimeoutError, promptly" in {
    reply = Reply(200, ok("late"), delayMs = 4000)
    val p     = image(".png", "png")
    val start = System.nanoTime()
    val e     = new GeminiVisionClient(cfg(request = 1)).analyzeImage(p.toString).left.toOption.get
    val took  = (System.nanoTime() - start) / 1000000
    e shouldBe a[TimeoutError]
    took should be < 3500L
    (errString(e) should not).include(Key)
  }

  // ---- interruption --------------------------------------------------------------------

  "GeminiVisionClient interruption" should "return a CancelledError, not throw, and keep the interrupt flag" in {
    reply = Reply(200, ok("late"), delayMs = 5000)
    val p                                                           = image(".png", "png")
    val client                                                      = new GeminiVisionClient(cfg(request = 30))
    @volatile var outcome: Either[Throwable, Either[LLMError, Any]] = null
    @volatile var flag                                              = false
    val done                                                        = new CountDownLatch(1)
    val t = new Thread(() => {
      outcome =
        try Right(client.analyzeImage(p.toString))
        catch { case e: Throwable => Left(e) }
      flag = Thread.currentThread().isInterrupted
      done.countDown()
    })
    t.start()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (arrived.get == 0 && System.nanoTime() < deadline) Thread.sleep(10)
    arrived.get shouldBe 1
    t.interrupt()
    done.await(10, TimeUnit.SECONDS) shouldBe true
    outcome match {
      case Left(thrown) => fail(s"analyzeImage threw instead of returning Left: $thrown")
      case Right(result) =>
        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[CancelledError]
    }
    flag shouldBe true
  }

  // ---- thread safety and concurrency ---------------------------------------------------

  "GeminiVisionClient concurrency" should "serve many parallel calls on one client without cross-talk" in {
    val client = new GeminiVisionClient(cfg())
    val p      = image(".png", "png")
    val pool   = Executors.newFixedThreadPool(8)
    try {
      val futures = (1 to 40).map { i =>
        pool.submit(new java.util.concurrent.Callable[Either[LLMError, String]] {
          override def call(): Either[LLMError, String] =
            client.analyzeImage(p.toString, Some(s"prompt-$i")).map(_.description)
        })
      }
      futures.foreach(_.get(30, TimeUnit.SECONDS) shouldBe Right("fine"))
      seen.size shouldBe 40
      val prompts = seen.asScala.map(s => ujson.read(s.body)("contents")(0)("parts")(0)("text").str).toSet
      prompts shouldBe (1 to 40).map(i => s"prompt-$i").toSet
      seen.asScala.foreach(_.headers("x-goog-api-key") shouldBe Key)
    } finally pool.shutdownNow()
  }
}
