package org.llm4s.reliability

import org.llm4s.error.{ RateLimitError, TimeoutError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
import java.time.Instant

class ReliableClientRateLimitSpec extends AnyFlatSpec with Matchers {

  private val conversation = Conversation(List(UserMessage("hello")))

  class CountingClient(result: => Result[Completion]) extends LLMClient {
    val callCount = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount.incrementAndGet()
      result
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def ok: Result[Completion] = Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))

  private def timingOut: Result[Completion] = Left(TimeoutError("slow", 1.second, "complete"))

  private val noRetry = RetryPolicy.exponentialBackoff(maxAttempts = 1, baseDelay = 1.millis)

  "ReliableClient" should "apply the configured rate limit" in {
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(noRetry)
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isRight shouldBe true
    client.complete(conversation).left.toOption.get shouldBe a[RateLimitError]
    underlying.callCount.get() shouldBe 1
  }

  it should "consult the rate limiter on every retry, not just the first attempt" in {
    // Burst of 1 with no refill: the first attempt drains the bucket, so every retry after it
    // is rejected before it reaches the underlying client.
    val underlying = new CountingClient(timingOut)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isLeft shouldBe true
    underlying.callCount.get() shouldBe 1
  }

  it should "reach the underlying client on every attempt when rate limiting is disabled" in {
    val underlying = new CountingClient(timingOut)
    val config = ReliabilityConfig.default
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isLeft shouldBe true
    underlying.callCount.get() shouldBe 3
  }

  it should "rate limit streaming calls too" in {
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(noRetry)
    val client = new ReliableClient(underlying, "test-provider", config)

    client.streamComplete(conversation, CompletionOptions(), _ => ()).isRight shouldBe true
    client.streamComplete(conversation, CompletionOptions(), _ => ()).isLeft shouldBe true
    underlying.callCount.get() shouldBe 1
  }

  it should "record a rate-limit metric when it rejects a call" in {
    val errors = mutable.Buffer.empty[(ErrorKind, String)]
    val metrics = new MetricsCollector {
      override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
        ()
      override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()
      override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = ()
      override def recordError(kind: ErrorKind, provider: String): Unit = errors += (kind -> provider)
    }
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(noRetry)
    val client = new ReliableClient(new CountingClient(ok), "test-provider", config, Some(metrics))

    client.complete(conversation)
    client.complete(conversation)
    // Recorded once, by the limiter; the terminal-error metric skips a local throttle
    errors.toList shouldBe List(ErrorKind.RateLimit -> "test-provider")
  }

  it should "not rate limit when reliability is disabled" in {
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .disabled
    val client = new ReliableClient(underlying, "test-provider", config)

    (1 to 3).foreach(_ => client.complete(conversation).isRight shouldBe true)
    underlying.callCount.get() shouldBe 3
  }

  it should "not count local throttles as provider failures for the circuit breaker" in {
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(noRetry)
      .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 2))
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isRight shouldBe true
    (1 to 5).foreach(_ => client.complete(conversation).left.toOption.get shouldBe a[RateLimitError])
    client.currentCircuitState shouldBe CircuitState.Closed
  }

  it should "release a half-open probe when the probe is throttled locally" in {
    // One token: the first call spends it and fails upstream, opening the circuit. After the
    // recovery timeout every call is a half-open probe that the empty bucket rejects locally;
    // each must give the probe back, or the next would fail as "probe already in progress".
    var now        = 0L
    val underlying = new CountingClient(timingOut)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(noRetry)
      .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, recoveryTimeout = 1.second))
    val client = new ReliableClient(underlying, "test-provider", config, None, () => Instant.ofEpochMilli(now))

    client.complete(conversation).isLeft shouldBe true
    client.currentCircuitState shouldBe CircuitState.Open

    now = 2000L
    client.complete(conversation).left.toOption.get shouldBe a[RateLimitError]
    client.currentCircuitState shouldBe CircuitState.HalfOpen
    client.complete(conversation).left.toOption.get shouldBe a[RateLimitError]
    underlying.callCount.get() shouldBe 1
  }

  it should "retry a local throttle when the next token arrives, not after a fixed backoff" in {
    // 600 RPM: the next token is ~100ms away. A 30s provider-style backoff would blow the bound.
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 600, burstCapacity = 1))
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
      .withoutDeadline
    val waits = scala.collection.mutable.Buffer.empty[Duration]
    val client = new ReliableClient(
      underlying,
      "test-provider",
      config,
      sleep = delay => { waits += delay; Thread.sleep(delay.toMillis) }
    )

    client.complete(conversation).isRight shouldBe true
    // The bucket is now empty: the client waits for the next token (~100ms, rounded up to whole
    // milliseconds), not the 30s a RateLimitError otherwise suggests, and then succeeds.
    client.complete(conversation).isRight shouldBe true
    waits should not be empty
    all(waits.map(_.toMillis)) should ((be > 0L).and(be <= 100L))
    underlying.callCount.get() shouldBe 2
  }

  it should "not retry a local throttle when the bucket never refills" in {
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
      .withoutDeadline
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isRight shouldBe true
    val start = System.nanoTime()
    client.complete(conversation).left.toOption.get shouldBe a[RateLimitError]
    (System.nanoTime() - start).nanos should be < 1.second
  }

  it should "report a local throttle cut off by the deadline as a local throttle, not a provider timeout" in {
    // 60 RPM: the next token is ~1s away, past the 200ms deadline. The call must come back as
    // the local throttle - a TimeoutError would count against the provider's circuit.
    val underlying = new CountingClient(ok)
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = 1))
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
      .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))
      .withDeadline(200.millis)
    val client = new ReliableClient(underlying, "test-provider", config)

    client.complete(conversation).isRight shouldBe true
    client.complete(conversation).left.toOption.get shouldBe a[RateLimitError]
    client.currentCircuitState shouldBe CircuitState.Closed
  }

  private def interruptedWhileWaitingForToken(
    config: ReliabilityConfig
  ): (Result[Completion], Boolean, CircuitState) = {
    val underlying = new CountingClient(ok)
    // The wait for the next token is interrupted.
    val client = new ReliableClient(
      underlying,
      "test-provider",
      config,
      sleep = _ => throw new InterruptedException("cancelled")
    )
    client.complete(conversation).isRight shouldBe true // spend the only token

    val result      = client.complete(conversation)
    val interrupted = Thread.interrupted() // reads and clears the flag, so it can't leak
    underlying.callCount.get() shouldBe 1
    (result, interrupted, client.currentCircuitState)
  }

  it should "report an interrupted wait to retry a provider error as an interruption" in {
    val underlying = new CountingClient(timingOut)
    val config = ReliabilityConfig.default
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
      .withoutDeadline
    val client = new ReliableClient(
      underlying,
      "test-provider",
      config,
      sleep = _ => throw new InterruptedException("cancelled")
    )
    val result = client.complete(conversation)
    Thread.interrupted() shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.CancelledError]
  }

  private def waitsForToken = ReliabilityConfig.default
    .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = 1))
    .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
    .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))

  it should "report an interrupted wait for a local token as a cancellation, out of the circuit, keeping the interrupt" in {
    val (result, interrupted, state) = interruptedWhileWaitingForToken(waitsForToken.withoutDeadline)
    result.left.toOption.get shouldBe a[org.llm4s.error.CancelledError]
    interrupted shouldBe true
    state shouldBe CircuitState.Closed
  }

  it should "do the same under a deadline" in {
    val (result, interrupted, state) = interruptedWhileWaitingForToken(waitsForToken.withDeadline(30.seconds))
    result.left.toOption.get shouldBe a[org.llm4s.error.CancelledError]
    interrupted shouldBe true
    state shouldBe CircuitState.Closed
  }

  // One token, so the first attempt reaches the provider and fails, and its retry is throttled
  // locally. The call ends as a local throttle, but the provider did fail: that must count.
  private def providerFailsThenThrottled(requestsPerMinute: Int) = ReliabilityConfig.default
    .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = requestsPerMinute, burstCapacity = 1))
    .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
    .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))

  private def circuitAfterProviderFailure(
    config: ReliabilityConfig,
    sleep: Duration => Unit = _ => (),
    cancelled: Boolean = false
  ) = {
    val underlying = new CountingClient(timingOut)
    val client     = new ReliableClient(underlying, "test-provider", config, sleep = sleep)
    val result     = client.complete(conversation)
    underlying.callCount.get() shouldBe 1
    if (cancelled) result.left.toOption.get shouldBe a[org.llm4s.error.CancelledError]
    else result.left.toOption.get shouldBe a[RateLimitError]
    client.currentCircuitState
  }

  it should "count a provider failure whose retry is then throttled locally" in {
    circuitAfterProviderFailure(providerFailsThenThrottled(0).withoutDeadline) shouldBe CircuitState.Open
  }

  it should "count it when the deadline then cuts off the wait for a token" in {
    // The next token is ~1s away, past the 200ms deadline
    circuitAfterProviderFailure(providerFailsThenThrottled(60).withDeadline(200.millis)) shouldBe CircuitState.Open
  }

  it should "count it when the wait for a token is then interrupted" in {
    // The provider backoff (1ms) sleeps; the ~1s wait for a token is interrupted
    val state = circuitAfterProviderFailure(
      providerFailsThenThrottled(60).withoutDeadline,
      sleep = delay => if (delay > 100.millis) throw new InterruptedException("cancelled"),
      cancelled = true
    )
    Thread.interrupted() shouldBe true
    state shouldBe CircuitState.Open
  }
}
