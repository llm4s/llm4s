package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.LLMError
import org.llm4s.types.Result

/**
 * Runs input guardrails on the run's input (`beforeAgent`) and output guardrails on its final
 * answer (`afterAgent`). Each list runs in order, each guardrail on the value the previous one
 * returned, so a `Fix` transforms; a `Warn` passes. Failures are collected into one
 * [[GuardrailBlocked]], which the tool loop ends the turn with as `TurnOutcome.Blocked`. When an
 * answer is blocked, the stored message's content becomes `refusal(guardrail, reason)`, or
 * [[GuardrailMiddleware.defaultRefusal]] when that is blank. A guardrail does not suspend.
 *
 * On a root agent it guards the whole agent family: its input guardrails run on every turn's query
 * and its output guardrails on every final answer, whichever agent is active after a handoff. On a
 * handoff target it applies only while that agent is active, inside the root's.
 */
final class GuardrailMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrails"),
  refusal: (String, String) => String = GuardrailMiddleware.defaultRefusal
) extends AgentMiddleware:

  /** The text that replaces an answer this middleware blocked. */
  def refusalFor(blocked: GuardrailBlocked): String = refusal(blocked.guardrail, blocked.reason)

  override def beforeAgent(text: String, context: RunContext): Result[String] = GuardrailMiddleware.run(input, text)

  override def afterAgent(answer: String, context: RunContext): Result[String] = GuardrailMiddleware.run(output, answer)

object GuardrailMiddleware:

  /** The refusal that replaces a blocked answer: names the guardrail and the reason. */
  val defaultRefusal: (String, String) => String = (g, r) => s"Response withheld by guardrail `$g`: $r"

  /**
   * Threads `value` through `guardrails`; a failing guardrail leaves it unchanged for the next.
   * The block names the first failing guardrail and joins every failure's formatted error.
   */
  private def run(guardrails: Seq[Guardrail[String]], value: String): Result[String] =
    val (last, failures) = guardrails.foldLeft((value, Vector.empty[(String, LLMError)])) {
      case ((current, failed), guardrail) =>
        guardrail.validate(current) match
          case Right(next) => (next, failed)
          case Left(err)   => (current, failed :+ (guardrail.name -> err))
    }
    failures.headOption match
      case None             => Right(last)
      case Some((first, _)) => Left(GuardrailBlocked(first, failures.map(_._2.formatted).mkString("; ")))
