package org.llm4s.error

import org.llm4s.annotation.Stable

import scala.concurrent.duration.FiniteDuration

/**
 * Marker trait for errors that may succeed on retry.
 *
 * Errors extending this trait indicate transient failures where retrying the operation
 * with appropriate backoff strategies may succeed. Examples include rate limiting,
 * network timeouts, and temporary service unavailability.
 *
 * '''What it promises.''' That the operation may succeed if it is attempted again, perhaps after the caller does
 * something first (waits, re-reads a record, corrects a request). It does not promise that repeating the identical
 * request is enough: the library's automatic retry, [[org.llm4s.reliability.RetryPolicy.isRetryable]], retries the
 * recoverable errors for which it is, and is never wider than this trait. Two recoverable errors are deliberately
 * not retried automatically: a response with a client-error HTTP status (`ServiceError`, `APIError`), because the
 * request itself is wrong, and an [[OptimisticLockFailure]], because the caller must re-read first.
 *
 * Use pattern matching or [[LLMError.isRecoverable]] to check recoverability:
 * {{{
 * error match {
 *   case _: RecoverableError => // Apply retry logic
 *   case _: NonRecoverableError => // Report failure
 * }
 * }}}
 *
 * @see [[NonRecoverableError]] for errors that cannot be recovered
 * @see [[ErrorRecovery]] for retry utilities
 */
@Stable
trait RecoverableError extends LLMError {

  /** Suggested delay before retrying. */
  def retryDelay: Option[FiniteDuration] = None

  /** Maximum number of retry attempts recommended. */
  def maxRetries: Int = 3
}
