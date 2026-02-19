package org.llm4s.reliability

import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.metrics.{ ErrorKind, MetricsCollector }
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class ReliableClientTest extends AnyFunSuite with Matchers {

  // Mock LLMClient for testing
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
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  // Test metrics collector
  class TestMetricsCollector extends MetricsCollector {
    val retryAttempts             = new AtomicInteger(0)
    val circuitBreakerTransitions = scala.collection.mutable.ListBuffer[String]()
    val recordedErrors            = scala.collection.mutable.ListBuffer[ErrorKind]()

    override def observeRequest(
      provider: String,
      model: String,
      outcome: org.llm4s.metrics.Outcome,
      duration: scala.concurrent.duration.FiniteDuration
    ): Unit = ()

    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()

    override def recordCost(provider: String, model: String, costUsd: Double): Unit = ()

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

  val testConversation = Conversation(List(UserMessage("test")))
  val testCompletion = Completion(
    id = "test-id",
    created = System.currentTimeMillis(),
    content = "response",
    model = "test-model",
    message = AssistantMessage(content = "response", toolCalls = List.empty)
  )

  test("ReliableClient succeeds on first attempt without retries") {
    val mockClient = new MockClient(() => Right(testCompletion))
    val reliableClient = new ReliableClient(
      mockClient,
      "test",
      ReliabilityConfig.default,
      None
    )

    val result = reliableClient.complete(testConversation)

    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 1
  }

  test("ReliableClient retries on retryable error and succeeds") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount < 3) Left(TimeoutError("timeout", 1.second, "test"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val metrics        = new TestMetricsCollector()
    val reliableClient = new ReliableClient(mockClient, "test", config, Some(metrics))

    val result = reliableClient.complete(testConversation)

    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 3
    metrics.retryAttempts.get() shouldBe 2 // 2 retries after initial attempt
  }

  test("ReliableClient does not retry non-retryable errors") {
    val authError  = AuthenticationError("Invalid API key", "test")
    val mockClient = new MockClient(() => Left(authError))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    val result = reliableClient.complete(testConversation)

    result shouldBe Left(authError)
    mockClient.callCount.get() shouldBe 1 // No retries
  }

  test("ReliableClient does not retry 4xx ServiceError") {
    val serviceError = ServiceError(400, "test", "Bad request")
    val mockClient   = new MockClient(() => Left(serviceError))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    val result = reliableClient.complete(testConversation)

    result shouldBe Left(serviceError)
    mockClient.callCount.get() shouldBe 1 // No retries for 4xx
  }

  test("ReliableClient retries 5xx ServiceError") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount < 2) Left(ServiceError(500, "test", "Internal server error"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    val result = reliableClient.complete(testConversation)

    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 2
  }

  test("Circuit breaker opens after consecutive failures") {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val metrics        = new TestMetricsCollector()
    val reliableClient = new ReliableClient(mockClient, "test", config, Some(metrics))

    // Make 3 failing requests
    reliableClient.complete(testConversation)
    reliableClient.complete(testConversation)
    reliableClient.complete(testConversation)

    // Circuit should now be open
    reliableClient.currentCircuitState shouldBe CircuitState.Open
    metrics.circuitBreakerTransitions should contain("open")

    // Next request should fail fast without calling underlying client
    val callCountBefore = mockClient.callCount.get()
    val result          = reliableClient.complete(testConversation)

    result match {
      case Left(e: ServiceError) if e.httpStatus == 503 && e.provider == "circuit-breaker" => succeed
      case _ => fail("Expected circuit breaker error")
    }

    mockClient.callCount.get() shouldBe callCountBefore // No new call made
  }

  test("Circuit breaker transitions to half-open after recovery timeout") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount <= 2) Left(TimeoutError("timeout", 1.second, "test"))
      else Right(testCompletion) // Third attempt succeeds
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 100.millis, successThreshold = 1),
      deadline = None
    )

    val metrics        = new TestMetricsCollector()
    val reliableClient = new ReliableClient(mockClient, "test", config, Some(metrics))

    // Open the circuit
    reliableClient.complete(testConversation)
    reliableClient.complete(testConversation)
    reliableClient.currentCircuitState shouldBe CircuitState.Open

    // Wait for recovery timeout (generous margin to avoid CI flakiness)
    Thread.sleep(500)

    // State should still be Open before any request
    reliableClient.currentCircuitState shouldBe CircuitState.Open

    // Next request transitions to half-open and succeeds
    val result = reliableClient.complete(testConversation)
    result.isRight shouldBe true // Succeeds

    // State should have transitioned through HalfOpen
    metrics.circuitBreakerTransitions should contain("half-open")
  }

  test("Circuit breaker closes after successful request in half-open state") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount <= 2) Left(TimeoutError("timeout", 1.second, "test"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 100.millis, successThreshold = 1),
      deadline = None
    )

    val metrics        = new TestMetricsCollector()
    val reliableClient = new ReliableClient(mockClient, "test", config, Some(metrics))

    // Open the circuit
    reliableClient.complete(testConversation)
    reliableClient.complete(testConversation)
    reliableClient.currentCircuitState shouldBe CircuitState.Open

    // Wait and make successful request (generous margin to avoid CI flakiness)
    Thread.sleep(500)
    val result = reliableClient.complete(testConversation)

    result shouldBe Right(testCompletion)
    reliableClient.currentCircuitState shouldBe CircuitState.Closed
    metrics.circuitBreakerTransitions should contain("closed")
  }

  test("Half-open probe permit is released after each successful probe") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount <= 2) Left(TimeoutError("timeout", 1.second, "test"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 100.millis, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    // Open the circuit
    reliableClient.complete(testConversation) // fail #1
    reliableClient.complete(testConversation) // fail #2
    reliableClient.currentCircuitState shouldBe CircuitState.Open

    // Wait for recovery (generous margin to avoid CI flakiness)
    Thread.sleep(500)

    // First probe in HalfOpen: permit acquired, succeeds, permit released (needs 2 successes total)
    val probe1 = reliableClient.complete(testConversation)
    probe1 shouldBe Right(testCompletion)
    reliableClient.currentCircuitState shouldBe CircuitState.HalfOpen

    // Second probe: permit was released after first success, this probe also passes through and closes circuit
    val probe2 = reliableClient.complete(testConversation)
    probe2 shouldBe Right(testCompletion)
    reliableClient.currentCircuitState shouldBe CircuitState.Closed
  }

  test("Deadline exceeded before first attempt returns TimeoutError") {
    val mockClient = new MockClient(() => Right(testCompletion))

    // Use a zero deadline so the deadline is already at the start time, expiring immediately
    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 10.millis),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = Some(0.millis)
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)
    val result         = reliableClient.complete(testConversation)

    result match {
      case Left(_: TimeoutError) => succeed
      case _                     => fail("Expected TimeoutError for already-expired deadline")
    }
    // Should not have called underlying at all
    mockClient.callCount.get() shouldBe 0
  }

  test("Custom retry policy does not retry 4xx ServiceError by default") {
    val serviceError = ServiceError(400, "test", "Bad request")
    val mockClient   = new MockClient(() => Left(serviceError))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.custom(
        attempts = 3,
        delayFn = (_, _) => 10.millis
        // uses default retryableFn which should NOT retry 4xx
      ),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)
    val result         = reliableClient.complete(testConversation)

    result shouldBe Left(serviceError)
    mockClient.callCount.get() shouldBe 1 // No retries for 4xx
  }

  test("Custom retry policy retries 5xx ServiceError by default") {
    var attemptCount = 0
    val mockClient = new MockClient(() => {
      attemptCount += 1
      if (attemptCount < 2) Left(ServiceError(500, "test", "Internal server error"))
      else Right(testCompletion)
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.custom(
        attempts = 3,
        delayFn = (_, _) => 10.millis
        // uses default retryableFn which should retry 5xx
      ),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)
    val result         = reliableClient.complete(testConversation)

    result shouldBe Right(testCompletion)
    mockClient.callCount.get() shouldBe 2 // Retried once
  }

  test("Deadline enforcement stops retries when time limit exceeded") {
    val mockClient = new MockClient(() => {
      Thread.sleep(100) // Simulate slow operation
      Left(TimeoutError("timeout", 1.second, "test"))
    })

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 10, baseDelay = 50.millis),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = Some(300.millis)
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    val startTime = System.currentTimeMillis()
    val result    = reliableClient.complete(testConversation)
    val duration  = System.currentTimeMillis() - startTime

    result match {
      case Left(_: TimeoutError) => succeed
      case _                     => fail("Expected TimeoutError due to deadline")
    }

    duration should be < 500L // Should stop before trying all 10 attempts (with some tolerance)
    mockClient.callCount.get() should be < 10
  }

  test("Circuit breaker is thread-safe under concurrent failures") {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    // Create thread pool
    val executor                      = Executors.newFixedThreadPool(20)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      // Run 20 failing requests concurrently
      val futures = (1 to 20).map { _ =>
        Future {
          reliableClient.complete(testConversation)
        }
      }.toList

      // Wait for all futures to complete
      import scala.concurrent.Await
      import scala.concurrent.duration._
      val results = Await.result(Future.sequence(futures), 10.seconds)

      // Circuit should be open (threshold was 10)
      reliableClient.currentCircuitState shouldBe CircuitState.Open

      // Verify all 20 requests completed
      results.size shouldBe 20
      // Call count should be at least 10 (some may be rejected by open circuit)
      mockClient.callCount.get() should be >= 10

    } finally
      executor.shutdown()
  }

  test("ReliableClient with disabled config passes through directly") {
    var callCount = 0
    val mockClient = new MockClient(() => {
      callCount += 1
      Left(TimeoutError("timeout", 1.second, "test"))
    })

    val reliableClient = new ReliableClient(
      mockClient,
      "test",
      ReliabilityConfig.disabled,
      None
    )

    val result = reliableClient.complete(testConversation)

    result.isLeft shouldBe true
    callCount shouldBe 1 // Only one attempt, no retries
  }

  test("ReliableClient preserves original error type after retries") {
    val rateLimitError = RateLimitError("test", 1000)
    val mockClient     = new MockClient(() => Left(rateLimitError))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 10.millis),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 10, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val metrics        = new TestMetricsCollector()
    val reliableClient = new ReliableClient(mockClient, "test", config, Some(metrics))

    val result = reliableClient.complete(testConversation)

    // Should preserve the original RateLimitError, not wrap it
    result shouldBe Left(rateLimitError)
    mockClient.callCount.get() shouldBe 2
  }

  test("ReliableClient.resetCircuitBreaker resets state correctly") {
    val mockClient = new MockClient(() => Left(TimeoutError("timeout", 1.second, "test")))

    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.noRetry,
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 1.minute, successThreshold = 2),
      deadline = None
    )

    val reliableClient = new ReliableClient(mockClient, "test", config, None)

    // Open the circuit
    reliableClient.complete(testConversation)
    reliableClient.complete(testConversation)
    reliableClient.currentCircuitState shouldBe CircuitState.Open

    // Reset
    reliableClient.resetCircuitBreaker()
    reliableClient.currentCircuitState shouldBe CircuitState.Closed
  }
}
