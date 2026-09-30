package org.llm4s.agent.guardrails

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Combines multiple guardrails with configurable validation mode.
 *
 * Supports three modes:
 * - All: All guardrails must pass (strictest)
 * - Any: At least one guardrail must pass (OR logic)
 * - First: First result wins (performance optimization)
 *
 * @param guardrails The guardrails to combine
 * @param mode How to combine validation results
 * @tparam A The type of value to validate
 */
class CompositeGuardrail[A](
  guardrails: Seq[Guardrail[A]],
  mode: ValidationMode = ValidationMode.All
) extends Guardrail[A] {

  def validate(value: A): Result[A] = mode match {
    case ValidationMode.All =>
      validateAll(value)

    case ValidationMode.Any =>
      validateAny(value)

    case ValidationMode.First =>
      validateFirst(value)
  }

  /**
   * All guardrails must pass.
   * Runs all guardrails and aggregates all errors.
   */
  private def validateAll(value: A): Result[A] = {
    val results = guardrails.map(_.validate(value))
    val errors  = results.collect { case Left(err) => err }

    if (errors.isEmpty) {
      Right(value)
    } else {
      // Aggregate all errors
      Left(
        ValidationError.invalid(
          "composite",
          s"Multiple validation failures: ${errors.map(_.formatted).mkString("; ")}"
        )
      )
    }
  }

  /**
   * At least one guardrail must pass.
   * Returns on first success.
   */
  private def validateAny(value: A): Result[A] = {
    val results   = guardrails.map(_.validate(value))
    val successes = results.collect { case Right(v) => v }

    if (successes.nonEmpty) {
      Right(successes.head)
    } else {
      val errors = results.collect { case Left(err) => err }
      Left(
        ValidationError.invalid(
          "composite",
          s"All validations failed: ${errors.map(_.formatted).mkString("; ")}"
        )
      )
    }
  }

  /**
   * Returns on first result (success or failure).
   * Useful for expensive guardrails where order matters.
   */
  private def validateFirst(value: A): Result[A] =
    guardrails.headOption match {
      case Some(guardrail) => guardrail.validate(value)
      case None            => Right(value)
    }

  val name: String = s"CompositeGuardrail(${guardrails.map(_.name).mkString(", ")})"

  override val description: Option[String] = Some(
    s"Composite guardrail with mode=$mode: ${guardrails.map(_.name).mkString(", ")}"
  )
}

object CompositeGuardrail {

  /**
   * Create a composite guardrail that validates all guardrails.
   * All must pass for validation to succeed.
   */
  def all[A](guardrails: Seq[Guardrail[A]]): CompositeGuardrail[A] =
    new CompositeGuardrail(guardrails, ValidationMode.All)

  /**
   * Create a composite guardrail where at least one must pass.
   * Returns success on first passing guardrail.
   */
  def any[A](guardrails: Seq[Guardrail[A]]): CompositeGuardrail[A] =
    new CompositeGuardrail(guardrails, ValidationMode.Any)

  /**
   * Create a composite guardrail that runs guardrails sequentially.
   * Stops on first failure (short-circuit evaluation).
   */
  def sequential[A](guardrails: Seq[Guardrail[A]]): Guardrail[A] = new Guardrail[A] {
    def validate(value: A): Result[A] =
      guardrails.foldLeft[Result[A]](Right(value))((acc, guardrail) => acc.flatMap(guardrail.validate))

    val name: String = s"SequentialGuardrail(${guardrails.map(_.name).mkString(" -> ")})"

    override val description: Option[String] = Some(
      s"Sequential validation: ${guardrails.map(_.name).mkString(" -> ")}"
    )
  }
}
