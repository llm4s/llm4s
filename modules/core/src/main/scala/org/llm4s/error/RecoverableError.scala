package org.llm4s.error

/**
 * Marker trait for recoverable errors - enables compile-time recoverability checks
 */
protected trait RecoverableError extends LLMError {
  def retryDelay: Option[Long] = None

  def maxRetries: Int = 3

  final override def isRecoverable: Boolean = true
}
