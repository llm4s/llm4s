package org.llm4s.error

import org.llm4s.annotation.Stable

import scala.concurrent.duration.FiniteDuration

/**
 * Raised when an operation exceeds its specified time limit.
 *
 * Represents operations that timed out. As a [[RecoverableError]],
 * these can potentially succeed with a different timeout, or by retrying
 * with exponential backoff strategies, assuming the service was temporarily slow.
 *
 * @param message human-readable error description
 * @param timeoutDuration the timeout duration that was exceeded
 * @param operation the operation that timed out (e.g., "api-call", "completion")
 * @param cause optional underlying exception
 * @param context additional key-value context for debugging
 *
 * @example
 * {{{
 * import scala.concurrent.duration._
 *
 * val error = TimeoutError("Request timed out", 30.seconds, "api-call")
 *   .withContext("endpoint", "https://api.openai.com")
 * }}}
 */
@Stable
final case class TimeoutError private (
  message: String,
  timeoutDuration: FiniteDuration,
  operation: String,
  cause: Option[Throwable],
  override val context: Map[String, String]
) extends LLMError
    with RecoverableError {

  /** Adds a single key-value pair to the error context. */
  def withContext(key: String, value: String): TimeoutError =
    copy(context = context + (key -> value))

  /** Adds multiple key-value pairs to the error context. */
  def withContext(entries: Map[String, String]): TimeoutError =
    copy(context = context ++ entries)

  /** Updates the operation and adds it to the context. */
  def withOperation(op: String): TimeoutError =
    copy(operation = op, context = context + ("operation" -> op))
}

object TimeoutError {
  def apply(
    message: String,
    timeoutDuration: FiniteDuration,
    operation: String,
    cause: Option[Throwable] = None,
    context: Map[String, String] = Map.empty
  ): TimeoutError = new TimeoutError(message, timeoutDuration, operation, cause, context)

  def unapply(error: TimeoutError): Option[(String, FiniteDuration, String, Option[Throwable], Map[String, String])] =
    Some((error.message, error.timeoutDuration, error.operation, error.cause, error.context))
}
