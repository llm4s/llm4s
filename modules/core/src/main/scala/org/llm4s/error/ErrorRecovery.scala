package org.llm4s.error

import org.llm4s.Result
import org.llm4s.types._

import scala.annotation.tailrec
import scala.concurrent.duration.{ Duration, DurationInt, DurationLong }

/**
 * Advanced pattern matching for error recovery and intelligent retry logic.
 *
 * Uses Scala's powerful pattern matching to implement sophisticated
 * error handling strategies with type-safe recovery patterns.
 */
object ErrorRecovery {

  /** Intelligent error recovery with exponential backoff */
  def recoverWithBackoff[A](
    operation: () => Result[A],
    maxAttempts: Int = 3,
    baseDelay: Duration = 1.second
  ): Result[A] = {

    @tailrec
    def attempt(attemptNumber: Int): Result[A] =
      operation() match {
        // Success - return immediately
        case success @ Right(_) => success

        // Recoverable errors - retry with backoff
        case Left(error) if attemptNumber < maxAttempts =>
          error match {
            case re: RateLimitError =>
              val delay = re.retryDelay.map(_.millis).getOrElse(baseDelay * Math.pow(2, attemptNumber).doubleValue)
              Thread.sleep(delay.toMillis)
              attempt(attemptNumber + 1)

            case _: ServiceError with RecoverableError =>
              Thread.sleep(baseDelay.toMillis * attemptNumber)
              attempt(attemptNumber + 1)

            case _: TimeoutError =>
              Thread.sleep(baseDelay.toMillis)
              attempt(attemptNumber + 1)

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

  /** Circuit breaker pattern for service resilience */
  class CircuitBreaker[A](
    failureThreshold: Int = 5,
    recoveryTimeout: Duration = 30.seconds,
    clock: () => Long = () => System.currentTimeMillis()
  ) {

    // All fields are accessed only inside `synchronized` blocks.
    private var state: CircuitState           = Closed
    private var failures: Int                 = 0
    private var lastFailureTime: Option[Long] = None

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
            case Some(t) if (now - t) > recoveryTimeout.toMillis =>
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
