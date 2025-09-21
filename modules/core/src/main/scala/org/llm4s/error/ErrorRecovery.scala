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
    recoveryTimeout: Duration = 30.seconds
  ) {

    @volatile private var state: CircuitState           = Closed
    @volatile private var failures                      = 0
    @volatile private var lastFailureTime: Option[Long] = None

    def execute(operation: () => Result[A]): Result[A] =
      state match {
        case Closed =>
          operation() match {
            case success @ Right(_) =>
              failures = 0
              success
            case failure @ Left(_) =>
              failures += 1
              if (failures >= failureThreshold) {
                state = Open
                lastFailureTime = Some(System.currentTimeMillis())
              }
              failure
          }

        case Open =>
          val now = System.currentTimeMillis()
          lastFailureTime match {
            case Some(lastFailure) if (now - lastFailure) > recoveryTimeout.toMillis =>
              state = HalfOpen
              execute(operation)
            case _ =>
              Result.failure(ServiceError(503, "circuit-breaker", "Circuit breaker is open - service unavailable"))
          }

        case HalfOpen =>
          operation() match {
            case success @ Right(_) =>
              state = Closed
              failures = 0
              success
            case failure =>
              state = Open
              lastFailureTime = Some(System.currentTimeMillis())
              failure
          }
      }
  }

  sealed trait CircuitState
  case object Closed   extends CircuitState
  case object Open     extends CircuitState
  case object HalfOpen extends CircuitState
}
