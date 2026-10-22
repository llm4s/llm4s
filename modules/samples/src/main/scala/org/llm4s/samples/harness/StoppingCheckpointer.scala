package org.llm4s.samples.harness

import org.llm4s.agent.AgentId
import org.llm4s.agent.events.{ SubagentEvents, TaskEnded }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.toolloop.ToolLoop
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * A store that stops for good at the first commit `at` picks, before or after writing it, as the store of a process
 * that is killed there would: every later call fails, so the run can neither commit nor release its claim again. The
 * exit examples use it to stop a "process" at a chosen point and recover the run in a new one over the same SQLite
 * file (design §4.38).
 */
final class StoppingCheckpointer(underlying: Checkpointer, at: Commit => Option[StoppingCheckpointer.Stop])
    extends Checkpointer {
  import StoppingCheckpointer.Stop

  private val dead = new AtomicBoolean(false)

  /** Whether the store has stopped. */
  def stopped: Boolean = dead.get

  private def down: Result[Nothing]                   = Left(ProcessingError("stopped", "the process has stopped"))
  private def alive[A](call: => Result[A]): Result[A] = if (dead.get) down else call

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = alive {
    at(commit) match {
      case Some(Stop.BeforeWriting) =>
        dead.set(true)
        down
      case Some(Stop.AfterWriting) =>
        underlying.commit(threadId, commit).flatMap { _ =>
          dead.set(true)
          down
        }
      case None => underlying.commit(threadId, commit)
    }
  }

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = alive(underlying.claim(threadId, request))
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] =
    alive(underlying.renew(threadId, token, ttl))
  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = alive(underlying.release(threadId, token))
  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]   = alive(underlying.latest(threadId))
  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] = alive(underlying.effect(threadId, key))
  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] =
    alive(underlying.history(threadId, before, limit))
  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
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
  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
    alive(underlying.checkpoint(threadId, checkpointId))
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = alive(underlying.prune(threadId, policy))
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    alive(underlying.eventsAfter(threadId, afterSeq, limit))
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
    alive(underlying.compactEvents(threadId, beforeSeq))
  def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]): Result[Unit] =
    alive(underlying.deleteThread(threadId, token, fence))
}

object StoppingCheckpointer {

  /** Where the store stops at the chosen commit. */
  enum Stop {

    /** The commit is lost: what the task did is not recorded, so `recover` runs it again. */
    case BeforeWriting

    /** The commit is recorded, then the process stops before it can go on. */
    case AfterWriting
  }

  /** A commit with a task's write to the state key `keyId`. */
  def writes(commit: Commit, keyId: String): Boolean =
    commit.pendingWrites.exists(_.operations.exists {
      case EncodedOperation.Update(id, _) => id == keyId
      case EncodedOperation.Remove(id)    => id == keyId
    })

  /** A commit with the result of one of agent `agent`'s tool calls. */
  def ranTool(commit: Commit, agent: AgentId): Boolean =
    commit.pendingWrites.exists(_.nodeId == ToolLoop.callToolNode(agent).value)

  /**
   * A commit with the report of a task subagent `agent` completed: the delegating call's result, committed with its
   * durable `TaskEnded` (design §4.47).
   */
  def reports(commit: Commit, agent: AgentId): Boolean =
    commit.events.exists(_.event match {
      case RunEvent.Custom(name, _, payload) if name == SubagentEvents.TaskEnded.name =>
        Try(upickle.default.read[TaskEnded](payload)).toOption.exists(e =>
          e.child.agent == agent && e.status == "completed"
        )
      case _ => false
    })

  /** A commit that records a completed external effect of `operation`, such as `memory.record`. */
  def completes(commit: Commit, operation: String): Boolean =
    commit.effects.exists(r => r.key.operation == operation && r.completed)
}
