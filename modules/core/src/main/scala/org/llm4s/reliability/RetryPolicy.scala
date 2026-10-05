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
   * Check if an error is retryable: whether the identical request is worth sending again, with nobody in the
   * loop.
   *
   * This is the library's one rule for automatic retry, and `LLMClientRetry` and the agent graph's node retries
   * use it too. It is derived from [[org.llm4s.error.LLMError.isRecoverable]], never wider: an error is retried
   * only if it is a [[org.llm4s.error.RecoverableError]]. `isRecoverable` says an error may succeed if tried again,
   * perhaps after the caller does something first; this rule keeps the part where repeating the same request is
   * enough. A recoverable error is therefore retried unless one of two documented exceptions applies:
   *
   *  - '''A client-error HTTP status.''' A [[org.llm4s.error.ServiceError]] or an [[org.llm4s.error.APIError]] that
   *    carries a status is retried only for 5xx, 429 or 408. Any other 4xx means the request itself is wrong, so
   *    sending it unchanged cannot succeed. An `APIError` with no status is retried: nothing says it cannot be.
   *  - '''An [[org.llm4s.error.OptimisticLockFailure]].''' The caller must re-read the record first; the same update
   *    fails again until it does.
   *
   * So the retried errors are [[org.llm4s.error.RateLimitError]], [[org.llm4s.error.TimeoutError]],
   * [[org.llm4s.error.NetworkError]], [[org.llm4s.error.ExecutionError]], [[org.llm4s.error.SystemError]], an
   * `APIError` or `ServiceError` with a retryable status, and an `APIError` without one. Every
   * [[org.llm4s.error.NonRecoverableError]] is not, including [[org.llm4s.error.CancelledError]].
   */
  def isRetryable(error: LLMError): Boolean = RetryPolicy.isTransient(error)
}

object RetryPolicy {

  /** The HTTP statuses worth retrying: any 5xx, 429 (rate limit) and 408 (request timeout). */
  private[llm4s] def isRetryableStatus(status: Int): Boolean =
    status >= 500 || status == 429 || status == 408

  /**
   * The one rule for automatic retry; see [[RetryPolicy.isRetryable]]. It is `LLMError.isRecoverable` with two
   * documented exceptions, and total: an error that is neither a `RecoverableError` nor a `NonRecoverableError`
   * (a subtype from outside the library) is not retried.
   */
  private[llm4s] def isTransient(error: LLMError): Boolean = error match {
    case s: ServiceError          => isRetryableStatus(s.httpStatus)
    case a: APIError              => a.statusCode.forall(isRetryableStatus)
    case _: OptimisticLockFailure => false
    case _: RecoverableError      => true
    case _                        => false
  }

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
