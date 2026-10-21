package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ GraphError, RunId, TenantId, ThreadId }
import org.llm4s.agent.service.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.sql.{ Connection, ResultSet }
import java.time.Clock
import scala.concurrent.duration.FiniteDuration

/**
 * The [[org.llm4s.agent.service.RunQueue RunQueue]] of a [[SqliteCheckpointer]] (`SqliteCheckpointer.runQueue`,
 * design 4.55): entries and attempts in the store's file (schema version 9), over its connection and in its write
 * transactions, so each call is atomic and bounded as the store's calls are. Leases expire, and closes are stamped,
 * by the store's clock. A write transaction holds the file's lock, so two queues over one file never take one entry.
 * `awaitClosed` polls, as the store's change notification does.
 */
final private[sqlite] class SqliteRunQueue(store: SqliteCheckpointer, clock: Clock) extends RunQueue:

  import SqliteCheckpointer.*
  import SqliteRunQueue.*

  private given SqliteCheckpointer.ErrorContext = store.errorContext

  // ---- submissions ----

  def submit(submission: Submission): Result[Ticket] = store.writing("queue.submit") { c =>
    val scope = keyScope(submission.tenantId)
    val held = submission.submissionKey.flatMap { key =>
      query(
        c,
        s"SELECT $Columns FROM llm4s_checkpoint_queue_entries WHERE key_scope = ? AND submission_key = ?",
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
          val now = epochNanos(clock.instant())
          val id  = bump(c, "last_entry")
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it runs
          if submission.policy == BusyThreadPolicy.Interrupt then
            queued.filter(r => r.interrupt && r.attempts == 0).foreach { r =>
              closeRow(c, r.id, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now)
            }
          update(
            c,
            """INSERT INTO llm4s_checkpoint_queue_entries
              |(entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, key_scope, submission_key,
              | max_attempts, attempt_count, submitted_nanos, thread_deleted, state)
              |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 'open')""".stripMargin,
            id,
            submission.threadId.value,
            submission.agentId.value,
            submission.tenantId.map(_.value),
            ujson.write(QueuedInput.toJson(submission.input)),
            submission.policy.toString,
            if submission.policy == BusyThreadPolicy.Interrupt then 1 else 0,
            scope,
            submission.submissionKey.map(_.value),
            submission.maxAttempts,
            now
          )
          val position = openOf(c, submission.threadId).indexWhere(_.id == id)
          Right(Ticket(EntryId.unsafe(id), submission.threadId, Some(position), existing = false))
  }

  private def same(row: Raw, submission: Submission): Boolean =
    row.threadId == submission.threadId.value && row.agentId == submission.agentId.value &&
      row.tenantId == submission.tenantId.map(_.value) && row.policy == submission.policy.toString &&
      ujson.read(row.input) == QueuedInput.toJson(submission.input)

  // ---- reads ----

  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    store.reading("queue.entry")(c => found(c, tenantId, entryId))

  private def found(c: Connection, tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    rowOf(c, entryId.value).filter(_.tenantId == tenantId.map(_.value)) match
      case Some(row) => view(c, row)
      case None =>
        val last = query(c, "SELECT value FROM llm4s_checkpoint_meta WHERE key = 'last_entry'")(_.getLong(1)).headOption
        if last.exists(entryId.value <= _) then Left(QueueError.TicketExpired(entryId))
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
        val now    = epochNanos(clock.instant())
        val agents = request.agents.toVector.map(_.value)
        val head = query(
          c,
          s"""SELECT e.entry_id FROM llm4s_checkpoint_queue_entries e
             |WHERE e.state = 'open' AND e.agent_id IN (${agents.map(_ => "?").mkString(", ")})
             |  AND (e.lease_token IS NULL OR e.lease_expires_nanos <= ?)
             |  AND e.entry_id = (
             |    SELECT h.entry_id FROM llm4s_checkpoint_queue_entries h
             |    WHERE h.thread_id = e.thread_id AND h.state = 'open'
             |    ORDER BY $RunOrder LIMIT 1)
             |  AND NOT EXISTS (
             |    SELECT 1 FROM llm4s_checkpoint_queue_entries o
             |    WHERE o.thread_id = e.thread_id AND o.state = 'open' AND o.entry_id <> e.entry_id
             |      AND o.lease_token IS NOT NULL AND o.lease_expires_nanos > ?)
             |ORDER BY e.entry_id LIMIT 1""".stripMargin,
          (agents ++ Vector(now, now))*
        )(_.getLong(1)).headOption
        head match
          case None => Right(None)
          case Some(id) =>
            val token   = bump(c, "last_lease")
            val expires = epochNanos(clock.instant().plusNanos(request.ttl.toNanos))
            update(
              c,
              """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = ?, lease_token = ?, lease_expires_nanos = ?
                |WHERE entry_id = ?""".stripMargin,
              request.holder,
              token,
              expires,
              id
            )
            val row = rowOf(c, id).get
            view(c, row).map { e =>
              Some(LeasedEntry(e, Lease(e.id, LeaseToken(token), request.holder, fromEpochNanos(expires))))
            }
      }

  /** `lease`'s entry, if `lease` is its current lease and it is open. */
  private def leased(c: Connection, lease: Lease): Result[Raw] =
    rowOf(c, lease.entryId.value) match
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
      val expires = epochNanos(clock.instant().plusNanos(ttl.toNanos))
      update(c, "UPDATE llm4s_checkpoint_queue_entries SET lease_expires_nanos = ? WHERE entry_id = ?", expires, row.id)
      lease.copy(expiresAt = fromEpochNanos(expires))
    }
  }

  def release(lease: Lease): Result[Unit] = store.writing("queue.release") { c =>
    leased(c, lease).foreach { row =>
      update(
        c,
        """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL
          |WHERE entry_id = ?""".stripMargin,
        row.id
      )
    }
    Right(())
  }

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = store.writing("queue.recordAttempt") { c =>
    leased(c, lease).flatMap { row =>
      // an attempt's run id names no other run on the thread: no checkpoint and no other attempt has it (rule 4)
      val used = query(
        c,
        """SELECT 1 FROM llm4s_checkpoint_queue_attempts WHERE thread_id = ? AND run_id = ?
          |UNION ALL
          |SELECT 1 FROM llm4s_checkpoint_history INDEXED BY llm4s_checkpoint_history_run
          |WHERE thread_id = ? AND run_id = ? LIMIT 1""".stripMargin,
        row.threadId,
        runId.value,
        row.threadId,
        runId.value
      )(_ => ()).nonEmpty
      if used then Left(runIdUsed(row.threadId, runId))
      else if row.threadDeleted then
        closeRow(c, row.id, EntryOutcome.Cancelled(CancelReason.ThreadDeleted), epochNanos(clock.instant()))
        view(c, rowOf(c, row.id).get).map(AttemptStart.ThreadDeleted(_))
      else if row.attempts >= row.maxAttempts then view(c, row).map(AttemptStart.Exhausted(_))
      else
        update(
          c,
          """INSERT INTO llm4s_checkpoint_queue_attempts (entry_id, attempt, thread_id, run_id, started_nanos, holder)
            |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin,
          row.id,
          row.attempts + 1,
          row.threadId,
          runId.value,
          epochNanos(clock.instant()),
          lease.holder
        )
        update(
          c,
          "UPDATE llm4s_checkpoint_queue_entries SET attempt_count = attempt_count + 1 WHERE entry_id = ?",
          row.id
        )
        view(c, rowOf(c, row.id).get).map(AttemptStart.Recorded(_))
    }
  }

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = store.writing("queue.close") { c =>
    leased(c, lease).flatMap { row =>
      closeRow(c, row.id, outcome, epochNanos(clock.instant()))
      view(c, rowOf(c, row.id).get)
    }
  }

  private def closeRow(c: Connection, id: Long, outcome: EntryOutcome, at: Long): Unit =
    update(
      c,
      """UPDATE llm4s_checkpoint_queue_entries
        |SET state = ?, outcome_json = ?, closed_nanos = ?, lease_holder = NULL, lease_token = NULL,
        |  lease_expires_nanos = NULL
        |WHERE entry_id = ?""".stripMargin,
      outcome.state,
      ujson.write(EntryOutcome.toJson(outcome)),
      at,
      id
    )

  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int] =
    if limit <= 0 then Right(0)
    else
      store.writing("queue.reclaim") { c =>
        val cutoff = epochNanos(clock.instant().minusNanos(ticketLifetime.toNanos))
        val expired = query(
          c,
          """SELECT entry_id FROM llm4s_checkpoint_queue_entries
            |WHERE state <> 'open' AND closed_nanos <= ? ORDER BY closed_nanos, entry_id LIMIT ?""".stripMargin,
          cutoff,
          limit
        )(_.getLong(1))
        expired.foreach { id =>
          update(c, "DELETE FROM llm4s_checkpoint_queue_attempts WHERE entry_id = ?", id)
          update(c, "DELETE FROM llm4s_checkpoint_queue_entries WHERE entry_id = ?", id)
        }
        Right(expired.size)
      }

  // ---- rows ----

  private def rowOf(c: Connection, id: Long): Option[Raw] =
    query(c, s"SELECT $Columns FROM llm4s_checkpoint_queue_entries WHERE entry_id = ?", id)(raw).headOption

  /** The thread's open entries, in the order they run. */
  private def openOf(c: Connection, threadId: ThreadId): Vector[Raw] =
    query(
      c,
      s"SELECT $Columns FROM llm4s_checkpoint_queue_entries WHERE thread_id = ? AND state = 'open' ORDER BY $RunOrder",
      threadId.value
    )(raw)

  private def view(c: Connection, row: Raw): Result[QueueEntry] =
    val position = Option.when(row.outcome.isEmpty)(openOf(c, ThreadId(row.threadId)).indexWhere(_.id == row.id))
    entryOf(c, row, position)

  private def entryOf(c: Connection, row: Raw, position: Option[Int]): Result[QueueEntry] =
    decoding("queue", "queue entry") {
      val attempts = query(
        c,
        "SELECT run_id, started_nanos, holder FROM llm4s_checkpoint_queue_attempts WHERE entry_id = ? ORDER BY attempt",
        row.id
      )(a => Attempt(RunId(a.getString(1)), fromEpochNanos(a.getLong(2)), a.getString(3)))
      QueueEntry.stored(
        EntryId.unsafe(row.id),
        ThreadId(row.threadId),
        AgentId.unsafe(row.agentId),
        row.tenantId.map(TenantId(_)),
        QueuedInput.fromJson(ujson.read(row.input)).fold(p => throw new IllegalStateException(p), identity),
        BusyThreadPolicy.valueOf(row.policy),
        row.key.map(SubmissionKey.unsafe),
        row.maxAttempts,
        fromEpochNanos(row.submitted),
        attempts,
        row.leaseHolder,
        row.leaseExpires.map(fromEpochNanos),
        row.outcome.map(json =>
          EntryOutcome.fromJson(ujson.read(json)).fold(p => throw new IllegalStateException(p), identity)
        ),
        row.closed.map(fromEpochNanos),
        position
      )
    }

  private def outcomeOf(json: String): Result[EntryOutcome] =
    decoding("queue", "queue outcome")(
      EntryOutcome.fromJson(ujson.read(json)).fold(p => throw new IllegalStateException(p), identity)
    )

  /** The meta counter `key`, moved on by one, inside the caller's write transaction. */
  private def bump(c: Connection, key: String): Long =
    update(c, "UPDATE llm4s_checkpoint_meta SET value = value + 1 WHERE key = ?", key)
    query(c, "SELECT value FROM llm4s_checkpoint_meta WHERE key = ?", key)(_.getLong(1)).head

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

private[sqlite] object SqliteRunQueue:

  /** A thread's open entries in the order they run (rule 5): attempted first, then `Interrupt` entries, then by id. */
  val RunOrder: String = "(attempt_count = 0), is_interrupt DESC, entry_id"

  val Columns: String =
    """entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, submission_key, max_attempts,
      |attempt_count, submitted_nanos, thread_deleted, lease_holder, lease_token, lease_expires_nanos, outcome_json,
      |closed_nanos""".stripMargin

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
    submitted: Long,
    threadDeleted: Boolean,
    leaseHolder: Option[String],
    leaseToken: Option[Long],
    leaseExpires: Option[Long],
    outcome: Option[String],
    closed: Option[Long]
  )

  def raw(r: ResultSet): Raw =
    def long(i: Int): Option[Long] = { val v = r.getLong(i); Option.when(!r.wasNull())(v) }
    Raw(
      r.getLong(1),
      r.getString(2),
      r.getString(3),
      Option(r.getString(4)),
      r.getString(5),
      r.getString(6),
      r.getInt(7) == 1,
      Option(r.getString(8)),
      r.getInt(9),
      r.getInt(10),
      r.getLong(11),
      r.getInt(12) == 1,
      Option(r.getString(13)),
      long(14),
      long(15),
      Option(r.getString(16)),
      long(17)
    )

  def runIdUsed(threadId: String, runId: RunId): ValidationError =
    ValidationError("runId", s"run '${runId.value}' already names a run or an attempt on thread '$threadId'")

  /** The scope a submission key is unique in: its tenant, or `-` for none (a tenant id is never `-` here: it is `t:`). */
  def keyScope(tenantId: Option[TenantId]): String = tenantId.fold("-")(t => s"t:${t.value}")
