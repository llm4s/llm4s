package org.llm4s.agent.graph.middleware

import org.llm4s.annotation.Experimental
import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.LLMError
import org.llm4s.types.Result

/**
 * Runs input guardrails on the turn's query as it is admitted (`admit`, #2854) and output guardrails on its final
 * answer (`afterAgent`). Each list runs in order, each guardrail on the value the previous one
 * returned, so a `Fix` transforms; a `Warn` passes. Failures are collected into one
 * [[GuardrailBlocked]], which blocks the run (design 4.13): it ends as a finished failure, an input
 * block storing nothing of the turn and an output block removing it, and the thread stays usable.
 * `Agent` reports it as `AgentStatus.Blocked`. A guardrail does not suspend.
 *
 * A guardrail that could not decide - an LLM judge whose provider call failed, with an error that is not a
 * `ValidationError` - is not a refusal (#3247): when no guardrail of the list refused, the result is a
 * [[GuardrailUnavailable]], which fails the run instead of blocking it. Nothing is let through, an output failure
 * keeps the turn so `recover` runs the output guardrails again, and `Agent.run` returns it as its `Left`.
 *
 * The input guardrails run before anything of the turn is stored, and before every middleware's `beforeAgent`,
 * wherever this middleware is in the stack: a query they refuse is in no checkpoint, event, queue entry or trace,
 * and one they rewrite is stored only as rewritten. They see the query as submitted - after the `admit` stages of
 * the middleware outside this one - and not what a `beforeAgent` hook later makes of it.
 *
 * On a root agent it guards the whole agent family: its input guardrails run on every turn's query
 * and its output guardrails on every final answer, whichever agent is active after a handoff. On a
 * handoff target it applies only while that agent is active, inside the root's.
 */
@Experimental
final class GuardrailMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrails")
) extends AgentMiddleware:

  override def admit(query: String, context: AdmissionContext): Result[String] =
    GuardrailMiddleware.run(input, query, context)

  /** `true` when there are output guardrails: a streamed answer is held until they pass it (#3231). */
  override def guardsAnswer: Boolean = output.nonEmpty

  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    GuardrailMiddleware.run(output, answer, context).map(BoundaryResult(_))

@Experimental
object GuardrailMiddleware:

  /**
   * Threads `value` through `guardrails`; a failing guardrail leaves it unchanged for the next. When one refuses
   * (`GuardrailUnavailable.isRefusal`: a `ValidationError`, a `ContentFilteredError` or a `GuardrailBlocked`), the
   * result is a [[GuardrailBlocked]] naming the first that refused and joining every failure's formatted error. When
   * some failed but none refused - a judge's provider call failed - it is a [[GuardrailUnavailable]] naming the
   * first that failed, with its error (#3247). Each guardrail validates with the stage's `context`, so a judge's model
   * calls are counted in the thread's usage.
   */
  private[middleware] def run(
    guardrails: Seq[Guardrail[String]],
    value: String,
    context: UsageAccounting
  ): Result[String] =
    val (last, failures) = threaded(guardrails, value, context)
    failures.find((_, error) => GuardrailUnavailable.isRefusal(error)) match
      case Some((first, _)) => Left(GuardrailBlocked(first, Some(failures.map(_._2.formatted).mkString("; "))))
      case None =>
        failures.headOption match
          case None                 => Right(last)
          case Some((first, error)) => Left(GuardrailUnavailable(first, error))

  /** `value` as `guardrails` left it, each failing one leaving it unchanged, and each failure with its guardrail. */
  private[middleware] def threaded(
    guardrails: Seq[Guardrail[String]],
    value: String,
    context: UsageAccounting
  ): (String, Vector[(String, LLMError)]) =
    guardrails.foldLeft((value, Vector.empty[(String, LLMError)])) { case ((current, failed), guardrail) =>
      guardrail.validateIn(current, context) match
        case Right(next) => (next, failed)
        case Left(err)   => (current, failed :+ (guardrail.name -> err))
    }
