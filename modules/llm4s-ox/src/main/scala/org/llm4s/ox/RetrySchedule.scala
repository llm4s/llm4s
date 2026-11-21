package org.llm4s.ox

import _root_.ox.resilience.{ retryEither, ResultPolicy, RetryConfig }
import _root_.ox.scheduling.{ Jitter, Schedule }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.reliability.RetryPolicy
import org.llm4s.types.Result

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Ox retry presets for `Result`-returning llm4s calls.
 *
 * What is retried is the library's one rule for automatic retry, the one `ReliableClient` and the agent graph use
 * ([[org.llm4s.reliability.RetryPolicy]]'s `isRetryable`): a recoverable error, except a `ServiceError` or an
 * `APIError` with a client-error status (any 4xx but 408 and 429) and an `OptimisticLockFailure`. Every other
 * error, a `CancelledError` included, is returned at once.
 *
 * {{{
 * val answer: Result[Completion] = RetrySchedule.retry()(client.complete(conversation))
 * val custom = retryEither(RetrySchedule.config[Completion](Schedule.fixedInterval(2.seconds).maxRetries(5)))(
 *   client.complete(conversation)
 * )
 * }}}
 */
@Experimental
object RetrySchedule {

  /** Exponential backoff from 1 second, each delay capped at 30 seconds, with equal jitter: at most 3 retries. */
  val default: Schedule =
    Schedule.exponentialBackoff(1.second).maxInterval(30.seconds).jitter(Jitter.Equal).maxRetries(3)

  /** Retries only what `RetryPolicy.isRetryable` retries; every result is a success. */
  def policy[A]: ResultPolicy[LLMError, A] = ResultPolicy.retryWhen(RetryPolicy.isTransient)

  /**
   * An Ox `RetryConfig` on `schedule` with `policy`, for Ox's own `retryEither`. It waits the schedule's delays
   * only; `retry` also honours the provider's retry-after hint.
   */
  def config[A](schedule: Schedule = default): RetryConfig[LLMError, A] = RetryConfig(schedule, policy[A])

  /**
   * Runs `operation`, retrying it on `schedule` (by default `default`) while it fails with an error
   * `RetryPolicy.isRetryable` retries, and returns its first success or its last failure.
   *
   * The wait before a retry is the schedule's delay, or longer when the provider asked for longer: a
   * `RateLimitError` waits at least its `retryDelay` (its retry-after, else `RateLimitError.DefaultRetryDelay`), a
   * `ServiceError` at least its explicit `retryAfter`, as core's `ErrorRecovery` does. Built on Ox's `retryEither`;
   * an interrupt during a wait propagates as `InterruptedException`, so an enclosing scope can end.
   */
  def retry[A](schedule: Schedule = default)(operation: => Result[A]): Result[A] = {
    // The earliest System.nanoTime at which the next attempt may start; Long.MinValue when the provider set none.
    val notBefore = new AtomicLong(Long.MinValue)
    val retried = config[A](schedule).afterAttempt { (_, outcome) =>
      val hinted = outcome.left.toOption.filter(RetryPolicy.isTransient).flatMap(providerDelay)
      notBefore.set(hinted.fold(Long.MinValue)(d => System.nanoTime() + d.toNanos))
    }
    retryEither(retried) {
      val deadline = notBefore.get()
      if (deadline != Long.MinValue) {
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
      }
      operation
    }
  }

  /** The delay the provider asked for before `error` may be retried, if any. */
  private def providerDelay(error: LLMError): Option[FiniteDuration] = error match {
    case rate: RateLimitError  => rate.retryDelay
    case service: ServiceError => service.retryAfter
    case _                     => None
  }
}
