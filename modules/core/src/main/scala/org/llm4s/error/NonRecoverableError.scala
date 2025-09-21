package org.llm4s.error

/**
 * Marker trait for non-recoverable errors - enables compile-time recoverability checks
 */
protected trait NonRecoverableError extends LLMError {
  final override def isRecoverable: Boolean = false
}
