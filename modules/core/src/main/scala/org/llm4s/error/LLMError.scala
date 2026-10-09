package org.llm4s.error

import org.llm4s.annotation.Stable

import org.slf4j.MDC

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

@Stable
trait LLMError extends Product with Serializable {

  /** Human-readable error message */
  def message: String

  /** Optional error code for programmatic handling */
  def code: Option[String] = None

  /** Additional context information */
  def context: Map[String, String] = Map.empty

  /** Error correlation IDs for debugging */
  def correlationId: Option[String] = Option(MDC.get("correlationId"))

  /** Formatted error message with context */
  def formatted: String = {
    val contextStr = context.toList.map { case (k, v) => s"$k=$v" }.mkString("[", ",", "]")
    val corrStr    = correlationId.map(id => s" [correlationId=$id]").getOrElse("")
    val codeStr    = code.map(c => s" [$c]").getOrElse("")

    s"${getClass.getSimpleName}: $message$codeStr$contextStr$corrStr"
  }
}

object LLMError {

  /**
   * Whether the error may succeed if tried again, perhaps after the caller does something first: `true` for a
   * [[RecoverableError]], `false` for anything else.
   *
   * It is total. An error that carries neither marker - a library error whose recoverability depends on more than
   * its type, such as `EmbeddingError`, or a custom `LLMError` that does not say - is not recoverable, as
   * [[org.llm4s.reliability.RetryPolicy.isRetryable]] and [[ErrorRecovery]] already treat it: nothing says it can
   * succeed on a retry. Mix in [[RecoverableError]] or [[NonRecoverableError]] to say which an error is.
   *
   * This is wider than what the library retries by itself. [[org.llm4s.reliability.RetryPolicy.isRetryable]] is the
   * rule for automatic retry of the identical request; it is derived from this check and excludes a client-error
   * response and an [[OptimisticLockFailure]], which need the caller first.
   *
   * @param error LLMError
   * @return whether the error is recoverable
   */
  def isRecoverable(error: LLMError): Boolean = error match {
    case _: RecoverableError => true
    case _                   => false
  }

  /** The errors that are [[isRecoverable]]. */
  def recoverableErrors(errors: List[LLMError]): List[LLMError] =
    errors.filter(isRecoverable)

  /** The errors that are not [[isRecoverable]]: every [[NonRecoverableError]], and every error with no marker. */
  def nonRecoverableErrors(errors: List[LLMError]): List[LLMError] =
    errors.filterNot(isRecoverable)

  /**
   * The text that names a cause in an error's message or `context`: the exception's message, or its class
   * name (`java.lang.RuntimeException`) when the exception has no message or an empty one. `Throwable#getMessage`
   * is `null` for an exception built without a message, and a `null` inside a `Map[String, String]` printed
   * `cause=null` and failed any later read of the value (#1556).
   */
  private[llm4s] def describeCause(cause: Throwable): String =
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getName)

  /**
   * Cats integration
   */

  /** Static show method - always works */
  def show(error: LLMError): String = error.formatted

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
