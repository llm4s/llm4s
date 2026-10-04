package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Runs input guardrails on the run's input (`beforeAgent`) and output guardrails on its final
 * answer (`afterAgent`). Each list runs in order, each guardrail on the value the previous one
 * returned, so a `Fix` transforms; a `Warn` passes. Failures are collected and fail the run with
 * the error `CompositeGuardrail.all` reports. A guardrail does not suspend.
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
   * The aggregate error is built as `CompositeGuardrail`'s `All` mode builds it, which is private.
   */
  private def run(guardrails: Seq[Guardrail[String]], value: String): Result[String] =
    val (last, errors) = guardrails.foldLeft((value, Vector.empty[org.llm4s.error.LLMError])) {
      case ((current, errs), guardrail) =>
        guardrail.validate(current) match
          case Right(next) => (next, errs)
          case Left(err)   => (current, errs :+ err)
    }
    if errors.isEmpty then Right(last)
    else
      Left(
        ValidationError.invalid(
          "composite",
          s"Multiple validation failures: ${errors.map(_.formatted).mkString("; ")}"
        )
      )
