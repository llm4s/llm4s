package org.llm4s.effect.cats

import cats.effect.kernel.Async
import cats.syntax.flatMap.*
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.{ Agent, AgentContext, AgentThread }
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.ToolRegistry

/**
 * cats-effect wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentThread]` return value into `F[AgentThread]`,
 * raising `LLMError` as [[LLMException]] in the error channel.
 * The underlying [[Agent.run]] and related methods are blocking — each
 * call is dispatched to the blocking thread pool via `Async[F].interruptible`, so cancelling the
 * fiber interrupts the running agent loop.
 *
 * Intentionally a thin wrapper: `run` does not expose `handoffs`, and `continueConversation` does
 * not expose `contextWindowConfig`; the underlying [[Agent]] defaults apply. Tracing, debug logging
 * and the trace log path are still available through the `context` parameter. Use [[Agent]]
 * directly (inside `Async[F].interruptible`) when you need handoffs or context-window pruning.
 *
 * Tool calls are not a failure of the effect: `Agent` hands a call with invalid arguments back to the
 * model as a structured error tool result and the run continues. The experimental
 * schema-validated `AgentTool` contract and the graph runtime (`org.llm4s.agent.graph`) are not
 * wrapped here.
 */
trait AgentIO[F[_]] {

  def run(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): F[AgentThread]

  def continueConversation(
    previous: AgentThread,
    newUserMessage: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    context: AgentContext = AgentContext.Default
  ): F[AgentThread]
}

object AgentIO {

  /** Wraps an already-constructed [[Agent]]. */
  def apply[F[_]: Async](agent: Agent): AgentIO[F] = new Impl[F](agent)

  final private class Impl[F[_]](agent: Agent)(using F: Async[F]) extends AgentIO[F] {

    def run(
      query: String,
      tools: ToolRegistry,
      inputGuardrails: Seq[InputGuardrail] = Seq.empty,
      outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
      maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
      systemPromptAddition: Option[String] = None,
      completionOptions: CompletionOptions = CompletionOptions(),
      context: AgentContext = AgentContext.Default
    ): F[AgentThread] =
      F.interruptible(
        agent.run(
          query,
          tools,
          inputGuardrails,
          outputGuardrails,
          maxSteps = maxSteps,
          systemPromptAddition = systemPromptAddition,
          completionOptions = completionOptions,
          context = context
        )
      ).flatMap {
        case Right(s) => F.pure(s)
        case Left(e)  => F.raiseError(new LLMException(e))
      }

    def continueConversation(
      previous: AgentThread,
      newUserMessage: String,
      tools: ToolRegistry,
      inputGuardrails: Seq[InputGuardrail] = Seq.empty,
      outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
      maxSteps: Option[Int] = None,
      context: AgentContext = AgentContext.Default
    ): F[AgentThread] =
      F.interruptible(
        agent.continueConversation(
          previous,
          newUserMessage,
          tools,
          inputGuardrails = inputGuardrails,
          outputGuardrails = outputGuardrails,
          maxSteps = maxSteps,
          context = context
        )
      ).flatMap {
        case Right(s) => F.pure(s)
        case Left(e)  => F.raiseError(new LLMException(e))
      }
  }
}
