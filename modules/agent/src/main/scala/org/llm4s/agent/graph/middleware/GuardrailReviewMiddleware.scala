package org.llm4s.agent.graph.middleware

import org.llm4s.agent.events.GuardrailPhase
import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * What a reviewer is asked when a guardrail refuses a turn's query (`Input`) or its final answer
 * (`Output`): the first failing guardrail, every failure's error, and the refused text.
 */
final case class GuardrailReview private (phase: GuardrailPhase, guardrail: String, reason: String, text: String)
    derives ReadWriter:
  def withPhase(p: GuardrailPhase): GuardrailReview = GuardrailReview(p, guardrail, reason, text)
  def withGuardrail(g: String): GuardrailReview     = GuardrailReview(phase, g, reason, text)
  def withReason(r: String): GuardrailReview        = GuardrailReview(phase, guardrail, r, text)
  def withText(t: String): GuardrailReview          = GuardrailReview(phase, guardrail, reason, t)

object GuardrailReview:
  def apply(phase: GuardrailPhase, guardrail: String, reason: String, text: String): GuardrailReview =
    new GuardrailReview(phase, guardrail, reason, text)

/** A reviewer's verdict on a refused query or answer. */
enum GuardrailVerdict derives ReadWriter:
  /** Let the refused text through unchanged. */
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
 * While an input review waits, nothing of the turn is stored; while an output review waits, the answer
 * is stored but the turn has no outcome yet. A verdict is used only for the text and phase it was asked
 * about, whatever the refusal's reason, and the guardrails do not run on that text again once it is given,
 * so a judge whose reason varies is asked once; if the hook runs again on different text, the guardrails
 * run on it and the reviewer may be asked again.
 */
final class GuardrailReviewMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrail-review")
) extends AgentMiddleware.Asking[GuardrailReview, GuardrailVerdict]:

  override def beforeAgent(text: String, context: RunContext): Result[String] =
    reviewed(GuardrailPhase.Input, text, context)(GuardrailMiddleware.run(input, text))

  override def afterAgent(answer: String, context: RunContext): Result[String] =
    reviewed(GuardrailPhase.Output, answer, context)(GuardrailMiddleware.run(output, answer))

  // A verdict is matched on the refused text and phase, not on the refusal's wording: a guardrail whose reason
  // varies between runs (an LLM judge) would otherwise be asked about forever. Once a verdict is given, the
  // guardrails do not run on that text again - the reviewer has decided it - so a judge is not called again.
  private def reviewed(phase: GuardrailPhase, text: String, context: RunContext)(
    guarded: => Result[String]
  ): Result[String] =
    answered(context).filter(r => r.question.phase == phase && r.question.text == text) match
      case Some(verdict) =>
        verdict.answer match
          case GuardrailVerdict.Allow      => Right(text)
          case GuardrailVerdict.Edit(next) => Right(next)
          case GuardrailVerdict.Block => Left(GuardrailBlocked(verdict.question.guardrail, verdict.question.reason))
      case None =>
        guarded match
          case Left(blocked: GuardrailBlocked) => ask(GuardrailReview(phase, blocked.guardrail, blocked.reason, text))
          case other                           => other
