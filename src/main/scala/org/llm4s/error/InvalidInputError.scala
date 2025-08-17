package org.llm4s.error

/**
 * Invalid input errors for validation failures
 */
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

object InvalidInputError {
  def apply(field: String, value: String, reason: String): InvalidInputError =
    new InvalidInputError(s"Invalid input for $field: $value", field, value, reason)

  /** Unapply extractor for pattern matching */
  def unapply(error: InvalidInputError): Option[(String, String, String, String)] =
    Some((error.message, error.field, error.value, error.reason))
}
