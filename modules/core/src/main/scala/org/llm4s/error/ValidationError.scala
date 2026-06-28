package org.llm4s.error

/**
 * Raised when the request parameters or inputs fail validation before being sent to the provider.
 *
 * This is a [[NonRecoverableError]]: it indicates malformed requests, such as
 * providing invalid parameters, missing required fields, or exceeding token limits
 * in the input. The user must fix the request payload to resolve this error.
 *
 * @param message human-readable description of the validation failure
 * @param field the name of the field that failed validation
 * @param violations list of specific validation rules that were violated
 */
final case class ValidationError private (
  override val message: String,
  field: String,
  violations: List[String]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("field" -> field) ++
    violations.headOption.fold(Map.empty[String, String])(_ => Map("violations" -> violations.mkString("; ")))

  def withViolation(violation: String): ValidationError =
    copy(violations = violations :+ violation)

  def withViolations(newViolations: List[String]): ValidationError =
    copy(violations = violations ++ newViolations)
}

object ValidationError {
  def apply(field: String, reason: String): ValidationError =
    new ValidationError(s"Invalid $field: $reason", field, List(reason))

  def apply(field: String, violations: List[String]): ValidationError =
    new ValidationError(s"Invalid $field: ${violations.mkString(", ")}", field, violations)

  def required(field: String): ValidationError =
    apply(s"Field '$field' is required", field, List("required"))

  def invalid(field: String, reason: String): ValidationError =
    apply(s"Field '$field' is invalid: $reason", field, List(reason))

  /** Unapply extractor for pattern matching */
  def unapply(error: ValidationError): Option[(String, String, List[String])] =
    Some((error.message, error.field, error.violations))
}
