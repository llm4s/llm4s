package org.llm4s.agent.guardrails

import org.llm4s.annotation.Experimental
import org.llm4s.agent.graph.middleware.{ GuardrailUnavailable, UsageAccounting }
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.types.Result

import scala.annotation.tailrec

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
@Experimental
class CompositeGuardrail[A](
  guardrails: Seq[Guardrail[A]],
  mode: ValidationMode = ValidationMode.All
) extends Guardrail[A] {

  def validate(value: A): Result[A] = run(value)(_.validate(_))

  /** As `validate(value)`, each guardrail validating with `context`, so a judge's calls are accounted. */
  override def validateIn(value: A, context: UsageAccounting): Result[A] = run(value)(_.validateIn(_, context))

  private def run(value: A)(check: (Guardrail[A], A) => Result[A]): Result[A] = mode match {
    case ValidationMode.All =>
      validateAll(value, check)

    case ValidationMode.Any =>
      validateAny(check(_, value))

    case ValidationMode.First =>
      validateFirst(value, check(_, value))
  }

  /**
   * All guardrails must pass.
   * Runs all guardrails and aggregates all errors. The value is threaded through them as a guardrail
   * list threads it: each sees the value as the one before it rewrote it, `all` returns the last rewrite
   * (so a masking guardrail inside it masks), and a failing guardrail leaves the value unchanged for the
   * next (#3232). When a guardrail refuses (a `ValidationError`), the errors are reported together as one; when
   * some failed but none refused (a judge's provider call failed), the first such error is returned unchanged, so
   * `GuardrailMiddleware` reports an outage, not a block (#3247).
   */
  private def validateAll(value: A, check: (Guardrail[A], A) => Result[A]): Result[A] = {
    val (last, errors) = guardrails.foldLeft((value, Vector.empty[LLMError])) { case ((current, failed), guardrail) =>
      check(guardrail, current) match {
        case Right(next) => (next, failed)
        case Left(err)   => (current, failed :+ err)
      }
    }

    if (errors.isEmpty) Right(last)
    else if (errors.exists(GuardrailUnavailable.isRefusal)) Left(CompositeGuardrail.multipleFailures(errors))
    else Left(errors.head) // none refused: a guardrail could not decide, and its error says why (#3247)
  }

  /**
   * At least one guardrail must pass.
   * Stops at the first guardrail that passes and returns its result, so a guardrail after it does not
   * run (an LLM judge costs a call). When none passes, every guardrail has run and their errors are
   * reported together, unless one could not decide (an error that is not a refusal, such as a judge's rate limit):
   * that error is returned unchanged, since that guardrail might have passed (#3247).
   */
  private def validateAny(check: Guardrail[A] => Result[A]): Result[A] = {
    @tailrec def next(remaining: List[Guardrail[A]], errors: Vector[LLMError]): Result[A] =
      remaining match {
        case Nil =>
          // one that could not decide might have passed, so its error is the result, not a refusal (#3247)
          errors.find(e => !GuardrailUnavailable.isRefusal(e)) match {
            case Some(undecided) => Left(undecided)
            case None =>
              Left(
                ValidationError.invalid(
                  "composite",
                  s"All validations failed: ${errors.map(_.formatted).mkString("; ")}"
                )
              )
          }
        case guardrail :: rest =>
          check(guardrail) match {
            case passed @ Right(_) => passed
            case Left(err)         => next(rest, errors :+ err)
          }
      }

    next(guardrails.toList, Vector.empty)
  }

  /**
   * Returns on first result (success or failure).
   * Useful for expensive guardrails where order matters.
   */
  private def validateFirst(value: A, check: Guardrail[A] => Result[A]): Result[A] =
    guardrails.headOption match {
      case Some(guardrail) => check(guardrail)
      case None            => Right(value)
    }

  val name: String = s"CompositeGuardrail(${guardrails.map(_.name).mkString(", ")})"

  override val description: Option[String] = Some(
    s"Composite guardrail with mode=$mode: ${guardrails.map(_.name).mkString(", ")}"
  )
}

@Experimental
object CompositeGuardrail {

  /**
   * The error `All` mode reports for `errors`, one or more: every failure, formatted, in one
   * `ValidationError`. Shared with `GuardrailMiddleware`, so the two report failures alike.
   */
  private[agent] def multipleFailures(errors: Seq[LLMError]): ValidationError =
    ValidationError.invalid("composite", s"Multiple validation failures: ${errors.map(_.formatted).mkString("; ")}")

  /**
   * Create a composite guardrail that validates all guardrails.
   * All must pass for validation to succeed.
   */
  def all[A](guardrails: Seq[Guardrail[A]]): CompositeGuardrail[A] =
    new CompositeGuardrail(guardrails, ValidationMode.All)

  /**
   * Create a composite guardrail where at least one must pass.
   * Stops at the first passing guardrail: those after it do not run.
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

    override def validateIn(value: A, context: UsageAccounting): Result[A] =
      guardrails.foldLeft[Result[A]](Right(value))((acc, guardrail) => acc.flatMap(guardrail.validateIn(_, context)))

    val name: String = s"SequentialGuardrail(${guardrails.map(_.name).mkString(" -> ")})"

    override val description: Option[String] = Some(
      s"Sequential validation: ${guardrails.map(_.name).mkString(" -> ")}"
    )
  }
}
