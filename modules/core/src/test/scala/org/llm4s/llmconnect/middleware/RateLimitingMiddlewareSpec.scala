package org.llm4s.llmconnect.middleware

import org.llm4s.error.{ RateLimitError, RateLimitOrigin }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class RateLimitingMiddlewareSpec extends AnyFlatSpec with Matchers {

  class TestMetricsCollector extends MetricsCollector {
    val recordedErrors: scala.collection.mutable.ListBuffer[(ErrorKind, String)] =
      scala.collection.mutable.ListBuffer.empty

    override def observeRequest(
      provider: String,
      model: String,
      outcome: org.llm4s.metrics.Outcome,
      duration: FiniteDuration
    ): Unit = ()

    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()

    override def recordCost(provider: String, model: String, costUsd: Double): Unit = ()

    override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit = ()

    override def recordCircuitBreakerTransition(provider: String, newState: String): Unit = ()

    override def recordError(errorKind: ErrorKind, provider: String): Unit =
      recordedErrors.synchronized {
        recordedErrors += (errorKind -> provider)
      }
  }

  class NoOpClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def getContextWindow(): Int     = 100
    override def getReserveCompletion(): Int = 10
  }

  "RateLimitingMiddleware" should "allow requests within limit" in {
    // 60 requests per minute = 1 request per second
    val middleware = new RateLimitingMiddleware(60, 60)
    val client     = middleware.wrap(new NoOpClient)

    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))
  }

  it should "reject requests when bucket is empty" in {
    // 1 request per minute, burst 1
    val middleware = new RateLimitingMiddleware(1, 1)
    val client     = middleware.wrap(new NoOpClient)

    // First request consumes the only token
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))

    // Second request should fail immediately (bucket empty)
    val result = client.complete(Conversation(Seq.empty))
    (result should be).a(Symbol("isLeft"))
    result.swap.getOrElse(fail("Expected Left")).shouldBe(a[RateLimitError])
  }

  it should "refill tokens over time" in {
    // 600 RPM = 10 requests per second = 1 token every 100ms
    val startTime  = System.nanoTime()
    var now        = startTime
    val timeSource = () => now

    val middleware = new RateLimitingMiddleware(600, 1, timeSource) // burst 1
    val client     = middleware.wrap(new NoOpClient)

    // Consume 1
    client.complete(Conversation(Seq.empty))

    // Immediate next should fail
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isLeft"))

    // Advance time by 150ms (in nanos)
    now = startTime + 150_000_000L

    // Should succeed now
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))
  }

  it should "record a metrics error on local rejection" in {
    val metrics    = new TestMetricsCollector
    val middleware = new RateLimitingMiddleware(1, 1, metrics = Some(metrics), providerName = "test-provider")
    val client     = middleware.wrap(new NoOpClient)

    client.complete(Conversation(Seq.empty))
    metrics.recordedErrors.toList shouldBe empty

    val result = client.complete(Conversation(Seq.empty))
    metrics.recordedErrors.toList shouldBe List(ErrorKind.RateLimit -> "test-provider")

    // The provider field must carry the real provider name, not the message text,
    // and the error must be tagged as locally-throttled so a wrapping ReliableClient
    // can recognize this metrics event was already recorded and avoid double-counting it.
    result.swap.getOrElse(fail("Expected Left")) match {
      case rle: RateLimitError =>
        rle.provider shouldBe "test-provider"
        rle.origin shouldBe RateLimitOrigin.LocalThrottle
      case other => fail(s"Expected RateLimitError, got $other")
    }
  }

  it should "allow at most burstCapacity successes under concurrent contention" in {
    // requestsPerMinute = 0 disables refill entirely, so the bucket can only
    // ever hand out exactly `burst` tokens no matter how many callers race for them.
    val burst      = 20
    val middleware = new RateLimitingMiddleware(0, burst)
    val client     = middleware.wrap(new NoOpClient)

    val pool               = Executors.newFixedThreadPool(40)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    val successCount       = new AtomicInteger(0)
    val rejectionCount     = new AtomicInteger(0)
    try {
      val futures = List.fill(200)(Future {
        client.complete(Conversation(Seq.empty)) match {
          case Right(_) => successCount.incrementAndGet()
          case Left(_)  => rejectionCount.incrementAndGet()
        }
      })
      Await.result(Future.sequence(futures), 10.seconds)
    } finally pool.shutdown()

    successCount.get() shouldBe burst
    rejectionCount.get() shouldBe (200 - burst)
  }
}
