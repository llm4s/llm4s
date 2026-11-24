package org.llm4s.error

import org.llm4s.annotation.Stable

import scala.concurrent.duration.FiniteDuration

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
 *  - '''Retrying.''' [[isRetryable]] is the one answer to "is the same request worth sending again": `false`
 *    unless the error says otherwise. There are no marker traits; an error of your own overrides the member.
 *  - '''Transport facts.''' [[httpStatus]], [[retryAfter]] and [[requestId]] are what the server's response said,
 *    on every error, `None` when the error is not an HTTP response or the response did not say.
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

  /**
   * Whether sending the identical request again may succeed, with nobody in the loop: the library's one rule for
   * retrying, which [[org.llm4s.reliability.RetryPolicy]], [[org.llm4s.reliability.ReliableClient]] and the agent
   * graph's node retries all apply.
   *
   * It is `true` for a [[RateLimitError]], a [[TimeoutError]], a [[NetworkError]], an [[ExecutionError]] and a
   * [[ServiceError]] whose status is 408, 429 or 5xx, and `false` for everything else: a request the provider
   * refused as wrong (any other 4xx, a [[ValidationError]], a [[ContextLengthError]], a [[ModelNotFoundError]]), a
   * rejected credential, a cancellation, and the two errors that need something to happen first - an
   * [[OptimisticLockFailure]] (re-read the record) and a [[CircuitOpenError]] (wait for the circuit).
   *
   * An error type of your own is not retried unless it overrides this member.
   */
  def isRetryable: Boolean = false

  /** The HTTP status of the response this error was mapped from; `None` when it is not a response's error. */
  def httpStatus: Option[Int] = None

  /**
   * The delay the server asked for before the request is sent again (an HTTP `Retry-After` header), and only
   * that: `None` when the server gave none. A delay the library chooses when the server is silent is the retry
   * policy's, never reported here, so "the server said" and "we guessed" stay distinguishable.
   */
  def retryAfter: Option[FiniteDuration] = None

  /** The provider's id for the failed request, from the response's headers or body, for a support ticket. */
  def requestId: Option[String] = None

  /** Formatted error message with context */
  def formatted: String = {
    val contextStr = context.toList.map { case (k, v) => s"$k=$v" }.mkString("[", ",", "]")
    val codeStr    = code.map(c => s" [$c]").getOrElse("")

    s"${getClass.getSimpleName}: $message$codeStr$contextStr"
  }
}

object LLMError {

  /**
   * The text that names a cause in an error's message or `context`: the exception's message, or its class
   * name (`java.lang.RuntimeException`) when the exception has no message or an empty one. `Throwable#getMessage`
   * is `null` for an exception built without a message, and a `null` inside a `Map[String, String]` printed
   * `cause=null` and failed any later read of the value (#1556).
   */
  private[llm4s] def describeCause(cause: Throwable): String =
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getName)
}
