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
 * design 4.55): entries and attempts in the store's file (schema version 10, and 11 for W3's stop requests), over its connection and in its write
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
        // only the submitter's tenant's entries are seen (rule 9, #3085): another tenant's are never reported
        val tenant = submission.tenantId.map(_.value)
        val queued = openLight(c, submission.threadId, tenant)
        // Reject: busy with an open entry of the submitter's tenant, or with a live claim of its tenant - a direct run
        // (W3) - by the store's clock; another tenant's entry or claim is never reported (rule 9)
        val busy         = claimed(c, submission.threadId, submission.tenantId)
        lazy val refused = Submission.refusal(submission, queued.size)
        if submission.policy == BusyThreadPolicy.Reject && (queued.nonEmpty || busy) then
          Left(GraphError.ThreadBusy(submission.threadId.value, None, GraphError.BusyHolder.Unnamed))
        else if refused.isDefined then Left(refused.get)
        else
          val now = epochNanos(clock.instant())
          val id  = bump(c, "last_entry")
          // an Interrupt reaches only its own tenant's entries of the thread (rule 9)
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it runs
          // the entries closed here are answered in the ticket, in this transaction, so only this submit reports them
          val interrupted =
            if submission.policy != BusyThreadPolicy.Interrupt then Vector.empty
            else
              queued.filter(r => r.interrupt && r.attempts == 0).map { r =>
                closeRow(c, r.id, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now)
                r.id
              }
          if submission.policy == BusyThreadPolicy.Interrupt then
            // an entry that has started is asked to stop at its next safe point for this one, unless it is cancelled
            queued.filter(r => r.attempts > 0 && !DecodedEntry.cancelling(r.stop)).foreach { r =>
              setStop(c, r.id, StopRequest.Interrupt(EntryId.unsafe(id)))
            }
          update(
            c,
            """INSERT INTO llm4s_checkpoint_queue_entries
              |(entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, key_scope, submission_key,
              | max_attempts, attempt_count, submitted_nanos, thread_deleted, admitted, state, trace_parent)
              |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 0, 'open', ?)""".stripMargin,
            id,
            submission.threadId.value,
            submission.agentId.value,
            submission.tenantId.map(_.value),
            ujson.write(QueuedInput.toJson(submission.input), escapeUnicode = true),
            submission.policy.toString,
            if submission.policy == BusyThreadPolicy.Interrupt then 1 else 0,
            scope,
            submission.submissionKey.map(_.value),
            submission.maxAttempts,
            now,
            submission.traceContext.map(_.traceParent)
          )
          val position = positionOf(c, rowOf(c, id).get)
          sequence(interrupted.map(i => view(c, rowOf(c, i).get))).map { closed =>
            Ticket(EntryId.unsafe(id), submission.threadId, Some(position), existing = false, closed)
          }
  }

  /**
   * Whether a live claim of `tenantId` holds `threadId`, by the store's clock: one that recorded its tenant, and it is
   * this one (a claim recorded no tenant only before claims carried one, and is not counted).
   */
  private def claimed(c: Connection, threadId: ThreadId, tenantId: Option[TenantId]): Boolean =
    val now = clock.instant()
    query(
      c,
      """SELECT 1 FROM llm4s_checkpoint_threads WHERE thread_id = ? AND claim_token IS NOT NULL
        |  AND claim_tenant_token = claim_token AND claim_tenant IS ?
        |  AND (claim_expires_second > ? OR (claim_expires_second = ? AND claim_expires_nano > ?))""".stripMargin,
      threadId.value,
      tenantId.map(_.value),
      now.getEpochSecond,
      now.getEpochSecond,
      now.getNano.toLong
    )(_ => ()).nonEmpty

  private def setStop(c: Connection, id: Long, request: StopRequest): Unit =
    update(
      c,
      "UPDATE llm4s_checkpoint_queue_entries SET stop_json = ? WHERE entry_id = ?",
      ujson.write(StopRequest.toJson(request), escapeUnicode = true),
      id
    )

  private def same(row: Raw, submission: Submission): Boolean =
    row.threadId == submission.threadId.value && row.agentId == submission.agentId.value &&
      row.tenantId == submission.tenantId.map(_.value) && row.policy == submission.policy.toString &&
      DecodedEntry.sameInput(row.input, submission)

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
      sequence(openOf(c, threadId, tenantId.map(_.value)).zipWithIndex.map((r, i) => entryOf(c, r, Some(i))))
        .map(_.map(_.entry))
    }

  // ---- leases ----

  def take(request: TakeRequest): Result[Option[LeasedEntry]] =
    if request.agents.isEmpty then Right(None)
    else
      store.writing("queue.take") { c =>
        val now    = epochNanos(clock.instant())
        val agents = request.agents.toVector.map(_.value)
        val skip   = request.skip.toVector.map(_.value)
        val after  = request.after.fold(0L)(_.value)
        val passed =
          if skip.isEmpty then "" else s"AND e.entry_id NOT IN (${skip.map(_ => "?").mkString(", ")})"
        def head = query(
          c,
          // each thread with an open entry of the agents, its head found once (#3085: not once per candidate)
          s"""SELECT e.entry_id, e.lease_token
             |FROM (SELECT DISTINCT thread_id FROM llm4s_checkpoint_queue_entries
             |      WHERE state = 'open' AND agent_id IN (${agents.map(_ => "?").mkString(", ")})) t
             |JOIN llm4s_checkpoint_queue_entries e ON e.entry_id = (${headOf("t.thread_id")})
             |WHERE e.agent_id IN (${agents.map(_ => "?").mkString(", ")}) $passed AND e.entry_id > ?
             |  AND (e.lease_token IS NULL OR e.lease_expires_nanos <= ?)
             |  AND (e.not_before_nanos IS NULL OR e.not_before_nanos <= ? OR e.stop_json IS NOT NULL)
             |  AND NOT EXISTS (
             |    SELECT 1 FROM llm4s_checkpoint_queue_entries o
             |    WHERE o.thread_id = e.thread_id AND o.state = 'open' AND o.entry_id <> e.entry_id
             |      AND o.lease_token IS NOT NULL AND o.lease_expires_nanos > ?)
             |ORDER BY e.entry_id LIMIT 1""".stripMargin,
          (agents ++ agents ++ skip ++ Vector(after, now, now, now))*
          // a takeable head still holding a lease token holds an expired lease, never released
        )(r => r.getLong(1) -> (r.getObject(2) != null)).headOption
        // a head that does not decode is closed, in this transaction, and the take goes on to the next (#3091)
        @scala.annotation.tailrec
        def readable(closed: Int): Result[Option[(Long, Boolean)]] =
          head match
            case Some((id, expired)) =>
              val row = rowOf(c, id).get
              entryOf(c, row, None) match
                case Right(decoded) if !decoded.readable && closed < MaxClosedPerTake =>
                  closeRow(c, id, decoded.unrunnable, epochNanos(clock.instant()))
                  readable(closed + 1)
                case Right(decoded) if !decoded.readable => Right(None)
                case Right(_)                            => Right(Some(id -> expired))
                case Left(error)                         => Left(error)
            case None => Right(None)
        readable(0).flatMap {
          case None                => Right(None)
          case Some((id, expired)) =>
            // a sibling whose lease expired loses it now, so its holder can no longer act beside the new head's
            update(
              c,
              """UPDATE llm4s_checkpoint_queue_entries
                |SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL
                |WHERE thread_id = (SELECT thread_id FROM llm4s_checkpoint_queue_entries WHERE entry_id = ?)
                |  AND state = 'open' AND entry_id <> ? AND lease_token IS NOT NULL""".stripMargin,
              id,
              id
            )
            val token   = bump(c, "last_lease")
            val expires = epochNanos(clock.instant().plusNanos(request.ttl.toNanos))
            update(
              c,
              // an attempt whose lease lapsed with it still open ended in a crash (#3287): counted, and given back
              """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = ?, lease_token = ?, lease_expires_nanos = ?,
                |  not_before_nanos = NULL,
                |  crashes = crashes + CASE WHEN lease_token IS NOT NULL AND attempt_open = 1 THEN 1 ELSE 0 END,
                |  max_attempts = max_attempts + CASE WHEN lease_token IS NOT NULL AND attempt_open = 1 THEN 1 ELSE 0 END,
                |  attempt_open = 0
                |WHERE entry_id = ?""".stripMargin,
              request.holder,
              token,
              expires,
              id
            )
            val row = rowOf(c, id).get
            view(c, row).map { e =>
              Some(
                LeasedEntry(
                  e,
                  Lease(e.id, LeaseToken(token), request.holder, fromEpochNanos(expires), e.stopRequest),
                  expired
                )
              )
            }
        }
      }

  def depth(agents: Set[AgentId]): Result[Map[AgentId, QueueDepth]] =
    if agents.isEmpty then Right(Map.empty) else depthOf(Some(agents))

  def allDepths(): Result[Map[AgentId, QueueDepth]] = depthOf(None)

  private def depthOf(agents: Option[Set[AgentId]]): Result[Map[AgentId, QueueDepth]] =
    store.reading("queue.depth") { c =>
      val now   = clock.instant()
      val ids   = agents.toVector.flatten.map(_.value)
      val asked = if agents.isEmpty then "" else s"AND agent_id IN (${ids.map(_ => "?").mkString(", ")})"
      val rows = query(
        c,
        s"""SELECT agent_id,
             |  SUM(CASE WHEN lease_token IS NOT NULL AND lease_expires_nanos > ? THEN 0 ELSE 1 END),
             |  SUM(CASE WHEN lease_token IS NOT NULL AND lease_expires_nanos > ? THEN 1 ELSE 0 END),
             |  MIN(CASE WHEN lease_token IS NOT NULL AND lease_expires_nanos > ? THEN NULL ELSE submitted_nanos END)
             |FROM llm4s_checkpoint_queue_entries
             |WHERE state = 'open' $asked
             |GROUP BY agent_id""".stripMargin,
        (Vector(epochNanos(now), epochNanos(now), epochNanos(now)) ++ ids)*
      ) { r =>
        val oldest = Option(r.getObject(4)).map(_ => fromEpochNanos(r.getLong(4)))
        AgentId.unsafe(r.getString(1)) -> QueueDepth(r.getLong(2), r.getLong(3), oldest.map(QueueDepth.age(_, now)))
      }
      Right(rows.toMap)
    }

  def closeUntaken(age: FiniteDuration, limit: Int): Result[Vector[QueueEntry]] =
    if limit <= 0 then Right(Vector.empty)
    else
      store.writing("queue.closeUntaken") { c =>
        val now = clock.instant()
        val candidates = query(
          c,
          // each thread's head, as take finds it, untaken: no attempt, no live lease on the thread, old enough
          s"""SELECT e.entry_id, e.thread_id, e.tenant_id, e.agent_id
             |FROM (SELECT DISTINCT thread_id FROM llm4s_checkpoint_queue_entries WHERE state = 'open') t
             |JOIN llm4s_checkpoint_queue_entries e ON e.entry_id = (${headOf("t.thread_id")})
             |WHERE e.attempt_count = 0 AND e.submitted_nanos <= ?
             |  AND NOT EXISTS (
             |    SELECT 1 FROM llm4s_checkpoint_queue_entries o
             |    WHERE o.thread_id = e.thread_id AND o.state = 'open'
             |      AND o.lease_token IS NOT NULL AND o.lease_expires_nanos > ?)
             |ORDER BY e.entry_id LIMIT ?""".stripMargin,
          epochNanos(now.minusNanos(age.toNanos)),
          epochNanos(now),
          limit
        )(r => (r.getLong(1), r.getString(2), Option(r.getString(3)), r.getString(4)))
        // a direct run holding the thread is what it waits for, not a missing worker
        val untaken =
          candidates.filterNot((_, thread, tenant, _) => claimed(c, ThreadId(thread), tenant.map(TenantId(_))))
        untaken.foreach { (id, _, _, agent) =>
          closeRow(c, id, RunQueue.untakenOutcome(AgentId.unsafe(agent), age), epochNanos(now))
        }
        sequence(untaken.map((id, _, _, _) => view(c, rowOf(c, id).get)))
      }

  /** `lease`'s entry, if `lease` is its current lease and it is open. */
  private def leased(c: Connection, lease: Lease): Result[Raw] =
    rowOf(c, lease.entryId.value) match
      case None => Left(QueueError.LeaseLost(lease.entryId, lease.token, None))
      case Some(row) =>
        row.outcome match
          case Some(json) => Left(QueueError.EntryClosed(lease.entryId, DecodedEntry.outcomeOf(json)))
          case None if !row.leaseToken.contains(lease.token.value) =>
            Left(QueueError.LeaseLost(lease.entryId, lease.token, row.leaseToken.map(LeaseToken(_))))
          case None => Right(row)

  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = store.writing("queue.renew") { c =>
    leased(c, lease).flatMap { row =>
      val expires = epochNanos(clock.instant().plusNanos(ttl.toNanos))
      update(c, "UPDATE llm4s_checkpoint_queue_entries SET lease_expires_nanos = ? WHERE entry_id = ?", expires, row.id)
      entryOf(c, row, None).map(e => lease.copy(expiresAt = fromEpochNanos(expires), stop = e.entry.stopRequest))
    }
  }

  def cancel(tenantId: Option[TenantId], target: CancelTarget, reason: CancelReason): Result[Cancellation] =
    store.writing("queue.cancel") { c =>
      val targeted: Result[Vector[Raw]] = target match
        case CancelTarget.Entry(id) => found(c, tenantId, id).map(_ => rowOf(c, id.value).toVector)
        case CancelTarget.Thread(t) => Right(openOf(c, t, tenantId.map(_.value)))
      targeted.flatMap { rows =>
        // the rows are read in this write transaction, so the entries closed here are closed by this call only
        val closed = rows.flatMap { row =>
          // never started: closed now, so no worker runs it; started: asked to stop, a cancel replacing an interrupt
          if row.outcome.isEmpty && row.attempts == 0 then
            closeRow(c, row.id, EntryOutcome.Cancelled(reason), epochNanos(clock.instant()))
            Some(EntryId.unsafe(row.id))
          else
            if row.outcome.isEmpty && !DecodedEntry.cancelling(row.stop) then
              setStop(c, row.id, StopRequest.Cancel(reason))
            None
        }
        // read back in one pass, each position from one reading of the thread's order (#3095)
        val after = rowsOf(c, rows.map(_.id))
        val positions =
          rows.headOption.fold(Map.empty[Long, Int])(r => positionsOf(c, ThreadId(r.threadId), r.tenantId))
        sequence(after.map(r => entryOf(c, r, Option.when(r.outcome.isEmpty)(positions.getOrElse(r.id, 0)))))
          .map(entries => Cancellation(entries.map(_.entry), closed.toSet))
      }
    }

  def release(lease: Lease): Result[Unit] = released(
    lease,
    "queue.release",
    """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL,
      |  attempt_open = 0
      |WHERE entry_id = ?""".stripMargin
  )

  // the drained attempt stays in the history, and the entry is given one more if it made one (#3241)
  override private[llm4s] def releaseDrained(lease: Lease): Result[Unit] = released(
    lease,
    "queue.releaseDrained",
    """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL,
      |  max_attempts = CASE WHEN attempt_count > 0 THEN max_attempts + 1 ELSE max_attempts END, attempt_open = 0
      |WHERE entry_id = ?""".stripMargin
  )

  // the attempt stays charged; no take hands the entry out until its delay has passed (#3257)
  override private[llm4s] def releaseRetry(lease: Lease, delay: FiniteDuration): Result[Unit] =
    store.writing("queue.releaseRetry") { c =>
      leased(c, lease) match
        case Left(_: QueueError.LeaseLost) => Right(())
        case Left(other)                   => Left(other)
        case Right(row) =>
          update(
            c,
            """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL,
              |  not_before_nanos = ?, attempt_open = 0
              |WHERE entry_id = ?""".stripMargin,
            epochNanos(clock.instant().plusNanos(delay.toNanos)),
            row.id
          )
          Right(())
    }

  private def released(lease: Lease, operation: String, sql: String): Result[Unit] = store.writing(operation) { c =>
    leased(c, lease) match
      case Left(_: QueueError.LeaseLost) => Right(())
      case Left(other)                   => Left(other)
      case Right(row) =>
        update(c, sql, row.id)
        Right(())
  }

  override def closedEntries(
    tenantId: Option[TenantId],
    state: String,
    after: Option[EntryId],
    limit: Int
  ): Result[Vector[QueueEntry]] =
    RunQueue.closedEntriesRefusal(state, limit).toLeft(()).flatMap { _ =>
      store.reading("queue.closedEntries") { c =>
        val rows = query(
          c,
          s"""SELECT $Columns FROM llm4s_checkpoint_queue_entries
             |WHERE state = ? AND tenant_id IS ? AND entry_id > ? ORDER BY entry_id LIMIT ?""".stripMargin,
          state,
          tenantId.map(_.value),
          after.fold(0L)(_.value),
          limit
        )(raw)
        sequence(rows.map(view(c, _)))
      }
    }

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = store
    .writing("queue.recordAttempt") { c =>
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
        // an entry an Interrupt entry displaced after its take is no longer the head: its lease ends here, so the
        // interrupting entry runs first (W3, Codex round 6)
        if !headIdOf(c, row.threadId).contains(row.id) then
          update(
            c,
            """UPDATE llm4s_checkpoint_queue_entries SET lease_holder = NULL, lease_token = NULL, lease_expires_nanos = NULL
            |WHERE entry_id = ?""".stripMargin,
            row.id
          )
          // committed, so the lease's end lands; the refusal is answered below
          Right(None)
        else if used then Left(runIdUsed(row.threadId, runId))
        else if row.threadDeleted then
          closeRow(c, row.id, EntryOutcome.Cancelled(CancelReason.ThreadDeleted), epochNanos(clock.instant()))
          view(c, rowOf(c, row.id).get).map(e => Some(AttemptStart.ThreadDeleted(e)))
        else if row.attempts >= row.maxAttempts then view(c, row).map(e => Some(AttemptStart.Exhausted(e)))
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
            "UPDATE llm4s_checkpoint_queue_entries SET attempt_count = attempt_count + 1, attempt_open = 1 WHERE entry_id = ?",
            row.id
          )
          view(c, rowOf(c, row.id).get).map(e => Some(AttemptStart.Recorded(e)))
      }
    }
    .flatMap(_.toRight(QueueError.LeaseLost(lease.entryId, lease.token, None)))

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = store.writing("queue.close") { c =>
    leased(c, lease).flatMap { row =>
      closeRow(c, row.id, outcome, epochNanos(clock.instant()))
      view(c, rowOf(c, row.id).get)
    }
  }

  /**
   * Closes entry `id`; an `Interrupt` entry that never started withdraws the stop it asked of its thread's other open
   * entries (#3094), so a cancelled or superseded interruption stops nothing.
   */
  private def closeRow(c: Connection, id: Long, outcome: EntryOutcome, at: Long): Unit =
    query(
      c,
      "SELECT thread_id FROM llm4s_checkpoint_queue_entries WHERE entry_id = ? AND is_interrupt = 1 AND attempt_count = 0",
      id
    )(_.getString(1)).headOption.foreach { thread =>
      val asked = query(
        c,
        """SELECT entry_id, stop_json FROM llm4s_checkpoint_queue_entries
          |WHERE thread_id = ? AND state = 'open' AND entry_id <> ? AND stop_json IS NOT NULL""".stripMargin,
        thread,
        id
      )(r => r.getLong(1) -> Option(r.getString(2)))
      asked.filter((_, stop) => DecodedEntry.interruptsFor(stop, EntryId.unsafe(id))).foreach { (sibling, _) =>
        update(c, "UPDATE llm4s_checkpoint_queue_entries SET stop_json = NULL WHERE entry_id = ?", sibling)
      }
    }
    update(
      c,
      """UPDATE llm4s_checkpoint_queue_entries
        |SET state = ?, outcome_json = ?, closed_nanos = ?, lease_holder = NULL, lease_token = NULL,
        |  closed_seq = COALESCE((SELECT next_seq - 1 FROM llm4s_checkpoint_threads t WHERE t.thread_id = llm4s_checkpoint_queue_entries.thread_id), 0),
        |  lease_expires_nanos = NULL
        |WHERE entry_id = ?""".stripMargin,
      outcome.state,
      ujson.write(EntryOutcome.toJson(outcome), escapeUnicode = true),
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

  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]] =
    if threads.isEmpty then Right(Map.empty)
    else
      store.reading("queue.openRuns") { c =>
        val ids = threads.distinct.map(_.value)
        val rows = query(
          c,
          s"""SELECT e.thread_id, a.run_id FROM llm4s_checkpoint_queue_entries e
             |LEFT JOIN llm4s_checkpoint_queue_attempts a ON a.entry_id = e.entry_id
             |WHERE e.state = 'open' AND e.thread_id IN (${ids.map(_ => "?").mkString(", ")})""".stripMargin,
          ids*
        )(r => ThreadId(r.getString(1)) -> Option(r.getString(2)).map(RunId(_)))
        Right(rows.groupMapReduce(_._1)(_._2.toSet)(_ ++ _))
      }

  // ---- rows ----

  private def rowOf(c: Connection, id: Long): Option[Raw] =
    query(c, s"SELECT $Columns FROM llm4s_checkpoint_queue_entries WHERE entry_id = ?", id)(raw).headOption

  private def rowsOf(c: Connection, ids: Vector[Long]): Vector[Raw] =
    if ids.isEmpty then Vector.empty
    else
      val read = query(
        c,
        s"SELECT $Columns FROM llm4s_checkpoint_queue_entries WHERE entry_id IN (${ids.map(_ => "?").mkString(", ")})",
        ids*
      )(raw).map(r => r.id -> r).toMap
      ids.flatMap(read.get)

  /** The thread's open entries of `tenantId`, in the order they run. */
  private def openOf(c: Connection, threadId: ThreadId, tenantId: Option[String]): Vector[Raw] =
    query(
      c,
      s"SELECT $Columns FROM (${ranked("thread_id = ? AND tenant_id IS ?")}) q ORDER BY ${runOrder("q")}",
      threadId.value,
      tenantId
    )(raw)

  /**
   * The thread's open entries of `tenantId`, in the order they run, without their inputs (#3095): what a submission
   * decides by.
   */
  private def openLight(c: Connection, threadId: ThreadId, tenantId: Option[String]): Vector[Light] =
    query(
      c,
      s"""SELECT entry_id, is_interrupt, attempt_count, stop_json
         |FROM (${ranked("thread_id = ? AND tenant_id IS ?")}) q ORDER BY ${runOrder("q")}""".stripMargin,
      threadId.value,
      tenantId
    )(r => Light(r.getLong(1), r.getInt(2) == 1, r.getInt(3), Option(r.getString(4))))

  /** The id of the thread's head, in the run order of every tenant's entries. */
  private def headIdOf(c: Connection, threadId: String): Option[Long] =
    query(c, headOf("?"), threadId)(_.getLong(1)).headOption

  /**
   * How many of its tenant's open entries of the thread run before `row` (rule 9), counted in SQL from the columns
   * the run order is made of, reading no input (#3095).
   */
  private def positionOf(c: Connection, row: Raw): Int =
    query(
      c,
      """SELECT COUNT(*) FROM llm4s_checkpoint_queue_entries o
        |WHERE o.thread_id = ? AND o.tenant_id IS ? AND o.state = 'open'
        |  AND ((o.attempt_count = 0), (1 - o.is_interrupt), o.entry_id) < (?, ?, ?)""".stripMargin,
      row.threadId,
      row.tenantId,
      if row.attempts == 0 then 1 else 0,
      if row.interrupt then 0 else 1,
      row.id
    )(_.getInt(1)).head

  /** Every open entry's position among its tenant's open entries of the thread, read once. */
  private def positionsOf(c: Connection, threadId: ThreadId, tenantId: Option[String]): Map[Long, Int] =
    query(
      c,
      s"SELECT entry_id FROM (${ranked("thread_id = ? AND tenant_id IS ?")}) q ORDER BY ${runOrder("q")}",
      threadId.value,
      tenantId
    )(_.getLong(1)).zipWithIndex.toMap

  private def view(c: Connection, row: Raw): Result[QueueEntry] =
    // only the entry's own tenant's entries are counted (rule 9)
    entryOf(c, row, Option.when(row.outcome.isEmpty)(positionOf(c, row))).map(_.entry)

  /** The entry `row` holds, decoded leniently: what does not decode is replaced and named (#3091). */
  private def entryOf(c: Connection, row: Raw, position: Option[Int]): Result[DecodedEntry] =
    decoding("queue", "queue entry") {
      val attempts = query(
        c,
        "SELECT run_id, started_nanos, holder FROM llm4s_checkpoint_queue_attempts WHERE entry_id = ? ORDER BY attempt",
        row.id
      )(a => Attempt(RunId(a.getString(1)), fromEpochNanos(a.getLong(2)), a.getString(3)))
      DecodedEntry(
        EntryId.unsafe(row.id),
        ThreadId(row.threadId),
        AgentId.unsafe(row.agentId),
        row.tenantId.map(TenantId(_)),
        row.input,
        row.policy,
        row.interrupt,
        row.key.map(SubmissionKey.unsafe),
        row.maxAttempts,
        row.traceParent,
        fromEpochNanos(row.submitted),
        attempts,
        row.admitted,
        row.leaseHolder,
        row.leaseExpires.map(fromEpochNanos),
        row.outcome,
        row.closed.map(fromEpochNanos),
        row.closedSeq,
        position,
        row.stop
      )
    }.map(d => d.copy(entry = d.entry.withAccounting(row.crashes, row.notBefore.map(fromEpochNanos))))

  /** The meta counter `key`, moved on by one, inside the caller's write transaction. */
  private def bump(c: Connection, key: String): Long =
    update(c, "UPDATE llm4s_checkpoint_meta SET value = value + 1 WHERE key = ?", key)
    query(c, "SELECT value FROM llm4s_checkpoint_meta WHERE key = ?", key)(_.getLong(1)).head

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

private[sqlite] object SqliteRunQueue:

  /** The most heads one take closes because they do not decode before it answers none, for its bound (#3091). */
  val MaxClosedPerTake: Int = 16

  /** An open entry without its input: what a submission decides by (#3095). */
  final case class Light(id: Long, interrupt: Boolean, attempts: Int, stop: Option[String])

  /**
   * A thread's open entries, the rows aliased `a` and [[ranked]], in the order they run (rule 5): attempted first; then each tenant's
   * entries together, the tenants in the order of their oldest open entry, so an `Interrupt` entry goes ahead of its
   * own tenant's entries only (#3085); within a tenant `Interrupt` entries first, then by id.
   */
  def runOrder(a: String): String = s"($a.attempt_count = 0), $a.tenant_first, $a.is_interrupt DESC, $a.entry_id"

  /**
   * The open entries `where` selects, each with `tenant_first`, its tenant's oldest open entry among them: computed
   * once per row set by a window, never by a subquery per row (#3085).
   */
  def ranked(where: String): String =
    s"""SELECT *, MIN(entry_id) OVER (PARTITION BY tenant_id) AS tenant_first
       |FROM llm4s_checkpoint_queue_entries WHERE $where AND state = 'open'""".stripMargin

  /** The head of thread `thread` (a column or `?`) in [[runOrder]], as a subquery. */
  def headOf(thread: String): String =
    s"SELECT h.entry_id FROM (${ranked(s"thread_id = $thread")}) h ORDER BY ${runOrder("h")} LIMIT 1"

  val Columns: String =
    """entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, submission_key, max_attempts,
      |attempt_count, submitted_nanos, thread_deleted, lease_holder, lease_token, lease_expires_nanos, outcome_json,
      |closed_nanos, closed_seq, admitted, trace_parent, stop_json, not_before_nanos, crashes""".stripMargin

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
    closed: Option[Long],
    closedSeq: Option[Long],
    admitted: Boolean,
    traceParent: Option[String],
    stop: Option[String],
    notBefore: Option[Long],
    crashes: Int
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
      long(17),
      long(18),
      r.getInt(19) == 1,
      Option(r.getString(20)),
      Option(r.getString(21)),
      long(22),
      r.getInt(23)
    )

  def runIdUsed(threadId: String, runId: RunId): ValidationError =
    ValidationError("runId", s"run '${runId.value}' already names a run or an attempt on thread '$threadId'")

  /** The scope a submission key is unique in: its tenant, or `-` for none (a tenant id is never `-` here: it is `t:`). */
  def keyScope(tenantId: Option[TenantId]): String = tenantId.fold("-")(t => s"t:${t.value}")
