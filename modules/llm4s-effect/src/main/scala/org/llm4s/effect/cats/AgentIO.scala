package org.llm4s.effect.cats

import cats.effect.kernel.Async
import cats.syntax.flatMap.*
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.{ Agent, AgentContext, AgentState }
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.ToolRegistry

/**
 * cats-effect wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentState]` return value into `F[AgentState]`,
 * raising `LLMError` as [[LLMException]] in the error channel.
 * The underlying [[Agent.run]] and related methods are blocking — each
 * call is dispatched to the blocking thread pool via `Async[F].blocking`.
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
  ): F[AgentState]

  def continueConversation(
    previousState: AgentState,
    newUserMessage: String,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    context: AgentContext = AgentContext.Default
  ): F[AgentState]
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
    ): F[AgentState] =
      F.blocking(
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
      previousState: AgentState,
      newUserMessage: String,
      inputGuardrails: Seq[InputGuardrail] = Seq.empty,
      outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
      maxSteps: Option[Int] = None,
      context: AgentContext = AgentContext.Default
    ): F[AgentState] =
      F.blocking(
        agent.continueConversation(
          previousState,
          newUserMessage,
          inputGuardrails,
          outputGuardrails,
          maxSteps,
          context = context
        )
      ).flatMap {
        case Right(s) => F.pure(s)
        case Left(e)  => F.raiseError(new LLMException(e))
      }
  }
}
