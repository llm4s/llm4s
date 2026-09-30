package org.llm4s.agent.guardrails

import org.llm4s.types.Result

/**
 * Base trait for all guardrails.
 *
 * A guardrail is a pure function that validates a value of type A.
 * Guardrails are used to validate inputs before agent processing and
 * outputs before returning results to users.
 *
 * @tparam A The type of value to validate
 */
trait Guardrail[A] {

  /**
   * Validate a value.
   *
   * This is a PURE FUNCTION - no side effects allowed.
   * Same input always produces same output.
   *
   * @param value The value to validate
   * @return Right(value) if valid, Left(error) if invalid
   */
  def validate(value: A): Result[A]

  /**
   * Name of this guardrail for logging and error messages.
   */
  def name: String

  /**
   * Optional description of what this guardrail validates.
   */
  def description: Option[String] = None

  /**
   * Compose this guardrail with another sequentially.
   *
   * The second guardrail runs only if this one passes.
   *
   * @param other The guardrail to run after this one
   * @return A composite guardrail that runs both in sequence
   */
  def andThen(other: Guardrail[A]): Guardrail[A] =
    CompositeGuardrail.sequential(Seq(this, other))
}

/**
 * Validates user input before agent processing.
 *
 * Input guardrails run BEFORE the LLM is called, validating:
 * - User queries
 * - System prompts
 * - Tool arguments
 */
trait InputGuardrail extends Guardrail[String] {

  /**
   * Optional: Transform the input after validation.
   * Default is identity (no transformation).
   *
   * @param input The validated input
   * @return The transformed input
   */
  def transform(input: String): String = input
}

/**
 * Validates agent output before returning to user.
 *
 * Output guardrails run AFTER the LLM responds, validating:
 * - Assistant messages
 * - Tool results
 * - Final responses
 */
trait OutputGuardrail extends Guardrail[String] {

  /**
   * Optional: Transform the output after validation.
   * Default is identity (no transformation).
   *
   * @param output The validated output
   * @return The transformed output
   */
  def transform(output: String): String = output
}
