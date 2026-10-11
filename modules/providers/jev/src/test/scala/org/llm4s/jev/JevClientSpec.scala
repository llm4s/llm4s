package org.llm4s.jev

import ch.qos.logback.classic.{ Level, Logger as LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.error.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.util.BoundedJson
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

  it should "let a request's header replace a configured one whatever its case" in {
    serve(ok()) { (url, seen) =>
      val rig = new Rig(url, tweak = _.withHeaders(Map("X-Trace" -> "config")))
      rig.client.evaluate(request.withHeader("x-trace", "request")).isRight shouldBe true

      seen().head.rawHeaders("x-trace") shouldBe Seq("request")
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

  it should "refuse a response that answers a question that was not asked, naming it" in {
    val extra =
      """{"model":"jev-1.13.0","answers":{"is_urgent":{"type":"noul","noul":0.95},""" +
        """"unasked":{"type":"noul","noul":0.1}},"usage":{"input_tokens":1,"output_tokens":1}}"""
    serve(ok(extra)) { (url, _) =>
      val error = new Rig(url).client.evaluate(request).left.value

      error shouldBe a[ProcessingError]
      error.message should include("question(s) that were not asked: unasked")
    }
  }

  it should "refuse an answer of another type than its question asked, naming the question" in {
    serve(ok(Examples.Noul)) { (url, _) =>
      val asChoice = JevRequest("s", Map("is_urgent" -> JevQuestion.choiceOf("Which?", "yes", "no")))
      val error    = new Rig(url).client.evaluate(asChoice).left.value

      error shouldBe a[ProcessingError]
      error.message should include("is_urgent")
      error.message should include("expected a choice answer")
    }
  }

  it should "refuse a Choice answer naming an option that was not offered" in {
    serve(ok(Examples.Choice)) { (url, _) =>
      def ask(options: String*) =
        new Rig(url).client.evaluate(JevRequest("s", Map("department" -> JevQuestion.choiceOf("Which?", options*))))

      ask("billing", "technical", "sales").isRight shouldBe true
      ask("technical", "sales").left.value.message should include("'billing' is not one of the options asked")
      ask("billing", "technical").left.value.message should include("'sales'")
    }
  }

  it should "refuse a Choice answer that leaves an offered option out of its distribution" in {
    serve(ok(Examples.Choice)) { (url, _) =>
      val fourOptions = JevQuestion.choiceOf("Which?", "billing", "technical", "sales", "refunds")
      val error       = new Rig(url).client.evaluate(JevRequest("s", Map("department" -> fourOptions))).left.value

      error shouldBe a[ProcessingError]
      error.message should include("no probability for 'refunds'")
    }
  }

  it should "refuse a Score answer that leaves a level the question asked out" in {
    serve(ok(Examples.Score)) { (url, _) =>
      val fourLevels = JevQuestion.score("How?", "Calm", "Frustrated", "Very angry", "Furious")
      val error      = new Rig(url).client.evaluate(JevRequest("s", Map("frustration" -> fourLevels))).left.value

      error shouldBe a[ProcessingError]
      error.message should include("no level 3")
    }
  }

  it should "mask the API key when a 200's error quotes a value the server chose" in {
    val echoed =
      s"""{"model":"jev-1.13.0","answers":{"is_urgent":{"type":"$secret","noul":0.95}},"usage":{"input_tokens":1,"output_tokens":1}}"""
    serve(ok(echoed)) { (url, _) =>
      val error = new Rig(url).client.evaluate(request).left.value

      error shouldBe a[ProcessingError]
      error.message should include("unsupported answer type '***'")
      (error.message should not).include(secret)
      (error.formatted should not).include(secret)
      (error.toString should not).include(secret)
    }
  }

  it should "refuse a Score answer with a level the question did not describe" in {
    serve(ok(Examples.Score)) { (url, _) =>
      def ask(levels: String*) =
        new Rig(url).client.evaluate(JevRequest("s", Map("frustration" -> JevQuestion.score("How?", levels*))))

      ask("Calm", "Frustrated", "Very angry").isRight shouldBe true
      val error = ask("Calm", "Frustrated").left.value
      error shouldBe a[ProcessingError]
      error.message should include("level 2")
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
      error.message should include("longer than")
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

  it should "keep the key out of an error when the server echoes it JSON-escaped" in {
    // A key may hold characters JSON escapes; the server may also escape '/' or any character as \\uXXXX.
    val key     = "tsk/live\"quote\\back"
    val unicode = key.map(c => f"\\u${c.toInt}%04x").mkString
    Seq(
      s"""{"message":"bad key ${ujson.write(ujson.Str(key)).drop(1).dropRight(1)}"}""",
      s"""{"message":"bad key ${ujson.write(ujson.Str(key)).drop(1).dropRight(1).replace("/", "\\/")}"}""",
      s"""{"error":{"message":"bad key $unicode"}}"""
    ).foreach { body =>
      serve(Reply(401, body)) { (url, _) =>
        val error = new Rig(url, tweak = _.withApiKey(key)).client.evaluate(request).left.value

        withClue(s"$body ") {
          error shouldBe an[AuthenticationError]
          error.message should include("***")
          (error.message should not).include(key)
          (error.formatted should not).include(key)
          (error.toString should not).include(key)
        }
      }
    }
  }

  it should "keep the key out of an error when the server echoes it URL-encoded" in {
    val key     = "tsk/live+0123=abc"
    val encoded = "tsk%2Flive%2B0123%3Dabc"
    java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8) shouldBe encoded
    Seq(
      Reply(401, s"""{"message":"bad key $encoded"}"""),
      ok(withChoice(encoded))
    ).foreach { reply =>
      serve(reply) { (url, _) =>
        val error = new Rig(url, tweak = _.withApiKey(key)).client.evaluate(choiceRequest).left.value

        withClue(s"${reply.status} ") {
          error.message should include("***")
          Seq(error.message, error.formatted, error.toString).foreach { text =>
            (text should not).include(key)
            (text should not).include(encoded)
          }
        }
      }
    }
  }

  private def choiceRequest =
    JevRequest("s", Map("department" -> JevQuestion.choice("Which team?", "billing" -> "Payments")))

  it should "mask the URL-encoded key in a body read as text" in {
    JevErrors.redactBody(
      "plain text, url tsk%2Flive%2B0123%3Dabc.",
      "tsk/live+0123=abc"
    ) shouldBe "plain text, url ***."
  }

  private def withChoice(choice: String): String =
    s"""{"model":"jev-1.13.0","answers":{"department":{"type":"choice","choice":"$choice","probabilities":{"$choice":1.0},"confidence":1.0}},"usage":{"input_tokens":1,"output_tokens":1}}"""

  /** Runs `body` on a thread with a 256 KiB stack, so a recursion over a deeply nested value overflows it. */
  private def onSmallStack[A](body: => A): A = {
    val outcome = new AtomicReference[Either[Throwable, A]](null)
    val thread  = new Thread(null, () => outcome.set(scala.util.Try(body).toEither), "small-stack", 256L * 1024)
    thread.start()
    thread.join(60000)
    thread.isAlive shouldBe false
    // A StackOverflowError is fatal, so Try rethrows it: the thread dies and records nothing.
    Option(outcome.get).getOrElse(fail("the call died on the small stack, most likely of a StackOverflowError")) match {
      case Right(a) => a
      case Left(e)  => throw e
    }
  }

  private def deepDepth = 100000

  it should "redact a non-2xx body nested too deeply to walk, without overflowing the stack" in {
    val body =
      s"""{"message":"bad key $secret","nested":${"[" * deepDepth}"$secret"${"]" * deepDepth}}"""

    val redacted = onSmallStack(JevErrors.redactBody(body, secret))

    (redacted should not).include(secret)
    redacted should include("***")
  }

  it should "return a Left for a non-2xx body nested 100,000 levels deep, with no key in it however escaped" in {
    val key     = "tsk/live\"quote\\back"
    val escaped = ujson.write(ujson.Str(key)).drop(1).dropRight(1).replace("/", "\\/")
    val body    = s"""{"message":"bad key $escaped","nested":${"[" * deepDepth}${"]" * deepDepth}}"""
    serve(Reply(500, body)) { (url, _) =>
      val rig    = new Rig(url, tweak = _.withApiKey(key))
      val result = onSmallStack(rig.client.evaluate(request))

      val error = result.left.value
      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].httpStatus shouldBe 500
      (error.message should not).include(key)
      (error.formatted should not).include(key)
    }
  }

  it should "return a Left, on a small stack, for a non-2xx body nested 511 levels deep" in {
    // under the shared 512-level limit, but deep enough to overflow a 256 KiB stack if it were walked and re-encoded
    val body = s"""{"message":"bad key $secret","x":${"[" * 510}"$secret"${"]" * 510}}"""
    BoundedJson.exceedsDepth(body) shouldBe false
    serve(Reply(500, body)) { (url, _) =>
      val result = onSmallStack(new Rig(url).client.evaluate(request))

      val error = result.left.value
      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].httpStatus shouldBe 500
      (error.message should not).include(secret)
      (error.formatted should not).include(secret)
    }
  }

  it should "redact, on a small stack, a non-2xx body nested 511 levels deep without reading it as JSON" in {
    val body     = s"""{"message":"bad key $secret","x":${"[" * 510}"$secret"${"]" * 510}}"""
    val redacted = onSmallStack(JevErrors.redactBody(body, secret))

    (redacted should not).include(secret)
  }

  it should "still read a non-2xx body at the error depth limit, with the key masked" in {
    // the object, then nested arrays: exactly the limit
    val depth = JevErrors.MaxErrorDepth - 1
    val body  = s"""{"message":"bad key $secret","x":${"[" * depth}"$secret"${"]" * depth}}"""
    BoundedJson.exceedsDepth(body, JevErrors.MaxErrorDepth) shouldBe false

    val redacted = onSmallStack(JevErrors.redactBody(body, secret))
    ujson.read(redacted)("message").str shouldBe "bad key ***"
    (redacted should not).include(secret)

    serve(Reply(500, body)) { (url, _) =>
      val error = onSmallStack(new Rig(url).client.evaluate(request)).left.value
      error.message should include("bad key ***")
    }
  }

  it should "pass no body to the mapper one level past the error depth limit" in {
    val depth = JevErrors.MaxErrorDepth
    val body  = s"""{"message":"bad key","x":${"[" * depth}${"]" * depth}}"""
    serve(Reply(500, body)) { (url, _) =>
      val error = new Rig(url).client.evaluate(request).left.value
      error shouldBe a[ServiceError]
      (error.message should not).include("bad key")
    }
  }

  it should "return a Left, on a small stack, for a 200 whose legend description is nested 511 levels deep" in {
    val nested = "[" * 507 + "]" * 507
    serve(ok(Examples.Score.replace("\"Very angry\"", nested))) { (url, _) =>
      val result = onSmallStack(
        new Rig(url).client.evaluate(JevRequest("s", Map("frustration" -> JevQuestion.score("How?", "a", "b", "c"))))
      )

      result.left.value shouldBe a[ProcessingError]
      result.left.value.message should include("nested more than")
    }
  }

  it should "return a Left, on a small stack, for a 200 nested 100,000 levels deep" in {
    val deep = "[" * deepDepth + "]" * deepDepth
    serve(ok(Examples.Score.replace("\"Very angry\"", deep))) { (url, _) =>
      val rig = new Rig(url)
      val result = onSmallStack(
        rig.client.evaluate(JevRequest("s", Map("frustration" -> JevQuestion.score("How?", "a", "b", "c"))))
      )

      val error = result.left.value
      error shouldBe a[ProcessingError]
      error.message should include("nested more than")
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
