package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * The base of every error llm4s returns in a `Left`.
 *
 * Each of the library's errors is a final case class with a private constructor, built through its companion, and
 * follows these conventions:
 *
 *  - '''`apply`.''' An error that generates its message takes its structured fields, then the free text as
 *    `details` or `reason`: `ServiceError(httpStatus, provider, details)`, `ProcessingError(operation, details)`,
 *    `ValidationError(field, reason)`. An error with nothing to generate a message from takes `message` first:
 *    `ConfigurationError(message)`, `NetworkError(message, cause, endpoint)`.
 *  - '''Extractor.''' Every error has an explicit `unapply`: `message`, then its fields in constructor order. Its
 *    arity is fixed, so a field added after the extractor was written is read by name, not bound by position:
 *    `RateLimitError.origin`, `ContentFilteredError.usage`.
 *  - '''Cause.''' An exception behind an error is `cause: Option[Throwable]`. [[UnknownError]] alone holds a
 *    `Throwable`: it exists only to carry an exception nothing else classified.
 *  - '''Context.''' `context` is derived from the error's fields. [[ExecutionError]] and [[TimeoutError]], raised
 *    from many unrelated call sites, also take the caller's entries (`withContext`).
 */
@Stable
trait LLMError extends Product with Serializable {

  /** Human-readable error message */
  def message: String

  /** Optional error code for programmatic handling */
  def code: Option[String] = None

  /** Additional context information */
  def context: Map[String, String] = Map.empty

  /** Formatted error message with context */
  def formatted: String = {
    val contextStr = context.toList.map { case (k, v) => s"$k=$v" }.mkString("[", ",", "]")
    val codeStr    = code.map(c => s" [$c]").getOrElse("")

    s"${getClass.getSimpleName}: $message$codeStr$contextStr"
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
}
