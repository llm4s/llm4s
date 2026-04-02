package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.security.RegexSafetyManager
import org.llm4s.types.Result

import java.util.regex.Pattern
import scala.util.matching.Regex

/**
 * Validates that content matches a regular expression.
 *
 * Can be used for both input and output validation.
 * Useful for enforcing format requirements like email addresses,
 * phone numbers, or custom patterns.
 *
 * @param pattern The regex pattern to match
 * @param errorMessage Optional custom error message
 */
class RegexValidator(
  compiledPattern: Pattern,
  patternDescription: String,
  errorMessage: Option[String] = None,
  fallbackError: Option[String] = None
) extends InputGuardrail
    with OutputGuardrail {

  def this(pattern: Regex, errorMessage: Option[String], fallbackError: Option[String]) =
    this(pattern.pattern, pattern.toString, errorMessage, fallbackError)

  def this(pattern: Regex, errorMessage: Option[String]) =
    this(pattern.pattern, pattern.toString, errorMessage, None)

  def this(pattern: Regex) =
    this(pattern.pattern, pattern.toString, None, None)

  def validate(value: String): Result[String] =
    fallbackError match {
      case Some(error) => Left(ValidationError.invalid("value", error))
      case None =>
        RegexSafetyManager.safeFind(compiledPattern, value) match {
          case Right(true) => Right(value)
          case Right(false) =>
            Left(
              ValidationError.invalid(
                "value",
                errorMessage.getOrElse(s"Value does not match pattern: $patternDescription")
              )
            )
          case Left(error) =>
            Left(ValidationError.invalid("value", s"Regex security error: $error"))
        }
    }

  val name: String = "RegexValidator"

  override val description: Option[String] = Some(
    s"Validates against pattern: $patternDescription"
  )

  // Resolve conflicting transform methods from both traits
  override def transform(input: String): String = input
}

object RegexValidator {

  /**
   * Create a regex validator from a pattern string.
   */
  def apply(pattern: String): RegexValidator =
    RegexSafetyManager.safeCompile(pattern) match {
      case Right(compiled) => new RegexValidator(compiled, pattern)
      case Left(error) =>
        new RegexValidator(
          Pattern.compile("(?!)"),
          pattern,
          fallbackError = Some(s"Invalid or unsafe regex pattern: $error")
        )
    }

  /**
   * Create a regex validator from a Regex object.
   */
  def apply(pattern: Regex): RegexValidator =
    new RegexValidator(pattern)

  /**
   * Create a regex validator with custom error message.
   */
  def apply(pattern: String, errorMessage: String): RegexValidator =
    RegexSafetyManager.safeCompile(pattern) match {
      case Right(compiled) => new RegexValidator(compiled, pattern, Some(errorMessage))
      case Left(error) =>
        new RegexValidator(
          Pattern.compile("(?!)"),
          pattern,
          Some(errorMessage),
          Some(s"Invalid or unsafe regex pattern: $error")
        )
    }

  /**
   * Validator for email addresses (basic pattern).
   */
  def email: RegexValidator = new RegexValidator(
    "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".r,
    Some("Invalid email address format")
  )

  /**
   * Validator for phone numbers (basic pattern).
   */
  def phone: RegexValidator = new RegexValidator(
    "^\\+?[0-9]{10,15}$".r,
    Some("Invalid phone number format")
  )

  /**
   * Validator for alphanumeric content only.
   */
  def alphanumeric: RegexValidator = new RegexValidator(
    "^[A-Za-z0-9]+$".r,
    Some("Content must be alphanumeric")
  )
}
