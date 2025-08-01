package org.llm4s.llmconnect.model

//@deprecated("Use org.llm4s.error.LLMError instead", "0.1.1")
sealed trait LLMError {
  def message: String
}

//@deprecated("Use org.llm4s.error.LLMError.AuthenticationError instead", "0.1.1")
case class AuthenticationError(message: String) extends LLMError

//@deprecated("Use org.llm4s.error.LLMError.RateLimitError instead", "0.1.1")
case class RateLimitError(message: String) extends LLMError

//@deprecated("Use org.llm4s.error.LLMError.ServiceError instead", "0.1.1")
case class ServiceError(message: String, code: Int) extends LLMError

//@deprecated("Use org.llm4s.error.LLMError.ValidationError instead", "0.1.1")
case class ValidationError(message: String) extends LLMError

//@deprecated("Use org.llm4s.error.LLMError.UnknownError instead", "0.1.1")
case class UnknownError(throwable: Throwable) extends LLMError {
  def message: String = throwable.getMessage
}
