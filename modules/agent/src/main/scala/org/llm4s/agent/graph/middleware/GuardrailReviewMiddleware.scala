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
final case class GuardrailReview(phase: GuardrailPhase, guardrail: String, reason: String, text: String)
    derives ReadWriter

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
 * is stored but the turn has no outcome yet. A verdict is used only for the refusal it was asked about:
 * if the guardrails refuse different text when the hook runs again, the reviewer is asked again.
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

  private def reviewed(phase: GuardrailPhase, text: String, context: RunContext)(
    guarded: Result[String]
  ): Result[String] =
    guarded match
      case Left(blocked: GuardrailBlocked) =>
        val review = GuardrailReview(phase, blocked.guardrail, blocked.reason, text)
        answered(context).filter(_.question == review).map(_.answer) match
          case Some(GuardrailVerdict.Allow)      => Right(text)
          case Some(GuardrailVerdict.Edit(next)) => Right(next)
          case Some(GuardrailVerdict.Block)      => Left(blocked)
          case None                              => ask(review)
      case other => other
