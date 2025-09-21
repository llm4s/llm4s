package org.llm4s.error

/**
 * Validation errors for malformed requests
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
