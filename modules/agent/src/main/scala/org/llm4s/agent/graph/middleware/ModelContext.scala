package org.llm4s.agent.graph.middleware

import org.llm4s.agent.AgentId
import org.llm4s.agent.events
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.{ RunContext, ThreadState }
import org.llm4s.agent.graph.toolloop.ToolLoop
import org.llm4s.annotation.Experimental
import org.llm4s.context.ConversationTokenCounter
import org.llm4s.error.{ LLMError, NonRecoverableError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  StreamedChunk,
  ThinkingBlock,
  TokenUsage
}
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * What a middleware's `beforeAgent`, `wrapModelCall`, `promptSections` and `afterAgent` hooks see: the task's
 * [[org.llm4s.agent.graph.RunContext]] and the committed [[org.llm4s.agent.graph.ThreadState]] the task read. A hook
 * never sees an update made in the same superstep, its own included: an update is visible from the next one.
 *
 * The loop counts every model call it makes for the agent. A hook that calls a model itself - or hands a client to
 * a library that does, such as a summariser, a memory manager or an LLM judge - wraps the client with [[accounted]],
 * so those calls are counted too, also when the task later fails ([[org.llm4s.agent.graph.RunEvent.RunFailed]]
 * carries them). Usage that arrives without a client call is recorded with [[recordUsage]].
 *
 * Under a token budget (`RunBudgets.maxTokens`) every accounted call is checked before it is made, as the loop checks
 * its own: at the budget it is refused with a `Left`. In `wrapModelCall` the model step then ends the turn
 * `TokenLimitReached`, whatever the wrapper does with the refusal; in `beforeAgent` and `afterAgent` the hook handles
 * it as any failed call (an LLM guardrail blocks). A completion that reports no usage is charged an
 * estimate (the request's messages in, the completion out), never zero, so the budget still advances.
 */
@Experimental
final class ModelContext private[agent] (val run: RunContext, val state: ThreadState, agent: AgentId):

  /**
   * `client`, with the usage of every `complete` and `streamComplete` through it - and of `completeStructured`,
   * which completes through `complete` - recorded in this task's usage and announced with a durable
   * `ModelCallCompleted` (`attempts` 0). At the run's token budget a call is refused, not made: it returns `Left`, and
   * a model step ends the turn `TokenLimitReached`. `close` does not close `client`: the hook that owns it does.
   */
  def accounted(client: LLMClient): LLMClient = new ModelContext.Accounted(client, this)

  /** Records usage that arrived without a client call, as one request to `model`. */
  def recordUsage(model: String, usage: TokenUsage, estimatedCost: Option[Double] = None): Unit =
    record(model, Some(usage), estimatedCost, attempt = 0, toolCalls = 0, usage)

  /**
   * Records a completed model call of `request`: attempt `attempt` of the agent's model step, or 0 for a hook's own
   * call. A completion without usage is charged an estimate (#2084).
   */
  private[agent] def recordCall(completion: Completion, attempt: Int, request: Seq[Message]): Unit =
    record(
      completion.model,
      completion.usage,
      completion.estimatedCost,
      attempt,
      completion.message.toolCalls.size,
      completion.usage.getOrElse(ModelContext.estimated(completion, request))
    )

  /**
   * `call` - a model call that records its usage before it returns - admitted under `RunBudgets.maxTokens` (#2084):
   * at or above the budget it is not made, and the result is `Left` - the refusal - with the task marked refused, so
   * the model step ends the turn `TokenLimitReached`. Under a budget the task's calls run one at a time, so each is
   * admitted against every earlier call's usage and a concurrent fan-out overshoots by one call at most.
   */
  private[agent] def admitted[A](call: => Result[A]): Result[A] =
    run.config.budgets.maxTokens match
      case None => call
      case Some(budget) =>
        run.usage.serialized(
          ToolLoop.runTokens(state, run).flatMap { used =>
            if used < budget then call
            else
              run.usage.refuse()
              Left(ModelContext.TokenLimitRefused(used, budget))
          }
        )

  private def record(
    model: String,
    usage: Option[TokenUsage],
    cost: Option[Double],
    attempt: Int,
    toolCalls: Int,
    charged: TokenUsage
  ): Unit =
    run.usage.add(model, charged, cost)
    // an attempt of the agent's model step, not a hook's own call: what the turn's step count counts
    if attempt > 0 then run.usage.step()
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

  private val logger = LoggerFactory.getLogger(getClass)

  /** The tokenizer of usage estimates: `cl100k_base`, else four characters a token when it is unavailable. */
  private lazy val counter: Option[ConversationTokenCounter] = ConversationTokenCounter.openAI().toOption

  /**
   * A conservative estimate of a call's usage, for a completion that reports none (#2084): `request`'s messages in,
   * the whole assistant message out - its text, every tool call's name and arguments, and its thinking - at least one
   * token each way, so a call is never free. Tool calls only on `Completion.toolCalls` count too.
   */
  private[agent] def estimated(completion: Completion, request: Seq[Message]): TokenUsage =
    val answer =
      if completion.message.toolCalls.isEmpty && completion.toolCalls.nonEmpty then
        completion.message.withToolCalls(completion.toolCalls)
      else completion.message
    val in    = math.max(1, request.map(tokens).sum)
    val out   = math.max(1, tokens(answer))
    val usage = TokenUsage(in, out, in + out)
    logger.debug(s"model '${completion.model}' reported no usage: charged an estimated $in in, $out out")
    usage

  /** `message`'s tokens by the estimate's tokenizer, else its whole text - tool calls and thinking too - at four characters a token. */
  private def tokens(message: Message): Int =
    counter.fold(ConversationTokenCounter.estimateOpaqueTokens(text(message)))(
      _.countMessage(message)
    )

  private def text(message: Message): String = message match
    case a: AssistantMessage =>
      (Seq(a.content) ++ a.toolCalls.flatMap(c => Seq(c.name, c.arguments.render())) ++
        a.thinking.map {
          case ThinkingBlock.Text(thought, _) => thought
          case ThinkingBlock.Redacted(data)   => data
          case ThinkingBlock.Opaque(_, data)  => data
        }).mkString
    case other => other.content

  /** An accounted call refused at the run's token budget (#2084): the model step ends the turn `TokenLimitReached`. */
  final private[agent] case class TokenLimitRefused(used: Long, budget: Long) extends LLMError with NonRecoverableError:
    def message: String = s"model call refused: the run has used $used tokens of its budget of $budget"

  final private class Accounted(client: LLMClient, context: ModelContext) extends LLMClient:
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      context.admitted(recorded(conversation, client.complete(conversation, options)))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      context.admitted(recorded(conversation, client.streamComplete(conversation, options, onChunk)))

    def getContextWindow(): Int           = client.getContextWindow()
    def getReserveCompletion(): Int       = client.getReserveCompletion()
    override def validate(): Result[Unit] = client.validate()

    private def recorded(conversation: Conversation, result: Result[Completion]): Result[Completion] =
      result.foreach(context.recordCall(_, attempt = 0, conversation.messages))
      result
