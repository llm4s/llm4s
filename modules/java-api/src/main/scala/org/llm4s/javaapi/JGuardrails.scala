package org.llm4s.javaapi

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ JSONValidator, LengthCheck, ProfanityFilter, RegexValidator }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * A built-in guardrail that checks an agent's final answer, for [[JAgentBuilder.outputGuardrails]] (#2476). Obtain one
 * from [[JGuardrails]]. A [[JGuardrail]] checks the answer too, and the query as well.
 */
sealed class JOutputGuardrail private[javaapi] (
  token: FacadeToken,
  private[javaapi] val output: Result[OutputGuardrail]
) {

  FacadeToken.check(token, "JOutputGuardrail")

  /** The guardrail's name, as a blocked turn's `status().guardrail()` reports it; empty for one that was refused. */
  def name(): String = output.fold(_ => "", _.name)

  override def toString: String =
    s"${getClass.getSimpleName}(${output.fold(e => s"invalid: ${e.message}", _.name)})"
}

/**
 * A built-in guardrail that checks an agent's query, for [[JAgentBuilder.inputGuardrails]], or its final answer, for
 * [[JAgentBuilder.outputGuardrails]] (#2476). Obtain one from [[JGuardrails]].
 */
final class JGuardrail private[javaapi] (
  token: FacadeToken,
  private[javaapi] val input: Result[InputGuardrail & OutputGuardrail]
) extends JOutputGuardrail(token, input)

/**
 * The built-in guardrails, for an agent built with [[Llm4s.agentBuilder]] (#2476).
 *
 * {{{
 * Llm4s.agentBuilder("support", client)
 *     .inputGuardrails(List.of(JGuardrails.length(1, 10_000), JGuardrails.profanity()))
 *     .outputGuardrails(List.of(JGuardrails.json()))
 * }}}
 *
 * A guardrail that fails blocks the turn: it ends with status `BLOCKED`, naming the guardrail and its reason, and the
 * conversation stays usable. No factory throws: an invalid argument (a negative length, a `null` pattern) gives a
 * guardrail that the agent's `build()` refuses with a failed result naming the argument.
 */
object JGuardrails {

  /** Text between `min` and `max` characters long, inclusive. `min` must be at least 0 and `max` at least `min`. */
  def length(min: Int, max: Int): JGuardrail =
    guardrail(
      if (min < 0) Left(ValidationError.invalid("min", s"must be at least 0, was $min"))
      else if (max < min) Left(ValidationError.invalid("max", s"must be at least min ($min), was $max"))
      else Right(new LengthCheck(min, max))
    )

  /**
   * Text that contains no word of the built-in word list, compared without regard to case. The list is deliberately
   * minimal: use it as an example of a word filter, not as a production profanity list.
   */
  def profanity(): JGuardrail = guardrail(Right(new ProfanityFilter()))

  /** Text that contains a match of `pattern`, in `java.util.regex` syntax; an unsafe or invalid pattern fails every text. */
  def regex(pattern: String): JGuardrail =
    guardrail(Option(pattern).toRight(ValidationError.required("pattern")).map(p => RegexValidator(p)))

  /** `regex(pattern)`, reporting `errorMessage` when the text does not match. */
  def regex(pattern: String, errorMessage: String): JGuardrail =
    guardrail(for {
      p <- Option(pattern).toRight(ValidationError.required("pattern"))
      m <- Option(errorMessage).toRight(ValidationError.required("errorMessage"))
    } yield RegexValidator(p, m))

  /** An answer that is valid JSON. It checks answers only, so it goes in `outputGuardrails`. */
  def json(): JOutputGuardrail = new JOutputGuardrail(FacadeToken.INSTANCE, Right(new JSONValidator()))

  private def guardrail(g: Result[InputGuardrail & OutputGuardrail]): JGuardrail =
    new JGuardrail(FacadeToken.INSTANCE, g)
}
