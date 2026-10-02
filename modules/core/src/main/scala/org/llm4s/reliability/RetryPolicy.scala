package org.llm4s.reliability

import org.llm4s.error._
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration, NANOSECONDS }

/**
 * Retry policy for transient failures.
 *
 * Defines how many times to retry and how long to wait between attempts.
 */
sealed trait RetryPolicy {

  /** Maximum number of retry attempts */
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
   * Check if an error is retryable.
   *
   * Retryable errors:
   * - RateLimitError (429)
   * - TimeoutError
   * - ServiceError with 5xx, 429, or 408 status codes
   * - NetworkError
   *
   * Non-retryable errors:
   * - ServiceError with 4xx status (except 408/429) - client errors
   * - AuthenticationError
   * - ValidationError
   * - Other errors
   */
  def isRetryable(error: LLMError): Boolean = error match {
    case _: RateLimitError => true
    case _: TimeoutError   => true
    case se: ServiceError  =>
      // Only retry 5xx (server errors), 429 (rate limit), 408 (timeout)
      se.httpStatus >= 500 || se.httpStatus == 429 || se.httpStatus == 408
    case _: NetworkError => true
    case _               => false
  }
}

object RetryPolicy {

  /**
   * The delay the provider asked for before retrying `error`, if it gave one: a rate limit's
   * `retryDelay` (its `Retry-After`, else [[RateLimitError.DefaultRetryDelay]]), or a service
   * error's explicit `retryAfter`. A service error without one gets the policy's own backoff,
   * not its generic default.
   */
  private[reliability] def serverDelay(error: LLMError): Option[FiniteDuration] = error match {
    case re: RateLimitError => re.retryDelay
    case se: ServiceError   => se.retryAfter
    case _                  => None
  }

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
   */
  def custom(
    attempts: Int,
    delayFn: (Int, LLMError) => FiniteDuration,
    retryableFn: LLMError => Boolean = {
      case _: RateLimitError => true
      case _: TimeoutError   => true
      case se: ServiceError  => se.httpStatus >= 500 || se.httpStatus == 429 || se.httpStatus == 408
      case _: NetworkError   => true
      case _                 => false
    }
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
