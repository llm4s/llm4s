package org.llm4s.error

/**
 * Marker trait for errors that may succeed on retry.
 *
 * Errors extending this trait indicate transient failures where retrying the operation
 * with appropriate backoff strategies may succeed. Examples include rate limiting,
 * network timeouts, and temporary service unavailability.
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
trait RecoverableError extends LLMError {

  /** Suggested delay in milliseconds before retrying. */
  def retryDelay: Option[Long] = None

  /** Maximum number of retry attempts recommended. */
  def maxRetries: Int = 3

  final override def isRecoverable: Boolean = true
}
