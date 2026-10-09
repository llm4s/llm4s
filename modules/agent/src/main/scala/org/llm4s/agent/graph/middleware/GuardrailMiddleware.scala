package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.LLMError
import org.llm4s.types.Result

/**
 * Runs input guardrails on the run's input (`beforeAgent`) and output guardrails on its final
 * answer (`afterAgent`). Each list runs in order, each guardrail on the value the previous one
 * returned, so a `Fix` transforms; a `Warn` passes. Failures are collected into one
 * [[GuardrailBlocked]], which blocks the run (design 4.13): it ends as a finished failure, an input
 * block storing nothing of the turn and an output block removing it, and the thread stays usable.
 * `Agent` reports it as `AgentStatus.Blocked`. A guardrail does not suspend.
 *
 * On a root agent it guards the whole agent family: its input guardrails run on every turn's query
 * and its output guardrails on every final answer, whichever agent is active after a handoff. On a
 * handoff target it applies only while that agent is active, inside the root's.
 */
final class GuardrailMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrails")
) extends AgentMiddleware:

  override def beforeAgent(text: String, context: RunContext): Result[String] = GuardrailMiddleware.run(input, text)

  override def afterAgent(answer: String, context: RunContext): Result[String] = GuardrailMiddleware.run(output, answer)

object GuardrailMiddleware:

  /**
   * Threads `value` through `guardrails`; a failing guardrail leaves it unchanged for the next.
   * The block names the first failing guardrail and joins every failure's formatted error.
   */
  private[middleware] def run(guardrails: Seq[Guardrail[String]], value: String): Result[String] =
    val (last, failures) = guardrails.foldLeft((value, Vector.empty[(String, LLMError)])) {
      case ((current, failed), guardrail) =>
        guardrail.validate(current) match
          case Right(next) => (next, failed)
          case Left(err)   => (current, failed :+ (guardrail.name -> err))
    }
    failures.headOption match
      case None             => Right(last)
      case Some((first, _)) => Left(GuardrailBlocked(first, failures.map(_._2.formatted).mkString("; ")))
