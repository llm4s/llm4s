package org.llm4s.agent.graph.middleware

import org.llm4s.annotation.Experimental
import org.llm4s.agent.events.GuardrailPhase
import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * What a reviewer is asked when a guardrail refuses a turn's query (`Input`) or its final answer
 * (`Output`): the first failing guardrail, every failure's error, and the refused text.
 */
@Experimental
final case class GuardrailReview private (phase: GuardrailPhase, guardrail: String, reason: String, text: String)
    derives ReadWriter:
  def withPhase(p: GuardrailPhase): GuardrailReview = GuardrailReview(p, guardrail, reason, text)
  def withGuardrail(g: String): GuardrailReview     = GuardrailReview(phase, g, reason, text)
  def withReason(r: String): GuardrailReview        = GuardrailReview(phase, guardrail, r, text)
  def withText(t: String): GuardrailReview          = GuardrailReview(phase, guardrail, reason, t)

@Experimental
object GuardrailReview:
  def apply(phase: GuardrailPhase, guardrail: String, reason: String, text: String): GuardrailReview =
    new GuardrailReview(phase, guardrail, reason, text)

/** A reviewer's verdict on a refused query or answer. */
@Experimental
enum GuardrailVerdict derives ReadWriter:
  /**
   * Let the refused text through, overriding the guardrails that refused it: it goes on as the guardrails that
   * passed it rewrote it (a `PIIMasker` beside the refusing guardrail still masks it, #3248).
   */
  case Allow

  /** Use `text` in its place; it is not checked by the guardrails again, since the reviewer wrote it. */
  case Edit(text: String)

  /** Refuse it, as [[GuardrailMiddleware]] would have: the run is blocked with [[GuardrailBlocked]]. */
  case Block

/**
 * Runs guardrails as [[GuardrailMiddleware]] does, but a refusal asks a reviewer instead of blocking:
 * the turn suspends with a [[GuardrailReview]], and the [[GuardrailVerdict]] it is answered with lets
 * the text through, replaces it, or blocks the run as `GuardrailMiddleware` would have. Text the
 * guardrails accept passes without a question, and a guardrail that transforms (`Fix`) still applies.
 *
 * Its input guardrails run in `beforeAgent`, not at admission, because a review suspends the turn: the query is in
 * the turn's first checkpoint before they see it, and while an input review waits the suspended question holds the
 * refused text and the guardrail's reason. While an output review waits, the answer is stored but the turn has no
 * outcome yet. Use [[GuardrailMiddleware]] for what must never be stored.
 *
 * A verdict is used only for the text and phase it was asked about, whatever the refusal's reason, and the
 * guardrails do not decide on that text again once it is given, so a judge whose reason varies is asked once; if
 * the hook runs again on different text, the guardrails run on it and the reviewer may be asked again. On `Allow`
 * the list runs once more on the text with every failure ignored, so the rewrites of the guardrails that passed it
 * apply (#3248); a judge in the list is called again then, and its verdict ignored.
 */
@Experimental
final class GuardrailReviewMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrail-review")
) extends AgentMiddleware.Asking[GuardrailReview, GuardrailVerdict]:

  override def beforeAgent(text: String, context: ModelContext): Result[BoundaryResult] =
    reviewed(GuardrailPhase.Input, input, text, context).map(BoundaryResult(_))

  /** `true` when there are output guardrails: a streamed answer is held until they pass it (#3231). */
  override def guardsAnswer: Boolean = output.nonEmpty

  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    reviewed(GuardrailPhase.Output, output, answer, context).map(BoundaryResult(_))

  // A verdict is matched on the refused text and phase, not on the refusal's wording: a guardrail whose reason
  // varies between runs (an LLM judge) would otherwise be asked about forever. Once a verdict is given, the
  // guardrails do not decide on that text again - the reviewer has decided it. An Allow overrides only the refusals:
  // the list runs once more, its failures ignored, so the rewrites of the guardrails that passed still apply (#3248).
  private def reviewed(
    phase: GuardrailPhase,
    guardrails: Seq[Guardrail[String]],
    text: String,
    context: ModelContext
  ): Result[String] =
    answered(context).filter(r => r.question.phase == phase && r.question.text == text) match
      case Some(verdict) =>
        verdict.answer match
          case GuardrailVerdict.Allow      => Right(GuardrailMiddleware.threaded(guardrails, text, context)._1)
          case GuardrailVerdict.Edit(next) => Right(next)
          case GuardrailVerdict.Block =>
            Left(GuardrailBlocked(verdict.question.guardrail, Some(verdict.question.reason)))
      case None =>
        GuardrailMiddleware.run(guardrails, text, context) match
          case Left(blocked: GuardrailBlocked) =>
            ask(GuardrailReview(phase, blocked.guardrail, blocked.reason.getOrElse(blocked.message), text))
          case other => other
