package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ GraphError, RunId, TenantId, ThreadId, withLock }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result

import java.time.{ Clock, Instant }
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * The [[RunQueue]] of an `InMemoryCheckpointer` (`InMemoryCheckpointer.runQueue`): entries in memory, under the
 * checkpointer's own lock, leases judged and closes stamped by its clock. It shares the checkpointer's lock so the
 * checkpointer's `deleteThread` marks its open entries, and its `prune` reads their runs, in one step. [[awaitClosed]] wakes on closes rather than polling. Its calls take only each other's brief turn at the lock,
 * so each returns at once.
 */
@Experimental
final class InMemoryRunQueue private[agent] (
  clock: Clock,
  lock: ReentrantLock,
  runCommitted: (ThreadId, RunId) => Boolean,
  lastEvent: ThreadId => Long
) extends RunQueue:

  final private case class Held(holder: String, token: Long, expiresAt: Instant)

  final private case class Row(
    id: Long,
    threadId: ThreadId,
    agentId: AgentId,
    tenantId: Option[TenantId],
    input: ujson.Value,
    policy: BusyThreadPolicy,
    key: Option[SubmissionKey],
    maxAttempts: Int,
    trace: Option[org.llm4s.trace.TraceContext],
    submittedAt: Instant,
    threadDeleted: Boolean,
    attempts: Vector[Attempt],
    lease: Option[Held],
    outcome: Option[EntryOutcome],
    closedAt: Option[Instant],
    closedAtSeq: Option[Long] = None,
    admitted: Boolean = false
  ):
    def open: Boolean = outcome.isEmpty

  private val rows      = mutable.TreeMap.empty[Long, Row]
  private val keys      = mutable.Map.empty[(Option[String], String), Long]
  private var lastId    = 0L
  private var lastLease = 0L

  /** Each thread's open entries, and the run ids every held entry of it recorded: so no call scans every entry. */
  private val openByThread = mutable.Map.empty[ThreadId, mutable.Set[Long]]
  private val attemptRuns  = mutable.Map.empty[ThreadId, mutable.Set[RunId]]

  /**
   * The run ids of attempts recorded before their thread's delete, by thread: fenced from committing to the id until
   * their entry is reclaimed, whether or not it is still open ([[orphaned]]).
   */
  private val deletedRuns = mutable.Map.empty[ThreadId, mutable.Set[RunId]]

  /** Closed entries by when they closed, then id: what `reclaim` takes from the front of. */
  private val closedOrder = mutable.TreeSet.empty[(Instant, Long)]

  /** Signalled, under `lock`, whenever an entry closes. */
  private val closed = lock.newCondition()

  // ---- the run order (rule 5) ----

  private def runOrder(row: Row): (Boolean, Boolean, Long) =
    (row.attempts.isEmpty, row.policy != BusyThreadPolicy.Interrupt, row.id)

  private def queueOf(threadId: ThreadId): Vector[Row] =
    openByThread.get(threadId).fold(Vector.empty[Row])(_.toVector.map(rows).sortBy(runOrder))

  private def view(row: Row): QueueEntry =
    val position = Option.when(row.open)(queueOf(row.threadId).indexWhere(_.id == row.id))
    QueueEntry.stored(
      EntryId.unsafe(row.id),
      row.threadId,
      row.agentId,
      row.tenantId,
      QueuedInput.fromJson(row.input).fold(problem => throw new IllegalStateException(problem), identity),
      row.policy,
      row.key,
      row.maxAttempts,
      row.trace,
      row.submittedAt,
      row.attempts,
      row.admitted,
      row.lease.map(_.holder),
      row.lease.map(_.expiresAt),
      row.outcome,
      row.closedAt,
      row.closedAtSeq,
      position
    )

  private def live(row: Row, now: Instant): Boolean = row.lease.exists(l => now.isBefore(l.expiresAt))

  // ---- submissions ----

  def submit(submission: Submission): Result[Ticket] = withLock(lock) {
    val tenant = submission.tenantId.map(_.value)
    submission.submissionKey.flatMap(k => keys.get(tenant -> k.value)).flatMap(rows.get) match
      case Some(existing) =>
        if same(existing, submission) then
          Right(Ticket(EntryId.unsafe(existing.id), existing.threadId, view(existing).position, existing = true))
        else Left(QueueError.SubmissionKeyReused(submission.submissionKey.get, EntryId.unsafe(existing.id)))
      case None =>
        val queued = queueOf(submission.threadId)
        if submission.policy == BusyThreadPolicy.Reject && queued.nonEmpty then
          Left(GraphError.ThreadBusy(submission.threadId.value, None, GraphError.BusyHolder.Unnamed))
        else
          lastId += 1
          val id  = lastId
          val now = clock.instant()
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it runs
          if submission.policy == BusyThreadPolicy.Interrupt then
            queued.filter(r => r.policy == BusyThreadPolicy.Interrupt && r.attempts.isEmpty).foreach { r =>
              closeRow(r, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now)
            }
          val row = Row(
            id,
            submission.threadId,
            submission.agentId,
            submission.tenantId,
            QueuedInput.toJson(submission.input),
            submission.policy,
            submission.submissionKey,
            submission.maxAttempts,
            submission.traceContext,
            now,
            false,
            Vector.empty,
            None,
            None,
            None
          )
          rows.update(id, row)
          openByThread.getOrElseUpdate(row.threadId, mutable.Set.empty) += id
          submission.submissionKey.foreach(k => keys.update(tenant -> k.value, id))
          Right(Ticket(EntryId.unsafe(id), submission.threadId, view(row).position, existing = false))
  }

  private def same(row: Row, submission: Submission): Boolean =
    row.threadId == submission.threadId && row.agentId == submission.agentId && row.tenantId == submission.tenantId &&
      row.input == QueuedInput.toJson(submission.input) && row.policy == submission.policy

  // ---- reads ----

  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] = withLock(lock)(found(tenantId, entryId))

  private def found(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    rows.get(entryId.value).filter(_.tenantId == tenantId) match
      case Some(row)                       => Right(view(row))
      case None if entryId.value <= lastId => Left(QueueError.TicketExpired(entryId))
      case None                            => Left(QueueError.TicketNotFound(entryId))

  /** Waits on the queue's own closes rather than polling; see [[RunQueue.awaitClosed]]. */
  override def awaitClosed(tenantId: Option[TenantId], entryId: EntryId, timeout: FiniteDuration): Result[QueueEntry] =
    CancelledError.catchInterrupt(withLock(lock) {
      @tailrec def await(remaining: Long): Result[QueueEntry] =
        found(tenantId, entryId) match
          case Right(open) if open.isOpen && remaining > 0 => await(closed.awaitNanos(remaining))
          case other                                       => other
      await(timeout.toNanos)
    }) match
      case Right(result) => result
      case Left(interrupted) =>
        Thread.currentThread().interrupt()
        Left(CancelledError(s"waiting for queue entry ${entryId.value}", Some(interrupted)))

  def openEntries(tenantId: Option[TenantId], threadId: ThreadId): Result[Vector[QueueEntry]] = withLock(lock) {
    Right(queueOf(threadId).filter(_.tenantId == tenantId).map(view))
  }

  // ---- leases ----

  def take(request: TakeRequest): Result[Option[LeasedEntry]] = withLock(lock) {
    val now = clock.instant()
    val heads = openByThread.keysIterator
      .flatMap { thread =>
        val ordered = queueOf(thread)
        val head    = ordered.head
        // one run per thread: no other entry of the thread holds a live lease
        Option.when(!live(head, now) && !ordered.tail.exists(live(_, now)))(head)
      }
      .filter(head => request.agents.contains(head.agentId) && !request.skip.contains(EntryId.unsafe(head.id)))
    heads.minByOption(_.id) match
      case None       => Right(None)
      case Some(head) =>
        // a sibling whose lease expired loses it now, so its holder can no longer act beside the new head's
        queueOf(head.threadId)
          .filter(r => r.id != head.id && r.lease.isDefined)
          .foreach(r => rows.update(r.id, r.copy(lease = None)))
        lastLease += 1
        val held = Held(request.holder, lastLease, now.plusNanos(request.ttl.toNanos))
        val row  = head.copy(lease = Some(held))
        rows.update(row.id, row)
        // a takeable head still holding a lease holds an expired one, never released
        Right(Some(LeasedEntry(view(row), leaseOf(row, held), expiredLease = head.lease.isDefined)))
  }

  def depth(agents: Set[AgentId]): Result[Map[AgentId, QueueDepth]] = withLock(lock) {
    val now  = clock.instant()
    val open = openByThread.valuesIterator.flatMap(_.iterator.map(rows)).filter(r => agents.contains(r.agentId))
    Right(
      open.toVector.groupBy(_.agentId).map { (agent, entries) =>
        val (leased, waiting) = entries.partition(live(_, now))
        val oldest            = waiting.map(_.submittedAt).minOption
        agent -> QueueDepth(waiting.size.toLong, leased.size.toLong, oldest.map(QueueDepth.age(_, now)))
      }
    )
  }

  private def leaseOf(row: Row, held: Held): Lease =
    Lease(EntryId.unsafe(row.id), LeaseToken(held.token), held.holder, held.expiresAt)

  /** `lease`'s entry, if `lease` is its current lease and it is open. */
  private def leased(lease: Lease): Result[Row] =
    rows.get(lease.entryId.value) match
      case None => Left(QueueError.LeaseLost(lease.entryId, lease.token, None))
      case Some(row) =>
        row.outcome match
          case Some(outcome) => Left(QueueError.EntryClosed(lease.entryId, outcome))
          case None if !row.lease.exists(_.token == lease.token.value) =>
            Left(QueueError.LeaseLost(lease.entryId, lease.token, row.lease.map(h => LeaseToken(h.token))))
          case None => Right(row)

  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = withLock(lock) {
    leased(lease).map { row =>
      val held = Held(lease.holder, lease.token.value, clock.instant().plusNanos(ttl.toNanos))
      rows.update(row.id, row.copy(lease = Some(held)))
      leaseOf(row, held)
    }
  }

  def release(lease: Lease): Result[Unit] = withLock(lock) {
    released(leased(lease)).map(_.foreach(row => rows.update(row.id, row.copy(lease = None))))
  }

  /** What `release` does with `leased`'s answer: a closed entry is reported, a lease that is not current is a no-op. */
  private def released(leased: Result[Row]): Result[Option[Row]] = leased match
    case Right(row)                    => Right(Some(row))
    case Left(_: QueueError.LeaseLost) => Right(None)
    case Left(other)                   => Left(other)

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = withLock(lock) {
    leased(lease).flatMap { row =>
      // an attempt's run id names no other run on the thread: no checkpoint and no other attempt has it (rule 4)
      val used = runCommitted(row.threadId, runId) ||
        attemptRuns.get(row.threadId).exists(_.contains(runId))
      if used then
        Left(
          ValidationError(
            "runId",
            s"run '${runId.value}' already names a run or an attempt on thread '${row.threadId.value}'"
          )
        )
      else if row.threadDeleted then
        Right(AttemptStart.ThreadDeleted(view(closeRow(row, EntryOutcome.Cancelled(CancelReason.ThreadDeleted)))))
      else if row.attempts.size >= row.maxAttempts then Right(AttemptStart.Exhausted(view(row)))
      else
        val recorded = row.copy(attempts = row.attempts :+ Attempt(runId, clock.instant(), lease.holder))
        rows.update(row.id, recorded)
        attemptRuns.getOrElseUpdate(row.threadId, mutable.Set.empty) += runId
        Right(AttemptStart.Recorded(view(recorded)))
    }
  }

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = withLock(lock) {
    leased(lease).map(row => view(closeRow(row, outcome)))
  }

  private def closeRow(row: Row, outcome: EntryOutcome, at: Instant = clock.instant()): Row =
    val done =
      row.copy(lease = None, outcome = Some(outcome), closedAt = Some(at), closedAtSeq = Some(lastEvent(row.threadId)))
    rows.update(row.id, done)
    closedOrder += (at -> row.id)
    openByThread.get(row.threadId).foreach { open =>
      open -= row.id
      if open.isEmpty then openByThread.remove(row.threadId)
    }
    closed.signalAll()
    done

  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int] = withLock(lock) {
    val cutoff  = clock.instant().minusNanos(ticketLifetime.toNanos)
    val expired = closedOrder.iterator.takeWhile((at, _) => !at.isAfter(cutoff)).take(math.max(limit, 0)).toVector
    expired.map(_._2).flatMap(rows.get).foreach { r =>
      rows.remove(r.id)
      closedOrder -= (r.closedAt.get -> r.id)
      attemptRuns.get(r.threadId).foreach { runs =>
        runs --= r.attempts.map(_.runId)
        if runs.isEmpty then attemptRuns.remove(r.threadId)
      }
      if r.threadDeleted then
        deletedRuns.get(r.threadId).foreach { runs =>
          runs --= r.attempts.map(_.runId)
          if runs.isEmpty then deletedRuns.remove(r.threadId)
        }
      r.key.foreach(k => keys.remove(r.tenantId.map(_.value) -> k.value))
    }
    Right(expired.size)
  }

  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]] = withLock(lock) {
    Right(
      threads.distinct.flatMap { thread =>
        Option.when(openByThread.contains(thread))(thread -> queueOf(thread).flatMap(_.attempts.map(_.runId)).toSet)
      }.toMap
    )
  }

  /**
   * Whether `runId` is an attempt recorded before a delete of `threadId` while its entry was open: such an attempt
   * must never write to the id again - also once its entry has closed, since its worker may still hold a claim taken
   * before the close - until the entry is reclaimed. Call under the lock.
   */
  private[agent] def orphaned(threadId: ThreadId, runId: RunId): Boolean =
    deletedRuns.get(threadId).exists(_.contains(runId))

  /** Marks the thread's open entries as having outlived their thread: the checkpointer's `deleteThread`, under the lock. */
  private[agent] def threadDeleted(threadId: ThreadId): Unit =
    queueOf(threadId).foreach { r =>
      rows.update(r.id, r.copy(threadDeleted = true))
      if r.attempts.nonEmpty then deletedRuns.getOrElseUpdate(threadId, mutable.Set.empty) ++= r.attempts.map(_.runId)
    }

  /**
   * Records that run `runId` committed a checkpoint on `threadId`: an open entry that recorded it as an attempt is
   * admitted from now on, whatever later leaves the history ([[QueueEntry.admitted]]). The checkpointer's commit,
   * under the lock.
   */
  private[agent] def committed(threadId: ThreadId, runId: RunId): Unit =
    if attemptRuns.get(threadId).exists(_.contains(runId)) then
      queueOf(threadId).filter(r => !r.admitted && r.attempts.exists(_.runId == runId)).foreach { r =>
        rows.update(r.id, r.copy(admitted = true))
      }

  /**
   * The runs whose newest checkpoint on `threadId` the checkpointer's `prune` keeps: every attempt of every open entry
   * of the thread (rule 4). Call under the lock.
   */
  private[agent] def keptRuns(threadId: ThreadId): Set[String] =
    queueOf(threadId).iterator.flatMap(_.attempts.map(_.runId.value)).toSet
