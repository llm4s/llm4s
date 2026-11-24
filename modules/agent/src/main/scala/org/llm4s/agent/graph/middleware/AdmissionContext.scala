package org.llm4s.agent.graph.middleware

import org.llm4s.agent.AgentId
import org.llm4s.agent.events.ModelCallCompleted
import org.llm4s.agent.graph.{ Principal, TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  TokenUsage,
  UsageSummary
}
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
 * What a middleware's [[AgentMiddleware.admit admit]] stage is given (#2854): who the turn is for, and a way to have
 * the stage's own model calls counted.
 *
 * It is less than a hook's [[ModelContext]] on purpose. Admission runs before the turn has a run, a task or a
 * checkpoint, so that a query it refuses or rewrites is never stored as submitted: there is no thread state to read
 * or update, no event to emit, no question to ask and no tool to call, and so nothing here offers them. A stage that
 * needs any of those belongs in `beforeAgent`.
 *
 * A stage that calls a model - an LLM judge - wraps its client with [[accounted]]. Its calls are counted in the
 * thread's usage and announced with a durable `ModelCallCompleted` as a hook's are, when the turn's input step
 * commits; they are made before the run exists, so its token budget (`RunBudgets.maxTokens`) does not refuse them,
 * though it counts them.
 *
 * @param threadId the thread the turn is for
 * @param agent the agent whose stack the stage is in
 * @param tenantId the tenant of the run being admitted (`RunConfig.tenantId`)
 * @param principal who the run is for (`RunConfig.principal`)
 */
@Experimental
final class AdmissionContext private[agent] (
  val threadId: ThreadId,
  val agent: AgentId,
  val tenantId: Option[TenantId],
  val principal: Option[Principal],
  ledger: AdmissionContext.Ledger
) extends UsageAccounting:

  /**
   * `client`, with the usage of every `complete` and `streamComplete` through it counted in the thread's usage, as
   * [[ModelContext.accounted]] counts a hook's. `close` does not close `client`: the stage that owns it does.
   */
  def accounted(client: LLMClient): LLMClient = ledger.accounted(client)

  /** Records usage that arrived without a client call, as one request to `model`. */
  def recordUsage(model: String, usage: TokenUsage, estimatedCost: Option[BigDecimal]): Unit =
    ledger.record(model, usage, estimatedCost)

  /** The usage the stages given this context have counted so far: what a test of a stage reads. */
  private[agent] def counted: UsageSummary = ledger.usage

@Experimental
object AdmissionContext:

  /** Where an admission stage's model calls are counted. */
  private[agent] trait Ledger:
    def accounted(client: LLMClient): LLMClient
    def record(model: String, usage: TokenUsage, cost: Option[BigDecimal]): Unit
    def usage: UsageSummary

  /**
   * The context of a stage run inside the turn's input step, for `agent`: its calls are counted by the step's own
   * `hook` context, budget and all. This is where a stage runs when the turn was not admitted before it was stored.
   */
  private[agent] def within(hook: ModelContext, agent: AgentId): AdmissionContext =
    val ledger = new Ledger:
      def accounted(client: LLMClient): LLMClient = hook.accounted(client)
      def record(model: String, usage: TokenUsage, cost: Option[BigDecimal]): Unit =
        hook.recordUsage(model, usage, cost)
      def usage: UsageSummary = hook.run.usage.summary
    new AdmissionContext(
      hook.run.position.threadId,
      agent,
      hook.run.config.tenantId,
      hook.run.config.principal,
      ledger
    )

  /** The context of a stage run before the turn is stored, its calls kept in `ledger` for the input step to count. */
  private[agent] def before(
    threadId: ThreadId,
    agent: AgentId,
    tenantId: Option[TenantId],
    principal: Option[Principal],
    ledger: Collected
  ): AdmissionContext = new AdmissionContext(threadId, agent, tenantId, principal, ledger.of(agent))

  /**
   * The model calls made by the stages of one admission, before any run exists to count them: their usage, summed,
   * and the event that announces each. The turn's input carries both, and its input step commits them.
   */
  final private[agent] class Collected:
    private val total    = new AtomicReference(UsageSummary())
    private val announce = new ConcurrentLinkedQueue[ModelCallCompleted]()

    /** The usage of every call counted so far. */
    def usage: UsageSummary = total.get

    /** The event of every call counted so far, in order. */
    def calls: Vector[ModelCallCompleted] = announce.asScala.toVector

    private def add(call: ModelContext.Recorded): Unit =
      total.updateAndGet(_.add(call.model, call.charged, call.cost))
      announce.add(call.event): Unit

    /** This ledger for the stages of `agent`'s stack: each call is announced as that agent's. */
    def of(agent: AgentId): Ledger = new Ledger:
      def accounted(client: LLMClient): LLMClient = new Counting(client, agent)
      def record(model: String, usage: TokenUsage, cost: Option[BigDecimal]): Unit =
        add(ModelContext.recordedUsage(agent, model, usage, cost))
      def usage: UsageSummary = total.get

    final private class Counting(client: LLMClient, agent: AgentId) extends LLMClient:
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        counted(conversation, options, client.complete(conversation, options))

      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = counted(conversation, options, client.streamComplete(conversation, options, onChunk))

      def getContextWindow(): Int                 = client.getContextWindow()
      def getReserveCompletion(): Int             = client.getReserveCompletion()
      override def validate(): Result[Unit]       = client.validate()
      override def providerId: Option[ProviderId] = client.providerId

      private def counted(
        conversation: Conversation,
        options: CompletionOptions,
        call: => Result[Completion]
      ): Result[Completion] =
        val (result, took) = ModelContext.timed(call)
        result.foreach { completion =>
          add(
            ModelContext.recorded(
              agent,
              completion,
              attempt = 0,
              conversation.messages,
              ModelContext.definitions(options.tools, options.responseFormat),
              client.providerId,
              took
            )
          )
        }
        result
