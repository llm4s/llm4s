package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ GraphError, RunId, TenantId, ThreadId }
import org.llm4s.agent.service.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result

import java.sql.{ Connection, ResultSet }
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Using

/**
 * The [[org.llm4s.agent.service.RunQueue RunQueue]] of a [[PostgresCheckpointer]] (`PostgresCheckpointer.runQueue`,
 * design 4.55): entries and attempts in the store's schema (version 4, and 5 for W3's stop requests), each call one transaction on a pooled
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
        // only the submitter's tenant's entries are seen (rule 9, #3085): another tenant's are never reported
        val tenant = submission.tenantId.map(_.value)
        val queued = openLight(c, submission.threadId, tenant)
        // Reject: busy with an open entry of the submitter's tenant, or with a live claim - a direct run (W3) - by the
        // database's clock; another tenant's entry or claim is never reported (rule 9): its submission is queued, and
        // the worker refuses it
        def claimed = query(
          c,
          Sql.queueThreadClaimed(t),
          submission.threadId.value,
          submission.tenantId.map(_.value),
          submission.tenantId.map(_.value),
          store.clockNow(c)
        )(_ => ()).nonEmpty
        lazy val refused = Submission.refusal(submission, queued.size)
        if submission.policy == BusyThreadPolicy.Reject && (queued.nonEmpty || claimed) then
          Left(GraphError.ThreadBusy(submission.threadId.value, None, GraphError.BusyHolder.Unnamed))
        else if refused.isDefined then Left(refused.get)
        else
          val now = store.clockNow(c)
          val id  = query(c, Sql.nextQueueEntryId(t))(_.getLong(1)).head
          // an Interrupt reaches only its own tenant's entries of the thread (rule 9)
          // of two Interrupt entries the later goes first: an earlier one that has not started is closed before it
          // runs - only while it still has no attempt, which a concurrent recordAttempt may have recorded meanwhile
          // the entries closed here - the update changed the row - are answered in the ticket: only this submit has
          // closed them, under the thread's lock
          val interrupted =
            if submission.policy != BusyThreadPolicy.Interrupt then Vector.empty
            else
              queued
                .filter(r => r.interrupt && r.attempts == 0)
                .filter(r =>
                  closeRow(c, r.id, EntryOutcome.Interrupted(Some(EntryId.unsafe(id))), now, unstarted = true)
                )
                .map(_.id)
          if submission.policy == BusyThreadPolicy.Interrupt then
            // an entry that has started is asked to stop at its next safe point for this one, unless it is cancelled;
            // decided under the thread's lock, which recording an attempt takes too
            queued.filter(r => r.attempts > 0 && !DecodedEntry.cancelling(r.stop)).foreach { r =>
              setStop(c, r.id, StopRequest.Interrupt(EntryId.unsafe(id)))
            }
          update(
            c,
            Sql.insertQueueEntry(t),
            id,
            submission.threadId.value,
            submission.agentId.value,
            submission.tenantId.map(_.value),
            ujson.write(QueuedInput.toJson(submission.input), escapeUnicode = true),
            submission.policy.toString,
            java.lang.Boolean.valueOf(submission.policy == BusyThreadPolicy.Interrupt),
            scope,
            submission.submissionKey.map(_.value),
            submission.maxAttempts,
            micros(now),
            submission.traceContext.map(_.traceParent)
          )
          val position = positionOf(c, rowOf(c, id).get)
          sequence(interrupted.map(i => view(c, rowOf(c, i).get))).map { closed =>
            Ticket(EntryId.unsafe(id), submission.threadId, Some(position), existing = false, closed)
          }
  }

  private def setStop(c: Connection, id: Long, request: StopRequest): Unit =
    update(c, Sql.setQueueStop(t), ujson.write(StopRequest.toJson(request), escapeUnicode = true), id)

  private def same(row: Raw, submission: Submission): Boolean =
    row.threadId == submission.threadId.value && row.agentId == submission.agentId.value &&
      row.tenantId == submission.tenantId.map(_.value) && row.policy == submission.policy.toString &&
      DecodedEntry.sameInput(row.input, submission)

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
      sequence(openOf(c, threadId, tenantId.map(_.value)).zipWithIndex.map((r, i) => entryOf(c, r, Some(i))))
        .map(_.map(_.entry))
    }

  /**
   * Waits for entry `entryId` to close (#3092): the wait is registered with the store's change notification before the
   * entry's state is read, and every close through any store over the schema notifies it in the closing
   * transaction, so a wake reads the state again - its state alone, not the entry - and the entry is read once, when
   * it has closed or the wait ends. While the listening connection is down, the state is read every
   * [[PostgresRunQueue.UnheardPoll]] instead. Bounded by `timeout` plus the bound of each read.
   */
  override def awaitClosed(tenantId: Option[TenantId], entryId: EntryId, timeout: FiniteDuration): Result[QueueEntry] =
    Using.resource(store.closeWaiter(entryId)) { waiter =>
      val deadline = System.nanoTime() + timeout.toNanos
      @tailrec def await(): Result[QueueEntry] =
        val open = store.reading("queue.awaitClosed") { c =>
          Right(
            query(c, Sql.queueEntryState(t), entryId.value, tenantId.map(_.value), tenantId.map(_.value))(
              _.getString(1)
            ).headOption.contains("open")
          )
        }
        val remaining = deadline - System.nanoTime()
        open match
          case Right(true) if remaining > 0 =>
            val slice = if store.listening then remaining else math.min(remaining, UnheardPoll.toNanos)
            CancelledError.catchInterrupt(waiter.await(slice)) match
              case Right(_) => await()
              case Left(interrupted) =>
                Thread.currentThread().interrupt()
                Left(CancelledError(s"waiting for queue entry ${entryId.value}", Some(interrupted)))
          case Right(_)    => entry(tenantId, entryId)
          case Left(error) => Left(error)
      await()
    }

  // ---- leases ----

  def take(request: TakeRequest): Result[Option[LeasedEntry]] =
    if request.agents.isEmpty then Right(None)
    else
      store.writing("queue.take") { c =>
        val now    = store.clockNow(c)
        val agents = request.agents.toVector.map(_.value).toArray
        val skip   = request.skip.map(_.value)
        val from   = request.after.fold(0L)(_.value)
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
          // candidates are read without locks; only the one a take tries is locked (#3096)
          val batch = query(
            c,
            Sql.queueCandidates(t),
            agents,
            agents,
            now,
            after,
            now
          )(rows => rows.getLong(1) -> rows.getString(2))
          batch.filterNot((id, _) => skip(id)).find(takeable) match
            case None if batch.size == Candidates => scan(batch.last._1)
            case chosen                           => chosen
        // a head that does not decode is closed, in this transaction, and the take goes on past it (#3091)
        @tailrec def readable(after: Long, closed: Int): Result[Option[(Long, String)]] =
          scan(after) match
            case Some((id, thread)) =>
              entryOf(c, rowOf(c, id).get, None) match
                case Right(decoded) if !decoded.readable && closed < MaxClosedPerTake =>
                  closeRow(c, id, decoded.unrunnable, now): Unit
                  readable(id, closed + 1)
                case Right(decoded) if !decoded.readable => Right(None)
                case Right(_)                            => Right(Some(id -> thread))
                case Left(error)                         => Left(error)
            case None => Right(None)
        readable(from, 0).flatMap {
          case None               => Right(None)
          case Some((id, thread)) =>
            // a takeable head still holding a lease token holds an expired lease, never released
            val expired = rowOf(c, id).exists(_.leaseToken.isDefined)
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
              Some(LeasedEntry(e, Lease(e.id, LeaseToken(token), request.holder, expires, e.stopRequest), expired))
            }
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
          case Some(json) => Left(QueueError.EntryClosed(lease.entryId, DecodedEntry.outcomeOf(json)))
          case None if !row.leaseToken.contains(lease.token.value) =>
            Left(QueueError.LeaseLost(lease.entryId, lease.token, row.leaseToken.map(LeaseToken(_))))
          case None => Right(row)

  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = store.writing("queue.renew") { c =>
    leased(c, lease).flatMap { row =>
      val expires = micros(store.clockNow(c).plusNanos(ttl.toNanos))
      update(c, Sql.renewLease(t), expires, row.id)
      entryOf(c, row, None).map(e => lease.copy(expiresAt = expires, stop = e.entry.stopRequest))
    }
  }

  def cancel(tenantId: Option[TenantId], target: CancelTarget, reason: CancelReason): Result[Cancellation] =
    store.writing("queue.cancel") { c =>
      // under the thread's lock, as recording an attempt is: an entry is closed only while it has none
      val thread = target match
        case CancelTarget.Entry(id)  => query(c, Sql.queueEntryThread(t), id.value)(_.getString(1)).headOption
        case CancelTarget.Thread(th) => Some(th.value)
      thread.foreach(th => lock(c, s"thread:$th"))
      val targeted: Result[Vector[Raw]] = target match
        case CancelTarget.Entry(id) =>
          found(c, tenantId, id).map(_ => query(c, Sql.lockedQueueEntry(t), id.value)(raw))
        case CancelTarget.Thread(th) => Right(openOf(c, th, tenantId.map(_.value)))
      targeted.flatMap { rows =>
        val now = store.clockNow(c)
        // an entry is closed by this call when its update changed the row: decided in the closing transaction
        val closed = rows.flatMap { row =>
          // never started: closed now, so no worker runs it; started: asked to stop, a cancel replacing an interrupt
          if row.outcome.isEmpty && row.attempts == 0 then
            Option.when(closeRow(c, row.id, EntryOutcome.Cancelled(reason), now, unstarted = true))(
              EntryId.unsafe(row.id)
            )
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

  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] = store
    .writing("queue.recordAttempt") { c =>
      // an attempt moves its entry ahead in the thread's run order, so it is decided under the thread's lock, as a take
      // and a submission are: a take can never lease another head while this attempt is being recorded
      query(c, Sql.queueEntryThread(t), lease.entryId.value)(_.getString(1)).headOption
        .foreach(thread => lock(c, s"thread:$thread"))
      leased(c, lease).flatMap { row =>
        // an attempt's run id names no other run on the thread: no checkpoint and no other attempt has it (rule 4)
        val used =
          query(c, Sql.runIdUsed(t), row.threadId, runId.value, row.threadId, runId.value)(_.getBoolean(1)).head
        // an entry an Interrupt entry displaced after its take is no longer the head: its lease ends here, so the
        // interrupting entry runs first (W3, Codex round 6); decided under the thread's lock, as a submission is
        if !query(c, Sql.queueHeadIdOf(t), row.threadId)(_.getLong(1)).headOption.contains(row.id) then
          update(c, Sql.releaseLease(t), row.id)
          // committed, so the lease's end lands; the refusal is answered below
          Right(None)
        else if used then
          Left(
            ValidationError(
              "runId",
              s"run '${runId.value}' already names a run or an attempt on thread '${row.threadId}'"
            )
          )
        else if row.threadDeleted then
          closeRow(c, row.id, EntryOutcome.Cancelled(CancelReason.ThreadDeleted), store.clockNow(c)): Unit
          view(c, rowOf(c, row.id).get).map(e => Some(AttemptStart.ThreadDeleted(e)))
        else if row.attempts >= row.maxAttempts then view(c, row).map(e => Some(AttemptStart.Exhausted(e)))
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
          view(c, rowOf(c, row.id).get).map(e => Some(AttemptStart.Recorded(e)))
      }
    }
    .flatMap(_.toRight(QueueError.LeaseLost(lease.entryId, lease.token, None)))

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = store.writing("queue.close") { c =>
    leased(c, lease).flatMap { row =>
      closeRow(c, row.id, outcome, store.clockNow(c)): Unit
      view(c, rowOf(c, row.id).get)
    }
  }

  /**
   * Closes entry `id`; with `unstarted`, only if it is still open with no attempt (checked on the locked row). Answers
   * whether this update closed it.
   */
  private def closeRow(
    c: Connection,
    id: Long,
    outcome: EntryOutcome,
    at: Instant,
    unstarted: Boolean = false,
    untaken: Option[Instant] = None
  ): Boolean =
    // an Interrupt entry that never started withdraws the stop it asked of its thread's other entries (#3094)
    val withdrawing = query(c, Sql.unstartedInterruptThread(t), id)(_.getString(1)).headOption
    val sql =
      if untaken.isDefined then Sql.closeUntakenQueueEntry(t)
      else if unstarted then Sql.closeUnstartedQueueEntry(t)
      else Sql.closeQueueEntry(t)
    val params = Vector(
      outcome.state,
      ujson.write(EntryOutcome.toJson(outcome), escapeUnicode = true),
      micros(at),
      id
    ) ++ untaken.toVector
    val closed = updated(c, sql, params*) > 0
    if closed then
      withdrawing.foreach { thread =>
        query(c, Sql.siblingStops(t), thread, id)(r => r.getLong(1) -> Option(r.getString(2)))
          .filter((_, stop) => DecodedEntry.interruptsFor(stop, EntryId.unsafe(id)))
          .foreach((sibling, _) => update(c, Sql.clearQueueStop(t), sibling))
      }
      // a waiter on the entry, in any store over the schema, wakes when this commits (#3092)
      store.notifyClosed(c, EntryId.unsafe(id))
    closed

  def depth(agents: Set[AgentId]): Result[Map[AgentId, QueueDepth]] =
    if agents.isEmpty then Right(Map.empty) else depthOf(Some(agents))

  def allDepths(): Result[Map[AgentId, QueueDepth]] = depthOf(None)

  private def depthOf(agents: Option[Set[AgentId]]): Result[Map[AgentId, QueueDepth]] =
    store.reading("queue.depth") { c =>
      val now = store.clockNow(c)
      val (sql, params) = agents match
        case Some(asked) => Sql.queueDepth(t)     -> Vector(now, now, now, asked.toVector.map(_.value).toArray)
        case None        => Sql.queueAllDepths(t) -> Vector(now, now, now)
      val rows = query(c, sql, params*) { r =>
        val oldest = Option(r.getObject(4)).map(_ => instant(r, 4))
        AgentId.unsafe(r.getString(1)) -> QueueDepth(r.getLong(2), r.getLong(3), oldest.map(QueueDepth.age(_, now)))
      }
      Right(rows.toMap)
    }

  def closeUntaken(age: FiniteDuration, limit: Int): Result[Vector[QueueEntry]] =
    if limit <= 0 then Right(Vector.empty)
    else
      store.writing("queue.closeUntaken") { c =>
        val now = store.clockNow(c)
        val candidates = query(c, Sql.queueUntaken(t), now.minusNanos(age.toNanos), now, limit)(r =>
          (r.getLong(1), r.getString(2), Option(r.getString(3)), r.getString(4))
        )
        // each under its thread's lock, tried, as a take's is: a submission or a take deciding the thread is left be
        // each decided again under its thread's lock, tried, as a take's is: the candidates are an earlier snapshot, and
        // a take may have leased one since (it leases only under that lock, so its lease has committed by now)
        val untaken = candidates.filter { (id, thread, tenant, _) =>
          lockIfFree(c, s"thread:$thread") &&
          query(c, Sql.queueStillUntaken(t), id, now)(_ => ()).nonEmpty &&
          query(c, Sql.queueThreadClaimed(t), thread, tenant, tenant, now)(_ => ()).isEmpty
        }
        val closed = untaken.filter((id, _, _, agent) =>
          closeRow(c, id, RunQueue.untakenOutcome(AgentId.unsafe(agent), age), now, untaken = Some(now))
        )
        sequence(closed.map((id, _, _, _) => view(c, rowOf(c, id).get)))
      }

  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]] =
    if threads.isEmpty then Right(Map.empty)
    else
      store.reading("queue.openRuns") { c =>
        val rows = query(c, Sql.queueOpenRuns(t), threads.distinct.map(_.value).toArray)(r =>
          ThreadId(r.getString(1)) -> Option(r.getString(2)).map(RunId(_))
        )
        Right(rows.groupMapReduce(_._1)(_._2.toSet)(_ ++ _))
      }

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

  private def rowsOf(c: Connection, ids: Vector[Long]): Vector[Raw] =
    if ids.isEmpty then Vector.empty
    else
      val read = query(c, Sql.queueEntriesOf(t), ids.map(_.toString).toArray)(raw).map(r => r.id -> r).toMap
      ids.flatMap(read.get)

  /** The thread's open entries of `tenantId`, in the order they run. */
  private def openOf(c: Connection, threadId: ThreadId, tenantId: Option[String]): Vector[Raw] =
    query(c, Sql.openQueueEntries(t), threadId.value, tenantId, tenantId)(raw)

  /** [[openOf]] without the entries' inputs (#3095): what a submission decides by. */
  private def openLight(c: Connection, threadId: ThreadId, tenantId: Option[String]): Vector[Light] =
    query(c, Sql.openQueueEntriesLight(t), threadId.value, tenantId, tenantId)(r =>
      Light(r.getLong(1), r.getBoolean(2), r.getInt(3), Option(r.getString(4)))
    )

  /** How many of its tenant's open entries of the thread run before `row` (rule 9), reading no input (#3095). */
  private def positionOf(c: Connection, row: Raw): Int =
    query(
      c,
      Sql.queuePosition(t),
      row.threadId,
      row.tenantId,
      row.tenantId,
      java.lang.Boolean.valueOf(row.attempts == 0),
      java.lang.Boolean.valueOf(!row.interrupt),
      row.id
    )(_.getInt(1)).head

  /** Every open entry's position among its tenant's open entries of the thread, read once. */
  private def positionsOf(c: Connection, threadId: ThreadId, tenantId: Option[String]): Map[Long, Int] =
    query(c, Sql.openQueueEntryIds(t), threadId.value, tenantId, tenantId)(_.getLong(1)).zipWithIndex.toMap

  private def view(c: Connection, row: Raw): Result[QueueEntry] =
    // only the entry's own tenant's entries are counted (rule 9)
    entryOf(c, row, Option.when(row.outcome.isEmpty)(positionOf(c, row))).map(_.entry)

  /** The entry `row` holds, decoded leniently: what does not decode is replaced and named (#3091). */
  private def entryOf(c: Connection, row: Raw, position: Option[Int]): Result[DecodedEntry] =
    decoding("queue", "queue entry") {
      val recorded = query(
        c,
        Sql.queueAttemptsOf(t),
        row.id
      )(a => Attempt(RunId(a.getString(1)), instant(a, 2), a.getString(3)))
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
        row.submitted,
        recorded,
        row.admitted,
        row.leaseHolder,
        row.leaseExpires,
        row.outcome,
        row.closed,
        row.closedSeq,
        position,
        row.stop
      )
    }

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
      r.getBoolean(19),
      Option(r.getString(20)),
      Option(r.getString(21))
    )

private[postgres] object PostgresRunQueue:

  /** How many candidate heads one `take` reads and tries at a time; it locks only the one it tries (#3096). */
  val Candidates: Int = 16

  /** The most heads one take closes because they do not decode before it answers none, for its bound (#3091). */
  val MaxClosedPerTake: Int = 16

  /** How often [[PostgresRunQueue.awaitClosed]] reads an entry's state while the store's listener is down (#3092). */
  val UnheardPoll: FiniteDuration = 1.second

  /** An open entry without its input: what a submission decides by (#3095). */
  final case class Light(id: Long, interrupt: Boolean, attempts: Int, stop: Option[String])

  val Columns: String =
    """entry_id, thread_id, agent_id, tenant_id, input_json, policy, is_interrupt, submission_key, max_attempts,
      |attempt_count, submitted_at, thread_deleted, lease_holder, lease_token, lease_expires_at, outcome_json,
      |closed_at, closed_seq, admitted, trace_parent, stop_json""".stripMargin

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
    admitted: Boolean,
    traceParent: Option[String],
    stop: Option[String]
  )

  /** The scope a submission key is unique in: its tenant (`t:` and its id), or `-` for none. */
  def keyScope(tenantId: Option[TenantId]): String = tenantId.fold("-")(t => s"t:${t.value}")
