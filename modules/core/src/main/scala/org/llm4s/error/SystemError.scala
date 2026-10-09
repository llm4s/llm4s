package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * System-level errors for unexpected exceptions that may be transient.
 *
 * Represents unexpected system failures such as resource exhaustion, temporary
 * unavailability, or other transient issues. As a [[RecoverableError]], these
 * can be retried with appropriate backoff strategies.
 *
 * @param message Human-readable error description
 * @param cause Optional underlying exception
 *
 * @example
 * {{{
 * val error = SystemError("Unexpected memory allocation failure", Some(cause))
 * error.context  // Map("exceptionType" -> "OutOfMemoryError")
 * }}}
 */
@Stable
final case class SystemError private (
  override val message: String,
  cause: Option[Throwable]
) extends LLMError
    with RecoverableError {
  override val context: Map[String, String] =
    cause.map(c => Map("exceptionType" -> c.getClass.getSimpleName)).getOrElse(Map.empty)
}

/** Factory methods for creating [[SystemError]] instances. */
object SystemError {

  /** Creates a system error with optional cause. */
  def apply(message: String, cause: Option[Throwable] = None): SystemError =
    new SystemError(message, cause)
}
