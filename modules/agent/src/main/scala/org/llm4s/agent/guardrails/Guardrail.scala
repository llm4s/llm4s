package org.llm4s.agent.guardrails

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.agent.graph.middleware.UsageAccounting
import org.llm4s.types.Result

import scala.annotation.unused

/**
 * Base trait for all guardrails.
 *
 * A guardrail is a pure function that validates a value of type A.
 * Guardrails are used to validate inputs before agent processing and
 * outputs before returning results to users.
 *
 * Inside an agent a guardrail sees only the turn's query (and the user messages of an imported `history`) and the
 * turn's final answer; see [[InputGuardrail]] and [[OutputGuardrail]] for what that leaves unguarded.
 *
 * @tparam A The type of value to validate
 */
@Stable
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
   * Validates `value` inside an agent, as `validate(value)` does, with the `context` of the stage that runs it: an
   * input guardrail's is the turn's admission (`AdmissionContext`), an output guardrail's the `afterAgent` hook's
   * `ModelContext`. A guardrail that calls a model overrides this to call through `context.accounted`, so the call is
   * counted in the thread's usage and on the run's terminal event even when the task fails (design §4.22); one that
   * combines guardrails passes `context` on to each. `GuardrailMiddleware` and `GuardrailReviewMiddleware` call this
   * form. A guardrail that needs more than accounting matches on the context it was given.
   *
   * Named apart from the Stable `validate(value)` so that MiMa, which skips an `@Experimental` method with every
   * overload of its name, still checks that one (#2835).
   */
  @Experimental
  def validateIn(value: A, @unused context: UsageAccounting): Result[A] = validate(value)

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
 * In an agent (`withInputGuardrails`, `GuardrailMiddleware`) input guardrails run once per turn, as the turn is
 * admitted and before the model is called, on:
 * - the turn's query;
 * - the text of each user message in a `history` the turn imports.
 *
 * Nothing else is an input to them (#3246): the system prompt, a middleware's prompt sections, a tool call's
 * arguments and a tool's result are never passed to an input guardrail, so text a tool returns (a web page, a file)
 * reaches the model unguarded. Validate a tool's arguments in the tool, and its result in a `wrapToolCall`
 * middleware.
 */
@Stable
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

object InputGuardrail {

  /**
   * `guardrail` as an input guardrail, for `GuardrailMiddleware`: a [[CompositeGuardrail]] or any other
   * `Guardrail[String]` (a cast throws a `ClassCastException`). `validate` and `validateIn` both delegate, so a
   * guardrail that calls a model through the hook's `context` keeps doing so, and the name and description are the
   * guardrail's.
   * One that already is an [[InputGuardrail]] is returned unchanged.
   */
  def from(guardrail: Guardrail[String]): InputGuardrail = guardrail match {
    case input: InputGuardrail => input
    case other =>
      new InputGuardrail {
        def validate(value: String): Result[String] = other.validate(value)
        override def validateIn(value: String, context: UsageAccounting): Result[String] =
          other.validateIn(value, context)
        val name: String                         = other.name
        override def description: Option[String] = other.description
      }
  }
}

/**
 * Validates agent output before returning to user.
 *
 * In an agent (`withOutputGuardrails`, `GuardrailMiddleware`) output guardrails run on the turn's final answer: the
 * last assistant message, the one without tool calls, once the model has finished. What they return is the answer
 * stored and returned.
 *
 * Nothing else is an output to them (#3246): tool results, and the text of an assistant message that also makes tool
 * calls, are stored, streamed and sent back to the model unguarded, and a turn that ends at its step or token limit
 * has no final answer, so its output guardrails do not run.
 */
@Stable
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

object OutputGuardrail {

  /**
   * `guardrail` as an output guardrail, for `GuardrailMiddleware`; see [[InputGuardrail.from]]. One that already is
   * an [[OutputGuardrail]] is returned unchanged.
   */
  def from(guardrail: Guardrail[String]): OutputGuardrail = guardrail match {
    case output: OutputGuardrail => output
    case other =>
      new OutputGuardrail {
        def validate(value: String): Result[String] = other.validate(value)
        override def validateIn(value: String, context: UsageAccounting): Result[String] =
          other.validateIn(value, context)
        val name: String                         = other.name
        override def description: Option[String] = other.description
      }
  }
}
