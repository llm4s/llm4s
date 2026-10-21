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
 * checkpointer's own lock, leases judged and closes stamped by its clock. It shares the checkpointer's lock so its
 * submissions read the thread's deletion marker, and the checkpointer's `prune` reads its open entries' runs, in one
 * step. [[awaitClosed]] wakes on closes rather than polling. Its calls take only each other's brief turn at the lock,
 * so each returns at once.
 */
@Experimental
final class InMemoryRunQueue private[agent] (
  clock: Clock,
  lock: ReentrantLock,
  deletionMarker: ThreadId => Long
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
    submittedAt: Instant,
    marker: Long,
    attempts: Vector[Attempt],
    lease: Option[Held],
    outcome: Option[EntryOutcome],
    closedAt: Option[Instant]
  ):
    def open: Boolean = outcome.isEmpty

  private val rows      = mutable.TreeMap.empty[Long, Row]
  private val keys      = mutable.Map.empty[(Option[String], String), Long]
  private var lastId    = 0L
  private var lastLease = 0L

  /** Signalled, under `lock`, whenever an entry closes. */
  private val closed = lock.newCondition()

  // ---- the run order (rule 5) ----

  private def runOrder(row: Row): (Boolean, Boolean, Long) =
    (row.attempts.isEmpty, row.policy != BusyThreadPolicy.Interrupt, row.id)

  private def queueOf(threadId: ThreadId): Vector[Row] =
    rows.valuesIterator.filter(r => r.open && r.threadId == threadId).toVector.sortBy(runOrder)

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
      row.submittedAt,
      row.attempts,
      row.lease.map(_.holder),
      row.lease.map(_.expiresAt),
      row.outcome,
      row.closedAt,
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
            now,
            deletionMarker(submission.threadId),
            Vector.empty,
            None,
            None,
            None
          )
          rows.update(id, row)
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
    val heads = rows.valuesIterator
      .filter(_.open)
      .toVector
      .groupBy(_.threadId)
      .valuesIterator
      .flatMap { queue =>
        val ordered = queue.sortBy(runOrder)
        val head    = ordered.head
        // one run per thread: no other entry of the thread holds a live lease
        Option.when(!live(head, now) && !ordered.tail.exists(live(_, now)))(head)
      }
      .filter(head => request.agents.contains(head.agentId))
    heads.minByOption(_.id) match
      case None => Right(None)
      case Some(head) =>
        lastLease += 1
        val held = Held(request.holder, lastLease, now.plusNanos(request.ttl.toNanos))
        val row  = head.copy(lease = Some(held))
        rows.update(row.id, row)
        Right(Some(LeasedEntry(view(row), leaseOf(row, held))))
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
    leased(lease).foreach(row => rows.update(row.id, row.copy(lease = None)))
    Right(())
  }

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = withLock(lock) {
    leased(lease).flatMap { row =>
      if row.attempts.exists(_.runId == runId) then
        Left(ValidationError("runId", s"entry ${row.id} already recorded an attempt as run '${runId.value}'"))
      else if deletionMarker(row.threadId) > row.marker then
        Right(AttemptStart.ThreadDeleted(view(closeRow(row, EntryOutcome.Cancelled(CancelReason.ThreadDeleted)))))
      else if row.attempts.size >= row.maxAttempts then Right(AttemptStart.Exhausted(view(row)))
      else
        val recorded = row.copy(attempts = row.attempts :+ Attempt(runId, clock.instant(), lease.holder))
        rows.update(row.id, recorded)
        Right(AttemptStart.Recorded(view(recorded)))
    }
  }

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = withLock(lock) {
    leased(lease).map(row => view(closeRow(row, outcome)))
  }

  private def closeRow(row: Row, outcome: EntryOutcome, at: Instant = clock.instant()): Row =
    val done = row.copy(lease = None, outcome = Some(outcome), closedAt = Some(at))
    rows.update(row.id, done)
    closed.signalAll()
    done

  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int] = withLock(lock) {
    val cutoff = clock.instant().minusNanos(ticketLifetime.toNanos)
    val expired = rows.valuesIterator
      .filter(r => r.closedAt.exists(at => !at.isAfter(cutoff)))
      .toVector
      .sortBy(r => (r.closedAt.get, r.id))
      .take(math.max(limit, 0))
    expired.foreach { r =>
      rows.remove(r.id)
      r.key.foreach(k => keys.remove(r.tenantId.map(_.value) -> k.value))
    }
    Right(expired.size)
  }

  /**
   * The runs whose newest checkpoint on `threadId` the checkpointer's `prune` keeps: every attempt of every open entry
   * of the thread (rule 4). Call under the lock.
   */
  private[agent] def keptRuns(threadId: ThreadId): Set[String] =
    rows.valuesIterator.filter(r => r.open && r.threadId == threadId).flatMap(_.attempts.map(_.runId.value)).toSet
