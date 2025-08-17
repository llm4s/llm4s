package org.llm4s.error

import cats.Show

/**
 * Enhanced comprehensive error hierarchy for LLM operations using ADTs.
 *
 * This replaces the simple org.llm4s.llmconnect.model.LLMError with a
 * much more comprehensive error system.
 *
 * Key improvements over legacy version:
 * - Structured context information
 * - Recovery guidance built-in - Trait-based recoverability
 * - Provider-agnostic design
 * - Rich error formatting
 * - Type-safe error handling
 * - Private case class constructors with smart constructors
 */

trait LLMError extends Product with Serializable {

  /** Human-readable error message */
  def message: String

  /** Optional error code for programmatic handling */
  def code: Option[String] = None

  /** Additional context information */
  def context: Map[String, String] = Map.empty

  /** DEPRECATED: Use type-level markers instead */
  @deprecated("Use pattern matching on RecoverableError/NonRecoverableError traits", "0.1.9")
  def isRecoverable: Boolean = this match {
    case _: RecoverableError    => true
    case _: NonRecoverableError => false
  }

  /** Formatted error message with context */
  def formatted: String = {
    val contextStr = context.toList.map { case (k, v) => s"$k=$v" }.mkString("[", ",", "]")
    s"${getClass.getSimpleName}: $message$contextStr"
  }
}

object LLMError {

  def fromThrowable(throwable: Throwable): LLMError = throwable match {
    case _: java.net.SocketTimeoutException =>
      NetworkError("Request timeout", Some(throwable), "unknown")
    case _: java.net.ConnectException =>
      NetworkError("Connection failed", Some(throwable), "unknown")
    case ex if Option(ex.getMessage).exists(_.contains("401")) =>
      AuthenticationError("unknown", "Authentication failed")
    case ex if Option(ex.getMessage).exists(_.contains("429")) =>
      RateLimitError("unknown")
    case ex =>
      UnknownError(Option(ex.getMessage).getOrElse("Unknown error"), ex)
  }

  /**
   * Type-safe recoverability checks
   * @param error LLMError
   * @return
   */

  def isRecoverable(error: LLMError): Boolean = error match {
    case _: RecoverableError        => true
    case _: NonRecoverableError     => false
    case serviceError: ServiceError => serviceError.isRecoverableStatus
  }

  def recoverableErrors(errors: List[LLMError]): List[LLMError] =
    errors.filter(isRecoverable)

  def nonRecoverableErrors(errors: List[LLMError]): List[LLMError] =
    errors.filterNot(isRecoverable)

  /**
   * Cats integration
   */

  /** Static show method - always works */
  def show(error: LLMError): String = error.formatted

  /** Extension methods for ergonomic usage */
  implicit class LLMErrorDisplayOps(error: LLMError) {
    def show: String    = error.formatted
    def display: String = error.formatted
  }

  implicit val llmErrorShow: Show[LLMError] = Show.show(_.formatted)

  // Smart constructors for backward compatibility
  def processingFailed(operation: String, message: String, cause: Option[Throwable] = None): ProcessingError =
    ProcessingError(operation, message, cause)

  def invalidImageInput(field: String, value: String, reason: String): InvalidInputError =
    InvalidInputError(field, value, reason)

  def apiCallFailed(
    provider: String,
    message: String,
    statusCode: Option[Int] = None,
    responseBody: Option[String] = None
  ): APIError =
    APIError(provider, message, statusCode, responseBody)
}
