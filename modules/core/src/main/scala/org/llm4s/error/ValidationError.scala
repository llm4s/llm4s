package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Raised when the request parameters or inputs fail validation before being sent to the provider.
 *
 * It is not [[LLMError.isRetryable retryable]]: it indicates malformed requests, such as
 * providing invalid parameters, missing required fields, or exceeding token limits
 * in the input. The user must fix the request payload to resolve this error.
 *
 * @param message human-readable description of the validation failure
 * @param field the name of the field that failed validation
 * @param violations list of specific validation rules that were violated
 * @param httpStatus the HTTP status of the provider's response (usually 400), when the provider refused the
 *                   request; `None` when the library refused it before sending
 * @param requestId the provider's id for the refused request, when the response carried one
 */
@Stable
final case class ValidationError private (
  override val message: String,
  field: String,
  violations: List[String],
  override val httpStatus: Option[Int],
  override val requestId: Option[String]
) extends LLMError {

  /** This error, carrying the HTTP status of the response it was mapped from. */
  def withHttpStatus(status: Int): ValidationError = copy(httpStatus = Some(status))

  /** This error, carrying the provider's id for the refused request. */
  def withRequestId(id: String): ValidationError = copy(requestId = Some(id))

  override val context: Map[String, String] = Map("field" -> field) ++
    violations.headOption.fold(Map.empty[String, String])(_ => Map("violations" -> violations.mkString("; ")))

  /**
   * Returns a copy with `violation` appended to `violations`.
   *
   * `context("violations")` reflects the new list. `message` and `field` keep their original values,
   * and this error is left unchanged.
   *
   * @param violation the violated rule to append
   * @return a new error whose `violations` end with `violation`
   */
  def withViolation(violation: String): ValidationError =
    copy(violations = violations :+ violation)

  /**
   * Returns a copy with `newViolations` appended to `violations`, in order.
   *
   * `context("violations")` reflects the new list. `message` and `field` keep their original values,
   * and this error is left unchanged. An empty list yields an error equal to this one.
   *
   * @param newViolations the violated rules to append
   * @return a new error whose `violations` end with `newViolations`
   */
  def withViolations(newViolations: List[String]): ValidationError =
    copy(violations = violations ++ newViolations)
}

object ValidationError {

  /**
   * Creates an error for a single violated rule.
   *
   * The message is `Invalid <field>: <reason>`, `violations` is `List(reason)`, and `context` holds
   * `field` and `violations`.
   *
   * @param field the name of the field that failed validation
   * @param reason why the value was rejected
   * @return the validation error
   */
  def apply(field: String, reason: String): ValidationError =
    new ValidationError(s"Invalid $field: $reason", field, List(reason), None, None)

  /**
   * Creates an error for several violated rules.
   *
   * The message is `Invalid <field>: ` followed by the violations joined with `", "`, while
   * `context("violations")` joins them with `"; "`. An empty list gives a message that ends after the
   * colon and a `context` with no `violations` entry.
   *
   * @param field the name of the field that failed validation
   * @param violations the rules that were violated, in the order given
   * @return the validation error
   */
  def apply(field: String, violations: List[String]): ValidationError =
    new ValidationError(s"Invalid $field: ${violations.mkString(", ")}", field, violations, None, None)

  /**
   * Creates an error for a missing required field.
   *
   * The message is `Field '<field>' is required` (it does not use the `Invalid <field>:` form) and
   * `violations` is `List("required")`.
   *
   * @example
   * {{{
   * val error = ValidationError.required("name")
   * error.message    // "Field 'name' is required"
   * error.violations // List("required")
   * }}}
   *
   * @param field the name of the missing field
   * @return the validation error
   */
  def required(field: String): ValidationError =
    new ValidationError(s"Field '$field' is required", field, List("required"), None, None)

  /**
   * Creates an error for a field whose value is not acceptable.
   *
   * The message is `Field '<field>' is invalid: <reason>` (it does not use the `Invalid <field>:` form)
   * and `violations` is `List(reason)`.
   *
   * @param field the name of the field that failed validation
   * @param reason why the value was rejected
   * @return the validation error
   */
  def invalid(field: String, reason: String): ValidationError =
    new ValidationError(s"Field '$field' is invalid: $reason", field, List(reason), None, None)

  /** Unapply extractor for pattern matching */
  def unapply(error: ValidationError): Option[(String, String, List[String])] =
    Some((error.message, error.field, error.violations))
}
