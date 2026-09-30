package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Validates string length is within bounds.
 *
 * Can be used for both input and output validation.
 * Ensures content is neither too short nor too long.
 *
 * @param min Minimum length (inclusive)
 * @param max Maximum length (inclusive)
 */
class LengthCheck(min: Int, max: Int) extends InputGuardrail with OutputGuardrail {
  require(min >= 0, "Minimum length must be non-negative")
  require(max >= min, "Maximum length must be >= minimum length")

  def validate(value: String): Result[String] =
    if (value.length < min) {
      Left(
        ValidationError.invalid(
          "input",
          s"Input too short: ${value.length} characters (minimum: $min)"
        )
      )
    } else if (value.length > max) {
      Left(
        ValidationError.invalid(
          "input",
          s"Input too long: ${value.length} characters (maximum: $max)"
        )
      )
    } else {
      Right(value)
    }

  val name: String = "LengthCheck"

  override val description: Option[String] = Some(
    s"Validates length between $min and $max characters"
  )

  // Resolve conflicting transform methods from both traits
  override def transform(input: String): String = input
}

object LengthCheck {

  /**
   * Create a length check with minimum and maximum bounds.
   */
  def apply(min: Int, max: Int): LengthCheck = new LengthCheck(min, max)

  /**
   * Create a length check with only maximum bound.
   */
  def maxOnly(max: Int): LengthCheck = new LengthCheck(0, max)

  /**
   * Create a length check with only minimum bound.
   */
  def minOnly(min: Int): LengthCheck = new LengthCheck(min, Int.MaxValue)
}
