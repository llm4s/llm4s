package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ GraphError, RunId, TenantId, ThreadId }
import org.llm4s.agent.service.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.sql.{ Connection, ResultSet }
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * The [[org.llm4s.agent.service.RunQueue RunQueue]] of a [[PostgresCheckpointer]] (`PostgresCheckpointer.runQueue`,
 * design 4.55): entries and attempts in the store's schema (version 3), each call one transaction on a pooled
 * connection, bounded as the store's calls are (rule 2). Leases expire, and closes are stamped, by the database's clock
 * - the one claims expire by - never a host's (rule 1).
 *
 * Concurrency: a submission takes a transaction-scoped advisory lock on its submission key, if any, and then on its
 * thread, so the key's lookup-or-insert and the thread's run order are decided one submission at a time. [[take]]
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

  private val entries  = t.queueEntries
  private val attempts = t.queueAttempts

  // ---- submissions ----

  def submit(submission: Submission): Result[Ticket] = store.writing("queue.submit") { c =>
    val scope = keyScope(submission.tenantId)
    submission.submissionKey.foreach(key => lock(c, s"key:$scope:${key.value}"))
    lock(c, s"thread:${submission.threadId.value}")
    val held = submission.submissionKey.flatMap { key =>
      query(c, s"SELECT $Columns FROM $entries WHERE key_scope = ? AND submission_key = ?", scope, key.value)(
        raw
      ).headOption
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
          val id  = query(c, s"SELECT nextval('${t.queueEntryIds}')")(_.getLong(1)).head
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it
          // runs - only while it still has no attempt, which a concurrent recordAttempt may have recorded meanwhile
          if submission.policy == BusyThreadPolicy.Interrupt then
            queued.filter(r => r.interrupt && r.attempts == 0).foreach { r =>
              closeRow(c, r.id, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now, unstarted = true)
            }
          update(
            c,
            s"""INSERT INTO $entries
               |(entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, key_scope, submission_key,
               | max_attempts, attempt_count, submitted_at, thread_deleted, state)
               |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, false, 'open')""".stripMargin,
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
        val issued = query(c, s"SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM ${t.queueEntryIds}")(
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
        // candidate heads, skipping rows another worker has locked: workers do not wait for one another
        val candidates = query(
          c,
          s"""SELECT e.entry_id, e.thread_id FROM $entries e
             |WHERE e.state = 'open' AND e.agent_id = ANY(?) AND (e.lease_token IS NULL OR e.lease_expires_at <= ?)
             |  AND e.entry_id = ($HeadOf)
             |ORDER BY e.entry_id
             |FOR UPDATE OF e SKIP LOCKED LIMIT $Candidates""".stripMargin,
          agents,
          now
        )(rows => rows.getLong(1) -> rows.getString(2))
        // the thread's lock, tried: a submission or another take on the thread is deciding its order now
        val chosen = candidates.find { (id, thread) =>
          tryLock(c, s"thread:$thread") && query(
            c,
            s"""SELECT 1 FROM $entries e
               |WHERE e.entry_id = ? AND e.state = 'open' AND (e.lease_token IS NULL OR e.lease_expires_at <= ?)
               |  AND e.entry_id = ($HeadOf)
               |  AND NOT EXISTS (
               |    SELECT 1 FROM $entries o
               |    WHERE o.thread_id = e.thread_id AND o.state = 'open' AND o.entry_id <> e.entry_id
               |      AND o.lease_token IS NOT NULL AND o.lease_expires_at > ?)""".stripMargin,
            id,
            now,
            now
          )(_ => ()).nonEmpty
        }
        chosen match
          case None => Right(None)
          case Some((id, _)) =>
            val token   = query(c, s"SELECT nextval('${t.queueLeaseTokens}')")(_.getLong(1)).head
            val expires = micros(now.plusNanos(request.ttl.toNanos))
            update(
              c,
              s"UPDATE $entries SET lease_holder = ?, lease_token = ?, lease_expires_at = ? WHERE entry_id = ?",
              request.holder,
              token,
              expires,
              id
            )
            view(c, rowOf(c, id).get).map { e =>
              Some(LeasedEntry(e, Lease(e.id, LeaseToken(token), request.holder, expires)))
            }
      }

  private def tryLock(c: Connection, name: String): Boolean =
    query(c, "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", lockName(name))(_.getBoolean(1)).head

  /** `lease`'s entry, locked, if `lease` is its current lease and it is open. */
  private def leased(c: Connection, lease: Lease): Result[Raw] =
    query(c, s"SELECT $Columns FROM $entries WHERE entry_id = ? FOR UPDATE", lease.entryId.value)(raw).headOption match
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
      update(c, s"UPDATE $entries SET lease_expires_at = ? WHERE entry_id = ?", expires, row.id)
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
          s"UPDATE $entries SET lease_holder = NULL, lease_token = NULL, lease_expires_at = NULL WHERE entry_id = ?",
          row.id
        )
        Right(())
  }

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = store.writing("queue.recordAttempt") { c =>
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
          s"""INSERT INTO $attempts (entry_id, attempt, thread_id, run_id, started_at, holder)
             |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin,
          row.id,
          row.attempts + 1,
          row.threadId,
          runId.value,
          micros(store.clockNow(c)),
          lease.holder
        )
        update(c, s"UPDATE $entries SET attempt_count = attempt_count + 1 WHERE entry_id = ?", row.id)
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
      s"""UPDATE $entries e
         |SET state = ?, outcome_json = ?, closed_at = ?, lease_holder = NULL, lease_token = NULL,
         |  closed_seq = COALESCE((SELECT t.next_seq - 1 FROM ${t.threads} t WHERE t.thread_id = e.thread_id), 0),
         |  lease_expires_at = NULL
         |WHERE entry_id = ?${if unstarted then " AND state = 'open' AND attempt_count = 0" else ""}""".stripMargin,
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
          s"""SELECT entry_id FROM $entries WHERE state <> 'open' AND closed_at <= ?
             |ORDER BY closed_at, entry_id LIMIT ? FOR UPDATE SKIP LOCKED""".stripMargin,
          cutoff,
          limit
        )(_.getLong(1))
        if expired.nonEmpty then
          val ids = expired.map(_.toString).toArray
          update(c, s"DELETE FROM $attempts WHERE entry_id = ANY(?::text[]::bigint[])", ids)
          update(c, s"DELETE FROM $entries WHERE entry_id = ANY(?::text[]::bigint[])", ids)
        Right(expired.size)
      }

  // ---- rows ----

  private def rowOf(c: Connection, id: Long): Option[Raw] =
    query(c, s"SELECT $Columns FROM $entries WHERE entry_id = ?", id)(raw).headOption

  /** The thread's open entries, in the order they run. */
  private def openOf(c: Connection, threadId: ThreadId): Vector[Raw] =
    query(
      c,
      s"SELECT $Columns FROM $entries WHERE thread_id = ? AND state = 'open' ORDER BY $RunOrder",
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
        s"SELECT run_id, started_at, holder FROM $attempts WHERE entry_id = ? ORDER BY attempt",
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
      long(18)
    )

  /** The head of `e`'s thread, as a correlated subquery. */
  private val HeadOf: String =
    s"SELECT h.entry_id FROM $entries h WHERE h.thread_id = e.thread_id AND h.state = 'open' ORDER BY $RunOrder LIMIT 1"

private[postgres] object PostgresRunQueue:

  /** A thread's open entries in the order they run (rule 5): attempted first, then `Interrupt` entries, then by id. */
  val RunOrder: String = "(attempt_count = 0), is_interrupt DESC, entry_id"

  /** How many candidate heads one `take` locks and tries. */
  val Candidates: Int = 16

  val Columns: String =
    """entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, submission_key, max_attempts,
      |attempt_count, submitted_at, thread_deleted, lease_holder, lease_token, lease_expires_at, outcome_json,
      |closed_at, closed_seq""".stripMargin

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
    closedSeq: Option[Long]
  )

  /** The scope a submission key is unique in: its tenant (`t:` and its id), or `-` for none. */
  def keyScope(tenantId: Option[TenantId]): String = tenantId.fold("-")(t => s"t:${t.value}")
