package org.llm4s.reliability

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Completion, CompletionOptions, StreamedChunk }
import org.llm4s.types.Result
import org.llm4s.error._
import org.llm4s.metrics.{ MetricsCollector, ErrorKind }

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference }

/**
 * Wrapper that adds reliability features to any LLMClient.
 *
 * Provides:
 * - Retry with configurable policies (exponential backoff, linear, fixed)
 * - Circuit breaker to fail fast when service is down
 * - Deadline enforcement to prevent hanging operations
 * - Metrics tracking for retry attempts and circuit breaker state
 *
 * Thread-safety: Uses AtomicInteger/AtomicReference for circuit breaker state management
 * to ensure correct behavior under concurrent access.
 *
 * @param underlying The client to wrap
 * @param providerName Explicit provider name for stable metrics labels
 * @param config Reliability configuration
 * @param collector Optional metrics collector for observability
 */
final class ReliableClient(
  underlying: LLMClient,
  providerName: String,
  config: ReliabilityConfig,
  collector: Option[MetricsCollector] = None
) extends LLMClient {

  // Circuit breaker state (thread-safe via atomic references)
  private val circuitState    = new AtomicReference[CircuitState](CircuitState.Closed)
  private val failureCount    = new AtomicInteger(0)
  private val successCount    = new AtomicInteger(0)
  private val lastFailureTime = new AtomicLong(0L)
  // Probe permit: only one request at a time passes through in HalfOpen state
  private val probePermit = new java.util.concurrent.atomic.AtomicBoolean(false)

  // LLMClient interface methods
  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.complete(conversation, options)
    } else {
      executeWithReliability(() => underlying.complete(conversation, options))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.streamComplete(conversation, options, onChunk)
    } else {
      executeWithReliability(() => underlying.streamComplete(conversation, options, onChunk))
    }

  override def getContextWindow(): Int     = underlying.getContextWindow()
  override def getReserveCompletion(): Int = underlying.getReserveCompletion()
  override def validate(): Result[Unit]    = underlying.validate()
  override def close(): Unit               = underlying.close()

  /**
   * Execute operation with retry, circuit breaker, and deadline.
   */
  private def executeWithReliability[A](operation: () => Result[A]): Result[A] = {
    // Check circuit breaker state
    checkCircuitBreaker() match {
      case Left(error) =>
        collector.foreach(_.recordError(ErrorKind.ServiceError, providerName))
        return Left(error)
      case Right(_) => // Continue
    }

    // Apply deadline if configured
    val result = config.deadline match {
      case Some(deadline) =>
        executeWithDeadlineAndRetry(operation, deadline)
      case None =>
        executeWithRetry(operation, attemptNumber = 1)
    }

    // Update circuit breaker state based on result
    result match {
      case Right(_) =>
        onSuccess()
      case Left(_) =>
        onFailure()
    }

    result
  }

  /**
   * Execute operation with deadline enforcement and retry logic combined.
   * Single retry loop that checks deadline before each attempt.
   */
  private def executeWithDeadlineAndRetry[A](operation: () => Result[A], deadline: Duration): Result[A] = {
    val startTime  = System.currentTimeMillis()
    val deadlineMs = startTime + deadline.toMillis

    @tailrec
    def loop(attemptNumber: Int, lastError: Option[LLMError]): Result[A] = {
      val remainingTime = deadlineMs - System.currentTimeMillis()

      // Check if deadline already exceeded
      if (remainingTime <= 0) {
        collector.foreach(_.recordError(ErrorKind.Timeout, providerName))
        return Left(
          TimeoutError(
            message = lastError match {
              case Some(err) =>
                s"Operation exceeded deadline of ${deadline.toSeconds}s after $attemptNumber attempts. Last error: ${err.message}"
              case None => s"Operation exceeded deadline of ${deadline.toSeconds}s before first attempt"
            },
            timeoutDuration = deadline,
            operation = "reliable-client.complete"
          )
        )
      }

      // Execute operation with interruption handling
      val result =
        try
          operation()
        catch {
          case _: InterruptedException =>
            Left(
              TimeoutError(
                message = s"Operation interrupted after $attemptNumber attempts",
                timeoutDuration = deadline,
                operation = "reliable-client.complete"
              )
            )
        }

      result match {
        case success @ Right(_) =>
          success

        case Left(error) if attemptNumber < config.retryPolicy.maxAttempts && config.retryPolicy.isRetryable(error) =>
          // Record retry attempt
          collector.foreach(_.recordRetryAttempt(providerName, attemptNumber))

          // Calculate delay and check if we have time (recompute after operation to avoid stale value)
          val delay               = config.retryPolicy.delayFor(attemptNumber, error)
          val remainingAfterDelay = (deadlineMs - System.currentTimeMillis()) - delay.toMillis

          if (remainingAfterDelay <= 0) {
            // Not enough time for retry
            collector.foreach(_.recordError(ErrorKind.Timeout, providerName))
            Left(
              TimeoutError(
                message =
                  s"Operation exceeded deadline of ${deadline.toSeconds}s after $attemptNumber attempts. Last error: ${error.message}",
                timeoutDuration = deadline,
                operation = "reliable-client.complete"
              )
            )
          } else {
            // Sleep and retry
            try
              Thread.sleep(delay.toMillis)
            catch {
              case _: InterruptedException =>
                return Left(
                  TimeoutError(
                    message = s"Operation interrupted during retry delay after $attemptNumber attempts",
                    timeoutDuration = deadline,
                    operation = "reliable-client.complete"
                  )
                )
            }
            loop(attemptNumber + 1, Some(error))
          }

        case Left(error) =>
          // Max attempts reached or non-retryable error
          if (attemptNumber > 1) {
            // Preserve original error type, add context via collector
            collector.foreach(_.recordError(ErrorKind.fromLLMError(error), providerName))
          }
          Left(error)
      }
    }

    loop(1, None)
  }

  /**
   * Execute operation with retry logic (no deadline).
   */
  @tailrec
  private def executeWithRetry[A](
    operation: () => Result[A],
    attemptNumber: Int
  ): Result[A] = {
    val result =
      try
        operation()
      catch {
        case _: InterruptedException =>
          Left(
            ExecutionError(
              message = s"Operation interrupted after $attemptNumber attempts",
              operation = "reliable-client.complete"
            )
          )
      }

    result match {
      case success @ Right(_) =>
        success

      case Left(error) if attemptNumber < config.retryPolicy.maxAttempts && config.retryPolicy.isRetryable(error) =>
        // Record retry attempt
        collector.foreach(_.recordRetryAttempt(providerName, attemptNumber))

        // Calculate delay
        val delay = config.retryPolicy.delayFor(attemptNumber, error)
        try
          Thread.sleep(delay.toMillis)
        catch {
          case _: InterruptedException =>
            return Left(
              ExecutionError(
                message = s"Operation interrupted during retry delay after $attemptNumber attempts",
                operation = "reliable-client.complete"
              )
            )
        }

        // Retry
        executeWithRetry(operation, attemptNumber + 1)

      case Left(error) =>
        // Max attempts reached or non-retryable error - preserve original error
        if (attemptNumber > 1) {
          collector.foreach(_.recordError(ErrorKind.fromLLMError(error), providerName))
        }
        Left(error)
    }
  }

  /**
   * Check circuit breaker state and transition if needed.
   */
  private def checkCircuitBreaker(): Result[Unit] =
    circuitState.get() match {
      case CircuitState.Closed =>
        Right(())

      case CircuitState.Open =>
        val now = System.currentTimeMillis()
        if ((now - lastFailureTime.get()) > config.circuitBreaker.recoveryTimeout.toMillis) {
          // Transition to half-open
          if (circuitState.compareAndSet(CircuitState.Open, CircuitState.HalfOpen)) {
            successCount.set(0)
            probePermit.set(false)
            collector.foreach(_.recordCircuitBreakerTransition(providerName, "half-open"))
          }
          Right(())
        } else {
          // Stay open
          Left(
            ServiceError(
              httpStatus = 503,
              provider = "circuit-breaker",
              details = "Circuit breaker is open - service appears to be down"
            )
          )
        }

      case CircuitState.HalfOpen =>
        // Only allow one probe request through at a time to avoid flooding a recovering service
        if (probePermit.compareAndSet(false, true))
          Right(())
        else
          Left(
            ServiceError(
              httpStatus = 503,
              provider = "circuit-breaker",
              details = "Circuit breaker is half-open - service probe already in progress"
            )
          )
    }

  /**
   * Handle successful operation.
   */
  private def onSuccess(): Unit =
    circuitState.get() match {
      case CircuitState.Closed =>
        // Reset failure count
        failureCount.set(0)

      case CircuitState.HalfOpen =>
        // Track successes in half-open state
        val newSuccessCount = successCount.incrementAndGet()
        if (newSuccessCount >= config.circuitBreaker.successThreshold) {
          // Close circuit
          if (circuitState.compareAndSet(CircuitState.HalfOpen, CircuitState.Closed)) {
            failureCount.set(0)
            successCount.set(0)
            probePermit.set(false)
            collector.foreach(_.recordCircuitBreakerTransition(providerName, "closed"))
          }
        } else {
          // Need more successes — release probe permit to allow next probe
          probePermit.set(false)
        }

      case CircuitState.Open =>
      // Should not happen
    }

  /**
   * Handle failed operation.
   */
  private def onFailure(): Unit = {
    lastFailureTime.set(System.currentTimeMillis())

    circuitState.get() match {
      case CircuitState.Closed =>
        // Track failures
        val newFailureCount = failureCount.incrementAndGet()
        if (newFailureCount >= config.circuitBreaker.failureThreshold) {
          // Open circuit
          if (circuitState.compareAndSet(CircuitState.Closed, CircuitState.Open)) {
            collector.foreach(_.recordCircuitBreakerTransition(providerName, "open"))
          }
        }

      case CircuitState.HalfOpen =>
        // Single failure in half-open → back to open
        if (circuitState.compareAndSet(CircuitState.HalfOpen, CircuitState.Open)) {
          successCount.set(0)
          probePermit.set(false)
          collector.foreach(_.recordCircuitBreakerTransition(providerName, "open"))
        }

      case CircuitState.Open =>
      // Already open
    }
  }

  /**
   * Get current circuit breaker state (for testing/monitoring).
   */
  def currentCircuitState: CircuitState = circuitState.get()

  /**
   * Reset circuit breaker state (for testing).
   */
  def resetCircuitBreaker(): Unit = {
    circuitState.set(CircuitState.Closed)
    failureCount.set(0)
    successCount.set(0)
    lastFailureTime.set(0L)
    probePermit.set(false)
  }
}

object ReliableClient {

  /**
   * Wrap a client with default reliability configuration.
   * Provider name derived from client class name (use withProviderName for custom).
   */
  def apply(client: LLMClient): ReliableClient = {
    val providerName = client.getClass.getSimpleName.replace("Client", "").toLowerCase
    new ReliableClient(client, providerName, ReliabilityConfig.default, None)
  }

  /**
   * Wrap a client with custom reliability configuration.
   * Provider name derived from client class name (use withProviderName for custom).
   */
  def apply(client: LLMClient, config: ReliabilityConfig): ReliableClient = {
    val providerName = client.getClass.getSimpleName.replace("Client", "").toLowerCase
    new ReliableClient(client, providerName, config, None)
  }

  /**
   * Wrap a client with reliability + metrics.
   * Provider name derived from client class name (use withProviderName for custom).
   */
  def apply(client: LLMClient, config: ReliabilityConfig, collector: MetricsCollector): ReliableClient = {
    val providerName = client.getClass.getSimpleName.replace("Client", "").toLowerCase
    new ReliableClient(client, providerName, config, Some(collector))
  }

  /**
   * Wrap a client with explicit provider name (recommended for production).
   */
  def withProviderName(
    client: LLMClient,
    providerName: String,
    config: ReliabilityConfig = ReliabilityConfig.default,
    collector: Option[MetricsCollector] = None
  ): ReliableClient =
    new ReliableClient(client, providerName, config, collector)
}

/**
 * Circuit breaker state.
 */
sealed trait CircuitState
object CircuitState {
  case object Closed   extends CircuitState // Normal operation
  case object Open     extends CircuitState // Failing fast
  case object HalfOpen extends CircuitState // Testing recovery
}
