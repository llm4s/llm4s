package org.llm4s.llmconnect.model

sealed trait LLMError {
  def message: String
}
case class AuthenticationError(message: String)     extends LLMError
case class RateLimitError(message: String)          extends LLMError
case class ServiceError(message: String, code: Int) extends LLMError
case class ValidationError(message: String)         extends LLMError
case class UnknownError(throwable: Throwable) extends LLMError {
  def message: String = throwable.getMessage
}

// Additional error types for image processing
case class ProcessingError(message: String) extends LLMError
case class InvalidInput(message: String)    extends LLMError
case class APIError(message: String)        extends LLMError

// Companion object for convenient constructors
object LLMError {
  def ProcessingError(message: String): LLMError = new ProcessingError(message)
  def InvalidInput(message: String): LLMError    = new InvalidInput(message)
  def APIError(message: String): LLMError        = new APIError(message)
}
