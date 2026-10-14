package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.{ StateKey, StateUpdate, TenantId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.agent.graph.toolloop.{ Messages, StoredMessage }
import org.llm4s.agent.memory.{
  AppliedWrite,
  Memory,
  MemoryBounds,
  MemoryFilter,
  MemoryManager,
  MemoryStore,
  TransactionalMemoryStore
}
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, UserMessage }
import org.llm4s.types.{ Result, TryOps }

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * Long-term semantic memory for an agent (design §4.31): before each turn it recalls what earlier threads of the same
 * tenant recorded and shows it to the model; after the turn it records the exchange, and, when `consolidate` is set,
 * replaces groups of old memories with a summary.
 *
 *   - **Tenant scope.** `stores` is asked once per tenant (`RunContext.config.tenantId`) for that tenant's store; the
 *     store is held in one cell per tenant, whose writes are serialised. Every thread of a tenant shares it; no two
 *     tenants may share a store instance (refused). A failed resolution is asked again on the next turn.
 *   - **The manager** is built for each hook call by `manager(view, context.accounted(client))`, over a view of the
 *     tenant's store whose writes are the middleware's effects, so every model call it makes is counted in the
 *     thread's usage.
 *   - **Recall** (`beforeAgent`) runs `getRelevantContext` on the turn's input, cuts it to
 *     `maxRecallTokens * 4` characters and stores it in [[SemanticMemoryMiddleware.RecalledKey]]; every model step of
 *     the turn shows it as the prompt section `semantic-memory`. A resolution or configuration failure refuses the
 *     input before any model call; a failed search is skipped, announced by [[MemoryEvents.Skipped]].
 *   - **Recording** (`afterAgent`) records the turn's query and answer through the manager as one replayable effect
 *     (`memory.record`, C4): on a [[org.llm4s.agent.memory.TransactionalMemoryStore]] a replay after a crash returns
 *     the recorded outcome and never writes twice; on any other store a replay of a write that may have run is
 *     refused. Each memory is cut to `maxRecordChars`.
 *   - **Consolidation** (`afterAgent`, after recording) runs the manager's `consolidateMemories` on memories older than
 *     `olderThan`; each group it replaces is one effect (`memory.consolidate`, keyed by the group's ids). It needs a
 *     transactional store: on any other, the input is refused.
 *   - **Bounds.** Every write evicts, in its own transaction, the memories of lowest importance, oldest first, beyond
 *     `bounds`, so a tenant's store stays bounded whether or not consolidation is on.
 *   - **Failures after the answer** are skipped and announced by [[MemoryEvents.Skipped]]: the answer is never
 *     retracted because memory could not be written. A crash is replayed by `recover` from C4's records.
 *
 * Register it outside guardrail middleware - before it, or with `runsBefore` naming it - so that a blocked answer is
 * never recorded: `afterAgent` runs in reverse stack order, and a guardrail's block stops the others. An effect that
 * applied stays applied: a Block restores thread state, not the store.
 *
 * The key is registered only by an agent family with a `SemanticMemoryMiddleware`: adding one to an existing agent
 * changes its graph's structure.
 *
 * @param stores the store of each tenant (`None` for runs without one): a store scoped to that tenant
 * @param manager builds the manager over a store and a client, e.g. `LLMMemoryManager(config, _, _)`
 * @param client the client the manager calls a model with; every call is accounted to the run
 * @param recall what to recall before a turn; `None` recalls nothing
 * @param record how to record a turn; `None` records nothing
 * @param consolidate when to consolidate old memories after a turn; `None` never
 * @param bounds the most each tenant's store keeps
 * @param clock where consolidation reads "now" from
 * @param runsBefore middleware this one must run outside of, such as `MiddlewareId("guardrails")`
 * @param id this middleware's id
 */
@Experimental
final class SemanticMemoryMiddleware(
  stores: Option[TenantId] => Result[MemoryStore],
  manager: (MemoryStore, LLMClient) => MemoryManager,
  client: LLMClient,
  val recall: Option[MemoryRecall] = Some(MemoryRecall()),
  val record: Option[MemoryRecording] = Some(MemoryRecording()),
  val consolidate: Option[MemoryConsolidation] = None,
  val bounds: MemoryBounds = MemoryBounds(),
  clock: Clock = Clock.systemUTC(),
  override val runsBefore: Set[MiddlewareId] = Set.empty,
  val id: MiddlewareId = SemanticMemoryMiddleware.DefaultId
) extends AgentMiddleware:
  import SemanticMemoryMiddleware.*

  private val cells     = new ConcurrentHashMap[Option[TenantId], TenantMemory]()
  private val resolving = new ReentrantLock()

  override def writes: Set[StateKey[?, ?]] = Set(RecalledKey)

  /** `tenant`'s store as the middleware's last write left it, resolving it if no run of the tenant has yet. */
  def storeFor(tenant: Option[TenantId]): Result[MemoryStore] = cell(tenant).map(_.current)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    cell(context.run.config.tenantId).flatMap { tenant =>
      recall.fold(Right(BoundaryResult(input))) { settings =>
        guarded(
          manager(MemoryView.reading(tenant), context.accounted(client))
            .getRelevantContext(input, settings.maxRecallTokens, settings.filter)
        ) match
          case Right(text) =>
            Right(BoundaryResult(input, recalled(clip(text, settings.maxChars))))
          case Left(cancelled: CancelledError) => Left(cancelled)
          case Left(error) =>
            skipped(context, "memory.recall", error)
            Right(BoundaryResult(input, recalled("")))
      }
    }

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    recall.fold(Right(Vector.empty)) { settings =>
      context.state.get(RecalledKey).map { text =>
        val shown = clip(text, settings.maxChars)
        if shown.trim.isEmpty then Vector.empty else Vector(PromptSection(SectionId, s"$Heading\n$shown"))
      }
    }

  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    // the recall served this turn only: the key is removed as the turn ends, so a thread at rest holds no recall
    val ended = BoundaryResult(answer, if recall.isDefined then StateUpdate.remove(RecalledKey) else StateUpdate.empty)
    if record.isEmpty && consolidate.isEmpty then Right(ended)
    else
      val done = cell(context.run.config.tenantId) match
        case Left(error) => skip(context, "memory.resolve", error)
        case Right(tenant) =>
          for
            _ <- record.fold(Right(()))(r => skip(context, "memory.record", recordTurn(tenant, r, answer, context)))
            _ <- consolidate.fold(Right(()))(k => skip(context, "memory.consolidate", consolidated(tenant, k, context)))
          yield ()
      done.map(_ => ended)

  /** The turn - its last user message and `answer` - recorded through the manager as one `memory.record` effect. */
  private def recordTurn(
    tenant: TenantMemory,
    settings: MemoryRecording,
    answer: String,
    context: ModelContext
  ): Result[Unit] =
    context.state.get(Messages.key).flatMap { history =>
      history.reverseIterator.collectFirst { case StoredMessage(id, user: UserMessage) => id -> user.content } match
        case None => Right(())
        case Some((turn, query)) =>
          val key = context.run.effectKey("memory.record", turn)
          MemoryEffects
            .write(context.run, tenant, key, bounds) {
              val view   = MemoryView.recording(tenant)
              val thread = context.run.position.threadId.value
              guarded(
                manager(view, context.accounted(client))
                  .recordConversation(Seq(UserMessage(query), AssistantMessage(answer)), thread)
              ).flatMap(recorded => buffered(recorded.store))
                .map(add => add.map(cut(_, settings.maxRecordChars)) -> Nil)
            }
            .map(out => MemoryEvents.Recorded.emit(context.run, MemoryRecorded(out.added.size, out.evicted)))
    }

  /** Old memories consolidated by the manager, each group it replaces one `memory.consolidate` effect. */
  private def consolidated(tenant: TenantMemory, settings: MemoryConsolidation, context: ModelContext): Result[Unit] =
    val outcomes = new java.util.concurrent.CopyOnWriteArrayList[AppliedWrite]()
    val maxChars = record.getOrElse(MemoryRecording()).maxRecordChars
    val view = MemoryView.consolidating(tenant) { (add, remove) =>
      val key = context.run.effectKey("memory.consolidate", remove.map(_.value).distinct.sorted*)
      MemoryEffects
        .write(context.run, tenant, key, bounds)(Right(add.map(cut(_, maxChars)) -> remove))
        .map(outcomes.add(_): Unit)
    }
    val cutoff = clock.instant().minusNanos(settings.olderThan.toNanos)
    guarded(manager(view, context.accounted(client)).consolidateMemories(cutoff, settings.minCount)).map { _ =>
      val done = outcomes.asScala.toVector
      if done.nonEmpty then
        MemoryEvents.Consolidated.emit(
          context.run,
          MemoryConsolidated(done.size, done.map(_.removed.size).sum, done.map(_.evicted).sum)
        )
    }

  /** A failure after the answer: a cancellation is the hook's `Left`; anything else is announced and skipped. */
  private def skip(context: ModelContext, operation: String, result: Result[Unit]): Result[Unit] =
    result match
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        skipped(context, operation, error)
        Right(())
      case ok => ok

  private def skip(context: ModelContext, operation: String, error: LLMError): Result[Unit] =
    skip(context, operation, Left(error))

  private def skipped(context: ModelContext, operation: String, error: LLMError): Unit =
    MemoryEvents.Skipped.emit(context.run, MemorySkipped(operation, error.getClass.getSimpleName))

  /** `tenant`'s cell, resolved on first use; a resolution that fails is not kept. */
  private def cell(tenant: Option[TenantId]): Result[TenantMemory] =
    Option(cells.get(tenant)) match
      case Some(found) => Right(found)
      case None        => Using.resource(new Holding(resolving))(_ => resolve(tenant))

  private def resolve(tenant: Option[TenantId]): Result[TenantMemory] =
    Option(cells.get(tenant)) match
      case Some(found) => Right(found)
      case None =>
        for
          store <- guarded(stores(tenant))
          _ <- Either.cond(
            !cells.values.asScala.exists(_.initial.eq(store)),
            (),
            ValidationError(
              "stores",
              s"the store for tenant ${name(tenant)} is the same instance as another tenant's: give each tenant its own"
            )
          )
          _ <- Either.cond(
            consolidate.isEmpty || store.isInstanceOf[TransactionalMemoryStore],
            (),
            ValidationError(
              "consolidate",
              s"consolidation needs a TransactionalMemoryStore, and the store for tenant ${name(tenant)} is a " +
                s"${store.getClass.getSimpleName}"
            )
          )
        yield
          val created = new TenantMemory(store)
          cells.put(tenant, created)
          created

@Experimental
object SemanticMemoryMiddleware:

  /** The default middleware id. */
  val DefaultId: MiddlewareId = MiddlewareId("semantic-memory")

  /**
   * What the turn's recall found, cut to `maxRecallTokens * 4` characters: written by `beforeAgent` (removed when it
   * found nothing) and removed by `afterAgent` as the turn ends, so a thread at rest holds no entry. Its initial value
   * means "nothing recalled", so a thread checkpointed before the middleware was added restores (design §4.29), and
   * one checkpointed with it restores after it is removed. One key for every instance, registered only by an agent
   * family with a `SemanticMemoryMiddleware`.
   */
  val RecalledKey: StateKey[String, String] = StateKey.replace[String]("semantic-memory-recalled", "")

  /** The turn's recall as an update: the text, or the key's removal when there is none (C5, §4.29). */
  private def recalled(text: String): StateUpdate =
    if text.isEmpty then StateUpdate.remove(RecalledKey) else StateUpdate.update(RecalledKey, text)

  /** The prompt section's id. */
  val SectionId: String = "semantic-memory"

  private val Heading = "Relevant memories from earlier conversations:"

  /** Holds `lock` from construction until `close`. */
  final private class Holding(lock: ReentrantLock) extends AutoCloseable:
    lock.lock()
    def close(): Unit = lock.unlock()

  /** `body`, a throw as `Left`: a manager or resolver that throws is a failed memory operation, not a failed hook. */
  private def guarded[A](body: => Result[A]): Result[A] = Try(body).toResult.flatten

  /** The memories a recording view buffered. */
  private def buffered(store: MemoryStore): Result[Seq[Memory]] = store match
    case view: MemoryView => Right(view.buffered)
    case other =>
      Left(
        ValidationError(
          "manager",
          s"the manager returned a ${other.getClass.getSimpleName}, not the store it was given"
        )
      )

  private def name(tenant: Option[TenantId]): String = tenant.fold("(none)")(t => s"'${t.value}'")

  /** `memory` with its content cut to `max` code points. */
  private def cut(memory: Memory, max: Int): Memory = memory.copy(content = clip(memory.content, max))

  /** `s` cut to `max` code points. */
  private def clip(s: String, max: Int): String =
    if s.codePointCount(0, s.length) <= max then s else s.substring(0, s.offsetByCodePoints(0, max))

/**
 * What [[SemanticMemoryMiddleware]] recalls before a turn: at most `maxRecallTokens` (estimated at four characters a
 * token, as the agent's prompt-section budget is), of the memories `filter` accepts.
 */
@Experimental
final case class MemoryRecall private (maxRecallTokens: Int, filter: MemoryFilter):
  def withMaxRecallTokens(n: Int): MemoryRecall = MemoryRecall(n, filter)
  def withFilter(f: MemoryFilter): MemoryRecall = MemoryRecall(maxRecallTokens, f)
  private[memory] def maxChars: Int             = maxRecallTokens * 4

@Experimental
object MemoryRecall:
  /** The defaults: 1024 tokens, every memory. */
  def apply(maxRecallTokens: Int = 1024, filter: MemoryFilter = MemoryFilter.All): MemoryRecall =
    require(
      maxRecallTokens >= 1 && maxRecallTokens <= MaxTokens,
      s"MemoryRecall: maxRecallTokens must be in 1..$MaxTokens, was $maxRecallTokens"
    )
    new MemoryRecall(maxRecallTokens, filter)

  private val MaxTokens = Int.MaxValue / 4

/** How [[SemanticMemoryMiddleware]] records a turn: each memory's content cut to `maxRecordChars` code points. */
@Experimental
final case class MemoryRecording private (maxRecordChars: Int):
  def withMaxRecordChars(n: Int): MemoryRecording = MemoryRecording(n)

@Experimental
object MemoryRecording:
  /** The default: 2000 code points. */
  def apply(maxRecordChars: Int = 2000): MemoryRecording =
    require(maxRecordChars >= 1, s"MemoryRecording: maxRecordChars must be at least 1, was $maxRecordChars")
    new MemoryRecording(maxRecordChars)

/**
 * When [[SemanticMemoryMiddleware]] consolidates: after each turn, the manager's `consolidateMemories` over memories
 * older than `olderThan`, in groups of at least `minCount`.
 */
@Experimental
final case class MemoryConsolidation private (olderThan: FiniteDuration, minCount: Int):
  def withOlderThan(d: FiniteDuration): MemoryConsolidation = MemoryConsolidation(d, minCount)
  def withMinCount(n: Int): MemoryConsolidation             = MemoryConsolidation(olderThan, n)

@Experimental
object MemoryConsolidation:
  /** The defaults: memories older than a day, in groups of at least 10. */
  def apply(olderThan: FiniteDuration = 1.day, minCount: Int = 10): MemoryConsolidation =
    require(olderThan >= Duration.Zero, s"MemoryConsolidation: olderThan must not be negative, was $olderThan")
    require(minCount >= 1, s"MemoryConsolidation: minCount must be at least 1, was $minCount")
    new MemoryConsolidation(olderThan, minCount)
