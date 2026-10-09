package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Error for invalid input values that fail validation.
 *
 * Provides detailed context about which field was invalid, what value was provided,
 * and why it was rejected. As a [[NonRecoverableError]], the input must be corrected
 * before retrying.
 *
 * @param message Human-readable error description
 * @param field The name of the field that has invalid input
 * @param value The invalid value that was provided
 * @param reason Explanation of why the value is invalid
 *
 * @example
 * {{{
 * val error = InvalidInputError("temperature", "2.5", "must be between 0 and 1")
 * error.context  // Map("field" -> "temperature", "value" -> "2.5", "reason" -> "...")
 * }}}
 */
@Stable
final case class InvalidInputError private (
  override val message: String,
  field: String,
  value: String,
  reason: String
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map(
    "field"  -> field,
    "value"  -> value,
    "reason" -> reason
  )
}

/** Factory methods for creating [[InvalidInputError]] instances. */
object InvalidInputError {

  /** Creates an invalid input error with auto-generated message. */
  def apply(field: String, value: String, reason: String): InvalidInputError =
    new InvalidInputError(s"Invalid input for $field: $value", field, value, reason)

  /** Pattern matching extractor returning message, field, value, and reason. */
  def unapply(error: InvalidInputError): Option[(String, String, String, String)] =
    Some((error.message, error.field, error.value, error.reason))
}
