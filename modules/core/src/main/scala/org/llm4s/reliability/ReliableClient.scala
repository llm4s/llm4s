// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.reliability

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Completion, CompletionOptions, StreamedChunk }
import org.llm4s.types.Result
import org.llm4s.error._
import org.llm4s.metrics.{ MetricsCollector, ErrorKind }

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }
import scala.util.{ Failure, Try }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference }

/**
 * Wrapper that adds reliability features to any LLMClient.
 *
 * Provides:
 * - Retry with configurable policies (exponential backoff, linear, fixed)
 * - Circuit breaker to fail fast when service is down: while the circuit is open, or half-open with a probe in
 *   flight, a call is refused with a [[org.llm4s.error.CircuitOpenError CircuitOpenError]], which is not retried
 * - A deadline on the attempts taken together: it is checked before each attempt and before each wait, so no
 *   retry starts once it has passed. It does not interrupt an attempt: a call hung inside the underlying client
 *   is bounded by that client's own timeout, not by the deadline
 * - Local token-bucket rate limiting ([[RateLimitConfig]]), checked before every attempt
 * - Metrics for retry attempts, circuit breaker transitions and the errors it ends on ([[ReliabilityMetrics]])
 *
 * Thread-safety: Uses AtomicInteger/AtomicReference for circuit breaker state management
 * to ensure correct behavior under concurrent access.
 *
 * A configuration the client cannot run with ([[ReliabilityConfig.validate]]: a `failureThreshold` below 1, a
 * `burstCapacity` below 1, a negative `requestsPerMinute`) does not throw here: every call, and `validate()`,
 * returns its [[org.llm4s.error.ValidationError ValidationError]].
 *
 * Build one with the companion's `apply`, and add a collector with [[withMetrics]]:
 * {{{
 * val reliable = ReliableClient(client, "openai-main", ReliabilityConfig.default).withMetrics(collector)
 * }}}
 * The constructor is private, so that a setting can be added without breaking a caller.
 */
@Stable
final class ReliableClient private (
  underlying: LLMClient,
  providerName: String,
  config: ReliabilityConfig,
  collector: Option[MetricsCollector],
  clock: () => Instant,
  sleep: FiniteDuration => Unit
) extends LLMClient {

  /**
   * This client reporting its retries, circuit-breaker transitions and errors to `collector`
   * ([[ReliabilityMetrics]]), labelled with the `providerName` it was built with.
   *
   * It returns a new client with a circuit breaker and rate limit of its own, so call it where the client is
   * built, before the first request.
   */
  def withMetrics(collector: MetricsCollector): ReliableClient =
    new ReliableClient(underlying, providerName, config, Some(collector), clock, sleep)

  /** Test seam: the current time, read for deadlines and circuit-breaker recovery. */
  private[llm4s] def withClock(clock: () => Instant): ReliableClient =
    new ReliableClient(underlying, providerName, config, collector, clock, sleep)

  /**
   * Test seam: how the client waits between attempts, so a test can record the delays chosen, or throw
   * `InterruptedException` to simulate an interrupted wait.
   */
  private[llm4s] def withSleep(sleep: FiniteDuration => Unit): ReliableClient =
    new ReliableClient(underlying, providerName, config, collector, clock, sleep)

  private val cancelledOperation = "reliable-client.complete"

  // What is wrong with the configuration, when it is enabled and cannot be run with
  private val configError: Option[LLMError] =
    if (config.enabled) config.validate.left.toOption else None

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
            collector.foreach(ReliabilityMetrics.failed(_, providerName, ErrorKind.RateLimit))
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
      configError
        .map(error => Left(error): Result[Completion])
        .getOrElse(executeWithReliability(rateLimited(() => underlying.complete(conversation, options))))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.streamComplete(conversation, options, onChunk)
    } else
      configError.map(error => Left(error): Result[Completion]).getOrElse {
        // Once a chunk has reached the caller, a failure is returned rather than retried: a retry would
        // deliver the reply again from the start and run (and bill) the call twice. A failure before the
        // first chunk is retried as usual.
        val delivered = new java.util.concurrent.atomic.AtomicBoolean(false)
        // The caller's own onChunk threw: the client turns that into a Left, but it says nothing about the
        // provider, so the circuit breaker counts it as neither success nor failure. It is rethrown unchanged.
        val callerFailed = new java.util.concurrent.atomic.AtomicBoolean(false)
        val tracking: StreamedChunk => Unit = { chunk =>
          delivered.set(true)
          Try(onChunk(chunk)) match {
            case Failure(e) =>
              callerFailed.set(true)
              throw e
            case _ => ()
          }
        }
        executeWithReliability(
          rateLimited(() => underlying.streamComplete(conversation, options, tracking)),
          canRetry = () => !delivered.get(),
          callerFailed = () => callerFailed.get()
        )
      }

  override def getContextWindow(): Int     = underlying.getContextWindow()
  override def getReserveCompletion(): Int = underlying.getReserveCompletion()
  override def validate(): Result[Unit]    = configError.toLeft(()).flatMap(_ => underlying.validate())
  override def close(): Unit               = underlying.close()

  override def providerId: Option[org.llm4s.types.ProviderModelTypes.ProviderId] = underlying.providerId

  /**
   * Execute operation with retry, circuit breaker, and deadline.
   */
  private def executeWithReliability[A](
    operation: () => Result[A],
    canRetry: () => Boolean = () => true,
    callerFailed: () => Boolean = () => false
  ): Result[A] = {
    // Check circuit breaker state. A call let through a half-open circuit is its probe: it holds the probe
    // permit, and hands it back with its outcome.
    val isProbe = checkCircuitBreaker() match {
      case Left(error) =>
        collector.foreach(ReliabilityMetrics.failed(_, providerName, ErrorKind.ServiceError))
        return Left(error)
      case Right(probe) => probe
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
        executeWithDeadlineAndRetry(tracked, deadline, cancelledAwaitingToken, canRetry)
      case None =>
        executeWithRetry(tracked, attemptNumber = 1, cancelledAwaitingToken, canRetry)
    }

    // Update circuit breaker state based on result. A call that only ever met the local rate
    // limit says nothing about the provider's health, so it counts as neither; it only hands
    // back a half-open probe permit.
    result match {
      case Right(_) =>
        onSuccess(isProbe)
      // The caller's own callback failed, not the provider
      case Left(_) if callerFailed() =>
        onNeutralOutcome(isProbe)
      // The provider's content filter answered: it is healthy, and one user's blocked prompts must not open the
      // breaker for every caller
      case Left(_: ContentFilteredError) =>
        onNeutralOutcome(isProbe)
      case Left(e) if isLocalThrottle(e) && !providerFailed.get() =>
        onNeutralOutcome(isProbe)
      // Cancelled while waiting for a local token after the provider had failed: that failure
      // counts, as it would had the wait ended as a local throttle
      case Left(_: CancelledError) if cancelledAwaitingToken.get() && providerFailed.get() =>
        onFailure(isProbe)
      // Otherwise a cancelled call says nothing about the provider's health
      case Left(_: CancelledError) =>
        onNeutralOutcome(isProbe)
      case Left(_) =>
        onFailure(isProbe)
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
    cancelledAwaitingToken: java.util.concurrent.atomic.AtomicBoolean,
    canRetry: () => Boolean
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
          (if (canRetry()) decideRetry(attemptNumber, error) else RetryDecision.DoNotRetry) match {
            case RetryDecision.Retry(delay) =>
              collector.foreach(ReliabilityMetrics.retried(_, providerName))

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
    cancelledAwaitingToken: java.util.concurrent.atomic.AtomicBoolean,
    canRetry: () => Boolean
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
        (if (canRetry()) decideRetry(attemptNumber, error) else RetryDecision.DoNotRetry) match {
          case RetryDecision.Retry(delay) =>
            // Record retry attempt
            collector.foreach(ReliabilityMetrics.retried(_, providerName))

            try
              sleep(delay)
            catch {
              case e: InterruptedException =>
                return interruptedDuringRetryDelay(error, cancelledAwaitingToken, e)
            }

            // Retry
            executeWithRetry(operation, attemptNumber + 1, cancelledAwaitingToken, canRetry)

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
   * [[org.llm4s.error.CancelledError CancelledError]], with the interrupt restored for the caller - whether the wait was a
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
        collector.foreach(ReliabilityMetrics.failed(_, providerName, ErrorKind.Timeout))
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
   * Skips [[org.llm4s.error.RateLimitError RateLimitError]]s of [[org.llm4s.error.RateLimitOrigin.LocalThrottle RateLimitOrigin.LocalThrottle]] origin: those were
   * rejected by this client's own token bucket inside the retry loop (see `rateLimited`),
   * which already recorded its own
   * [[org.llm4s.metrics.ErrorKind.RateLimit ErrorKind.RateLimit]] event at the point of rejection. Recording it again here
   * would double-count the same event. Upstream-provider rate limits (the default
   * origin) are never recorded anywhere else, so they still go through.
   */
  private def recordTerminalError(error: LLMError): Unit =
    error match {
      case _: CancelledError                                                  => ()
      case rle: RateLimitError if rle.origin == RateLimitOrigin.LocalThrottle => ()
      case _ => collector.foreach(ReliabilityMetrics.failed(_, providerName, ErrorKind.fromLLMError(error)))
    }

  /**
   * Whether the circuit lets this call through, and if so whether as the probe of a half-open circuit.
   *
   * The probe permit is taken before the circuit moves from open to half-open, and by the caller that moves it, so
   * of any number of callers that arrive once the recovery time has passed exactly one probes (#2797): the caller
   * that made the transition used to go ahead without the permit, and a second caller could take it.
   */
  private def checkCircuitBreaker(): Result[Boolean] =
    circuitState.get() match {
      case CircuitState.Closed =>
        Right(false)

      case CircuitState.Open =>
        val remaining = config.circuitBreaker.recoveryTimeout.toMillis - (nowMillis() - lastFailureTime.get())
        if (remaining >= 0)
          Left(CircuitOpenError.open(providerName, FiniteDuration(math.max(remaining, 1L), MILLISECONDS)))
        else if (!probePermit.compareAndSet(false, true)) Left(CircuitOpenError.probing(providerName))
        else if (circuitState.compareAndSet(CircuitState.Open, CircuitState.HalfOpen)) {
          successCount.set(0)
          collector.foreach(ReliabilityMetrics.transitioned(_, providerName, CircuitState.HalfOpen))
          Right(true)
        } else {
          // The circuit moved while the permit was being taken: hand it back and read the state again
          probePermit.set(false)
          checkCircuitBreaker()
        }

      case CircuitState.HalfOpen =>
        // Only allow one probe request through at a time to avoid flooding a recovering service
        if (!probePermit.compareAndSet(false, true)) Left(CircuitOpenError.probing(providerName))
        else if (circuitState.get() == CircuitState.HalfOpen) Right(true)
        else {
          probePermit.set(false)
          checkCircuitBreaker()
        }
    }

  /**
   * Handle successful operation. Only a probe's success counts towards closing a half-open circuit: a call that
   * started before the circuit opened says nothing about the service now.
   */
  private def onSuccess(isProbe: Boolean): Unit = {
    circuitState.get() match {
      case CircuitState.Closed =>
        // Reset failure count
        failureCount.set(0)

      case CircuitState.HalfOpen if isProbe =>
        if (
          successCount.incrementAndGet() >= config.circuitBreaker.successThreshold &&
          circuitState.compareAndSet(CircuitState.HalfOpen, CircuitState.Closed)
        ) {
          failureCount.set(0)
          successCount.set(0)
          collector.foreach(ReliabilityMetrics.transitioned(_, providerName, CircuitState.Closed))
        }

      case _ => ()
    }
    releaseProbe(isProbe)
  }

  /** An outcome that says nothing about the provider: a probe only hands back its permit. */
  private def onNeutralOutcome(isProbe: Boolean): Unit = releaseProbe(isProbe)

  /** Hands back the probe permit, whatever state the circuit is in by now, so the next probe can be let through. */
  private def releaseProbe(isProbe: Boolean): Unit = if (isProbe) probePermit.set(false)

  /**
   * Handle failed operation.
   */
  private def onFailure(isProbe: Boolean): Unit = {
    lastFailureTime.set(nowMillis())

    circuitState.get() match {
      case CircuitState.Closed =>
        // Track failures
        val newFailureCount = failureCount.incrementAndGet()
        if (newFailureCount >= config.circuitBreaker.failureThreshold) {
          // Open circuit
          if (circuitState.compareAndSet(CircuitState.Closed, CircuitState.Open)) {
            collector.foreach(ReliabilityMetrics.transitioned(_, providerName, CircuitState.Open))
          }
        }

      case CircuitState.HalfOpen =>
        // Single failure in half-open → back to open
        if (circuitState.compareAndSet(CircuitState.HalfOpen, CircuitState.Open)) {
          successCount.set(0)
          collector.foreach(ReliabilityMetrics.transitioned(_, providerName, CircuitState.Open))
        }

      case CircuitState.Open =>
      // Already open
    }
    releaseProbe(isProbe)
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
   * A client that adds retries, a circuit breaker, a deadline and a local rate limit to `underlying`.
   *
   * @param underlying the client to wrap
   * @param providerName the `provider` label of this client's metrics and the provider its own errors name. It
   *                     is a label, not a vendor id: often the provider's id, but any stable name the caller
   *                     chooses (a configuration section, a pipeline)
   * @param config what to retry, when to open the circuit, the deadline and the rate limit
   */
  def apply(
    underlying: LLMClient,
    providerName: String,
    config: ReliabilityConfig = ReliabilityConfig.default
  ): ReliableClient =
    new ReliableClient(
      underlying,
      providerName,
      config,
      collector = None,
      clock = () => Instant.now(),
      sleep = delay => Thread.sleep(delay.toMillis)
    )
}

/**
 * Circuit breaker state.
 */
@Stable
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
