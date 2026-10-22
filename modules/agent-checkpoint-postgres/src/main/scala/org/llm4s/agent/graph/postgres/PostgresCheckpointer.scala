package org.llm4s.agent.graph.postgres

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.agent.graph.*
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding

import java.sql.{ Connection, PreparedStatement, ResultSet, SQLException }
import java.time.{ Instant, OffsetDateTime, ZoneOffset }
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Executor, ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit }
import javax.sql.DataSource
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * A durable [[org.llm4s.agent.graph.Checkpointer]] in a PostgreSQL database: threads, their checkpoint histories,
 * pending writes, event logs, effect records and run claims, shared by every runtime on every machine that opens a
 * store over the same database and schema. It is the store for running one agent service on several machines
 * (Stage 5, design 4.50).
 *
 * Open it with [[PostgresCheckpointer.open]], over a pool of its own, or [[PostgresCheckpointer.create]], over a
 * `DataSource` the caller already has; close it with [[close]]. It meets the `Checkpointer` contract and passes
 * `CheckpointerContract` from `llm4s-agent-testkit`:
 *
 *  - '''Atomic, fenced commits.''' Every call that writes is one transaction, which first locks the thread's row
 *    (`SELECT ... FOR UPDATE`) and then checks the claim's token, a `Commit.fence`'s claim on another thread, and the
 *    parent checkpoint, before it allocates event numbers from the row and writes. A call that locks two threads' rows
 *    - a fenced commit or delete - locks them in the order of their ids, so two such calls never deadlock.
 *  - '''The database decides time.''' A claim's expiry and a retention policy's age are judged by the database
 *    server's clock (`clock_timestamp()`, read once the call holds its locks), never a host's: skew between the
 *    machines sharing the store changes nothing. Age is measured from the commit time the store stamps, by that
 *    clock, on every checkpoint and event row - never from the producer's `Checkpoint.createdAt` or event
 *    `timestamp`, which stay as the producer's record. The `clock` a runtime is given still schedules its renewals.
 *  - '''Fencing tokens''' come from one sequence for the whole schema, so they increase across restarts, releases,
 *    takeovers and [[deleteThread]], and are never issued twice.
 *  - '''History, retention, deletes and children''' behave exactly as in the SQLite store: every superseded
 *    checkpoint is kept until `prune`, `retractTurn` or [[deleteThread]] removes it; a deleted thread leaves a
 *    tombstone of its event numbering; a parent's children are listed in the order their first commits landed.
 *  - '''Data only.''' Checkpoints, pending writes, events and effect records are stored as JSON text and decoded on
 *    read, so nothing executable is stored.
 *  - '''Change notification by polling''' for now: a subscription reads the log once per `WatchPolicy.pollInterval`
 *    (the `Checkpointer.awaitEventsAfter` default). `LISTEN`/`NOTIFY` replaces it in a later slice.
 *
 * Reads run in a `REPEATABLE READ`, read-only transaction, so what one call reads is one snapshot; they never wait for
 * a writer.
 *
 * '''Every call returns within a bound''', as the `Checkpointer` contract requires (#1919): the pool's connection
 * timeout plus [[PostgresCheckpointerConfig.callTimeout]], plus the time a rollback takes. The call timeout is set on
 * the server for the transaction (`statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`) and
 * enforced on the client as well: a call that reaches it has its connection aborted and discarded from the pool, so a
 * stalled network cannot hold it either. A call that reaches the bound fails with a `ProcessingError`. Like any
 * networked store, a call that times out while its `COMMIT` is in flight cannot know whether the commit landed; the
 * claim's fencing keeps that safe, as a later commit on a stale view is refused.
 *
 * '''Interrupts.''' An interrupt flag already set when a call begins decides nothing: it is cleared for the call and
 * set again when the call returns. An interrupt that lands during a call (on a virtual thread, it closes the
 * connection's socket) ends that call with `CancelledError` or a failed result, and the connection is discarded, so
 * the store stays usable by every other thread.
 *
 * Every failure of the database itself is a `ProcessingError`, and so is any call after [[close]]. Safe to call from
 * any number of threads: each call takes a connection of its own from the pool.
 */
@Experimental
final class PostgresCheckpointer private (
  dataSource: DataSource,
  ownsDataSource: Boolean,
  val config: PostgresCheckpointerConfig,
  clockSql: String
) extends Checkpointer
    with AutoCloseable:

  import PostgresCheckpointer.*

  private[postgres] val t                            = PostgresSchema.Tables(config.schema)
  @volatile private var shut                         = false
  private[postgres] given errorContext: ErrorContext = ErrorContext(config.schema)
  private val callTimeout                            = config.callTimeout
  private val transactionSetting                     = settings(callTimeout)

  /** The schema this store's tables are in. */
  def schema: String = config.schema

  // ---- claims ----

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = writing("claim") { c =>
    val current = lockOrCreate(c, threadId)
    val now     = clockNow(c)
    current.claim.filter(live => now.isBefore(live.expiresAt)) match
      case Some(live) =>
        // another tenant's holder is never named
        val holder =
          if live.tenant == request.tenantId.map(_.value) then GraphError.BusyHolder.Run(RunId(live.holder))
          else GraphError.BusyHolder.Unnamed
        Left(GraphError.ThreadBusy(threadId.value, current.checkpointId, holder))
      case None =>
        val claim = Claim(
          request.holder.value,
          request.tenantId.map(_.value),
          nextToken(c),
          micros(now.plusNanos(request.ttl.toNanos))
        )
        setClaim(c, threadId, Some(claim))
        // a claim still held here has expired: this one takes it over
        Right(granted(threadId, claim, current.claim))
  }

  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] = writing("renew") { c =>
    val current = lockRow(c, threadId)
    current.flatMap(_.claim).filter(_.token == token.value) match
      case None => Left(stale(threadId, current, token))
      case Some(held) =>
        val renewed = held.copy(expiresAt = micros(clockNow(c).plusNanos(ttl.toNanos)))
        setClaim(c, threadId, Some(renewed))
        Right(granted(threadId, renewed))
  }

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = writing("release") { c =>
    if lockRow(c, threadId).flatMap(_.claim).exists(_.token == token.value) then
      setClaim(c, threadId, None)
      dropIfEmpty(c, threadId)
    Right(())
  }

  // ---- data ----

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = writing("commit") { c =>
    val rows     = lockRows(c, threadId +: commit.fence.map(_._1).toVector)
    val current  = rows.get(threadId)
    val latestId = current.flatMap(_.checkpointId)
    // the thread's own claim first, then the fence's, both under the locks the commit is made under
    val fenced = Option
      .when(!current.flatMap(_.claim).exists(_.token == commit.token.value))(stale(threadId, current, commit.token))
      .orElse(commit.fence.flatMap { (other, otherToken) =>
        val above = rows.get(other)
        Option.when(!above.flatMap(_.claim).exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    def invalid(reason: String) = GraphError.InvalidCommit(threadId.value, reason)
    // checked only once the token and the version have passed, so a stale writer learns nothing of the history
    def duplicate = commit.checkpoint.filter(c0 => positionOf(c, threadId, c0.id).isDefined).map { checkpoint =>
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
    // a queued attempt recorded before a delete never writes to the id again (design 4.55); the thread's row is
    // locked, and a delete locks it too, so the delete's marks are seen
    def orphaned = commit.checkpoint
      .filter(c0 => query(c, Sql.orphanedAttempt(t), threadId.value, c0.runId)(_ => ()).nonEmpty)
      .map(checkpoint =>
        invalid(
          s"run ${checkpoint.runId} is an attempt of a queue entry whose thread was deleted after it was recorded"
        )
      )
    fenced.orElse(conflict).orElse(duplicate).orElse(misdirected).orElse(unanchored).orElse(orphaned).toLeft(()).map {
      _ =>
        // a thread with a claim always has a row
        val row = current.getOrElse(throw new IllegalStateException("a claimed thread has no row"))
        // what retention judges age by: the database's clock, never the producer's timestamps (rule 1)
        val committedAt =
          if commit.checkpoint.isDefined || commit.events.nonEmpty || commit.metadata.isDefined then clockNow(c)
          else Instant.EPOCH
        commit.checkpoint.foreach { checkpoint =>
          writeCheckpoint(c, threadId, row, commit.retractTurn, committedAt, checkpoint)
          // an open queue entry whose attempt this is was admitted, whatever a later retraction removes (design 4.55)
          update(c, Sql.admitAttempt(t), threadId.value, checkpoint.runId)
        }
        appendWrites(c, threadId, commit.pendingWrites)
        // the registry's metadata (schema 2): empty is kept as none, so an emptied row can go
        commit.metadata.foreach { metadata =>
          update(
            c,
            Sql.setMetadata(t),
            Option.unless(metadata.isEmpty)(ujson.write(metadata.toJson)),
            committedAt,
            threadId.value
          )
        }
        // released before this commit's own records are applied
        if commit.checkpoint.isDefined then
          query(c, Sql.effectTasks(t), threadId.value)(rows =>
            (rows.getString(1), TaskId(rows.getString(2)), rows.getString(3))
          ).filter((_, task, lineage) => commit.releasesEffect(task, lineage)).foreach { (key, _, _) =>
            update(c, Sql.deleteEffect(t), threadId.value, key)
          }
        commit.effects.foreach { record =>
          update(
            c,
            Sql.upsertEffect(t),
            threadId.value,
            record.key.value,
            record.taskId.value,
            record.lineage,
            commit.token.value,
            ujson.write(EffectRecord.toJson(record))
          )
        }
        val stored = commit.events.zipWithIndex.map { (draft, i) =>
          val seq = row.nextSeq + i
          (seq, draft, upickle.default.write(EventRecord.committed(threadId.value, seq, commit.token, draft)))
        }
        if stored.nonEmpty then
          Using.resource(c.prepareStatement(Sql.insertEvent(t))) { insert =>
            stored.foreach { (seq, draft, json) =>
              bind(insert, threadId.value, seq, draft.checkpointId, committedAt, json)
              insert.addBatch()
            }
            insert.executeBatch(): Unit
          }
          update(c, Sql.setNextSeq(t), row.nextSeq + stored.size, threadId.value)
        // handed back decoded from what was stored, as a later read would see them
        stored.map((_, _, json) => upickle.default.read[EventRecord](json))
    }
  }

  /** A commit's new checkpoint, inside its transaction: history, latest, pending writes cleared, its child place. */
  private def writeCheckpoint(
    c: Connection,
    threadId: ThreadId,
    row: Row,
    retract: Boolean,
    committedAt: Instant,
    checkpoint: Checkpoint
  ): Unit =
    val json = ujson.write(Checkpoint.toJson(checkpoint))
    // no checkpoint yet means a new thread under the id (a tombstone's, or none); a retraction below empties the
    // history but leaves the thread the same one
    val fresh = row.checkpointId.isEmpty
    // positions only grow, also across a retraction, so the history's order is its primary key's
    val position = query(c, Sql.nextPosition(t), threadId.value)(_.getLong(1)).head
    // a blocked turn is retracted back to the newest settled checkpoint, which stays
    if retract then
      update(
        c,
        Sql.retract(t),
        threadId.value,
        threadId.value,
        CheckpointStatus.Completed.toString,
        CheckpointStatus.Failed.toString
      )
    update(
      c,
      Sql.insertHistory(t),
      threadId.value,
      position,
      checkpoint.id,
      checkpoint.status.toString,
      committedAt,
      json,
      checkpoint.runId
    )
    update(
      c,
      Sql.setLatest(t),
      checkpoint.id,
      json,
      checkpoint.tenantId,
      checkpoint.snapshot.graphId,
      registryStatus(checkpoint),
      committedAt,
      threadId.value
    )
    update(c, Sql.deletePendingWrites(t), threadId.value)
    placeChild(c, threadId, row, checkpoint, fresh)

  /**
   * Records the parent `checkpoint`'s origin names: the thread keeps its place among that parent's children while the
   * parent stays the same, takes a new place under a new one (or as a new thread under the id), and has none as a
   * top-level thread. A new place is drawn from the store's sequence under a transaction-scoped advisory lock on the
   * parent, so places among one parent's children follow the order their commits land, and a reader paging them
   * never passes a place a commit still in flight will take. That lock is the last one the commit takes, so it adds
   * no deadlock.
   */
  private def placeChild(c: Connection, threadId: ThreadId, row: Row, checkpoint: Checkpoint, fresh: Boolean): Unit =
    val parent = checkpoint.origin.map(_.parent.value)
    if row.parent != parent || fresh then
      val order = parent.map { p =>
        query(c, Sql.lockChildren, PostgresSchema.lockName(config.schema) + ":children:" + p)(_ => ()): Unit
        query(c, Sql.nextChildOrder(t))(_.getLong(1)).head
      }
      update(c, Sql.setParent(t), parent, order, threadId.value)

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
    reading("latest") { c =>
      val checkpoint = query(c, Sql.latest(t), threadId.value)(_.getString(1)).headOption
      val writes = checkpoint.fold(Vector.empty[String]) { _ =>
        query(c, Sql.pendingWrites(t), threadId.value)(_.getString(1))
      }
      Right(checkpoint.map(_ -> writes))
    }.flatMap {
      case None => Right(None)
      case Some((json, writes)) =>
        for
          checkpoint <- decodeCheckpoint("latest", json)
          decoded    <- decoding("latest", "pending write")(writes.map(upickle.default.read[PendingWrite](_)))
        yield Some(StoredCheckpoint(checkpoint, decoded))
    }

  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] =
    reading("effect")(c => Right(query(c, Sql.effect(t), threadId.value, key.value)(_.getString(1)).headOption))
      .flatMap {
        case None => Right(None)
        case Some(json) =>
          decoding("effect", "effect record")(ujson.read(json)).flatMap { parsed =>
            EffectRecord
              .fromJson(parsed)
              .left
              .map(e => undecodable("effect", "effect record", e.message, None))
              .map(Some(_))
          }
      }

  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] =
    reading("history") { c =>
      val from = before match
        case None     => Right(Long.MaxValue)
        case Some(id) => positionOf(c, threadId, id).toRight(GraphError.CheckpointNotFound(threadId.value, id))
      from.map { position =>
        if limit <= 0 then Vector.empty
        else query(c, Sql.historyPage(t), threadId.value, position, limit)(_.getString(1))
      }
    }.flatMap(rows => sequence(rows.map(decodeCheckpoint("history", _))))

  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
    reading("checkpoint") { c =>
      Right(query(c, Sql.checkpointById(t), threadId.value, checkpointId)(_.getString(1)).headOption)
    }.flatMap {
      case None       => Right(None)
      case Some(json) => decodeCheckpoint("checkpoint", json).map(Some(_))
    }

  /**
   * Removes what `policy` does not keep, in one transaction holding the thread's row, so it sees and changes one
   * state of the thread; it takes no claim, as the contract says. Age is judged by the database's clock against the
   * time, by that clock, each checkpoint and event was committed - never the producer's `createdAt` or `timestamp`,
   * which a writer with a skewed clock would make too old or too young (rule 1).
   */
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = writing("prune") { c =>
    lockRow(c, threadId) match
      case None => Right(())
      case Some(current) =>
        val cutoff = policy.maxAge.map(age => clockNow(c).minusNanos(age.toNanos))
        // oldest first: (position, id, committed at)
        val history =
          query(c, Sql.historyAges(t), threadId.value)(rows => (rows.getLong(1), rows.getString(2), instant(rows, 3)))
        // the latest checkpoint is always kept, whatever its age
        val (older, latest) = history.splitAt(history.size - 1)
        val young           = older.filter((_, _, at) => cutoff.forall(cut => !at.isBefore(cut)))
        val kept            = policy.maxCheckpoints.fold(young ++ latest)(n => (young ++ latest).takeRight(n))
        // an open queue entry's admission evidence (rule 4): the newest checkpoint each of its runs committed
        val evidence      = query(c, Sql.queueEvidence(t), threadId.value)(_.getLong(1)).toSet
        val keptPositions = kept.map(_._1).toSet ++ evidence
        history.filterNot((position, _, _) => keptPositions(position)).foreach { (position, _, _) =>
          update(c, Sql.deleteHistoryAt(t), threadId.value, position)
        }
        // events are kept while their checkpoints are: from the oldest kept checkpoint's first event on
        val byCheckpoint =
          if kept.size == history.size then None
          else
            query(c, Sql.firstEventOf(t), threadId.value, kept.map(_._2).toArray)(rows =>
              Option(rows.getObject(1)).map(_ => rows.getLong(1))
            ).head
        val byAge = cutoff.map { cut =>
          query(c, Sql.firstEventSince(t), threadId.value, cut)(rows =>
            Option(rows.getObject(1)).map(_ => rows.getLong(1))
          ).head
            .getOrElse(current.nextSeq)
        }
        val bySize = policy.maxEvents.map(n => current.nextSeq - n)
        val floor  = (Vector(current.earliestSeq) ++ byCheckpoint ++ byAge ++ bySize).max.min(current.nextSeq)
        update(c, Sql.deleteEventsBefore(t), threadId.value, floor)
        update(c, Sql.setFloor(t), floor, threadId.value)
        Right(())
  }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    reading("eventsAfter") { c =>
      val row      = readRow(c, threadId, Sql.row(t))
      val first    = row.fold(1L)(_.firstSeq)
      val earliest = row.fold(1L)(_.earliestSeq)
      // a reader from the start reads the thread now under the id, from its first event
      if Checkpointer.deleted(afterSeq, first) then Left(GraphError.ThreadDeleted(threadId.value, afterSeq, first))
      else if math.max(afterSeq, first - 1) + 1 < earliest then
        Left(GraphError.ReplayUnavailable(threadId.value, earliest))
      else if limit <= 0 then Right(Vector.empty)
      else Right(query(c, Sql.eventsAfter(t), threadId.value, afterSeq, limit)(_.getString(1)))
    }.flatMap(rows => decoding("eventsAfter", "event")(rows.map(upickle.default.read[EventRecord](_))))

  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] =
    if runIds.isEmpty then Right(None)
    else
      reading("latestOfRuns") { c =>
        query(c, Sql.latestOfRuns(t), runIds.toVector.map(_.value).toArray, threadId.value)(
          _.getString(1)
        ).headOption match
          case None       => Right(None)
          case Some(json) => decodeCheckpoint("latestOfRuns", json).map(Some(_))
      }

  /**
   * The store's run queue (design 4.55), in the same schema and over the same pool as its checkpoints: its open
   * entries learn of their thread's deletion in [[deleteThread]]'s own transaction, its leases expire by the same
   * database clock as claims, and [[prune]] keeps the newest checkpoint of every run an open entry recorded. Closing
   * the store closes it.
   */
  lazy val runQueue: org.llm4s.agent.service.RunQueue = new PostgresRunQueue(this)

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = writing("compactEvents") { c =>
    lockRow(c, threadId).foreach { current =>
      val floor = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
      update(c, Sql.deleteEventsBefore(t), threadId.value, floor)
      update(c, Sql.setFloor(t), floor, threadId.value)
    }
    Right(())
  }

  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
    reading("children") { c =>
      val from: Result[Long] = after match
        case None => Right(0L)
        case Some(cursor) =>
          query(c, Sql.childOrderOf(t), cursor.value, parent.value)(_.getLong(1)).headOption
            .toRight(ValidationError("after", s"'${cursor.value}' is not a known child of '${parent.value}'"))
      from.map { order =>
        // a tombstone keeps its place but is not listed
        if limit <= 0 then Vector.empty
        else query(c, Sql.childrenPage(t), parent.value, order, limit)(_.getString(1))
      }
    }.flatMap(rows => sequence(rows.map(decodeCheckpoint("children", _))).map(_.flatMap(ChildThread.of)))

  def threads(query: ThreadQuery): Result[ThreadPage] =
    reading("threads")(c => Right(registry(c, threadsStatement(t, query, clockNow(c)))))
      .flatMap(rows => summaries("threads", rows, query.limit))

  def incompleteThreads(
    tenantId: Option[TenantId],
    after: Option[ThreadCursor],
    limit: Int
  ): Result[ThreadPage] =
    ThreadQuery.checkLimit(limit).flatMap { limit =>
      reading("incompleteThreads")(c => Right(registry(c, incompleteStatement(t, tenantId, after, limit, clockNow(c)))))
        .flatMap(rows => summaries("incompleteThreads", rows, limit))
    }

  private def registry(c: Connection, statement: (String, Vector[Any])): Vector[RegistryRow] =
    query(c, statement._1, statement._2*) { rows =>
      RegistryRow(
        rows.getString(1),
        Option(rows.getString(2)),
        rows.getString(3),
        rows.getString(4),
        rows.getString(5),
        Option(rows.getString(6)),
        instant(rows, 7),
        rows.getBoolean(8)
      )
    }

  /**
   * Deletes the thread in one transaction, which first locks its row (and a `fence` thread's, in id order) and checks
   * that `token` is its current claim - so the check and the delete see one state of the thread. The row stays,
   * emptied, as a tombstone once the thread has numbered an event, so that a new thread of the id numbers on past it.
   */
  def deleteThread(
    threadId: ThreadId,
    token: FencingToken,
    fence: Option[(ThreadId, FencingToken)]
  ): Result[Unit] = writing("deleteThread") { c =>
    val rows    = lockRows(c, threadId +: fence.map(_._1).toVector)
    val current = rows.get(threadId)
    val refused = Option
      .when(!current.flatMap(_.claim).exists(_.token == token.value))(stale(threadId, current, token))
      .orElse(fence.flatMap { (other, otherToken) =>
        val above = rows.get(other)
        Option.when(!above.flatMap(_.claim).exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    refused match
      case Some(error) => Left(error)
      case None =>
        Vector(t.events, t.pendingWrites, t.history, t.effects).foreach { table =>
          update(c, s"DELETE FROM $table WHERE thread_id = ?", threadId.value)
        }
        // the thread's open queue entries learn of the delete in its own transaction (design 4.55), so none of them
        // runs on a new thread of the id, whatever the deleted thread numbered
        update(c, Sql.queueThreadDeleted(t), threadId.value)
        update(c, Sql.tombstone(t), threadId.value)
        dropIfEmpty(c, threadId)
        Right(())
  }

  /**
   * Closes the store, and its pool if it owns one. Later calls return a `ProcessingError`; closing again does nothing.
   * A claim this store granted stays in the database until it is released through another store or expires.
   */
  def close(): Unit =
    if !shut then
      shut = true
      if ownsDataSource then
        dataSource match
          case closeable: AutoCloseable => Try(closeable.close()): Unit
          case _                        => ()

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = shut

  // ---- transactions ----

  /** `body` in one read-committed transaction that locks the rows it writes; see [[call]]. */
  private[postgres] def writing[A](operation: String)(body: Connection => Result[A]): Result[A] =
    call(operation, transactionSetting)(body)

  /** `body` in one repeatable-read, read-only transaction, so what it reads is one snapshot; see [[call]]. */
  private[postgres] def reading[A](operation: String)(body: Connection => Result[A]): Result[A] =
    call(operation, s"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; $transactionSetting")(body)

  /**
   * `body` in one transaction on a connection of its own from the pool, begun with `begin` (which bounds it on the
   * server), committed if it returns `Right` and rolled back otherwise. A [[Watch]] aborts the connection if the call
   * is still running after the call timeout. A connection whose call threw, or that was aborted, is discarded rather
   * than returned to the pool, so a socket an interrupt closed, or a cancel that may still be in flight, never reaches
   * another call.
   */
  private def call[A](operation: String, begin: String)(body: Connection => Result[A]): Result[A] =
    if shut then Left(failure(operation, "the store is closed", None))
    else
      // a flag set before the call decides nothing here: on a virtual thread it would close the socket
      val flagged = Thread.interrupted()
      val result = Try(dataSource.getConnection()).toEither.left
        .map(e => failure(operation, describe(e), Some(e)))
        .flatMap { c =>
          val watch = Watch.arm(c, callTimeout)
          val outcome = Try {
            c.setAutoCommit(false)
            Using.resource(RollbackUnlessCommitted(c)) { guard =>
              execute(c, begin)
              val result = body(c)
              if result.isRight then
                c.commit()
                guard.committed = true
              result
            }
          }
          val aborted = !watch.disarm()
          // back to the pool only as it came, after a call that went well; anything else is discarded, so a connection
          // whose reset or close fails is never left checked out
          val returned =
            !aborted && outcome.isSuccess && Try(c.setAutoCommit(true)).isSuccess && Try(c.close()).isSuccess
          if !returned then discard(c)
          outcome.toEither.left.map { e =>
            if aborted then failure(operation, timedOut(callTimeout), Some(e))
            else failure(operation, describe(e), Some(e))
          }.flatten
        }
      val interrupted = Thread.currentThread().isInterrupted
      if flagged || interrupted then Thread.currentThread().interrupt()
      // an interrupt that landed during the call is why it failed
      if interrupted && !flagged then
        result.left.map {
          case cancelled: CancelledError => cancelled
          case failed: ProcessingError   => CancelledError(s"postgres-checkpointer.$operation", failed.cause)
          case other                     => other
        }
      else result

  /** Takes `c` out of the pool for good: evicted from a HikariCP pool, aborted otherwise. */
  private def discard(c: Connection): Unit =
    dataSource match
      case hikari: HikariDataSource => Try(hikari.evictConnection(c)): Unit
      case _ =>
        Try(c.abort(DirectExecutor)): Unit
        Try(c.close()): Unit

  // ---- rows ----

  private[postgres] def clockNow(c: Connection): Instant = query(c, s"SELECT $clockSql")(instant(_, 1)).head

  private def readRow(c: Connection, threadId: ThreadId, sql: String): Option[Row] =
    query(c, sql, threadId.value) { rows =>
      val token = rows.getLong(6)
      val claim = Option.when(!rows.wasNull()) {
        Claim(rows.getString(5), Option(rows.getString(8)), token, instant(rows, 7))
      }
      Row(
        Option(rows.getString(1)),
        rows.getLong(2),
        rows.getLong(3),
        rows.getLong(4),
        claim,
        Option(rows.getString(9))
      )
    }.headOption

  /** The thread's row, locked for the rest of the transaction; `None` if it has none. */
  private def lockRow(c: Connection, threadId: ThreadId): Option[Row] = readRow(c, threadId, Sql.lockedRow(t))

  /** The rows of `threadIds`, each locked, in the order of their ids - the one order every call locks rows in. */
  private def lockRows(c: Connection, threadIds: Vector[ThreadId]): Map[ThreadId, Row] =
    threadIds.distinct.sortBy(_.value).flatMap(id => lockRow(c, id).map(id -> _)).toMap

  /**
   * The thread's row, created if it has none, and locked. A row a concurrent call deletes (an emptied thread's) while
   * this one waits for its lock is gone once the lock is granted: the insert is tried again.
   */
  private def lockOrCreate(c: Connection, threadId: ThreadId): Row =
    Iterator
      .continually {
        update(c, Sql.ensureRow(t), threadId.value)
        lockRow(c, threadId)
      }
      .take(8)
      .collectFirst { case Some(row) => row }
      .getOrElse(throw new IllegalStateException(s"thread ${threadId.value}'s row kept vanishing while being locked"))

  private def setClaim(c: Connection, threadId: ThreadId, claim: Option[Claim]): Unit =
    update(
      c,
      Sql.setClaim(t),
      claim.map(_.holder),
      claim.map(_.token),
      claim.map(_.expiresAt),
      claim.flatMap(_.tenant),
      threadId.value
    )

  /** Removes the thread's row once it holds nothing a new thread of its id would not: as `InMemoryCheckpointer`. */
  private def dropIfEmpty(c: Connection, threadId: ThreadId): Unit = update(c, Sql.dropIfEmpty(t), threadId.value)

  private def positionOf(c: Connection, threadId: ThreadId, id: String): Option[Long] =
    query(c, Sql.positionById(t), threadId.value, id)(_.getLong(1)).headOption

  /** The next fencing token for the whole schema; drawn only while the thread's row is locked. */
  private def nextToken(c: Connection): Long = query(c, Sql.nextToken(t))(_.getLong(1)).head

  private def appendWrites(c: Connection, threadId: ThreadId, writes: Vector[PendingWrite]): Unit =
    if writes.nonEmpty then
      val first = query(c, Sql.nextWritePosition(t), threadId.value)(_.getLong(1)).head
      Using.resource(c.prepareStatement(Sql.insertWrite(t))) { insert =>
        writes.zipWithIndex.foreach { (write, i) =>
          bind(insert, threadId.value, first + i, write.checkpointId, upickle.default.write(write))
          insert.addBatch()
        }
        insert.executeBatch(): Unit
      }

  /** A stored checkpoint decoded: a format this build cannot read is the contract's refusal, anything else a bad row. */
  private def decodeCheckpoint(operation: String, json: String): Result[Checkpoint] =
    decoding(operation, "checkpoint")(ujson.read(json)).flatMap { parsed =>
      Checkpoint.fromJson(parsed).left.map {
        case refusal: GraphError => refusal
        case other               => undecodable(operation, "checkpoint", other.message, None)
      }
    }

object PostgresCheckpointer:

  /**
   * Opens the store over a HikariCP pool of its own to `jdbcUrl` (`jdbc:postgresql://host:port/database`), sized and
   * timed out by `config`, and brings its schema to the current version (see [[create]]). The pool is closed with the
   * store. The driver's socket timeout is set past the call timeout, as a last resort behind the store's own bound.
   */
  def open(
    jdbcUrl: String,
    user: String,
    password: String,
    config: PostgresCheckpointerConfig = PostgresCheckpointerConfig.default
  ): Result[PostgresCheckpointer] =
    given ErrorContext = ErrorContext(config.schema)
    Try {
      val hikari = new HikariConfig()
      hikari.setJdbcUrl(jdbcUrl)
      hikari.setUsername(user)
      hikari.setPassword(password)
      hikari.setMaximumPoolSize(config.maxPoolSize)
      hikari.setConnectionTimeout(DurationRounding.ceilMillis(config.connectionTimeout))
      hikari.setPoolName(s"llm4s-checkpointer-${config.schema}")
      hikari.addDataSourceProperty("socketTimeout", socketTimeoutSeconds(config.callTimeout).toString)
      hikari.addDataSourceProperty("tcpKeepAlive", "true")
      hikari.addDataSourceProperty("ApplicationName", "llm4s-agent-checkpoint-postgres")
      new HikariDataSource(hikari)
    }.toEither.left
      .map(e => failure("open", describe(e), Some(e)))
      .flatMap(pool => create(pool, ownsDataSource = true, config))

  /**
   * The store over `dataSource`, a pool the caller already has, closed with the store only if `ownsDataSource`. The
   * schema `config` names is created if it is missing and brought to the current version in one transaction, under a
   * transaction-scoped advisory lock, so any number of instances may start at once. A schema that a newer build
   * migrated is refused with `GraphError.UnsupportedStoreSchema`, changing nothing; a database that cannot be reached
   * is a `ProcessingError`. Either way an owned `dataSource` is closed.
   *
   * The pool's own connection timeout takes `config.connectionTimeout`'s place in the store's bound, and its driver
   * should set a socket timeout; the call timeout is the store's.
   */
  def create(
    dataSource: DataSource,
    ownsDataSource: Boolean,
    config: PostgresCheckpointerConfig = PostgresCheckpointerConfig.default
  ): Result[PostgresCheckpointer] = createWith(dataSource, ownsDataSource, config, ServerClock)

  /** The database server's clock, read once a call holds its locks. */
  private[postgres] val ServerClock: String = "clock_timestamp()"

  /**
   * [[create]] judging time by `clockSql`, an SQL expression of type `timestamptz` evaluated in the database: the
   * server's clock outside tests; a test reads a clock it moves from a table, so claims expire without real waiting.
   */
  private[postgres] def createWith(
    dataSource: DataSource,
    ownsDataSource: Boolean,
    config: PostgresCheckpointerConfig,
    clockSql: String
  ): Result[PostgresCheckpointer] =
    val store    = new PostgresCheckpointer(dataSource, ownsDataSource, config, clockSql)
    val migrated = store.writing("open")(c => PostgresSchema.migrate(c, config.schema))
    migrated match
      case Right(_) => Right(store)
      case Left(error) =>
        store.close()
        Left(error)

  // ---- rows ----

  /** A stored claim; `tenant` is the tenant it was requested for, `None` for a claim of no tenant. */
  final private case class Claim(holder: String, tenant: Option[String], token: Long, expiresAt: Instant)

  final private case class Row(
    checkpointId: Option[String],
    nextSeq: Long,
    earliestSeq: Long,
    firstSeq: Long,
    claim: Option[Claim],
    parent: Option[String]
  )

  // the replaced claim's holder is named only to a claim of its own tenant
  private def granted(threadId: ThreadId, claim: Claim, replaced: Option[Claim] = None): RunClaim =
    RunClaim(
      threadId,
      RunId(claim.holder),
      claim.tenant.map(TenantId(_)),
      FencingToken(claim.token),
      claim.expiresAt,
      replaced.filter(_.tenant == claim.tenant).map(r => RunId(r.holder))
    )

  private def stale(threadId: ThreadId, current: Option[Row], token: FencingToken): GraphError =
    GraphError.StaleClaim(threadId.value, token, current.flatMap(_.claim).map(c => FencingToken(c.token)))

  /** PostgreSQL keeps microseconds: an instant the store hands back is one it can store exactly. */
  private[postgres] def micros(at: Instant): Instant = at.truncatedTo(ChronoUnit.MICROS)

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

  // ---- the thread registry (schema 2) ----

  /** The status the registry stores for a thread whose latest checkpoint is `checkpoint`: `Deleted` once deleting. */
  private def registryStatus(checkpoint: Checkpoint): String =
    if checkpoint.deleting then ThreadStatus.Deleted.toString else checkpoint.status.toString

  /**
   * The registry's key for a tenant (`tenant_key`, a column the database generates from `tenant_id`): `=` and the
   * tenant, or empty for none, so that no tenant is NULL in the indexes - PostgreSQL orders an index scan by the
   * columns after one compared by equality, but not after one tested with `IS NULL`.
   */
  private[postgres] def tenantKey(tenantId: Option[TenantId]): String = tenantId.fold("")(t => "=" + t.value)

  /** The statement and parameters of a page of `query`, its claims judged at `now`, the database's clock. */
  private[postgres] def threadsStatement(
    t: PostgresSchema.Tables,
    query: ThreadQuery,
    now: Instant
  ): (String, Vector[Any]) =
    registryStatement(t, query.tenantId, newestFirst = true, query.after, query.limit, now) { where =>
      query.status.foreach(statusFilter(where, _, now))
      query.agent.foreach(agent => where.add("graph_id = ?", agent))
      query.updatedSince.foreach(since => where.add("updated_at >= ?", since))
      // containment, through the GIN index on the metadata
      if !query.metadata.isEmpty then where.add("metadata @> CAST(? AS jsonb)", ujson.write(query.metadata.toJson))
    }

  /** The statement and parameters of a page of `tenantId`'s incomplete threads, their claims judged at `now`. */
  private[postgres] def incompleteStatement(
    t: PostgresSchema.Tables,
    tenantId: Option[TenantId],
    after: Option[ThreadCursor],
    limit: Int,
    now: Instant
  ): (String, Vector[Any]) =
    registryStatement(t, tenantId, newestFirst = false, after, limit, now)(statusFilter(_, ThreadStatus.Idle, now))

  /**
   * One page of the registry, and one row more to tell whether another follows: the root threads of `tenantId` with a
   * checkpoint, past `after` in the page's order, narrowed by the conditions `filters` adds, through the registry's
   * partial indexes. Each row says whether a live claim holds its thread at `now`.
   */
  private def registryStatement(
    t: PostgresSchema.Tables,
    tenantId: Option[TenantId],
    newestFirst: Boolean,
    after: Option[ThreadCursor],
    limit: Int,
    now: Instant
  )(filters: Conditions => Unit): (String, Vector[Any]) =
    val where = Conditions()
    // the partial indexes' own predicate, so every one of them serves the statement
    where.add(Sql.Registered)
    where.add("tenant_key = ?", tenantKey(tenantId))
    filters(where)
    after.foreach { cursor =>
      val direction = if newestFirst then "<" else ">"
      where.add(s"""(updated_at, thread_id COLLATE "C") $direction (?, ?)""", cursor.updatedAt, cursor.threadId.value)
    }
    Sql.registryPage(t, where.sql, newestFirst) -> ((now +: where.params) :+ (limit + 1))

  /**
   * The condition a status filter adds: the stored status (`Deleted` while a cascade delete is under way) and whether
   * a live claim holds the thread at `now`, as `ThreadStatus.of` decides.
   */
  private def statusFilter(where: Conditions, status: ThreadStatus, now: Instant): Unit =
    val unclaimed = "(claim_expires_at IS NULL OR claim_expires_at <= ?)"
    status match
      case ThreadStatus.Deleted => where.add("thread_status = ?", ThreadStatus.Deleted.toString)
      case ThreadStatus.Running =>
        // through the partial index of claimed threads
        where.add("claim_expires_at IS NOT NULL")
        where.add(s"thread_status <> ? AND ${Sql.LiveClaim}", ThreadStatus.Deleted.toString, now)
      case ThreadStatus.Idle => where.add(s"thread_status = ? AND $unclaimed", CheckpointStatus.Running.toString, now)
      case other             => where.add(s"thread_status = ? AND $unclaimed", other.toString, now)

  /** A `WHERE` clause built a condition at a time, with its parameters in order. */
  final private class Conditions:
    private val clauses = Vector.newBuilder[String]
    private val values  = Vector.newBuilder[Any]
    def add(clause: String, params: Any*): Unit =
      clauses += clause
      values ++= params
    def sql: String         = clauses.result().mkString(" AND ")
    def params: Vector[Any] = values.result()

  /** A registry row as read: `claimed`, whether a live claim held the thread by the database's clock when read. */
  final private case class RegistryRow(
    threadId: String,
    tenant: Option[String],
    graphId: String,
    status: String,
    checkpointId: String,
    metadata: Option[String],
    updatedAt: Instant,
    claimed: Boolean
  )

  /** The page `rows` hold, decoded; a row that does not decode is a `ProcessingError` naming the operation. */
  private def summaries(operation: String, rows: Vector[RegistryRow], limit: Int)(using
    ErrorContext
  ): Result[ThreadPage] =
    sequence(rows.map { row =>
      for
        metadata <- row.metadata.fold[Result[ThreadMetadata]](Right(ThreadMetadata.empty)) { json =>
          decoding(operation, "thread metadata")(ujson.read(json)).flatMap(
            ThreadMetadata.fromJson(_).left.map(e => undecodable(operation, "thread metadata", e.message, None))
          )
        }
        status <- decoding(operation, "thread status") {
          if row.status == ThreadStatus.Deleted.toString then ThreadStatus.Deleted
          else ThreadStatus.of(CheckpointStatus.valueOf(row.status), deleting = false, row.claimed)
        }
      yield ThreadSummary(
        ThreadId(row.threadId),
        row.tenant.map(TenantId(_)),
        row.graphId,
        status,
        row.checkpointId,
        metadata,
        row.updatedAt
      )
    }).map(ThreadPage.of(_, limit))

  // ---- errors ----

  final private[postgres] case class ErrorContext(schema: String)

  private def failure(operation: String, message: String, cause: Option[Throwable])(using
    context: ErrorContext
  ): LLMError =
    ProcessingError(
      s"postgres-checkpointer.$operation",
      s"$message (${PostgresSchema.description(context.schema)})",
      cause
    )

  private def undecodable(operation: String, what: String, problem: String, cause: Option[Throwable])(using
    ErrorContext
  ): LLMError =
    failure(operation, s"a stored $what does not decode: $problem", cause)

  private[postgres] def decoding[A](operation: String, what: String)(body: => A)(using ErrorContext): Result[A] =
    Try(body).toEither.left.map(e =>
      undecodable(operation, what, Option(e.getMessage).getOrElse(e.getClass.getName), Some(e))
    )

  private[postgres] def timedOut(callTimeout: FiniteDuration): String =
    s"the call did not finish within its call timeout ($callTimeout), so its connection was discarded"

  /** What went wrong, from an exception: a timeout the server enforced is named as one. */
  private[postgres] def describe(e: Throwable): String =
    val message = Option(e.getMessage).getOrElse(e.getClass.getName)
    sqlState(e) match
      case Some("57014") => s"the call reached its call timeout on the server: $message"
      case Some("55P03") => s"the call waited its whole call timeout for a lock another connection holds: $message"
      case Some("25P03") => s"the transaction sat idle past its call timeout: $message"
      case _             => message

  private def sqlState(e: Throwable): Option[String] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(8).collectFirst {
      case sql: SQLException if sql.getSQLState != null => sql.getSQLState
    }

  // ---- bounds ----

  /**
   * The settings that bound one transaction on the server: a statement, a lock wait and an idle transaction may each
   * last `callTimeout` at most. Set with `SET LOCAL`, so they end with the transaction and never leak into the pool.
   */
  private[postgres] def settings(callTimeout: FiniteDuration): String =
    val millis = DurationRounding.ceilMillis(callTimeout)
    Vector("statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout")
      .map(name => s"SET LOCAL $name = $millis")
      .mkString("; ")

  /** The driver's socket timeout for an owned pool: whole seconds, past twice the call timeout. */
  private[postgres] def socketTimeoutSeconds(callTimeout: FiniteDuration): Long =
    math.max(1L, (callTimeout * 2).toSeconds + 1)

  private object DirectExecutor extends Executor:
    def execute(command: Runnable): Unit = command.run()

  /** One daemon thread, for every store in the JVM, that aborts the connections of calls past their timeout. */
  private lazy val watchdog: ScheduledThreadPoolExecutor =
    val count = new AtomicInteger()
    val factory: ThreadFactory = runnable =>
      val thread = new Thread(runnable, s"llm4s-postgres-checkpointer-watchdog-${count.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    val executor = new ScheduledThreadPoolExecutor(1, factory)
    executor.setRemoveOnCancelPolicy(true)
    executor

  /**
   * A call's client-side bound: unless [[disarm]]ed first, it aborts the call's connection once `timeout` has passed,
   * closing its socket, so a call whose server or network stalls fails at the bound rather than at the socket timeout.
   */
  final private[postgres] class Watch private (connection: Connection):
    private val state = new java.util.concurrent.atomic.AtomicInteger(0) // 0 armed, 1 disarmed, 2 fired
    @volatile private var task: Option[ScheduledFuture[?]] = None

    private def fire(): Unit =
      if state.compareAndSet(0, 2) then Try(connection.abort(DirectExecutor)): Unit

    /**
     * Stops the watch and takes its task off the watchdog's queue, so a finished call holds neither its connection
     * nor a queued task until its timeout; `false` if it had already fired and aborted the connection.
     */
    def disarm(): Boolean =
      val stopped = state.compareAndSet(0, 1) || state.get == 1
      task.foreach(_.cancel(false))
      stopped

  private[postgres] object Watch:
    def arm(connection: Connection, timeout: FiniteDuration): Watch =
      val watch = new Watch(connection)
      watch.task = Some(watchdog.schedule((() => watch.fire()): Runnable, timeout.toNanos, TimeUnit.NANOSECONDS))
      watch

    /** How many watches are queued, armed or not: what a finished call must not leave behind. */
    private[postgres] def queued: Int = watchdog.getQueue.size

  // ---- JDBC ----

  private[postgres] def instant(rows: ResultSet, index: Int): Instant =
    rows.getObject(index, classOf[OffsetDateTime]).toInstant

  private def bind(statement: PreparedStatement, params: Any*): Unit =
    params.zipWithIndex.foreach { (param, i) =>
      param match
        case None        => statement.setObject(i + 1, null)
        case Some(value) => bind1(statement, i + 1, value)
        case value       => bind1(statement, i + 1, value)
    }

  private def bind1(statement: PreparedStatement, index: Int, value: Any): Unit = value match
    case s: String   => statement.setString(index, s)
    case l: Long     => statement.setLong(index, l)
    case i: Int      => statement.setInt(index, i)
    case at: Instant => statement.setObject(index, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
    case a: Array[String] =>
      statement.setArray(index, statement.getConnection.createArrayOf("text", a.map(s => s: AnyRef)))
    case other => statement.setObject(index, other)

  private[postgres] def update(c: Connection, sql: String, params: Any*): Unit =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, params*)
      statement.executeUpdate(): Unit
    }

  private[postgres] def query[A](c: Connection, sql: String, params: Any*)(read: ResultSet => A): Vector[A] =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, params*)
      Using.resource(statement.executeQuery()) { rows =>
        Iterator.continually(rows).takeWhile(_.next()).map(read).toVector
      }
    }

  private def execute(c: Connection, sql: String): Unit =
    Using.resource(c.createStatement())(_.execute(sql): Unit)

  final private class RollbackUnlessCommitted(c: Connection) extends AutoCloseable:
    var committed = false
    def close(): Unit =
      if !committed then Try(c.rollback()): Unit
