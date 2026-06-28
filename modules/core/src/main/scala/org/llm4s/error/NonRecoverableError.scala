package org.llm4s.error

/**
 * Marker trait for errors that cannot be recovered through retries.
 *
 * Errors extending this trait indicate permanent failures that require user
 * intervention or code changes to resolve. Examples include authentication
 * failures, invalid input, and configuration errors.
 *
 * Use pattern matching or [[LLMError.isRecoverable]] to check recoverability:
 * {{{
 * error match {
 *   case _: RecoverableError => // Apply retry logic
 *   case _: NonRecoverableError => // Report failure to user
 * }
 * }}}
 *
 * @see [[RecoverableError]] for errors that may succeed on retry
 */
trait NonRecoverableError extends LLMError {
  final override def isRecoverable: Boolean = false
}
