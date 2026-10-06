package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.Result
import org.llm4s.types._

import scala.annotation.tailrec
import java.time.Instant
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Advanced pattern matching for error recovery and intelligent retry logic.
 *
 * Uses Scala's powerful pattern matching to implement sophisticated
 * error handling strategies with type-safe recovery patterns.
 */
@Stable
object ErrorRecovery {

  /** Binary-compatible overload — delegates to the full version, sleeping the calling thread. */
  def recoverWithBackoff[A](
    operation: () => Result[A],
    maxAttempts: Int,
    baseDelay: FiniteDuration
  ): Result[A] =
    recoverWithBackoff(operation, maxAttempts, baseDelay, threadSleep)

  /**
   * Retries `operation` while it fails with a transient error, up to `maxAttempts` calls in all.
   *
   * Only three error types are retried, each on its own schedule:
   *  - [[RateLimitError]]: waits its [[RateLimitError.retryDelay]], which is the provider's
   *    `retryAfter` hint, else [[RateLimitError.DefaultRetryDelay]] (30 seconds). `baseDelay` is not used.
   *  - [[ServiceError]] with a 5xx, 429 or 408 status (`isRecoverableStatus`): waits the provider's
   *    `retryAfter` hint, else `baseDelay * n` after the n-th attempt (linear: `baseDelay`,
   *    `2 * baseDelay`, ...). Its [[ServiceError.retryDelay]] default is not used. A `ServiceError`
   *    with any other status, such as a 404, is not retried.
   *  - [[TimeoutError]]: waits `baseDelay` before every retry.
   *
   * Every other error, including a [[CancelledError]], is returned unchanged at once, whatever the
   * attempt count. An interrupt during a wait returns a [[CancelledError]]. When the last attempt
   * fails with one of the three retried types, the result is an [[ExecutionError]] (itself a
   * [[RecoverableError]]) whose message names the number of attempts made and the last error's
   * message, and whose `operation` is the last error's `formatted` text. The operation is always
   * called at least once, even when `maxAttempts` is below 1.
   *
   * @param sleepFn pauses between attempts (default: sleeps the calling thread); override for testing
   */
  def recoverWithBackoff[A](
    operation: () => Result[A],
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second,
    sleepFn: FiniteDuration => Unit = threadSleep
  ): Result[A] = {

    def sleepOrCancel(delay: FiniteDuration): Result[Unit] =
      CancelledError.catchInterrupt(sleepFn(delay)).left.map { e =>
        Thread.currentThread().interrupt()
        CancelledError("error-recovery", Some(e))
      }

    /** The wait before the next attempt, or `None` when `error` is not retried. */
    def retryDelay(error: LLMError, attemptNumber: Int): Option[FiniteDuration] =
      error match {
        case re: RateLimitError => Some(re.retryAfter.getOrElse(RateLimitError.DefaultRetryDelay))
        case se: ServiceError if se.isRecoverableStatus =>
          Some(se.retryAfter.getOrElse(baseDelay * attemptNumber.toLong))
        case _: TimeoutError => Some(baseDelay)
        case _               => None
      }

    @tailrec
    def attempt(attemptNumber: Int): Result[A] =
      CancelledError.attempt("error-recovery")(operation()) match {
        case success @ Right(_) => success

        case failure @ Left(error) =>
          retryDelay(error, attemptNumber) match {
            // Not retried (including CancelledError) - returned unchanged, whatever the attempt count
            case None => failure

            // Retried type, attempts left - wait, then try again
            case Some(delay) if attemptNumber < maxAttempts =>
              sleepOrCancel(delay) match {
                case Right(())       => attempt(attemptNumber + 1)
                case Left(cancelled) => Left(cancelled)
              }

            // Retried type, attempts exhausted
            case Some(_) =>
              Left(
                ExecutionError(
                  message = s"Operation failed after $attemptNumber attempts. Last error: ${error.message}",
                  operation = error.formatted
                )
              )
          }
      }

    attempt(1)
  }

  /** Pauses the calling thread for `delay`; the default `sleepFn`. */
  private def threadSleep(delay: FiniteDuration): Unit = Thread.sleep(delay.toMillis)

  /**
   * Circuit breaker pattern for service resilience.
   *
   * @param clock the current time; injectable for tests
   */
  class CircuitBreaker[A](
    failureThreshold: Int = 5,
    recoveryTimeout: FiniteDuration = 30.seconds,
    clock: () => Instant = () => Instant.now()
  ) {

    // All fields are accessed only inside `synchronized` blocks.
    private var state: CircuitState              = Closed
    private var failures: Int                    = 0
    private var lastFailureTime: Option[Instant] = None

    // Atomically decide what to do and, when transitioning Open→HalfOpen, claim
    // the exclusive probe slot.  Returns the state we committed to running as,
    // or None if the call should be fast-rejected.
    private def acquireSlot(): Option[CircuitState] = synchronized {
      state match {
        case Closed => Some(Closed)
        // A probe is already in flight; reject to avoid multiple concurrent probes.
        case HalfOpen => None
        case Open =>
          val now = clock()
          lastFailureTime match {
            case Some(t) if java.time.Duration.between(t, now).toMillis > recoveryTimeout.toMillis =>
              state = HalfOpen // exactly one thread wins this assignment
              Some(HalfOpen)
            case _ => None
          }
      }
    }

    // Atomically record the outcome of an operation that ran under `entryState`.
    private def recordResult(entryState: CircuitState, result: Result[A]): Unit = synchronized {
      entryState match {
        case HalfOpen =>
          result match {
            case Right(_) => state = Closed; failures = 0
            case Left(_)  => state = Open; lastFailureTime = Some(clock())
          }
        case Closed =>
          result match {
            case Right(_) => failures = 0
            case Left(_) =>
              failures += 1
              if (failures >= failureThreshold) {
                state = Open
                lastFailureTime = Some(clock())
              }
          }
        case Open => // shouldn't occur; leave state unchanged
      }
    }

    def execute(operation: () => Result[A]): Result[A] =
      acquireSlot() match {
        case None =>
          Result.failure(ServiceError(503, "circuit-breaker", "Circuit breaker is open - service unavailable"))
        case Some(entryState) =>
          val result = operation()
          recordResult(entryState, result)
          result
      }
  }

  sealed trait CircuitState
  case object Closed   extends CircuitState
  case object Open     extends CircuitState
  case object HalfOpen extends CircuitState
}
