package org.llm4s.error

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
object ErrorRecovery {

  /** Binary-compatible overload — delegates to the full version, sleeping the calling thread. */
  def recoverWithBackoff[A](
    operation: () => Result[A],
    maxAttempts: Int,
    baseDelay: FiniteDuration
  ): Result[A] =
    recoverWithBackoff(operation, maxAttempts, baseDelay, threadSleep)

  /**
   * Intelligent error recovery with exponential backoff.
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

    @tailrec
    def attempt(attemptNumber: Int): Result[A] =
      CancelledError.attempt("error-recovery")(operation()) match {
        // Success - return immediately
        case success @ Right(_) => success

        // Cancellation - never retried, never wrapped
        case cancelled @ Left(_: CancelledError) => cancelled

        // Recoverable errors - retry with backoff
        case Left(error) if attemptNumber < maxAttempts =>
          error match {
            case re: RateLimitError =>
              val delay = re.retryDelay.getOrElse(baseDelay * Math.pow(2, attemptNumber).toLong)
              sleepOrCancel(delay) match {
                case Right(())   => attempt(attemptNumber + 1)
                case Left(error) => Left(error)
              }

            case se: ServiceError =>
              sleepOrCancel(se.retryAfter.getOrElse(baseDelay * attemptNumber.toLong)) match {
                case Right(())   => attempt(attemptNumber + 1)
                case Left(error) => Left(error)
              }

            case _: TimeoutError =>
              sleepOrCancel(baseDelay) match {
                case Right(())   => attempt(attemptNumber + 1)
                case Left(error) => Left(error)
              }

            case _ => Left(error) // Non-recoverable
          }

        // Max attempts reached
        case Left(error) =>
          Left(
            ExecutionError(
              message = s"Operation failed after $maxAttempts attempts. Last error: ${error.message}",
              operation = error.formatted
            )
          )
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
