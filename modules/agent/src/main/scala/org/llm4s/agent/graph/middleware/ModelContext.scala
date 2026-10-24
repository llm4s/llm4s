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
  ResponseFormat,
  StreamedChunk,
  ThinkingBlock,
  TokenUsage,
  UserMessage
}
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.slf4j.LoggerFactory

import scala.util.Try

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
 * its own: at the budget it is refused with a `Left`. In `wrapModelCall` and `promptSections` the model step then ends the turn
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
    record(model, Some(usage), estimatedCost, attempt = 0, toolCalls = 0, usage, provider = None)

  /**
   * Records a completed model call of `request`, sent with `definitions` (its tool and response-format schemas, as
   * [[ModelContext.definitions]] renders them): attempt `attempt` of the agent's model step, or 0 for a hook's own
   * call. A completion without usage is charged an estimate (#2084). `provider` is the called provider's id, when known.
   */
  private[agent] def recordCall(
    completion: Completion,
    attempt: Int,
    request: Seq[Message],
    definitions: => Seq[String],
    provider: Option[String]
  ): Unit =
    record(
      completion.model,
      completion.usage,
      completion.estimatedCost,
      attempt,
      Option(completion.message).flatMap(m => Option(m.toolCalls)).fold(0)(_.size),
      completion.usage.getOrElse(ModelContext.estimated(completion, request, definitions)),
      provider
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
    charged: TokenUsage,
    provider: Option[String]
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
        cost,
        provider
      )
    )

@Experimental
object ModelContext:

  private val logger = LoggerFactory.getLogger(getClass)

  /** The tokenizer of usage estimates: `cl100k_base`, else four characters a token when it is unavailable. */
  private lazy val counter: Option[ConversationTokenCounter] = ConversationTokenCounter.openAI().toOption

  /**
   * A conservative estimate of a call's usage, for a completion that reports none (#2084): the whole request in -
   * its messages and `definitions`, the tool and response-format schemas providers count on every call - and the
   * whole assistant message out - its text, every tool call's name and arguments, and its thinking - at least one
   * token each way, so a call is never free. Tool calls only on `Completion.toolCalls` count too.
   */
  private[agent] def estimated(completion: Completion, request: Seq[Message], definitions: => Seq[String]): TokenUsage =
    // an estimate never fails the call it charges: whatever a field holds (a custom client may leave one null), each
    // part falls back, and the whole to the one-token minimum
    val usage = Try {
      val answer = Try {
        // a null list is an empty one, so calls only on `Completion.toolCalls` still count
        if nonNull(completion.message.toolCalls).isEmpty && nonNull(completion.toolCalls).nonEmpty then
          completion.message.withToolCalls(nonNull(completion.toolCalls))
        else completion.message
      }.getOrElse(completion.message)
      val in = math.max(1, nonNull(request).map(tokens).sum + nonNull(definitions).map(d => tokens(UserMessage(d))).sum)
      val out = math.max(1, tokens(answer))
      TokenUsage(in, out, in + out)
    }.getOrElse(TokenUsage(1, 1, 2))
    logger.debug(
      s"model '${completion.model}' reported no usage: charged an estimated ${usage.promptTokens} in, " +
        s"${usage.completionTokens} out"
    )
    usage

  /**
   * `message`'s tokens by the estimate's tokenizer, else its whole text - tool calls and thinking too - at four
   * characters a token, else one.
   */
  private def tokens(message: Message): Int =
    counter
      .flatMap(c => Try(c.countMessage(message)).toOption)
      .orElse(Try(ConversationTokenCounter.estimateOpaqueTokens(text(message))).toOption)
      .getOrElse(1)

  private def nonNull[A](items: Seq[A]): Seq[A] = Option(items).getOrElse(Nil).filter(_ != null)

  private def text(message: Message): String =
    def str(s: String): String = Option(s).getOrElse("")
    message match
      case a: AssistantMessage =>
        val calls = nonNull(a.toolCalls).flatMap(c => Seq(str(c.name), Option(c.arguments).fold("")(_.render())))
        val thoughts = nonNull(a.thinking).map {
          case ThinkingBlock.Text(thought, _) => str(thought)
          case ThinkingBlock.Redacted(data)   => str(data)
          case ThinkingBlock.Opaque(_, data)  => str(data)
        }
        (str(a.content) +: (calls ++ thoughts)).mkString
      case other => str(other.content)

  /** The schemas a request is sent with, rendered for the usage estimate: each tool's definition and a JSON schema. */
  private[agent] def definitions(tools: Seq[ToolFunction[?, ?]], format: Option[ResponseFormat]): Seq[String] =
    // total, as the estimate is: a null field contributes nothing
    val defined = nonNull(tools).flatMap(t => Try(t.toOpenAITool(strict = false).render()).toOption)
    val schema = Try(Option(format).flatten.collect { case ResponseFormat.JsonSchema(schema, name, _) =>
      Option(name).getOrElse("") + Option(schema).fold("")(_.render())
    }).toOption.flatten
    defined ++ schema

  /** An accounted call refused at the run's token budget (#2084): the model step ends the turn `TokenLimitReached`. */
  final private[agent] case class TokenLimitRefused(used: Long, budget: Long) extends LLMError with NonRecoverableError:
    def message: String = s"model call refused: the run has used $used tokens of its budget of $budget"

  final private class Accounted(client: LLMClient, context: ModelContext) extends LLMClient:
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      context.admitted(recorded(conversation, options, client.complete(conversation, options)))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      context.admitted(recorded(conversation, options, client.streamComplete(conversation, options, onChunk)))

    def getContextWindow(): Int                 = client.getContextWindow()
    def getReserveCompletion(): Int             = client.getReserveCompletion()
    override def validate(): Result[Unit]       = client.validate()
    override def providerId: Option[ProviderId] = client.providerId

    private def recorded(
      conversation: Conversation,
      options: CompletionOptions,
      result: Result[Completion]
    ): Result[Completion] =
      result.foreach(
        context.recordCall(
          _,
          attempt = 0,
          conversation.messages,
          definitions(options.tools, options.responseFormat),
          client.providerId.map(_.asString)
        )
      )
      result
