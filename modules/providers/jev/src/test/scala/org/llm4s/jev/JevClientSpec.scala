package org.llm4s.jev

import ch.qos.logback.classic.{ Level, Logger as LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.error.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.net.ServerSocket
import java.time.{ ZoneOffset, ZonedDateTime }
import java.time.format.DateTimeFormatter
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class JevClientSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  import FakeJev.*

  private val secret = "tsk-live-0123456789abcdef"

  private val urgent  = JevQuestion.noul("Does this convey urgency?")
  private val request = JevRequest("Help! Payouts are failing.", Map("is_urgent" -> urgent))

  /** A client over `baseUrl` whose retry sleeps are recorded instead of taken. */
  final private class Rig(
    baseUrl: String,
    policy: JevRetryPolicy = JevRetryPolicy.none,
    tweak: JevConfig => JevConfig = identity
  ) {
    val slept: ArrayBuffer[FiniteDuration] = ArrayBuffer.empty
    private val retry                      = new JevRetry(policy, sleep = d => slept += d, random = () => 0.0)
    val client: JevClient =
      JevClient
        .withHttp(tweak(JevConfig(secret, baseUrl = baseUrl, retry = policy)), Llm4sHttpClient.create(), retry)
        .value
  }

  // ---- logs: whatever the client does, the secret must not reach them ----

  private val appender     = new ListAppender[ILoggingEvent]
  private val root         = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
  private var level: Level = _

  override def beforeEach(): Unit = {
    appender.list.clear()
    appender.start()
    level = root.getLevel
    root.setLevel(Level.TRACE)
    root.addAppender(appender)
  }

  override def afterEach(): Unit = {
    root.detachAppender(appender): Unit
    root.setLevel(level)
    appender.stop()
  }

  private def loggedText: String =
    appender.list.asScala
      .map(e => e.getFormattedMessage + Option(e.getThrowableProxy).map(_.getMessage).getOrElse(""))
      .mkString("\n")

  // ---- the request on the wire ----

  "evaluate" should "POST the contract's request to /v1/systemone with the key as a bearer token" in {
    serve(ok()) { (url, seen) =>
      new Rig(url).client.evaluate(request).isRight shouldBe true

      val r = seen().head
      r.method shouldBe "POST"
      r.path shouldBe "/v1/systemone"
      r.headers("authorization") shouldBe s"Bearer $secret"
      r.headers("content-type") shouldBe "application/json"
      r.headers("accept") shouldBe "application/json"
      ujson.read(r.body) shouldBe ujson.read(
        """{"state":"Help! Payouts are failing.","model":"jev-latest","questions":{"is_urgent":{"type":"noul","instructions":"Does this convey urgency?"}}}"""
      )
    }
  }

  it should "send the configured model, and a request's own model in preference" in {
    serve(ok()) { (url, seen) =>
      val rig = new Rig(url, tweak = _.withModel("jev-1.13.0"))
      rig.client.evaluate(request)
      rig.client.evaluate(request.withModel("jev-preview"))

      seen().map(r => ujson.read(r.body)("model").str) shouldBe Seq("jev-1.13.0", "jev-preview")
    }
  }

  it should "keep a path prefix of the base URL" in {
    serve(ok()) { (url, seen) =>
      new Rig(s"$url/jev/").client.evaluate(request).isRight shouldBe true

      seen().head.path shouldBe "/jev/v1/systemone"
    }
  }

  it should "send default and per-request headers, a request's winning, and never replace its own" in {
    serve(ok()) { (url, seen) =>
      val rig = new Rig(url, tweak = _.withHeaders(Map("X-Team" -> "platform", "X-Trace" -> "config")))
      rig.client.evaluate(request.withHeader("X-Trace", "request").withHeader("X-Extra", "1"))

      val h = seen().head.headers
      h("x-team") shouldBe "platform"
      h("x-trace") shouldBe "request"
      h("x-extra") shouldBe "1"
      h("authorization") shouldBe s"Bearer $secret"
    }
  }

  it should "refuse an invalid request before anything is sent" in {
    serve(ok()) { (url, seen) =>
      val bad = JevRequest("s", Map("q" -> JevQuestion.Score(ujson.Str("q"), Seq(ujson.Str("only one")))))

      new Rig(url).client.evaluate(bad).left.value shouldBe a[ValidationError]
      seen() shouldBe empty
    }
  }

  // ---- the answers ----

  it should "return the answer to each question, with the model, the usage and the request id" in {
    val body =
      """{"model":"jev-1.13.0","answers":{"is_urgent":{"type":"noul","noul":0.95},"department":{"type":"choice","choice":"billing","probabilities":{"billing":0.88,"technical":0.12},"confidence":0.81}},"usage":{"input_tokens":318,"output_tokens":34}}"""
    serve(ok(body, Map("x-typesafe-request-id" -> "req_abc"))) { (url, _) =>
      val two = JevRequest(
        "s",
        Map(
          "is_urgent"  -> urgent,
          "department" -> JevQuestion.choice("Which team?", "billing" -> "Payments", "technical" -> "Bugs")
        )
      )

      val response = new Rig(url).client.evaluate(two).value

      response.model shouldBe "jev-1.13.0"
      response.usage shouldBe JevUsage(318, 34)
      response.requestId shouldBe Some("req_abc")
      response.noul("is_urgent").value.probability shouldBe 0.95
      response.choice("department").value.choice shouldBe "billing"
    }
  }

  it should "refuse a response that leaves a question unanswered, naming it" in {
    serve(ok(Examples.Noul)) { (url, _) =>
      val two     = JevRequest("s", Map("is_urgent" -> urgent, "other" -> urgent))
      val message = new Rig(url).client.evaluate(two).left.value

      message shouldBe a[ProcessingError]
      message.message should include("other")
    }
  }

  it should "refuse a 200 whose body is not the API's, without echoing the body" in {
    serve(ok("""<html>tsk-live-0123456789abcdef secret-state</html>""")) { (url, seen) =>
      val error = new Rig(url, JevRetryPolicy.default).client.evaluate(request).left.value

      error shouldBe a[ProcessingError]
      (error.message should not).include("secret-state")
      (error.message should not).include(secret)
      seen().size shouldBe 1 // a malformed answer is not a transient failure
    }
  }

  it should "refuse a response body beyond the size cap" in {
    serve(ok("a" * (JevClient.MaxResponseChars + 1))) { (url, _) =>
      val error = new Rig(url).client.evaluate(request).left.value

      error shouldBe a[ProcessingError]
      error.message should include("larger than")
    }
  }

  // ---- errors, as the contract lists them ----

  it should "map 401 and 403 to an AuthenticationError that does not retry" in {
    Seq(401, 403).foreach { status =>
      serve(Reply(status, """{"message":"Invalid API key"}""")) { (url, seen) =>
        val rig   = new Rig(url, JevRetryPolicy.default)
        val error = rig.client.evaluate(request).left.value

        withClue(s"$status ") {
          error shouldBe a[AuthenticationError]
          seen().size shouldBe 1
          rig.slept shouldBe empty
        }
      }
    }
  }

  it should "map 422 and 400 to a ValidationError carrying the server's explanation, without retrying" in {
    Seq(422, 400).foreach { status =>
      serve(Reply(status, """{"message":"questions.is_urgent.instructions is required"}""")) { (url, seen) =>
        val rig   = new Rig(url, JevRetryPolicy.default)
        val error = rig.client.evaluate(request).left.value

        withClue(s"$status ") {
          error shouldBe a[ValidationError]
          error.message should include("questions.is_urgent.instructions is required")
          seen().size shouldBe 1
        }
      }
    }
  }

  it should "map 429 to a RateLimitError carrying the seconds in Retry-After" in {
    serve(Reply(429, """{"message":"slow down"}""", Map("Retry-After" -> "7"))) { (url, _) =>
      val error = new Rig(url).client.evaluate(request).left.value

      error shouldBe a[RateLimitError]
      error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(7.seconds)
    }
  }

  it should "read Retry-After given as an HTTP date" in {
    val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60))
    serve(Reply(429, "{}", Map("Retry-After" -> date))) { (url, _) =>
      val delay = new Rig(url).client.evaluate(request).left.value.asInstanceOf[RateLimitError].retryAfter.get

      delay should ((be > 50.seconds).and(be <= 61.seconds))
    }
  }

  it should "prefer retry-after-ms to Retry-After, and fall back when it is not a number" in {
    def retryAfter(headers: Map[String, String]): Option[FiniteDuration] = {
      var out: Option[FiniteDuration] = None
      serve(Reply(429, "{}", headers)) { (url, _) =>
        out = new Rig(url).client.evaluate(request).left.value.asInstanceOf[RateLimitError].retryAfter
      }
      out
    }

    retryAfter(Map("retry-after-ms" -> "1500")) shouldBe Some(1500.millis)
    retryAfter(Map("Retry-After" -> "7", "retry-after-ms" -> "250")) shouldBe Some(250.millis)
    retryAfter(Map("Retry-After" -> "7", "retry-after-ms" -> "soon")) shouldBe Some(7.seconds)
    retryAfter(Map("Retry-After" -> "7", "retry-after-ms" -> "-5")) shouldBe Some(7.seconds)
  }

  it should "map 529 Overloaded and 5xx to a ServiceError, and 404 to one that is not retried" in {
    Seq(529, 500, 503, 404).foreach { status =>
      serve(Reply(status, """{"message":"x"}""")) { (url, _) =>
        val error = new Rig(url).client.evaluate(request).left.value

        withClue(s"$status ") {
          error shouldBe a[ServiceError]
          error.asInstanceOf[ServiceError].httpStatus shouldBe status
        }
      }
    }
  }

  it should "carry the server's retry delay on a ServiceError" in {
    serve(Reply(529, "{}", Map("retry-after-ms" -> "120"))) { (url, _) =>
      new Rig(url).client.evaluate(request).left.value.asInstanceOf[ServiceError].retryAfter shouldBe Some(120.millis)
    }
  }

  // ---- retries ----

  it should "retry a 503 with the SDK's schedule and return the success that follows, resending the same request" in {
    serve(Reply(503, "{}"), Reply(503, "{}"), ok()) { (url, seen) =>
      val rig = new Rig(url, JevRetryPolicy.default)

      rig.client.evaluate(request).isRight shouldBe true

      seen().size shouldBe 3
      rig.slept.toSeq shouldBe Seq(500.millis, 1.second)
      seen().map(_.body).distinct.size shouldBe 1
      seen().map(_.headers("authorization")).distinct shouldBe Seq(s"Bearer $secret")
    }
  }

  it should "send a header the caller attached unchanged on every attempt" in {
    serve(Reply(503, "{}"), Reply(503, "{}"), ok()) { (url, seen) =>
      new Rig(url, JevRetryPolicy.default).client
        .evaluate(request.withHeader("X-Request-Token", "abc-123"))
        .isRight shouldBe true

      seen().map(_.headers.get("x-request-token")) shouldBe Seq(Some("abc-123"), Some("abc-123"), Some("abc-123"))
    }
  }

  it should "wait what a 429 asks for, and what a 529's retry-after-ms asks for" in {
    serve(Reply(429, "{}", Map("Retry-After" -> "3")), Reply(529, "{}", Map("retry-after-ms" -> "120")), ok()) {
      (url, _) =>
        val rig = new Rig(url, JevRetryPolicy.default)

        rig.client.evaluate(request).isRight shouldBe true
        rig.slept.toSeq shouldBe Seq(3.seconds, 120.millis)
    }
  }

  it should "give up after the last retry with the last error as it is" in {
    serve(Reply(503, """{"message":"down"}""")) { (url, seen) =>
      val rig   = new Rig(url, JevRetryPolicy.default)
      val error = rig.client.evaluate(request).left.value

      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].httpStatus shouldBe 503
      seen().size shouldBe 3
    }
  }

  it should "retry a connection failure, then report it as a network error" in {
    val closed = { val s = new ServerSocket(0); val p = s.getLocalPort; s.close(); p }
    val rig    = new Rig(s"http://localhost:$closed", JevRetryPolicy.default)

    val error = rig.client.evaluate(request).left.value

    error shouldBe a[NetworkError]
    rig.slept.size shouldBe 2
  }

  it should "report a request that outlasts the timeout as a timeout" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { url =>
      val rig = new Rig(url, tweak = _.withTimeout(300.millis))

      rig.client.evaluate(request).left.value shouldBe a[TimeoutError]
    }
  }

  it should "cap each attempt's timeout at what is left of the retry budget" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { url =>
      // a 30 s per-attempt timeout, but only 400 ms of budget: the retry budget bounds the call, not the timeout
      val policy = JevRetryPolicy.default.withBudget(400.millis).withBackoffInitial(Duration.Zero)
      val rig    = new Rig(url, policy, _.withTimeout(30.seconds))

      val started = System.nanoTime()
      rig.client.evaluate(request).left.value shouldBe a[TimeoutError]
      FiniteDuration(System.nanoTime() - started, NANOSECONDS) should be < 5.seconds
    }
  }

  it should "end with a CancelledError, promptly, when the calling thread is interrupted" in {
    val arrived = new CountDownLatch(1)
    LocalProviderTestServer.withServer("/") { exchange =>
      arrived.countDown()
      LocalProviderTestServer.holdOpen(exchange)
    } { url =>
      val outcome = new AtomicReference[Option[org.llm4s.types.Result[JevResponse]]](None)
      val worker  = new Thread(() => outcome.set(Some(new Rig(url).client.evaluate(request))))
      worker.start()

      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      worker.interrupt()
      worker.join(10000)

      worker.isAlive shouldBe false
      outcome.get.get.left.value shouldBe a[CancelledError]
    }
  }

  // ---- the secret never leaves the Authorization header ----

  it should "keep the key out of an error even when the server echoes it" in {
    Seq(
      Reply(401, s"""{"error":{"message":"Invalid key $secret"}}"""),
      Reply(422, s"""{"message":"bad Bearer $secret"}"""),
      Reply(429, s"""{"message":"limit for $secret"}"""),
      Reply(500, s"""{"message":"oops $secret"}"""),
      Reply(404, s"plain text mentioning $secret")
    ).foreach { reply =>
      serve(reply) { (url, _) =>
        val error = new Rig(url).client.evaluate(request).left.value

        withClue(s"${reply.status} ") {
          (error.message should not).include(secret)
          (error.formatted should not).include(secret)
          (error.toString should not).include(secret)
        }
      }
    }
  }

  it should "keep the key out of the logs, on success, on failure and on retry" in {
    serve(Reply(503, s"""{"message":"$secret"}"""), ok(), Reply(401, s"""{"message":"$secret"}""")) { (url, _) =>
      val rig = new Rig(url, JevRetryPolicy.default)

      rig.client.evaluate(request).isRight shouldBe true
      rig.client.evaluate(request).isLeft shouldBe true
    }

    appender.list.size should be > 0 // the capture works: something was logged
    (loggedText should not).include(secret)
  }

  // ---- building clients ----

  "A client" should "refuse an invalid config instead of being built" in {
    JevClient(JevConfig(secret, baseUrl = "http://evil.example")).left.value shouldBe a[ConfigurationError]
    JevClient(JevConfig("")).left.value shouldBe a[ConfigurationError]
  }

  it should "build over its own HTTP client and close it" in {
    val client = JevClient(JevConfig(secret)).value

    noException should be thrownBy client.close()
  }

  it should "leave an HTTP client it was handed open" in {
    var closed = false
    val http = new Llm4sHttpClient {
      override def get(
        url: String,
        headers: Map[String, String],
        params: Map[String, String],
        timeout: FiniteDuration
      ) = fail("unused")
      override def post(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        fail("unused")
      override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: FiniteDuration) =
        fail("unused")
      override def postMultipart(
        url: String,
        headers: Map[String, String],
        parts: Seq[org.llm4s.http.MultipartPart],
        timeout: FiniteDuration
      ) = fail("unused")
      override def put(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        fail("unused")
      override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration) = fail("unused")
      override def postRaw(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        fail("unused")
      override def postStream(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        fail("unused")
      override def close(): Unit = closed = true
    }

    JevClient.withHttp(JevConfig(secret), http, new JevRetry(JevRetryPolicy.none)).value.close()

    closed shouldBe false
  }
}
