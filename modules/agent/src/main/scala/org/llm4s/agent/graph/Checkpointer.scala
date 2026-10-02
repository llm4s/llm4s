package org.llm4s.agent.graph

import org.llm4s.types.{ Result, TryOps }

import scala.collection.mutable
import scala.util.Try

/**
 * Durable storage for graph threads: each thread's latest checkpoint, the pending writes recorded
 * against it, and its durable event log. One store owns all three so that a commit is atomic.
 *
 * Implementations must be safe to call from several threads, and must:
 *
 *  - apply a [[Commit]] entirely or not at all;
 *  - accept a new checkpoint only when its `parent` is the thread's latest checkpoint id
 *    ([[GraphError.CheckpointConflict]] otherwise), and only pending writes naming the resulting
 *    latest checkpoint ([[GraphError.InvalidCommit]]);
 *  - number a thread's events `1, 2, 3, ...` in commit order, inside the commit, never reusing
 *    a number - including after compaction or a failed commit;
 *  - keep events until [[compactEvents]] removes them, and report the earliest sequence still
 *    available when asked to replay from before it ([[GraphError.ReplayUnavailable]]).
 *
 * Fencing a commit with a run-claim token is Stage 2; the parent check is its precursor.
 */
trait Checkpointer:

  /** Applies `commit` atomically and returns its events with their sequence numbers. */
  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]]

  /** The thread's latest checkpoint and its pending writes; `None` for a new thread. */
  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]

  /** Up to `limit` events with `seq > afterSeq`, ascending. */
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]]

  /** Drops events with `seq < beforeSeq`; replay can then start no earlier than `beforeSeq`. */
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit]

/**
 * A [[Checkpointer]] in memory. Checkpoints and pending writes are stored as JSON and decoded on
 * read, exactly as a database-backed store would, so nothing executable survives a round trip.
 */
final class InMemoryCheckpointer extends Checkpointer:

  final private case class ThreadRecord(
    checkpoint: Option[ujson.Value],
    pendingWrites: Vector[ujson.Value],
    events: Vector[EventRecord],
    nextSeq: Long,
    earliestSeq: Long
  )

  private val threads = mutable.Map.empty[String, ThreadRecord]

  private def record(threadId: ThreadId): ThreadRecord =
    threads.getOrElse(threadId.value, ThreadRecord(None, Vector.empty, Vector.empty, 1L, 1L))

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = synchronized {
    val current  = record(threadId)
    val latestId = current.checkpoint.flatMap(_.obj.get("id")).map(_.str)
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    val resultingId = commit.checkpoint.map(_.id).orElse(latestId)
    val misdirected = commit.pendingWrites.find(w => !resultingId.contains(w.checkpointId)).map { write =>
      GraphError.InvalidCommit(
        threadId.value,
        s"pending write for task ${write.taskId} names checkpoint ${write.checkpointId}, not ${resultingId.getOrElse("<none>")}"
      )
    }
    conflict.orElse(misdirected).toLeft(()).map { _ =>
      val records = commit.events.zipWithIndex.map { (draft, i) =>
        EventRecord.committed(threadId.value, current.nextSeq + i, draft)
      }
      val writes = commit.pendingWrites.map(upickle.default.writeJs(_))
      threads.update(
        threadId.value,
        current.copy(
          checkpoint = commit.checkpoint.map(Checkpoint.toJson).orElse(current.checkpoint),
          pendingWrites = if commit.checkpoint.isDefined then writes else current.pendingWrites ++ writes,
          events = current.events ++ records,
          nextSeq = current.nextSeq + records.size
        )
      )
      records
    }
  }

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = synchronized {
    val current = record(threadId)
    current.checkpoint match
      case None => Right(None)
      case Some(json) =>
        for
          checkpoint <- Checkpoint.fromJson(json)
          writes     <- Try(current.pendingWrites.map(upickle.default.read[PendingWrite](_))).toResult
        yield Some(StoredCheckpoint(checkpoint, writes))
  }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = synchronized {
    val current = record(threadId)
    if afterSeq + 1 < current.earliestSeq then Left(GraphError.ReplayUnavailable(threadId.value, current.earliestSeq))
    else Right(current.events.filter(_.seq > afterSeq).take(limit))
  }

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = synchronized {
    val current = record(threadId)
    val floor   = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
    threads.update(threadId.value, current.copy(events = current.events.filter(_.seq >= floor), earliestSeq = floor))
    Right(())
  }
