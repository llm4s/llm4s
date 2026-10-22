package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.*
import org.llm4s.agent.memory.*
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration.FiniteDuration

/** Test doubles for the semantic-memory specs. */
object MemoryTestSupport:

  /** A client answering every call with `answer(conversation)`, counting calls; for a memory manager's own calls. */
  final class FunctionClient(answer: Conversation => String) extends LLMClient:
    val calls = new AtomicInteger()
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      calls.incrementAndGet()
      val text = answer(conversation)
      Right(
        Completion(
          id = s"memory-${calls.get}",
          created = 0L,
          content = text,
          model = "memory-model",
          message = AssistantMessage(text, Seq.empty),
          toolCalls = Nil,
          usage = Some(TokenUsage(promptTokens = 3, completionTokens = 2, totalTokens = 5))
        )
      )
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    def getContextWindow(): Int     = 128000
    def getReserveCompletion(): Int = 4096

  /** Where [[Crashing]] goes down: at a commit, before or after writing it. */
  enum Crash:
    case Before, After

  /**
   * A store that goes down for good at the first commit `at` picks, before or after writing it: every later call fails,
   * as in a dead process.
   */
  final class Crashing(val underlying: Checkpointer, at: Commit => Option[Crash]) extends Checkpointer:
    val dead                                            = new AtomicBoolean(false)
    private def down: Result[Nothing]                   = Left(ProcessingError("crashing", "the store is down"))
    private def alive[A](call: => Result[A]): Result[A] = if dead.get then down else call

    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = alive {
      at(commit) match
        case Some(Crash.Before) =>
          dead.set(true)
          down
        case Some(Crash.After) =>
          underlying.commit(threadId, commit).flatMap { _ =>
            dead.set(true)
            down
          }
        case None => underlying.commit(threadId, commit)
    }
    def claim(threadId: ThreadId, request: ClaimRequest) = alive(underlying.claim(threadId, request))
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = alive(
      underlying.renew(threadId, token, ttl)
    )
    def release(threadId: ThreadId, token: FencingToken) = alive(underlying.release(threadId, token))
    def latest(threadId: ThreadId)                       = alive(underlying.latest(threadId))
    def effect(threadId: ThreadId, key: EffectKey)       = alive(underlying.effect(threadId, key))
    def history(threadId: ThreadId, before: Option[String], limit: Int) =
      alive(underlying.history(threadId, before, limit))
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int) =
      alive(underlying.children(parent, after, limit))
    def threads(query: org.llm4s.agent.graph.ThreadQuery) =
      alive(underlying.threads(query))
    def incompleteThreads(
      tenantId: Option[org.llm4s.agent.graph.TenantId],
      after: Option[org.llm4s.agent.graph.ThreadCursor],
      limit: Int
    ) =
      alive(underlying.incompleteThreads(tenantId, after, limit))
    def latestOfRuns(threadId: ThreadId, runIds: Set[org.llm4s.agent.graph.RunId]) =
      alive(underlying.latestOfRuns(threadId, runIds))
    def sweep(
      policy: org.llm4s.agent.graph.SweepPolicy,
      after: Option[org.llm4s.agent.graph.ThreadId],
      guard: org.llm4s.agent.graph.SweepGuard
    ) =
      alive(underlying.sweep(policy, after, guard))
    def checkpoint(threadId: ThreadId, checkpointId: String) = alive(underlying.checkpoint(threadId, checkpointId))
    def prune(threadId: ThreadId, policy: RetentionPolicy)   = alive(underlying.prune(threadId, policy))
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
      alive(underlying.eventsAfter(threadId, afterSeq, limit))
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = alive(underlying.compactEvents(threadId, beforeSeq))
    def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) = alive(
      underlying.deleteThread(threadId, token, fence)
    )

  /** A commit's effect records of `operation`, completed (`true`) or begun. */
  def effects(commit: Commit, operation: String, completed: Boolean): Boolean =
    commit.effects.exists(r => r.key.operation == operation && r.completed == completed)

  /**
   * A store that is not a [[TransactionalMemoryStore]]: an immutable in-memory store behind the plain
   * [[MemoryStore]] interface, as an application's own store would be.
   */
  class PlainStore(base: InMemoryStore) extends MemoryStore:
    private def wrap(r: Result[MemoryStore]): Result[MemoryStore] =
      r.map(s => new PlainStore(s.asInstanceOf[InMemoryStore]))
    def store(memory: Memory)                                  = wrap(base.store(memory))
    def get(id: MemoryId)                                      = base.get(id)
    def recall(filter: MemoryFilter, limit: Int)               = base.recall(filter, limit)
    def search(query: String, topK: Int, filter: MemoryFilter) = base.search(query, topK, filter)
    def delete(id: MemoryId)                                   = wrap(base.delete(id))
    def deleteMatching(filter: MemoryFilter)                   = wrap(base.deleteMatching(filter))
    def update(id: MemoryId, f: Memory => Memory)              = wrap(base.update(id, f))
    def count(filter: MemoryFilter)                            = base.count(filter)
    def clear()                                                = wrap(base.clear())
    def recent(limit: Int, filter: MemoryFilter)               = base.recent(limit, filter)

  /** `underlying`, with `getRelevantContext` answering `recalled(query)` whatever budget it is given. */
  final class Overlong(
    underlying: MemoryManager,
    recalled: String => Result[String],
    recording: Option[Result[MemoryManager]] = None,
    consolidating: Option[MemoryStore => Result[MemoryManager]] = None
  ) extends MemoryManager:
    export underlying.{
      consolidateMemories as _,
      contextTokenBudget as _,
      getRelevantContext as _,
      recordConversation as _,
      *
    }
    def getRelevantContext(query: String, maxTokens: Int, filter: MemoryFilter): Result[String] = recalled(query)
    def recordConversation(messages: Seq[Message], conversationId: String): Result[MemoryManager] =
      recording.getOrElse(underlying.recordConversation(messages, conversationId))
    def consolidateMemories(olderThan: java.time.Instant, minCount: Int): Result[MemoryManager] =
      consolidating.fold(underlying.consolidateMemories(olderThan, minCount))(_(underlying.store))

  /** Every memory of `store`, by content. */
  def contents(store: MemoryStore): Vector[String] =
    store.recall(MemoryFilter.All, Int.MaxValue).fold(e => sys.error(e.message), _.map(_.content).toVector.sorted)
