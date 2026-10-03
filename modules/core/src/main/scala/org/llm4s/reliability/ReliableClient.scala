// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.reliability

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Completion, CompletionOptions, StreamedChunk }
import org.llm4s.types.Result
import org.llm4s.error._
import org.llm4s.metrics.{ MetricsCollector, ErrorKind }

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference }

/**
 * Wrapper that adds reliability features to any LLMClient.
 *
 * Provides:
 * - Retry with configurable policies (exponential backoff, linear, fixed)
 * - Circuit breaker to fail fast when service is down
 * - Deadline enforcement to prevent hanging operations
 * - Local token-bucket rate limiting ([[RateLimitConfig]]), checked before every attempt
 * - Metrics tracking for retry attempts and circuit breaker state
 *
 * Thread-safety: Uses AtomicInteger/AtomicReference for circuit breaker state management
 * to ensure correct behavior under concurrent access.
 *
 * @param underlying The client to wrap
 * @param providerName Explicit provider name for stable metrics labels
 * @param config Reliability configuration
 * @param collector Optional metrics collector for observability
 * @param clock the current time, read for deadlines and circuit-breaker recovery; injectable for tests
 * @param sleep how the client waits between attempts; injectable so a test can record the
 *              delays chosen, or throw `InterruptedException` to simulate an interrupted wait
 */
final class ReliableClient(
  underlying: LLMClient,
  providerName: String,
  config: ReliabilityConfig,
  collector: Option[MetricsCollector] = None,
  clock: () => Instant = () => Instant.now(),
  sleep: FiniteDuration => Unit = delay => Thread.sleep(delay.toMillis)
) extends LLMClient {

  private val cancelledOperation = "reliable-client.complete"

  // Deadline and circuit-breaker arithmetic is in epoch milliseconds internally
  private def nowMillis(): Long = clock().toEpochMilli

  // Local rate limit, consulted on every attempt (retries included) before the call is made
  private val rateLimiter: Option[TokenBucket] =
    Option.when(config.rateLimit.enabled)(
      new TokenBucket(config.rateLimit.requestsPerMinute, config.rateLimit.burstCapacity)
    )

  private def rateLimited[A](operation: () => Result[A]): () => Result[A] =
    rateLimiter match {
      case None => operation
      case Some(bucket) =>
        () =>
          if (bucket.tryAcquire()) operation()
          else {
            collector.foreach(_.recordError(ErrorKind.RateLimit, providerName))
            Left(RateLimitError.local(providerName))
          }
    }

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
      executeWithReliability(rateLimited(() => underlying.complete(conversation, options)))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.streamComplete(conversation, options, onChunk)
    } else {
      executeWithReliability(rateLimited(() => underlying.streamComplete(conversation, options, onChunk)))
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

    // Whether any attempt reached the provider and failed. The final result alone cannot say:
    // a provider failure whose retry is then throttled locally ends as a local throttle.
    val providerFailed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val tracked: () => Result[A] = () => {
      val attempt = operation()
      attempt match {
        case Left(e) if !isLocalThrottle(e) => providerFailed.set(true)
        case _                              => ()
      }
      attempt
    }
    // Whether the call was cancelled while waiting for a local token rather than a provider backoff
    val cancelledAwaitingToken = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Apply deadline if configured
    val result = config.deadline match {
      case Some(deadline) =>
        executeWithDeadlineAndRetry(tracked, deadline, cancelledAwaitingToken)
      case None =>
        executeWithRetry(tracked, attemptNumber = 1, cancelledAwaitingToken)
    }

    // Update circuit breaker state based on result. A call that only ever met the local rate
    // limit says nothing about the provider's health, so it counts as neither; it only hands
    // back a half-open probe permit.
    result match {
      case Right(_) =>
        onSuccess()
      case Left(e) if isLocalThrottle(e) && !providerFailed.get() =>
        onNeutralOutcome()
      // Cancelled while waiting for a local token after the provider had failed: that failure
      // counts, as it would had the wait ended as a local throttle
      case Left(_: CancelledError) if cancelledAwaitingToken.get() && providerFailed.get() =>
        onFailure()
      // Otherwise a cancelled call says nothing about the provider's health
      case Left(_: CancelledError) =>
        onNeutralOutcome()
      case Left(_) =>
        onFailure()
    }

    result
  }

  /**
   * Execute operation with deadline enforcement and retry logic combined.
   * Single retry loop that checks deadline before each attempt.
   */
  private def executeWithDeadlineAndRetry[A](
    operation: () => Result[A],
    deadline: FiniteDuration,
    cancelledAwaitingToken: java.util.concurrent.atomic.AtomicBoolean
  ): Result[A] = {
    val startTime  = nowMillis()
    val deadlineMs = startTime + deadline.toMillis

    @tailrec
    def loop(attemptNumber: Int, lastError: Option[LLMError]): Result[A] = {
      val remainingTime = deadlineMs - nowMillis()

      // Check if deadline already exceeded
      if (remainingTime <= 0) {
        return deadlineExceeded(attemptNumber, lastError, deadline)
      }

      // Execute operation with interruption handling
      val result = CancelledError.whenInterrupted(
        try
          operation()
        catch {
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            Left(CancelledError(cancelledOperation, Some(e)))
        },
        cancelledOperation
      )

      result match {
        case success @ Right(_) =>
          success

        case Left(error) =>
          decideRetry(attemptNumber, error) match {
            case RetryDecision.Retry(delay) =>
              collector.foreach(_.recordRetryAttempt(providerName, attemptNumber))

              // Recompute remaining time after operation to avoid a stale value
              val remainingAfterDelay = (deadlineMs - nowMillis()) - delay.toMillis

              if (remainingAfterDelay <= 0) {
                // Not enough time for retry
                deadlineExceeded(attemptNumber, Some(error), deadline)
              } else {
                // Sleep and retry
                try
                  sleep(delay)
                catch {
                  case e: InterruptedException =>
                    return interruptedDuringRetryDelay(error, cancelledAwaitingToken, e)
                }
                loop(attemptNumber + 1, Some(error))
              }

            case RetryDecision.DoNotRetry =>
              // Max attempts reached or non-retryable error
              if (attemptNumber > 1) {
                // Preserve original error type, add context via collector
                recordTerminalError(error)
              }
              Left(error)
          }
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
    attemptNumber: Int,
    cancelledAwaitingToken: java.util.concurrent.atomic.AtomicBoolean
  ): Result[A] = {
    val result = CancelledError.whenInterrupted(
      try
        operation()
      catch {
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(CancelledError(cancelledOperation, Some(e)))
      },
      cancelledOperation
    )

    result match {
      case success @ Right(_) =>
        success

      case Left(error) =>
        decideRetry(attemptNumber, error) match {
          case RetryDecision.Retry(delay) =>
            // Record retry attempt
            collector.foreach(_.recordRetryAttempt(providerName, attemptNumber))

            try
              sleep(delay)
            catch {
              case e: InterruptedException =>
                return interruptedDuringRetryDelay(error, cancelledAwaitingToken, e)
            }

            // Retry
            executeWithRetry(operation, attemptNumber + 1, cancelledAwaitingToken)

          case RetryDecision.DoNotRetry =>
            // Max attempts reached or non-retryable error - preserve original error
            if (attemptNumber > 1) {
              recordTerminalError(error)
            }
            Left(error)
        }
    }
  }

  /**
   * Pure decision of whether a failed attempt should be retried, and if so after
   * how long. Isolated from `executeWithRetry`/`executeWithDeadlineAndRetry` so the
   * retry/no-retry boundary (previously duplicated in both loops) is a single,
   * directly testable calculation with no clock reads or sleeping.
   */
  private[reliability] def decideRetry(attemptNumber: Int, error: LLMError): RetryDecision =
    if (error.isInstanceOf[CancelledError]) RetryDecision.DoNotRetry
    else if (attemptNumber < config.retryPolicy.maxAttempts && config.retryPolicy.isRetryable(error))
      error match {
        // Our own bucket knows exactly when the next token arrives; a provider-style backoff
        // does not. A bucket that never refills is not worth retrying.
        case e: RateLimitError if isLocalThrottle(e) && rateLimiter.isDefined =>
          rateLimiter.flatMap(_.nanosUntilNextToken) match {
            // Rounded up: the retry sleeps whole milliseconds, and waking a fraction early
            // finds the bucket still empty
            case Some(nanos) => RetryDecision.Retry(FiniteDuration(math.ceil(nanos / 1e6).toLong, MILLISECONDS))
            case None        => RetryDecision.DoNotRetry
          }
        case _ => RetryDecision.Retry(config.retryPolicy.delayFor(attemptNumber, error))
      }
    else
      RetryDecision.DoNotRetry

  /**
   * The outcome when the thread is interrupted while waiting to retry `pending`: always a
   * [[CancelledError]], with the interrupt restored for the caller - whether the wait was a
   * provider backoff or for a local token. A wait for a local token is noted in
   * `cancelledAwaitingToken`, so the circuit counts it as it would the local throttle.
   */
  private def interruptedDuringRetryDelay[A](
    pending: LLMError,
    cancelledAwaitingToken: java.util.concurrent.atomic.AtomicBoolean,
    interruption: InterruptedException
  ): Result[A] = {
    Thread.currentThread().interrupt()
    if (isLocalThrottle(pending)) cancelledAwaitingToken.set(true)
    Left(CancelledError(cancelledOperation, Some(interruption)))
  }

  private def isLocalThrottle(error: LLMError): Boolean = error match {
    case e: RateLimitError => e.origin == RateLimitOrigin.LocalThrottle
    case _                 => false
  }

  /**
   * The outcome when the deadline leaves no time for another attempt. A local throttle is
   * returned as itself: no provider call failed, so it must not read as a provider timeout
   * (which would count against the circuit).
   */
  private def deadlineExceeded[A](
    attemptNumber: Int,
    lastError: Option[LLMError],
    deadline: FiniteDuration
  ): Result[A] =
    lastError match {
      case Some(e) if isLocalThrottle(e) => Left(e)
      case _ =>
        collector.foreach(_.recordError(ErrorKind.Timeout, providerName))
        Left(
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

  /**
   * Record the outcome metric for an attempt that exhausted retries.
   *
   * Skips [[RateLimitError]]s of [[RateLimitOrigin.LocalThrottle]] origin: those were
   * rejected by this client's own token bucket inside the retry loop (see `rateLimited`),
   * which already recorded its own
   * [[ErrorKind.RateLimit]] event at the point of rejection. Recording it again here
   * would double-count the same event. Upstream-provider rate limits (the default
   * origin) are never recorded anywhere else, so they still go through.
   */
  private def recordTerminalError(error: LLMError): Unit =
    error match {
      case _: CancelledError                                                  => ()
      case rle: RateLimitError if rle.origin == RateLimitOrigin.LocalThrottle => ()
      case _ => collector.foreach(_.recordError(ErrorKind.fromLLMError(error), providerName))
    }

  /**
   * Check circuit breaker state and transition if needed.
   */
  private def checkCircuitBreaker(): Result[Unit] =
    circuitState.get() match {
      case CircuitState.Closed =>
        Right(())

      case CircuitState.Open =>
        val now = nowMillis()
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

  /** An outcome that says nothing about the provider: hands back a half-open probe permit. */
  private def onNeutralOutcome(): Unit =
    if (circuitState.get() == CircuitState.HalfOpen) probePermit.set(false)

  /**
   * Handle failed operation.
   */
  private def onFailure(): Unit = {
    lastFailureTime.set(nowMillis())

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

/**
 * Circuit breaker state.
 */
sealed trait CircuitState
object CircuitState {
  case object Closed   extends CircuitState // Normal operation
  case object Open     extends CircuitState // Failing fast
  case object HalfOpen extends CircuitState // Testing recovery
}

/**
 * Outcome of [[ReliableClient.decideRetry]]: whether a failed attempt should be
 * retried, and after how long.
 */
sealed private[reliability] trait RetryDecision
private[reliability] object RetryDecision {
  final case class Retry(delay: FiniteDuration) extends RetryDecision
  case object DoNotRetry                        extends RetryDecision
}
