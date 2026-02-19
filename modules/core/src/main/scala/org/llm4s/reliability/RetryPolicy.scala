package org.llm4s.reliability

import org.llm4s.error._
import scala.concurrent.duration.{ Duration, DurationInt }

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
  def delayFor(attemptNumber: Int, error: LLMError): Duration

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
   * Exponential backoff: 2^n * baseDelay.
   *
   * Example: baseDelay=1s → 1s, 2s, 4s, 8s, ...
   */
  def exponentialBackoff(
    maxAttempts: Int = 3,
    baseDelay: Duration = 1.second,
    maxDelay: Duration = 32.seconds
  ): RetryPolicy = new ExponentialBackoff(maxAttempts, baseDelay, maxDelay)

  /**
   * Linear backoff: attemptNumber * baseDelay.
   *
   * Example: baseDelay=2s → 2s, 4s, 6s, 8s, ...
   */
  def linearBackoff(
    maxAttempts: Int = 3,
    baseDelay: Duration = 2.seconds
  ): RetryPolicy = new LinearBackoff(maxAttempts, baseDelay)

  /**
   * Fixed delay: always wait the same amount of time.
   *
   * Example: delay=3s → 3s, 3s, 3s, ...
   */
  def fixedDelay(
    maxAttempts: Int = 3,
    delay: Duration = 2.seconds
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
    delayFn: (Int, LLMError) => Duration,
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
  baseDelay: Duration,
  maxDelay: Duration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): Duration = {
    // Check for server-provided retry delay (e.g., Retry-After header)
    val serverDelay = error match {
      case re: RateLimitError => re.retryDelay.map(millis => Duration.fromNanos(millis * 1000000))
      case _                  => None
    }

    serverDelay.getOrElse {
      val exponentialDelay = baseDelay * Math.pow(2, attemptNumber - 1).toLong.toDouble
      exponentialDelay.min(maxDelay)
    }
  }
}

/**
 * Linear backoff retry policy.
 */
private class LinearBackoff(
  val maxAttempts: Int,
  baseDelay: Duration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): Duration = {
    // Check for server-provided retry delay
    val serverDelay = error match {
      case re: RateLimitError => re.retryDelay.map(millis => Duration.fromNanos(millis * 1000000))
      case _                  => None
    }

    serverDelay.getOrElse(baseDelay * attemptNumber)
  }
}

/**
 * Fixed delay retry policy.
 */
private class FixedDelay(
  val maxAttempts: Int,
  delay: Duration
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): Duration = {
    // Check for server-provided retry delay
    val serverDelay = error match {
      case re: RateLimitError => re.retryDelay.map(millis => Duration.fromNanos(millis * 1000000))
      case _                  => None
    }

    serverDelay.getOrElse(delay)
  }
}

/**
 * No retry policy - fail immediately.
 */
private class NoRetry extends RetryPolicy {
  val maxAttempts: Int = 1

  override def delayFor(attemptNumber: Int, error: LLMError): Duration =
    Duration.Zero

  override def isRetryable(error: LLMError): Boolean = false
}

/**
 * Custom retry policy with user-defined logic.
 */
private class CustomRetryPolicy(
  val maxAttempts: Int,
  delayFn: (Int, LLMError) => Duration,
  retryableFn: LLMError => Boolean
) extends RetryPolicy {

  override def delayFor(attemptNumber: Int, error: LLMError): Duration =
    delayFn(attemptNumber, error)

  override def isRetryable(error: LLMError): Boolean =
    retryableFn(error)
}
