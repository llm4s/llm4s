package org.llm4s.zio

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.{ Agent, AgentContext, AgentState }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.ToolRegistry
import zio.ZIO

/**
 * ZIO wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentState]` return value into `ZIO[Any, LLMError, AgentState]`.
 * The underlying blocking [[Agent]] methods are shifted to ZIO's blocking thread pool.
 */
trait AgentZ {

  def run(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
    systemPromptAddition: Option[String] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    context: AgentContext = AgentContext.Default
  ): ZIO[Any, LLMError, AgentState]

  def continueConversation(
    previousState: AgentState,
    newUserMessage: String,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    context: AgentContext = AgentContext.Default
  ): ZIO[Any, LLMError, AgentState]
}

object AgentZ {

  /** Wraps an already-constructed [[Agent]]. */
  def apply(agent: Agent): AgentZ = new Impl(agent)

  final private class Impl(agent: Agent) extends AgentZ {

    def run(
      query: String,
      tools: ToolRegistry,
      inputGuardrails: Seq[InputGuardrail] = Seq.empty,
      outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
      maxSteps: Option[Int] = Some(Agent.DefaultMaxSteps),
      systemPromptAddition: Option[String] = None,
      completionOptions: CompletionOptions = CompletionOptions(),
      context: AgentContext = AgentContext.Default
    ): ZIO[Any, LLMError, AgentState] =
      ZIO.blocking {
        ZIO.fromEither(
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
        )
      }

    def continueConversation(
      previousState: AgentState,
      newUserMessage: String,
      inputGuardrails: Seq[InputGuardrail] = Seq.empty,
      outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
      maxSteps: Option[Int] = None,
      context: AgentContext = AgentContext.Default
    ): ZIO[Any, LLMError, AgentState] =
      ZIO.blocking {
        ZIO.fromEither(
          agent.continueConversation(
            previousState,
            newUserMessage,
            inputGuardrails,
            outputGuardrails,
            maxSteps,
            context = context
          )
        )
      }
  }
}
