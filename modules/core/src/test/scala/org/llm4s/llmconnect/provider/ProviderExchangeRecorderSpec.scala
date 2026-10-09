package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ProcessingError, ServiceError }
import org.llm4s.llmconnect.{
  JsonlProviderExchangeSink,
  ProviderExchange,
  ProviderExchangeLogging,
  ProviderExchangeOutcome,
  ProviderExchangeSink
}
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.{ Duration => JDuration, Instant }
import java.util.UUID
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit }
import scala.jdk.CollectionConverters.*
import scala.util.Try

class ProviderExchangeRecorderSpec extends AnyFlatSpec with Matchers {

  /** A thread-safe sink that keeps every exchange it is handed, in arrival order. */
  final private class CollectingSink extends ProviderExchangeSink {
    private val seen = new ConcurrentLinkedQueue[ProviderExchange]()

    override def record(exchange: ProviderExchange): Unit = {
      seen.add(exchange)
      ()
    }

    def exchanges: List[ProviderExchange] = seen.asScala.toList
  }

  private val success: Result[String] = Right("ok")

  private def recordInto(
    sink: CollectingSink,
    result: Result[?] = success,
    provider: String = "openai",
    model: Option[String] = Some("gpt-4o"),
    startedAt: Instant = Instant.now(),
    requestBody: String = """{"prompt":"hello"}""",
    responseBody: Option[String] = Some("""{"text":"hi"}"""),
    requestId: Option[String] = None,
    correlationId: Option[String] = None
  ): Unit =
    ProviderExchangeRecorder.record(
      ProviderExchangeLogging.enabled(sink),
      provider,
      model,
      startedAt,
      requestBody,
      responseBody,
      result,
      requestId,
      correlationId
    )

  private def onlyExchange(sink: CollectingSink): ProviderExchange = {
    val all = sink.exchanges
    withClue(s"exchanges recorded: ${all.size}; ")(all.size shouldBe 1)
    all.head
  }

  // ---- disabled

  "ProviderExchangeRecorder.record" should "do nothing at all when logging is disabled" in {
    // Disabled has no sink to observe, so prove it by the arguments it never touches: a null `result` would be
    // dereferenced the moment an exchange was built (the control below), and Disabled must return before that.
    val nullResult = null.asInstanceOf[Result[String]]
    val now        = Instant.now()

    val disabled =
      Try(ProviderExchangeRecorder.record(ProviderExchangeLogging.Disabled, "p", None, now, "req", None, nullResult))
    val control = Try(
      ProviderExchangeRecorder.record(
        ProviderExchangeLogging.enabled(new CollectingSink),
        "p",
        None,
        now,
        "req",
        None,
        nullResult
      )
    )

    control.isFailure shouldBe true
    disabled.isSuccess shouldBe true
  }

  // ---- what is recorded

  it should "hand an enabled sink exactly one exchange per call" in {
    val sink = new CollectingSink

    recordInto(sink)

    sink.exchanges.size shouldBe 1
  }

  it should "record the provider, model and both bodies it was given" in {
    val sink = new CollectingSink

    recordInto(
      sink,
      provider = "anthropic",
      model = Some("claude-haiku-4-5"),
      requestBody = """{"messages":[{"role":"user","content":"ping"}]}""",
      responseBody = Some("""{"content":"pong"}""")
    )

    val exchange = onlyExchange(sink)
    exchange.provider shouldBe "anthropic"
    exchange.model shouldBe Some("claude-haiku-4-5")
    exchange.requestBody shouldBe """{"messages":[{"role":"user","content":"ping"}]}"""
    exchange.responseBody shouldBe Some("""{"content":"pong"}""")
  }

  it should "keep a missing model and a missing response body missing" in {
    val sink = new CollectingSink

    recordInto(sink, model = None, responseBody = None, result = Left(ProcessingError("send", "connection reset")))

    val exchange = onlyExchange(sink)
    exchange.model shouldBe None
    exchange.responseBody shouldBe None
  }

  it should "carry the request and correlation ids when given and leave them empty otherwise" in {
    val withIds    = new CollectingSink
    val withoutIds = new CollectingSink

    recordInto(withIds, requestId = Some("req-42"), correlationId = Some("corr-7"))
    recordInto(withoutIds)

    val carried = onlyExchange(withIds)
    carried.requestId shouldBe Some("req-42")
    carried.correlationId shouldBe Some("corr-7")
    val absent = onlyExchange(withoutIds)
    absent.requestId shouldBe None
    absent.correlationId shouldBe None
  }

  it should "give every exchange its own UUID" in {
    val sink = new CollectingSink

    (1 to 5).foreach(_ => recordInto(sink))

    val ids = sink.exchanges.map(_.exchangeId)
    ids.distinct.size shouldBe 5
    ids.foreach(id => UUID.fromString(id).toString shouldBe id)
  }

  // ---- outcome

  it should "record a Right result as a Success with no error message, whatever the payload" in {
    val sink = new CollectingSink

    recordInto(sink, result = Right(Vector(1, 2, 3)))

    val exchange = onlyExchange(sink)
    exchange.outcome shouldBe ProviderExchangeOutcome.Success
    exchange.errorMessage shouldBe None
  }

  it should "record a Left result as an Error carrying that error's message" in {
    val sink  = new CollectingSink
    val error = ServiceError(503, "openai", "upstream overloaded, marker-7f3a")

    recordInto(sink, result = Left(error))

    val exchange = onlyExchange(sink)
    exchange.outcome shouldBe ProviderExchangeOutcome.Error
    exchange.errorMessage shouldBe Some(error.message)
    exchange.errorMessage.exists(_.contains("marker-7f3a")) shouldBe true
  }

  it should "keep the response body of a failed exchange when there is one" in {
    val sink = new CollectingSink

    recordInto(
      sink,
      result = Left(ServiceError(429, "openai", "rate limited")),
      responseBody = Some("""{"error":"slow down"}""")
    )

    val exchange = onlyExchange(sink)
    exchange.outcome shouldBe ProviderExchangeOutcome.Error
    exchange.responseBody shouldBe Some("""{"error":"slow down"}""")
  }

  // ---- timing

  it should "report a duration that is the span between the start and the completion it recorded" in {
    val sink      = new CollectingSink
    val startedAt = Instant.now().minusMillis(250)

    recordInto(sink, startedAt = startedAt)

    val exchange = onlyExchange(sink)
    exchange.startedAt shouldBe startedAt
    exchange.duration.toNanos shouldBe JDuration.between(exchange.startedAt, exchange.completedAt).toNanos
  }

  it should "never report a negative duration for an exchange that started before it was recorded" in {
    val sink = new CollectingSink

    recordInto(sink, startedAt = Instant.now())

    onlyExchange(sink).duration.toNanos should be >= 0L
  }

  it should "measure the time that really elapsed since the exchange started" in {
    val sink      = new CollectingSink
    val startedAt = Instant.now().minusMillis(400)

    recordInto(sink, startedAt = startedAt)

    onlyExchange(sink).duration.toMillis should be >= 400L
  }

  it should "stamp the completion time inside the window of the call" in {
    val sink   = new CollectingSink
    val before = Instant.now()

    recordInto(sink, startedAt = before)

    val after     = Instant.now()
    val completed = onlyExchange(sink).completedAt
    completed.isBefore(before) shouldBe false
    completed.isAfter(after) shouldBe false
  }

  // ---- a failing sink

  it should "swallow an exception thrown by the sink, after having called it" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val throwing = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit = {
        calls.incrementAndGet()
        throw new IllegalStateException("disk full")
      }
    }

    val outcome = Try(
      ProviderExchangeRecorder.record(
        ProviderExchangeLogging.enabled(throwing),
        "openai",
        Some("gpt-4o"),
        Instant.now(),
        "req",
        Some("resp"),
        success
      )
    )

    calls.get() shouldBe 1
    outcome.isSuccess shouldBe true
  }

  it should "keep recording after a call whose sink failed" in {
    val failFirst = new java.util.concurrent.atomic.AtomicBoolean(true)
    val kept      = new ConcurrentLinkedQueue[ProviderExchange]()
    val flaky = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit =
        if (failFirst.getAndSet(false)) throw new RuntimeException("first write fails")
        else {
          kept.add(exchange)
          ()
        }
    }
    val logging = ProviderExchangeLogging.enabled(flaky)

    ProviderExchangeRecorder.record(logging, "p", None, Instant.now(), "one", None, success)
    ProviderExchangeRecorder.record(logging, "p", None, Instant.now(), "two", None, success)

    kept.asScala.toList.map(_.requestBody) shouldBe List("two")
  }

  it should "swallow the I/O failure of the built-in JSONL sink when its file cannot be written" in {
    // the path is an existing directory, so the sink's append throws an IOException
    val unwritable = Files.createTempDirectory("provider-exchange-unwritable")
    try {
      val outcome = Try(
        ProviderExchangeRecorder.record(
          ProviderExchangeLogging.enabled(JsonlProviderExchangeSink(unwritable)),
          "openai",
          Some("gpt-4o"),
          Instant.now(),
          "req",
          Some("resp"),
          success
        )
      )

      outcome.isSuccess shouldBe true
      Files.isDirectory(unwritable) shouldBe true
    } finally Files.deleteIfExists(unwritable)
  }

  // ---- end to end with the built-in sink

  it should "leave no credential in the request body, response body or error message the JSONL sink writes" in {
    val file = Files.createTempFile("provider-exchange-recorder", ".jsonl")
    try {
      ProviderExchangeRecorder.record(
        ProviderExchangeLogging.enabled(JsonlProviderExchangeSink(file)),
        "openai",
        Some("gpt-4o"),
        Instant.now(),
        """{"api_key":"sk-request-secret-123","prompt":"hello"}""",
        Some("""{"token":"response-secret-456"}"""),
        Left(ServiceError(401, "openai", "Authorization: Bearer error-secret-789"))
      )

      val lines = Files.readAllLines(file).asScala.toList
      lines.size shouldBe 1
      val row = ujson.read(lines.head)
      row("outcome").str shouldBe "Error"
      row("request_body").str should include("[REDACTED]")
      row("request_body").str should include("hello")
      row("response_body").str should include("[REDACTED]")
      row("error_message").str should include("[REDACTED]")
      List("sk-request-secret-123", "response-secret-456", "error-secret-789").foreach { secret =>
        withClue(s"secret $secret: ")((lines.head should not).include(secret))
      }
    } finally Files.deleteIfExists(file)
  }

  // ---- ordering and concurrency

  it should "deliver sequential exchanges to the sink in call order" in {
    val sink = new CollectingSink

    List("first", "second", "third").foreach(body => recordInto(sink, requestBody = body))

    sink.exchanges.map(_.requestBody) shouldBe List("first", "second", "third")
  }

  it should "lose and mix up no exchange when many threads record at once" in {
    val sink    = new CollectingSink
    val threads = 16
    val perThr  = 25
    val pool    = Executors.newFixedThreadPool(threads)
    val ready   = new CountDownLatch(threads)
    val go      = new CountDownLatch(1)
    val done    = new CountDownLatch(threads)

    val finished =
      try {
        (0 until threads).foreach { t =>
          pool.execute { () =>
            ready.countDown()
            go.await()
            (0 until perThr).foreach { i =>
              recordInto(sink, provider = s"provider-$t", requestBody = s"body-$t-$i", requestId = Some(s"req-$t-$i"))
            }
            done.countDown()
          }
        }
        ready.await(10, TimeUnit.SECONDS) shouldBe true
        go.countDown()
        done.await(30, TimeUnit.SECONDS)
      } finally {
        go.countDown()
        pool.shutdownNow()
      }

    finished shouldBe true
    val all = sink.exchanges
    all.size shouldBe threads * perThr
    all.map(_.exchangeId).distinct.size shouldBe threads * perThr
    // each exchange still carries the fields of the single call that produced it
    all.foreach { exchange =>
      val t = exchange.provider.stripPrefix("provider-")
      exchange.requestBody should startWith(s"body-$t-")
      exchange.requestId.map(_.stripPrefix("req-")) shouldBe Some(exchange.requestBody.stripPrefix("body-"))
    }
  }
}
