package org.llm4s.error

import org.llm4s.{ error, llmconnect }

/**
 * Compatibility bridge between old and new error types.
 * Allows gradual migration without breaking existing code.
 *
 * This bridge will be removed in later versions when migration is complete.
 */
object ErrorBridge {

  /**
   * Convert legacy error to enhanced core error
   */
  def toCore(oldError: llmconnect.model.LLMError): error.LLMError = oldError match {
    case llmconnect.model.AuthenticationError(message) =>
      error.LLMError.AuthenticationError(message, "unknown")
    case llmconnect.model.RateLimitError(message) =>
      error.LLMError.RateLimitError(message, None, "unknown")
    case llmconnect.model.ServiceError(message, code) =>
      error.LLMError.ServiceError(message, code, "unknown")
    case llmconnect.model.ValidationError(message) =>
      error.LLMError.ValidationError(message, "unknown")
    case llmconnect.model.UnknownError(throwable) =>
      error.LLMError.fromThrowable(throwable)
    case llmconnect.model.ProcessingError(message) =>
      error.LLMError.ServiceError(message, 500, "unknown")
    case llmconnect.model.InvalidInput(message) =>
      error.LLMError.ValidationError(message, "input")
    case llmconnect.model.APIError(message) =>
      error.LLMError.ServiceError(message, 500, "unknown")
  }

  /**
   * Convert enhanced core error to legacy error (for backward compatibility)
   */
  def toLegacy(coreError: error.LLMError): llmconnect.model.LLMError = coreError match {
    case error.LLMError.AuthenticationError(message, _, _) =>
      llmconnect.model.AuthenticationError(message)
    case error.LLMError.RateLimitError(message, _, _, _, _) =>
      llmconnect.model.RateLimitError(message)
    case error.LLMError.ServiceError(message, status, _, _) =>
      llmconnect.model.ServiceError(message, status)
    case error.LLMError.ValidationError(message, _, _) =>
      llmconnect.model.ValidationError(message)
    case error.LLMError.NetworkError(message, Some(cause), _) =>
      llmconnect.model.UnknownError(cause)
    case error.LLMError.UnknownError(message, cause) =>
      llmconnect.model.UnknownError(cause)
    case other =>
      llmconnect.model.UnknownError(new RuntimeException(other.message))
  }
}
