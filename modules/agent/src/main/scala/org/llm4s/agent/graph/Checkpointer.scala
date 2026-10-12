package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.{ Result, TryOps }

import java.time.{ Clock, Instant }
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Durable storage for graph threads: each thread's checkpoint history, the pending writes recorded
 * against its latest checkpoint, its durable event log, and the claim of the run that holds it. One
 * store owns all of them so that a commit is atomic and fenced. Several [[GraphRuntime]]s, in one
 * process or many, may share a store: the claim is what keeps two of them from running one thread at
 * once.
 *
 * Implementations must be safe to call from several threads and processes, and must:
 *
 *  - grant at most one live claim per thread ([[claim]]): a claim is live until its `expiresAt`, by
 *    the store's own clock, has passed; a request while one is live is refused with
 *    [[GraphError.ThreadBusy]] naming its holder, and an expired claim is replaced - taken over;
 *  - issue each claim a [[FencingToken]] strictly greater than every token issued for that thread
 *    before - across releases, takeovers and [[deleteThread]] - so a token is never reused;
 *  - apply a [[Commit]] entirely or not at all, and only if its token is the thread's current claim
 *    ([[GraphError.StaleClaim]] otherwise, checked first) - so a writer that lost its claim, even
 *    one whose token is the highest it has seen, records nothing after another run took over;
 *  - accept a new checkpoint only when its `parent` is the thread's latest checkpoint id - the
 *    thread version the writer expects ([[GraphError.CheckpointConflict]] otherwise) - and its id is
 *    not already in the thread's history, and only pending writes naming the resulting latest
 *    checkpoint ([[GraphError.InvalidCommit]]);
 *  - keep every checkpoint a commit superseded in the thread's history, newest first in [[history]]
 *    and readable by id with [[checkpoint]], until [[prune]], a commit's `retractTurn` or
 *    [[deleteThread]] removes it - but keep pending writes for the latest checkpoint only;
 *  - number a thread's events `1, 2, 3, ...` in commit order, inside the commit, never reusing
 *    a number - including after compaction, pruning or a failed commit;
 *  - keep events until [[compactEvents]] or [[prune]] removes them, and report the earliest sequence
 *    still available when asked to replay from before it ([[GraphError.ReplayUnavailable]]);
 *  - store checkpoints and pending writes as data ([[Checkpoint.toJson]], `PendingWrite`'s
 *    `ReadWriter`), so nothing executable survives a round trip;
 *  - return from [[awaitEventsAfter]] with what [[eventsAfter]] would, at once if the log already
 *    holds an event after `afterSeq`, and otherwise within about `timeout` - with the events of a
 *    commit made meanwhile through any store over the same storage, or empty;
 *  - tolerate the thread calling [[eventsAfter]] or [[awaitEventsAfter]] being interrupted at any
 *    point: cancelling a subscription interrupts its watch mid-read, so an interrupt must end that
 *    one call - with `Left(CancelledError)` or a failed result - and leave the store usable by every
 *    other thread and later call (see [[awaitEventsAfter]]).
 *
 * The holder of a claim may commit and [[renew]] it after `expiresAt` for as long as no other claim
 * has replaced it: expiry only lets another run take the thread over. `CheckpointerContract` in
 * `llm4s-agent-testkit` is the executable form of this contract; a store should pass it.
 */
trait Checkpointer:

  /**
   * Claims `threadId` for `request.holder` until `request.ttl` from now, by the store's clock, if no
   * other claim on it is live, and returns the claim with a new [[FencingToken]]. A live claim -
   * whoever holds it, the same holder included - is `Left(GraphError.ThreadBusy)` naming its holder,
   * with nothing changed. A thread need not have a checkpoint to be claimed.
   */
  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim]

  /**
   * Extends the claim whose token is `token` to `ttl` from now, by the store's clock, and returns it.
   * `Left(GraphError.StaleClaim)` if `token` is not the thread's current claim (another run took it
   * over, it was released, or the thread was deleted). An expired claim nobody replaced is renewed.
   */
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim]

  /**
   * Ends the claim whose token is `token`, so the thread can be claimed again at once. A token that
   * is not the thread's current claim changes nothing and is `Right(())`: a run that lost its claim
   * must never release its successor's.
   */
  def release(threadId: ThreadId, token: FencingToken): Result[Unit]

  /** Applies `commit` atomically, fenced by its token, and returns its events with their sequence numbers. */
  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]]

  /** The thread's latest checkpoint and its pending writes; `None` for a new thread. */
  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]

  /**
   * Up to `limit` of the thread's checkpoints, newest first - the latest is the first of the first
   * page - starting after checkpoint `before` when it is given, so a caller pages through the history
   * by passing the last id of each page. A `before` that is not in the history is
   * `Left(GraphError.CheckpointNotFound)`; a thread with no checkpoint has an empty history.
   */
  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]]

  /** Checkpoint `checkpointId` of the thread's history, latest included; `None` if it is not there. */
  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]]

  /**
   * Removes the checkpoints and events of the thread that `policy` does not keep (see
   * [[RetentionPolicy]]), by the store's clock. The latest checkpoint and its pending writes are always
   * kept. Pruning a checkpoint also removes the events before the oldest checkpoint kept; `maxAge` and
   * `maxEvents` may remove more, including the latest checkpoint's. Removing events raises the replay
   * floor as [[compactEvents]] does. Needs no claim: it touches nothing a run reads or writes. An unknown thread
   * is `Right(())`.
   */
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit]

  /** Up to `limit` events with `seq > afterSeq`, ascending. */
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]]

  /**
   * [[eventsAfter]], waiting for a commit when there is nothing to return yet: up to `limit` events
   * with `seq > afterSeq`, ascending, at once if the log holds any; otherwise those of a commit that
   * lands within `timeout`, or an empty `Vector` once it has passed. It is how a subscription learns
   * of commits made through another [[GraphRuntime]] or process sharing the store (see
   * [[WatchPolicy]]): each subscription calls it in a loop, from the last event it has queued.
   *
   * The default polls. It reads once and, finding nothing, waits `timeout` and returns empty, so the
   * caller's next call reads again: a commit is seen up to `timeout` after it lands, at the cost of
   * one [[eventsAfter]] per `timeout` per subscription. A store that can be told of commits overrides
   * it to return as soon as one lands, without reading meanwhile - [[InMemoryCheckpointer]] wakes on
   * its own commits, and a database store can use its notification channel (PostgreSQL's
   * `LISTEN`/`NOTIFY`). Such a store must wake for commits made through every store over the same
   * storage, not only its own, and still return within about `timeout` when none lands.
   *
   * Errors are [[eventsAfter]]'s - [[GraphError.ReplayUnavailable]] when `afterSeq` is behind the
   * replay floor. Interrupting the waiting thread ends the wait with `Left(CancelledError)`, the
   * thread's interrupt flag set again.
   *
   * '''The reading thread may be interrupted at any point.''' A subscription's watch calls this
   * on a thread of its own, and [[Subscription.cancel]] - or the subscription's end - interrupts that
   * thread whether it is waiting or in the middle of a read, so the interrupt can land inside the
   * store's own I/O ([[eventsAfter]] included). A store must take that as the end of this one call,
   * never as damage to itself: release what the call held (a pooled connection, a lock), and stay
   * usable by other threads and later calls. Beware drivers whose I/O goes through a
   * `java.nio.channels.InterruptibleChannel` (a `FileChannel` or `SocketChannel`): an interrupt
   * during a read or write closes the channel, with `ClosedByInterruptException`, so a store that
   * shares one channel - one connection - between threads would lose it for all of them. Such a
   * store reads on a connection of its own for the waiting call, or discards and replaces a
   * connection an interrupt has closed.
   */
  def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    eventsAfter(threadId, afterSeq, limit).flatMap { found =>
      if found.nonEmpty then Right(found)
      else
        CancelledError.catchInterrupt(TimeUnit.NANOSECONDS.sleep(timeout.toNanos)) match
          case Right(_)          => Right(Vector.empty)
          case Left(interrupted) => Checkpointer.waitCancelled(threadId, interrupted)
    }

  /** Drops events with `seq < beforeSeq`; replay can then start no earlier than `beforeSeq`. */
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit]

  /**
   * Removes everything stored for the thread - its checkpoint history, its pending writes, its
   * event log and its claim - so that its id names a new thread again, whose events are numbered
   * from 1; tokens issued after it are still greater than every token issued before. An unknown
   * thread is `Right(())`. Use [[GraphRuntime.deleteThread]], which claims the thread first, so it
   * refuses a thread with a live run in any runtime, and checks the tenant; the store itself checks
   * neither.
   */
  def deleteThread(threadId: ThreadId): Result[Unit]

object Checkpointer:

  /** An interrupted [[Checkpointer.awaitEventsAfter]]: the flag set again, and `CancelledError`. */
  private[graph] def waitCancelled(threadId: ThreadId, interrupted: InterruptedException): Result[Nothing] =
    Thread.currentThread().interrupt()
    Left(CancelledError(s"waiting for events on thread ${threadId.value}", Some(interrupted)))

/**
 * A [[Checkpointer]] in memory. Checkpoints and pending writes are stored as JSON and decoded on
 * read, exactly as a database-backed store would, so nothing executable survives a round trip.
 * Claims expire, and [[prune]] judges age, by `clock`. Fencing tokens come from one counter for the
 * whole store, so they never repeat for a thread, even after it is deleted. [[awaitEventsAfter]] wakes
 * on the store's own commits, so every runtime sharing the instance sees a commit as soon as it lands,
 * without polling.
 */
final class InMemoryCheckpointer(clock: Clock = Clock.systemUTC()) extends Checkpointer:

  /** For callers that cannot use default arguments (Java, Kotlin): claims expire by the system clock. */
  def this() = this(Clock.systemUTC())

  final private case class Claim(holder: String, token: Long, expiresAt: Instant)

  /** One stored checkpoint: its JSON, with the fields the store itself reads. */
  final private case class Stored(id: String, status: CheckpointStatus, createdAt: Instant, json: ujson.Value)

  final private case class ThreadRecord(
    /** Oldest first; the last is the latest. */
    history: Vector[Stored],
    pendingWrites: Vector[ujson.Value],
    events: Vector[(Long, ujson.Value)],
    nextSeq: Long,
    earliestSeq: Long,
    claim: Option[Claim]
  ):
    def latestId: Option[String] = history.lastOption.map(_.id)
    def isEmpty: Boolean         = history.isEmpty && claim.isEmpty && nextSeq == 1L

  private val threads   = mutable.Map.empty[String, ThreadRecord]
  private var lastToken = 0L
  private val lock      = new java.util.concurrent.locks.ReentrantLock()

  /** Signalled, under `lock`, whenever a commit appends events. */
  private val appended = lock.newCondition()

  private def record(threadId: ThreadId): ThreadRecord =
    threads.getOrElse(threadId.value, ThreadRecord(Vector.empty, Vector.empty, Vector.empty, 1L, 1L, None))

  private def store(threadId: ThreadId, record: ThreadRecord): Unit =
    if record.isEmpty then threads.remove(threadId.value): Unit else threads.update(threadId.value, record)

  private def granted(threadId: ThreadId, claim: Claim): RunClaim =
    RunClaim(threadId, RunId(claim.holder), FencingToken(claim.token), claim.expiresAt)

  private def stale(threadId: ThreadId, current: ThreadRecord, token: FencingToken): GraphError =
    GraphError.StaleClaim(threadId.value, token.value, current.claim.map(_.token))

  private def settled(status: CheckpointStatus): Boolean =
    status == CheckpointStatus.Completed || status == CheckpointStatus.Failed

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = withLock(lock) {
    val current = record(threadId)
    val now     = clock.instant()
    current.claim.filter(c => now.isBefore(c.expiresAt)) match
      case Some(live) => Left(GraphError.ThreadBusy(threadId.value, current.latestId, Some(live.holder)))
      case None =>
        lastToken += 1
        val claim = Claim(request.holder.value, lastToken, now.plusNanos(request.ttl.toNanos))
        store(threadId, current.copy(claim = Some(claim)))
        Right(granted(threadId, claim))
  }

  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] = withLock(lock) {
    val current = record(threadId)
    current.claim.filter(_.token == token.value) match
      case None => Left(stale(threadId, current, token))
      case Some(held) =>
        val renewed = held.copy(expiresAt = clock.instant().plusNanos(ttl.toNanos))
        store(threadId, current.copy(claim = Some(renewed)))
        Right(granted(threadId, renewed))
  }

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    if current.claim.exists(_.token == token.value) then store(threadId, current.copy(claim = None))
    Right(())
  }

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = withLock(lock) {
    val current                 = record(threadId)
    val latestId                = current.latestId
    def invalid(reason: String) = GraphError.InvalidCommit(threadId.value, reason)
    val fenced =
      Option.when(!current.claim.exists(_.token == commit.token.value))(stale(threadId, current, commit.token))
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    val duplicate = commit.checkpoint.filter(c => current.history.exists(_.id == c.id)).map { checkpoint =>
      invalid(s"checkpoint ${checkpoint.id} is already in the thread's history")
    }
    val resultingId = commit.checkpoint.map(_.id).orElse(latestId)
    val misdirected = commit.pendingWrites.find(w => !resultingId.contains(w.checkpointId)).map { write =>
      invalid(
        s"pending write for task ${write.taskId} names checkpoint ${write.checkpointId}, not ${resultingId.getOrElse("<none>")}"
      )
    }
    val unanchored =
      Option.when(commit.retractTurn && commit.checkpoint.isEmpty)(invalid("retractTurn needs a new checkpoint"))
    fenced.orElse(conflict).orElse(duplicate).orElse(misdirected).orElse(unanchored).toLeft(()).map { _ =>
      // events are stored as JSON, like checkpoints, so neither the caller's payloads nor the
      // records handed back can alias what is durable
      val stored = commit.events.zipWithIndex.map { (draft, i) =>
        val seq = current.nextSeq + i
        seq -> upickle.default.writeJs(EventRecord.committed(threadId.value, seq, draft))
      }
      val records = stored.map((_, json) => upickle.default.read[EventRecord](json))
      val writes  = commit.pendingWrites.map(upickle.default.writeJs(_))
      // a blocked turn is retracted back to the newest settled checkpoint, which stays
      val kept =
        if commit.retractTurn then current.history.take(current.history.lastIndexWhere(c => settled(c.status)) + 1)
        else current.history
      val added = commit.checkpoint.map(c => Stored(c.id, c.status, c.createdAt, Checkpoint.toJson(c)))
      store(
        threadId,
        current.copy(
          history = kept ++ added,
          pendingWrites = if commit.checkpoint.isDefined then writes else current.pendingWrites ++ writes,
          events = current.events ++ stored,
          nextSeq = current.nextSeq + stored.size
        )
      )
      if stored.nonEmpty then appended.signalAll()
      records
    }
  }

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = withLock(lock) {
    val current = record(threadId)
    current.history.lastOption match
      case None => Right(None)
      case Some(latest) =>
        for
          checkpoint <- Checkpoint.fromJson(latest.json)
          writes     <- Try(current.pendingWrites.map(upickle.default.read[PendingWrite](_))).toResult
        yield Some(StoredCheckpoint(checkpoint, writes))
  }

  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] = withLock(lock) {
    val newestFirst = record(threadId).history.reverse
    val after = before match
      case None => Right(newestFirst)
      case Some(id) =>
        newestFirst.indexWhere(_.id == id) match
          case -1 => Left(GraphError.CheckpointNotFound(threadId.value, id))
          case at => Right(newestFirst.drop(at + 1))
    after.flatMap(page => sequence(page.take(math.max(limit, 0)).map(c => Checkpoint.fromJson(c.json))))
  }

  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] = withLock(lock) {
    record(threadId).history.find(_.id == checkpointId) match
      case None         => Right(None)
      case Some(stored) => Checkpoint.fromJson(stored.json).map(Some(_))
  }

  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    if current.history.isEmpty && current.events.isEmpty then Right(())
    else
      Try {
        val now    = clock.instant()
        val cutoff = policy.maxAge.map(age => now.minusNanos(age.toNanos))
        // the latest checkpoint is always kept, whatever its age
        val (older, latest) = current.history.splitAt(current.history.size - 1)
        val young           = older.filter(c => cutoff.forall(cut => !c.createdAt.isBefore(cut)))
        val kept            = policy.maxCheckpoints.fold(young ++ latest)(n => (young ++ latest).takeRight(n))
        val keptIds         = kept.map(_.id).toSet
        val events          = current.events.map((seq, json) => seq -> upickle.default.read[EventRecord](json))
        // events are kept while their checkpoints are: from the oldest kept checkpoint's first event on
        val byCheckpoint =
          if kept.size == current.history.size then None
          else events.collectFirst { case (seq, e) if e.checkpointId.exists(keptIds) => seq }
        val byAge = cutoff.map { cut =>
          events.collectFirst { case (seq, e) if !e.timestamp.isBefore(cut) => seq }.getOrElse(current.nextSeq)
        }
        val bySize = policy.maxEvents.map(n => current.nextSeq - n)
        val floor  = (Vector(current.earliestSeq) ++ byCheckpoint ++ byAge ++ bySize).max.min(current.nextSeq)
        store(
          threadId,
          current.copy(history = kept, events = current.events.filter(_._1 >= floor), earliestSeq = floor)
        )
      }.toResult
  }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = withLock(lock) {
    val current = record(threadId)
    if afterSeq + 1 < current.earliestSeq then Left(GraphError.ReplayUnavailable(threadId.value, current.earliestSeq))
    else
      // events are held in ascending `seq`, so the first one after `afterSeq` is found by bisection
      val first: (Long, ujson.Value) = (afterSeq + 1) -> ujson.Null
      val from = current.events.search(first)(using Ordering.by[(Long, ujson.Value), Long](_._1)).insertionPoint
      Try(
        current.events.slice(from, from + limit).map((_, json) => upickle.default.read[EventRecord](json))
      ).toResult
  }

  /** Waits on the store's own commits rather than polling; see [[Checkpointer.awaitEventsAfter]]. */
  override def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    CancelledError.catchInterrupt(withLock(lock) {
      @tailrec def await(remaining: Long): Result[Vector[EventRecord]] =
        eventsAfter(threadId, afterSeq, limit) match
          case Right(found) if found.isEmpty && remaining > 0 => await(appended.awaitNanos(remaining))
          case other                                          => other
      await(timeout.toNanos)
    }) match
      case Right(result)     => result
      case Left(interrupted) => Checkpointer.waitCancelled(threadId, interrupted)

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    val floor   = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
    store(threadId, current.copy(events = current.events.filter(_._1 >= floor), earliestSeq = floor))
    Right(())
  }

  def deleteThread(threadId: ThreadId): Result[Unit] = withLock(lock) {
    threads.remove(threadId.value)
    Right(())
  }

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))
