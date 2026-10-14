package org.llm4s.agent.graph.middleware

import org.llm4s.agent.AgentId
import org.llm4s.agent.events
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.{ RunContext, ThreadState }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, TokenUsage }
import org.llm4s.types.Result

/**
 * What a middleware's `beforeAgent`, `wrapModelCall`, `promptSections` and `afterAgent` hooks see: the task's
 * [[org.llm4s.agent.graph.RunContext]] and the committed [[org.llm4s.agent.graph.ThreadState]] the task read. A hook
 * never sees an update made in the same superstep, its own included: an update is visible from the next one.
 *
 * The loop counts every model call it makes for the agent. A hook that calls a model itself - or hands a client to
 * a library that does, such as a summariser, a memory manager or an LLM judge - wraps the client with [[accounted]],
 * so those calls are counted too, also when the task later fails ([[org.llm4s.agent.graph.RunEvent.RunFailed]]
 * carries them). Usage that arrives without a client call is recorded with [[recordUsage]].
 */
@Experimental
final class ModelContext private[agent] (val run: RunContext, val state: ThreadState, agent: AgentId):

  /**
   * `client`, with the usage of every `complete` and `streamComplete` through it - and of `completeStructured`,
   * which completes through `complete` - recorded in this task's usage and announced with a durable
   * `ModelCallCompleted` (`attempts` 0). `close` does not close `client`: the hook that owns it does.
   */
  def accounted(client: LLMClient): LLMClient = new ModelContext.Accounted(client, this)

  /** Records usage that arrived without a client call, as one request to `model`. */
  def recordUsage(model: String, usage: TokenUsage, estimatedCost: Option[Double] = None): Unit =
    record(model, Some(usage), estimatedCost, attempt = 0, toolCalls = 0)

  /** Records a completed model call: attempt `attempt` of the agent's model step, or 0 for a hook's own call. */
  private[agent] def recordCall(completion: Completion, attempt: Int): Unit =
    record(completion.model, completion.usage, completion.estimatedCost, attempt, completion.message.toolCalls.size)

  private def record(
    model: String,
    usage: Option[TokenUsage],
    cost: Option[Double],
    attempt: Int,
    toolCalls: Int
  ): Unit =
    run.usage.add(model, usage.getOrElse(TokenUsage(0, 0, 0)), cost)
    AgentEvents.ModelCallCompleted.emit(
      run,
      events.ModelCallCompleted(
        agent.value,
        model,
        attempt,
        toolCalls,
        usage.map(events.CallUsage.fromTokenUsage),
        cost
      )
    )

@Experimental
object ModelContext:

  final private class Accounted(client: LLMClient, context: ModelContext) extends LLMClient:
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      recorded(client.complete(conversation, options))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = recorded(client.streamComplete(conversation, options, onChunk))

    def getContextWindow(): Int           = client.getContextWindow()
    def getReserveCompletion(): Int       = client.getReserveCompletion()
    override def validate(): Result[Unit] = client.validate()

    private def recorded(result: Result[Completion]): Result[Completion] =
      result.foreach(context.recordCall(_, attempt = 0))
      result
