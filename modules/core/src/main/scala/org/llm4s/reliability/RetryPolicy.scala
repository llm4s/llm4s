package org.llm4s.reliability

import org.llm4s.annotation.Stable
import org.llm4s.error._
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration, NANOSECONDS }

/**
 * Retry policy for transient failures.
 *
 * Defines how many times to retry and how long to wait between attempts.
 */
@Stable
sealed trait RetryPolicy {

  /** The most attempts one call makes, the first included: 3 is the first try and up to two retries, 1 is no retry. */
  def maxAttempts: Int

  /**
   * Calculate delay before next retry attempt.
   *
   * @param attemptNumber The attempt number (1-indexed)
   * @param error The error that triggered the retry
   * @return Delay duration before next attempt
   */
  def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration

  /**
   * Whether this policy sends the request again after `error`.
   *
   * By default it is the library's one rule, [[org.llm4s.error.LLMError.isRetryable]]: [[ReliableClient]] applies it
   * through this method, and the agent graph's node retries use the same rule. `noRetry` retries nothing, and a
   * [[RetryPolicy.custom custom]] policy may decide differently.
   */
  def isRetryable(error: LLMError): Boolean = error.isRetryable
}

object RetryPolicy {

  /**
   * How long a backoff policy waits after a [[org.llm4s.error.RateLimitError]] whose provider gave no
   * `Retry-After`: the library's own guess, longer than the first steps of a backoff because a rate-limit window
   * rarely reopens within a second or two. A delay the provider did give (`error.retryAfter`) is used as it is.
   */
  val DefaultRateLimitDelay: FiniteDuration = 30.seconds

  /**
   * The delay a backoff policy uses for `error` in place of its own schedule: the delay the server asked for
   * (`error.retryAfter`), else [[DefaultRateLimitDelay]] for a rate limit. Any other error gets the policy's
   * backoff.
   */
  private[llm4s] def serverDelay(error: LLMError): Option[FiniteDuration] =
    error.retryAfter.orElse(error match {
      case _: RateLimitError => Some(DefaultRateLimitDelay)
      case _                 => None
    })

  /**
   * Exponential backoff: 2^n * baseDelay.
   *
   * Example: baseDelay=1s → 1s, 2s, 4s, 8s, ...
   */
  def exponentialBackoff(
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second,
    maxDelay: FiniteDuration = 32.seconds
  ): RetryPolicy = new ExponentialBackoff(maxAttempts, baseDelay, maxDelay)

  /**
   * Linear backoff: attemptNumber * baseDelay.
   *
   * Example: baseDelay=2s → 2s, 4s, 6s, 8s, ...
   */
  def linearBackoff(
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 2.seconds
  ): RetryPolicy = new LinearBackoff(maxAttempts, baseDelay)

  /**
   * Fixed delay: always wait the same amount of time.
   *
   * Example: delay=3s → 3s, 3s, 3s, ...
   */
  def fixedDelay(
    maxAttempts: Int = 3,
    delay: FiniteDuration = 2.seconds
  ): RetryPolicy = new FixedDelay(maxAttempts, delay)

  /**
   * No retry: fail immediately on first error.
   */
  def noRetry: RetryPolicy = new NoRetry

  /**
   * Custom retry policy.
   *
   * `retryableFn` defaults to the library's one retry rule, [[org.llm4s.error.LLMError.isRetryable]], so a policy that only
   * customises the delay retries exactly what the other factories' policies retry.
   */
  def custom(
    attempts: Int,
    delayFn: (Int, LLMError) => FiniteDuration,
    retryableFn: LLMError => Boolean = _.isRetryable
  ): RetryPolicy = new CustomRetryPolicy(attempts, delayFn, retryableFn)
}

/**
 * Exponential backoff retry policy.
 */
private class ExponentialBackoff(
  val maxAttempts: Int,
  baseDelay: FiniteDuration,
  maxDelay: FiniteDuration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration =
    RetryPolicy.serverDelay(error).getOrElse {
      // In nanoseconds as a Double, so a large attempt number caps at maxDelay instead of overflowing
      val exponentialNanos = baseDelay.toNanos * Math.pow(2, attemptNumber - 1)
      if (exponentialNanos >= maxDelay.toNanos) maxDelay
      else FiniteDuration(exponentialNanos.toLong, NANOSECONDS).toCoarsest
    }
}

/**
 * Linear backoff retry policy.
 */
private class LinearBackoff(
  val maxAttempts: Int,
  baseDelay: FiniteDuration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration =
    RetryPolicy.serverDelay(error).getOrElse(baseDelay * attemptNumber)
}

/**
 * Fixed delay retry policy.
 */
private class FixedDelay(
  val maxAttempts: Int,
  delay: FiniteDuration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration =
    RetryPolicy.serverDelay(error).getOrElse(delay)
}

/**
 * No retry policy - fail immediately.
 */
private class NoRetry extends RetryPolicy {
  val maxAttempts: Int = 1

  override def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration =
    Duration.Zero

  override def isRetryable(error: LLMError): Boolean = false
}

/**
 * Custom retry policy with user-defined logic.
 */
private class CustomRetryPolicy(
  val maxAttempts: Int,
  delayFn: (Int, LLMError) => FiniteDuration,
  retryableFn: LLMError => Boolean
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration =
    delayFn(attemptNumber, error)

  override def isRetryable(error: LLMError): Boolean =
    retryableFn(error)
}
