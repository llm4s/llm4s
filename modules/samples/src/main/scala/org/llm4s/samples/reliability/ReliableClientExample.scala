package org.llm4s.samples.reliability

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model._
import org.llm4s.reliability._
import org.llm4s.types.Result
import org.llm4s.error._
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

/**
 * Example demonstrating how to configure and use ReliableClient.
 *
 * ReliableClient adds resilience to any base LLMClient, providing:
 * 1. Retry Policies (Exponential Backoff, Linear, Fixed)
 * 2. Circuit Breakers to fail-fast when a provider is down
 * 3. Deadline Enforcement to terminate hanging requests
 *
 * == When to use each Configuration ==
 *
 * - **Basic Retry with Exponential Backoff:**
 *   Use this as the default for all external LLM provider calls. It handles
 *   transient network errors, DNS hiccups, and Rate Limits (429) gracefully
 *   by backing off and trying again without overloading the provider.
 *
 * - **Circuit Breaker:**
 *   Use this when calling providers that might experience prolonged outages.
 *   If a provider fails consistently (e.g., 5 times in a row), the circuit opens
 *   and fails subsequent requests immediately. This saves resources, prevents threads
 *   from waiting on a dead service, and allows the application to fallback immediately.
 *
 * - **Deadline Enforcement:**
 *   Use this to guarantee maximum response times. If an LLM call hangs or takes too
 *   long (e.g., due to slow generation or network read timeouts), the deadline will
 *   forcibly interrupt the call and fail. This is critical for keeping user-facing
 *   APIs responsive.
 *
 * Run this example:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.reliability.ReliableClientExample"
 * }}}
 */
object ReliableClientExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // Dummy conversation and completion for testing
  private val testConversation = Conversation(List(UserMessage("Hello")))
  private val dummyCompletion = Completion(
    id = "dummy-id",
    created = System.currentTimeMillis(),
    content = "Hello! I am a reliable assistant.",
    model = "mock-model",
    message = AssistantMessage(content = "Hello! I am a reliable assistant.", toolCalls = List.empty)
  )

  def main(args: Array[String]): Unit = {
    logger.info("=== Starting ReliableClient Example ===")

    demoConfiguration1()
    logger.info("")

    demoConfiguration2()
    logger.info("")

    demoConfiguration3()
    logger.info("")

    demoWithRealProvider()
  }

  /**
   * Configuration 1: Basic retry with exponential backoff.
   *
   * Demonstrates how ReliableClient retries transient errors with increasing delays
   * before succeeding.
   */
  private def demoConfiguration1(): Unit = {
    logger.info("--- Configuration 1: Basic Retry with Exponential Backoff ---")

    val retryConfig = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(
        maxAttempts = 3,
        baseDelay = 200.millis,
        maxDelay = 2.seconds
      ),
      enabled = true
    )

    // Set up a mock client that fails twice and succeeds on the third attempt
    val attemptCounter = new AtomicInteger(0)
    val mockBaseClient = new MockClient(() => {
      val attempt = attemptCounter.incrementAndGet()
      if (attempt < 3) {
        logger.info(s"Base Client attempt $attempt: Simulating RateLimitError (429)")
        Left(RateLimitError("openai-demo"))
      } else {
        logger.info(s"Base Client attempt $attempt: Simulating success")
        Right(dummyCompletion)
      }
    })

    val reliableClient = new ReliableClient(mockBaseClient, "openai-demo", retryConfig)

    logger.info("Executing completion request...")
    val result = reliableClient.complete(testConversation)

    result match {
      case Right(completion) =>
        logger.info("Result: SUCCESS")
        logger.info(s"Response content: ${completion.message.content}")
        logger.info(s"Total base client calls made: ${mockBaseClient.getCallCount}")
      case Left(error) =>
        logger.error(s"Result: FAILED - ${error.formatted}")
    }
  }

  /**
   * Configuration 2: Circuit breaker to fail fast.
   *
   * Demonstrates how the circuit breaker opens after consecutive failures,
   * causing subsequent calls to fail immediately without calling the base client.
   */
  private def demoConfiguration2(): Unit = {
    logger.info("--- Configuration 2: Circuit Breaker to Fail Fast ---")

    val circuitConfig = ReliabilityConfig(
      circuitBreaker = CircuitBreakerConfig(
        failureThreshold = 3,
        recoveryTimeout = 10.seconds,
        successThreshold = 1
      ),
      // Disable retries so we can see the individual call failures clearly
      retryPolicy = RetryPolicy.noRetry,
      enabled = true
    )

    // Set up a mock client that always fails with a 503 Service Unavailable error
    val mockBaseClient = new MockClient(() => {
      logger.info("Base Client: Executing call (and returning 503)")
      Left(ServiceError(httpStatus = 503, provider = "mock-provider", details = "Service is down"))
    })

    val reliableClient = new ReliableClient(mockBaseClient, "broken-provider", circuitConfig)

    // Call the client 4 times.
    // The first 3 calls should trigger the base client and fail.
    // The 4th call should fail immediately at the circuit breaker level (circuit open).
    for (i <- 1 to 4) {
      logger.info(s"--- Request #$i ---")
      val result = reliableClient.complete(testConversation)
      result match {
        case Right(_) =>
          logger.info("Result: SUCCESS")
        case Left(error: ServiceError) if error.provider == "circuit-breaker" =>
          logger.warn(s"Result: FAILED (Circuit Breaker Intercepted) - ${error.message}")
        case Left(error) =>
          logger.error(s"Result: FAILED (Base Error) - ${error.message}")
      }
    }

    logger.info(s"Total calls reaching the Base Client: ${mockBaseClient.getCallCount}")
  }

  /**
   * Configuration 3: Deadline enforcement.
   *
   * Demonstrates how a request is aborted if it exceeds the specified deadline.
   */
  private def demoConfiguration3(): Unit = {
    logger.info("--- Configuration 3: Deadline Enforcement ---")

    val deadlineConfig = ReliabilityConfig(
      deadline = Some(1.second),
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 600.millis),
      enabled = true
    )

    // Set up a mock client that fails with a transient error after 200ms
    val mockBaseClient = new MockClient(() => {
      logger.info("Base Client: Starting operation (sleeping 200ms)...")
      Thread.sleep(200)
      Left(ServiceError(500, "slow-provider", "Transient connection failure"))
    })

    val reliableClient = new ReliableClient(mockBaseClient, "slow-provider", deadlineConfig)

    logger.info("Executing completion request with a 1-second deadline and 600ms retry delay...")
    val result = reliableClient.complete(testConversation)

    result match {
      case Right(_) =>
        logger.info("Result: SUCCESS")
      case Left(error: TimeoutError) =>
        logger.warn(s"Result: FAILED (Timed Out) - ${error.message}")
      case Left(error) =>
        logger.error(s"Result: FAILED - ${error.formatted}")
    }
  }

  /**
   * Demonstration running with a real provider if configured.
   */
  private def demoWithRealProvider(): Unit = {
    logger.info("--- Optional: Real Provider Demonstration ---")

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      baseClient <- LLMConnect.getClient(providerCfg)
      _ = logger.info(s"Loaded configured provider: ${providerCfg.provider}")

      // Wrap with ReliabilityConfig (default: exponential backoff + circuit breaker)
      reliableClient = new ReliableClient(baseClient, providerCfg.provider.toString, ReliabilityConfig.default)

      _ = logger.info("Sending request through ReliableClient wrapped real provider...")
      completion <- reliableClient.complete(
        Conversation(List(UserMessage("Why is a circuit breaker useful in software? Describe in one short sentence.")))
      )
      _ = {
        logger.info("Result: SUCCESS")
        logger.info(s"Response: ${completion.message.content.trim}")
      }
    } yield ()

    result.fold(
      err => {
        logger.info("Real provider configuration not found or failed. Skipping real provider demo.")
        logger.info(s"Details: ${err.message}")
        logger.info("Tip: Set the default provider config in application.local.conf to test with a real LLM.")
      },
      identity
    )
  }

  /**
   * Simple Mock LLMClient wrapper for programmatic simulation.
   */
  class MockClient(behavior: () => Result[Completion]) extends LLMClient {
    private val callCount = new AtomicInteger(0)

    def getCallCount: Int = callCount.get()

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
}
