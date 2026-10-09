package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * A simple error with just a message and no additional context.
 *
 * Use this error type for basic error cases where no structured context is needed.
 * For errors with more context, prefer specific error types like [[ValidationError]]
 * or [[ConfigurationError]].
 *
 * @param message Human-readable error description
 *
 * @example
 * {{{
 * val error = SimpleError("Something went wrong")
 * error.message  // "Something went wrong"
 * error.context  // Map.empty
 * }}}
 */
@Stable
final case class SimpleError private (
  override val message: String
) extends LLMError
    with NonRecoverableError

/** Factory methods for creating [[SimpleError]] instances. */
object SimpleError {

  /** Creates a simple error with the given message. */
  def apply(message: String): SimpleError = new SimpleError(message)

  /** Pattern matching extractor returning message and context. */
  def unapply(error: SimpleError): Option[(String, Map[String, String])] =
    Some((error.message, error.context))
}
