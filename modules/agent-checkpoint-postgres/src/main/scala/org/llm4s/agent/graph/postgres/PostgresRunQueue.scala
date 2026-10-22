package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ GraphError, RunId, TenantId, ThreadId }
import org.llm4s.agent.service.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.sql.{ Connection, ResultSet }
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/**
 * The [[org.llm4s.agent.service.RunQueue RunQueue]] of a [[PostgresCheckpointer]] (`PostgresCheckpointer.runQueue`,
 * design 4.55): entries and attempts in the store's schema (version 3), each call one transaction on a pooled
 * connection, bounded as the store's calls are (rule 2). Leases expire, and closes are stamped, by the database's clock
 * - the one claims expire by - never a host's (rule 1).
 *
 * Concurrency: a submission takes a transaction-scoped advisory lock on its submission key, if any, and then on its
 * thread, so the key's lookup-or-insert and the thread's run order are decided one submission at a time; recording an
 * attempt, which moves its entry ahead in that order, takes the thread's lock too. [[take]]
 * reads candidate heads `FOR UPDATE SKIP LOCKED`, so workers skip each other's entries rather than wait, and leases
 * one only once it holds the thread's advisory lock (tried, never waited for) and has checked again that the entry is
 * the thread's head and no other entry of the thread holds a live lease. Every call under a lease locks the entry's
 * row first, so it is fenced by the lease token in the same transaction as its write.
 */
final private[postgres] class PostgresRunQueue(store: PostgresCheckpointer) extends RunQueue:

  import PostgresCheckpointer.*
  import PostgresRunQueue.*

  private val t                                   = store.t
  private given PostgresCheckpointer.ErrorContext = store.errorContext

  // ---- submissions ----

  def submit(submission: Submission): Result[Ticket] = store.writing("queue.submit") { c =>
    val scope = keyScope(submission.tenantId)
    submission.submissionKey.foreach(key => lock(c, s"key:$scope:${key.value}"))
    lock(c, s"thread:${submission.threadId.value}")
    val held = submission.submissionKey.flatMap { key =>
      // locked, so a reclaim running now either deletes it first - and the key is free - or skips it
      query(
        c,
        Sql.queueEntryByKey(t),
        scope,
        key.value
      )(raw).headOption
    }
    held match
      case Some(existing) =>
        if same(existing, submission) then
          view(c, existing).map(e => Ticket(e.id, e.threadId, e.position, existing = true))
        else Left(QueueError.SubmissionKeyReused(submission.submissionKey.get, EntryId.unsafe(existing.id)))
      case None =>
        val queued = openOf(c, submission.threadId)
        if submission.policy == BusyThreadPolicy.Reject && queued.nonEmpty then
          Left(GraphError.ThreadBusy(submission.threadId.value, None, GraphError.BusyHolder.Unnamed))
        else
          val now = store.clockNow(c)
          val id  = query(c, Sql.nextQueueEntryId(t))(_.getLong(1)).head
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it
          // runs - only while it still has no attempt, which a concurrent recordAttempt may have recorded meanwhile
          if submission.policy == BusyThreadPolicy.Interrupt then
            queued.filter(r => r.interrupt && r.attempts == 0).foreach { r =>
              closeRow(c, r.id, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now, unstarted = true)
            }
          update(
            c,
            Sql.insertQueueEntry(t),
            id,
            submission.threadId.value,
            submission.agentId.value,
            submission.tenantId.map(_.value),
            ujson.write(QueuedInput.toJson(submission.input)),
            submission.policy.toString,
            java.lang.Boolean.valueOf(submission.policy == BusyThreadPolicy.Interrupt),
            scope,
            submission.submissionKey.map(_.value),
            submission.maxAttempts,
            micros(now)
          )
          val position = openOf(c, submission.threadId).indexWhere(_.id == id)
          Right(Ticket(EntryId.unsafe(id), submission.threadId, Some(position), existing = false))
  }

  private def same(row: Raw, submission: Submission): Boolean =
    row.threadId == submission.threadId.value && row.agentId == submission.agentId.value &&
      row.tenantId == submission.tenantId.map(_.value) && row.policy == submission.policy.toString &&
      ujson.read(row.input) == QueuedInput.toJson(submission.input)

  /** Takes the transaction-scoped advisory lock `name`, in this store's schema, waiting for it. */
  private def lock(c: Connection, name: String): Unit =
    query(c, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockName(name))(_ => ()): Unit

  private def lockName(name: String): String = s"llm4s.agent.queue:${t.quotedSchema}:$name"

  // ---- reads ----

  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    store.reading("queue.entry")(c => found(c, tenantId, entryId))

  private def found(c: Connection, tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    rowOf(c, entryId.value).filter(_.tenantId == tenantId.map(_.value)) match
      case Some(row) => view(c, row)
      case None =>
        val issued = query(c, Sql.issuedQueueEntries(t))(
          _.getLong(1)
        ).head
        if entryId.value <= issued then Left(QueueError.TicketExpired(entryId))
        else Left(QueueError.TicketNotFound(entryId))

  def openEntries(tenantId: Option[TenantId], threadId: ThreadId): Result[Vector[QueueEntry]] =
    store.reading("queue.openEntries") { c =>
      val open = openOf(c, threadId)
      sequence(open.zipWithIndex.filter(_._1.tenantId == tenantId.map(_.value)).map((r, i) => entryOf(c, r, Some(i))))
    }

  // ---- leases ----

  def take(request: TakeRequest): Result[Option[LeasedEntry]] =
    if request.agents.isEmpty then Right(None)
    else
      store.writing("queue.take") { c =>
        val now    = store.clockNow(c)
        val agents = request.agents.toVector.map(_.value).toArray
        // the thread's lock, tried: a submission, an attempt or another take on the thread is deciding its order now
        def takeable(id: Long, thread: String): Boolean =
          lockIfFree(c, s"thread:$thread") && query(
            c,
            Sql.queueTakeable(t),
            id,
            now,
            now
          )(_ => ()).nonEmpty && siblingsFree(c, thread, id, now)
        // candidate heads a batch at a time, skipping rows another worker has locked - workers do not wait for one
        // another - and going on past a full batch none of which could be taken, so busy heads never hide a takeable
        // one behind them
        @tailrec def scan(after: Long): Option[(Long, String)] =
          val batch = query(
            c,
            Sql.queueCandidates(t),
            agents,
            now,
            after,
            now
          )(rows => rows.getLong(1) -> rows.getString(2))
          batch.find(takeable) match
            case None if batch.size == Candidates => scan(batch.last._1)
            case chosen                           => chosen
        scan(0L) match
          case None               => Right(None)
          case Some((id, thread)) =>
            // a sibling whose lease expired loses it now, so its holder can no longer act beside the new head's
            update(
              c,
              Sql.endSiblingLeases(t),
              thread,
              id
            )
            val token   = query(c, Sql.nextLeaseToken(t))(_.getLong(1)).head
            val expires = micros(now.plusNanos(request.ttl.toNanos))
            update(
              c,
              Sql.setLease(t),
              request.holder,
              token,
              expires,
              id
            )
            view(c, rowOf(c, id).get).map { e =>
              Some(LeasedEntry(e, Lease(e.id, LeaseToken(token), request.holder, expires)))
            }
      }

  /** Whether no other open entry of `thread` holds a live lease, its leased rows locked so none is renewed meanwhile. */
  private def siblingsFree(c: Connection, thread: String, id: Long, now: Instant): Boolean =
    query(
      c,
      Sql.siblingLeases(t),
      thread,
      id
    )(instant(_, 1)).forall(expires => !now.isBefore(expires))

  private def lockIfFree(c: Connection, name: String): Boolean =
    query(c, "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", lockName(name))(_.getBoolean(1)).head

  /** `lease`'s entry, locked, if `lease` is its current lease and it is open. */
  private def leased(c: Connection, lease: Lease): Result[Raw] =
    query(c, Sql.lockedQueueEntry(t), lease.entryId.value)(raw).headOption match
      case None => Left(QueueError.LeaseLost(lease.entryId, lease.token, None))
      case Some(row) =>
        row.outcome match
          case Some(json) =>
            outcomeOf(json).flatMap(outcome => Left(QueueError.EntryClosed(lease.entryId, outcome)))
          case None if !row.leaseToken.contains(lease.token.value) =>
            Left(QueueError.LeaseLost(lease.entryId, lease.token, row.leaseToken.map(LeaseToken(_))))
          case None => Right(row)

  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = store.writing("queue.renew") { c =>
    leased(c, lease).map { row =>
      val expires = micros(store.clockNow(c).plusNanos(ttl.toNanos))
      update(c, Sql.renewLease(t), expires, row.id)
      lease.copy(expiresAt = expires)
    }
  }

  def release(lease: Lease): Result[Unit] = store.writing("queue.release") { c =>
    leased(c, lease) match
      case Left(_: QueueError.LeaseLost) => Right(())
      case Left(other)                   => Left(other)
      case Right(row) =>
        update(
          c,
          Sql.releaseLease(t),
          row.id
        )
        Right(())
  }

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = store.writing("queue.recordAttempt") { c =>
    // an attempt moves its entry ahead in the thread's run order, so it is decided under the thread's lock, as a take
    // and a submission are: a take can never lease another head while this attempt is being recorded
    query(c, Sql.queueEntryThread(t), lease.entryId.value)(_.getString(1)).headOption
      .foreach(thread => lock(c, s"thread:$thread"))
    leased(c, lease).flatMap { row =>
      // an attempt's run id names no other run on the thread: no checkpoint and no other attempt has it (rule 4)
      val used =
        query(c, Sql.runIdUsed(t), row.threadId, runId.value, row.threadId, runId.value)(_.getBoolean(1)).head
      if used then
        Left(
          ValidationError(
            "runId",
            s"run '${runId.value}' already names a run or an attempt on thread '${row.threadId}'"
          )
        )
      else if row.threadDeleted then
        closeRow(c, row.id, EntryOutcome.Cancelled(CancelReason.ThreadDeleted), store.clockNow(c))
        view(c, rowOf(c, row.id).get).map(AttemptStart.ThreadDeleted(_))
      else if row.attempts >= row.maxAttempts then view(c, row).map(AttemptStart.Exhausted(_))
      else
        update(
          c,
          Sql.insertQueueAttempt(t),
          row.id,
          row.attempts + 1,
          row.threadId,
          runId.value,
          micros(store.clockNow(c)),
          lease.holder
        )
        update(c, Sql.countQueueAttempt(t), row.id)
        view(c, rowOf(c, row.id).get).map(AttemptStart.Recorded(_))
    }
  }

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = store.writing("queue.close") { c =>
    leased(c, lease).flatMap { row =>
      closeRow(c, row.id, outcome, store.clockNow(c))
      view(c, rowOf(c, row.id).get)
    }
  }

  /** Closes entry `id`; with `unstarted`, only if it is still open with no attempt (checked on the locked row). */
  private def closeRow(c: Connection, id: Long, outcome: EntryOutcome, at: Instant, unstarted: Boolean = false): Unit =
    update(
      c,
      if unstarted then Sql.closeUnstartedQueueEntry(t) else Sql.closeQueueEntry(t),
      outcome.state,
      ujson.write(EntryOutcome.toJson(outcome)),
      micros(at),
      id
    )

  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int] =
    if limit <= 0 then Right(0)
    else
      store.writing("queue.reclaim") { c =>
        val cutoff = store.clockNow(c).minusNanos(ticketLifetime.toNanos)
        val expired = query(
          c,
          Sql.reclaimableQueueEntries(t),
          cutoff,
          limit
        )(_.getLong(1))
        if expired.nonEmpty then
          val ids = expired.map(_.toString).toArray
          update(c, Sql.deleteQueueAttempts(t), ids)
          update(c, Sql.deleteQueueEntries(t), ids)
        Right(expired.size)
      }

  // ---- rows ----

  private def rowOf(c: Connection, id: Long): Option[Raw] =
    query(c, Sql.queueEntry(t), id)(raw).headOption

  /** The thread's open entries, in the order they run. */
  private def openOf(c: Connection, threadId: ThreadId): Vector[Raw] =
    query(
      c,
      Sql.openQueueEntries(t),
      threadId.value
    )(
      raw
    )

  private def view(c: Connection, row: Raw): Result[QueueEntry] =
    val position = Option.when(row.outcome.isEmpty)(openOf(c, ThreadId(row.threadId)).indexWhere(_.id == row.id))
    entryOf(c, row, position)

  private def entryOf(c: Connection, row: Raw, position: Option[Int]): Result[QueueEntry] =
    decoding("queue", "queue entry") {
      val recorded = query(
        c,
        Sql.queueAttemptsOf(t),
        row.id
      )(a => Attempt(RunId(a.getString(1)), instant(a, 2), a.getString(3)))
      QueueEntry.stored(
        EntryId.unsafe(row.id),
        ThreadId(row.threadId),
        AgentId.unsafe(row.agentId),
        row.tenantId.map(TenantId(_)),
        QueuedInput.fromJson(ujson.read(row.input)).fold(p => throw new IllegalStateException(p), identity),
        BusyThreadPolicy.valueOf(row.policy),
        row.key.map(SubmissionKey.unsafe),
        row.maxAttempts,
        row.submitted,
        recorded,
        row.admitted,
        row.leaseHolder,
        row.leaseExpires,
        row.outcome.map(json =>
          EntryOutcome.fromJson(ujson.read(json)).fold(p => throw new IllegalStateException(p), identity)
        ),
        row.closed,
        row.closedSeq,
        position
      )
    }

  private def outcomeOf(json: String): Result[EntryOutcome] =
    decoding("queue", "queue outcome")(
      EntryOutcome.fromJson(ujson.read(json)).fold(p => throw new IllegalStateException(p), identity)
    )

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

  private def raw(r: ResultSet): Raw =
    def long(i: Int): Option[Long]  = { val v = r.getLong(i); Option.when(!r.wasNull())(v) }
    def at(i: Int): Option[Instant] = Option(r.getObject(i)).map(_ => instant(r, i))
    Raw(
      r.getLong(1),
      r.getString(2),
      r.getString(3),
      Option(r.getString(4)),
      r.getString(5),
      r.getString(6),
      r.getBoolean(7),
      Option(r.getString(8)),
      r.getInt(9),
      r.getInt(10),
      instant(r, 11),
      r.getBoolean(12),
      Option(r.getString(13)),
      long(14),
      at(15),
      Option(r.getString(16)),
      at(17),
      long(18),
      r.getBoolean(19)
    )

private[postgres] object PostgresRunQueue:

  /** How many candidate heads one `take` locks and tries at a time. */
  val Candidates: Int = 16

  val Columns: String =
    """entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, submission_key, max_attempts,
      |attempt_count, submitted_at, thread_deleted, lease_holder, lease_token, lease_expires_at, outcome_json,
      |closed_at, closed_seq, admitted""".stripMargin

  final case class Raw(
    id: Long,
    threadId: String,
    agentId: String,
    tenantId: Option[String],
    input: String,
    policy: String,
    interrupt: Boolean,
    key: Option[String],
    maxAttempts: Int,
    attempts: Int,
    submitted: Instant,
    threadDeleted: Boolean,
    leaseHolder: Option[String],
    leaseToken: Option[Long],
    leaseExpires: Option[Instant],
    outcome: Option[String],
    closed: Option[Instant],
    closedSeq: Option[Long],
    admitted: Boolean
  )

  /** The scope a submission key is unique in: its tenant (`t:` and its id), or `-` for none. */
  def keyScope(tenantId: Option[TenantId]): String = tenantId.fold("-")(t => s"t:${t.value}")
