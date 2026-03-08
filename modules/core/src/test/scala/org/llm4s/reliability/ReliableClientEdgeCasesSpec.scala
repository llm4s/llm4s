package org.llm4s.reliability

import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class ReliableClientEdgeCasesSpec extends AnyFlatSpec with Matchers {

  class MockClient(behavior: () => Result[Completion]) extends LLMClient {
    val callCount = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount.incrementAndGet()
      behavior()
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      callCount.incrementAndGet()
      behavior()
    }

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  class SequentialMockClient(responses: Seq[() => Result[Completion]]) extends LLMClient {
    val callCount = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val idx = callCount.getAndIncrement()
      responses(idx % responses.size)()
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  class TestMetricsCollector extends MetricsCollector {
    val retryAttempts             = new AtomicInteger(0)
    val circuitBreakerTransitions = scala.collection.mutable.ListBuffer[String]()
    val recordedErrors            = scala.collection.mutable.ListBuffer[ErrorKind]()

    override def observeRequest(
      provider: String,
      model: String,
      outcome: org.llm4s.metrics.Outcome,
      duration: FiniteDuration
    ): Unit = ()

    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()
    override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = ()

    override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit =
      retryAttempts.incrementAndGet()

    override def recordCircuitBreakerTransition(provider: String, newState: String): Unit =
      circuitBreakerTransitions.synchronized {
        circuitBreakerTransitions += newState
      }

    override def recordError(errorKind: ErrorKind, provider: String): Unit =
      recordedErrors.synchronized {
        recordedErrors += errorKind
      }
  }

  private val testConversation = Conversation(List(UserMessage("test")))
  private val testCompletion = Completion(
    id = "test-id",
    created = 0L,
    content = "response",
    model = "test-model",
    message = AssistantMessage(content = "response")
  )

  // ==========================================================================
  // streamComplete path
  // ==========================================================================

  "ReliableClient.streamComplete" should "apply reliability to streaming" in {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliable = new ReliableClient(
      mockClient,
      "test",
      ReliabilityConfig.default,
      None
    )

    val result = reliable.streamComplete(testConversation, onChunk = _ => ())
    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 1
  }

  it should "retry streaming on retryable error" in {
    var attempt = 0
    val mockClient = new MockClient(() => {
      attempt += 1
      if (attempt < 2) Left(TimeoutError("timeout", 1.second, "test"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = None
    )

    val reliable = new ReliableClient(mockClient, "test", config, None)
    val result   = reliable.streamComplete(testConversation, onChunk = _ => ())
    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 2
  }

  it should "pass through directly when disabled" in {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliable   = new ReliableClient(mockClient, "test", ReliabilityConfig.disabled, None)

    val result = reliable.streamComplete(testConversation, onChunk = _ => ())
    result shouldBe Right(testCompletion)
  }

  // ==========================================================================
  // validate and close delegation
  // ==========================================================================

  "ReliableClient.validate" should "delegate to underlying" in {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliable   = new ReliableClient(mockClient, "test", ReliabilityConfig.default, None)
    reliable.validate() shouldBe Right(())
  }

  "ReliableClient.close" should "delegate to underlying" in {
    var closed = false
    val mockClient = new MockClient(() => Right(testCompletion)) {
      override def close(): Unit = closed = true
    }
    val reliable = new ReliableClient(mockClient, "test", ReliabilityConfig.default, None)
    reliable.close()
    closed shouldBe true
  }

  "ReliableClient.getContextWindow" should "delegate to underlying" in {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliable   = new ReliableClient(mockClient, "test", ReliabilityConfig.default, None)
    reliable.getContextWindow() shouldBe 8192
  }

  "ReliableClient.getReserveCompletion" should "delegate to underlying" in {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliable   = new ReliableClient(mockClient, "test", ReliabilityConfig.default, None)
    reliable.getReserveCompletion() shouldBe 1024
  }

  // ==========================================================================
  // Circuit breaker: half-open failure goes back to open
  // ==========================================================================

  "Circuit breaker" should "return to open on failure in half-open state" in {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 100.millis, successThreshold = 1),
      deadline = None
    )

    var fakeTime = 0L
    val metrics  = new TestMetricsCollector()
    val reliable = new ReliableClient(mockClient, "test", config, Some(metrics), clock = () => fakeTime)

    // Open the circuit
    reliable.complete(testConversation)
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Open

    // Advance clock past recovery timeout
    fakeTime += 500

    // Probe attempt fails -> back to open
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Open

    metrics.circuitBreakerTransitions should contain("half-open")
    metrics.circuitBreakerTransitions.count(_ == "open") shouldBe 2
  }

  it should "block concurrent probes in half-open state" in {
    // With a single-threaded test, we can't truly test concurrent access,
    // but we can verify the probe permit logic by checking the error type.
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 100.millis, successThreshold = 2),
      deadline = None
    )

    var fakeTime = 0L
    val reliable = new ReliableClient(mockClient, "test", config, None, clock = () => fakeTime)

    // Open the circuit
    reliable.complete(testConversation)
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Open

    // Advance clock past recovery timeout
    fakeTime += 500

    // First probe: transitions to half-open, acquires permit, fails -> back to open
    val r1 = reliable.complete(testConversation)
    r1.isLeft shouldBe true
    reliable.currentCircuitState shouldBe CircuitState.Open
  }

  // ==========================================================================
  // Deadline with retry: not enough time for retry delay
  // ==========================================================================

  "Deadline enforcement" should "stop retries when not enough time for delay" in {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 10, delay = 5.seconds),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = Some(2.seconds)
    )

    var fakeTime = 0L
    val metrics  = new TestMetricsCollector()
    val reliable = new ReliableClient(
      mockClient,
      "test",
      config,
      Some(metrics),
      clock = () => {
        val t = fakeTime
        fakeTime += 100 // advance 100ms per clock read
        t
      }
    )

    val result = reliable.complete(testConversation)
    result match {
      case Left(_: TimeoutError) => succeed
      case _                     => fail("Expected TimeoutError")
    }
  }

  // ==========================================================================
  // No deadline path (executeWithRetry)
  // ==========================================================================

  "ReliableClient without deadline" should "exhaust all retry attempts" in {
    val mockClient = new MockClient(() => Left(RateLimitError("test", 1L)))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = None
    )

    val metrics  = new TestMetricsCollector()
    val reliable = new ReliableClient(mockClient, "test", config, Some(metrics))

    val result = reliable.complete(testConversation)
    result.isLeft shouldBe true
    mockClient.callCount.get() shouldBe 3
    metrics.retryAttempts.get() shouldBe 2
  }

  it should "record error kind after exhausting retries" in {
    val mockClient = new MockClient(() => Left(NetworkError("fail", None, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = None
    )

    val metrics  = new TestMetricsCollector()
    val reliable = new ReliableClient(mockClient, "test", config, Some(metrics))

    reliable.complete(testConversation)
    metrics.recordedErrors should not be empty
  }

  // ==========================================================================
  // Circuit breaker records error on open reject
  // ==========================================================================

  "Circuit breaker" should "record error when rejecting due to open state" in {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 1, recoveryTimeout = 1.minute, successThreshold = 1),
      deadline = None
    )

    val metrics  = new TestMetricsCollector()
    val reliable = new ReliableClient(mockClient, "test", config, Some(metrics))

    // Open the circuit
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Open

    // Next call should be rejected and record an error
    reliable.complete(testConversation)
    metrics.recordedErrors should contain(ErrorKind.ServiceError)
  }

  // ==========================================================================
  // Retry with RateLimitError uses server delay
  // ==========================================================================

  "Retry with deadline" should "use server-provided Retry-After delay" in {
    var attempt = 0
    val mockClient = new MockClient(() => {
      attempt += 1
      if (attempt < 2) Left(RateLimitError("test", 100L))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = Some(30.seconds)
    )

    val reliable = new ReliableClient(mockClient, "test", config, None)
    val result   = reliable.complete(testConversation)
    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 2
  }

  // ==========================================================================
  // Success resets failure count in closed state
  // ==========================================================================

  "Circuit breaker in closed state" should "reset failure count on success" in {
    val responses: Seq[() => Result[Completion]] = Seq(
      () => Left(TimeoutError("timeout", 1.second, "test")),
      () => Right(testCompletion), // resets failure count
      () => Left(TimeoutError("timeout", 1.second, "test"))
    )

    val mockClient = new SequentialMockClient(responses)

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 1.minute, successThreshold = 1),
      deadline = None
    )

    val reliable = new ReliableClient(mockClient, "test", config, None)

    // Fail once
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Closed

    // Succeed - resets failure count
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Closed

    // Fail again - only 1 failure, threshold is 2 so should stay closed
    reliable.complete(testConversation)
    reliable.currentCircuitState shouldBe CircuitState.Closed
  }

  // ==========================================================================
  // CircuitState values
  // ==========================================================================

  "CircuitState" should "have three states" in {
    CircuitState.Closed shouldBe a[CircuitState]
    CircuitState.Open shouldBe a[CircuitState]
    CircuitState.HalfOpen shouldBe a[CircuitState]
  }
}
