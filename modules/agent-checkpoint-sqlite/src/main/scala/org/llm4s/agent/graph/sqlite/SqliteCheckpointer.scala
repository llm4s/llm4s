package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding

import java.nio.file.Path
import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet }
import java.time.{ Clock, Instant }
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * A durable [[org.llm4s.agent.graph.Checkpointer]] in one SQLite database file: threads, their checkpoint histories,
 * pending writes, event logs and run claims survive a restart, and several runtimes - in this process or in
 * others on the same host - can share the file, each through a store of its own.
 *
 * Open it with [[SqliteCheckpointer.open]] and close it with [[close]]. It meets the `Checkpointer` contract (and
 * passes `CheckpointerContract` from `llm4s-agent-testkit`):
 *
 *  - '''Atomic, fenced commits.''' Every call that writes is one `BEGIN IMMEDIATE` transaction: a `Commit`'s
 *    checkpoint, pending writes and events land together or not at all, and the claim's token and the thread's
 *    version are checked inside the same transaction, so two stores over one file cannot both pass the checks; so is
 *    a `Commit.fence`'s claim on another thread.
 *  - '''Claims expire by `clock`''', the clock this store was opened with: the clock of the process that grants or
 *    renews a claim decides when it lapses. Stores in processes on different hosts sharing one file therefore
 *    judge expiry by different clocks, and skew between them changes a claim's effective `ttl` (see
 *    `docs/guide/agents/durable-checkpointers.md`); fencing keeps writes safe regardless.
 *  - '''History and retention.''' Every checkpoint a commit supersedes stays in the file, readable with `history`
 *    (newest first, paged through the `(thread, position)` primary key) and `checkpoint`, until `prune`, a commit's
 *    `retractTurn` or [[deleteThread]] removes it; each of those is one write transaction too.
 *  - '''Fencing tokens''' come from one counter for the whole file, stored in it, so they keep increasing across
 *    restarts and [[deleteThread]], and are never issued twice.
 *  - '''Fenced deletes, numbered incarnations.''' [[deleteThread]] checks the deleter's token in its own write
 *    transaction, as a commit does. It keeps the thread's next event number in the file, so a new thread of the same
 *    id numbers its events past the deleted one's, in this process and every other, and a read from inside the
 *    deleted log is refused with `GraphError.ThreadDeleted`.
 *  - '''Data only.''' Checkpoints, pending writes and events are stored as JSON - the checkpoint through
 *    `Checkpoint.toJson`, which records its format version - and decoded on read, so nothing executable is stored.
 *  - '''Change notification by polling.''' SQLite cannot tell a connection that another has committed, so this
 *    store keeps `Checkpointer.awaitEventsAfter`'s default: a subscription watching it reads the event log once per
 *    `WatchPolicy.pollInterval`, and sees commits made through other stores on the file that long after they land.
 *
 * The database runs in WAL mode with `synchronous = FULL`: readers never block the writer, and a commit that
 * returned survives a crash or a power loss. SQLite admits one writer at a time per file; a write that finds
 * another connection writing waits up to [[SqliteCheckpointerConfig.busyTimeout]], then fails with a
 * `ProcessingError`, changing nothing. WAL needs shared memory between the processes that open the file, so keep
 * the file on a local disk - not on a network file system.
 *
 * '''Fair writes within a process.''' SQLite does not queue a waiting writer: it sleeps and tries again, and wins
 * only if it wakes while no other connection is writing, so a store beside one that writes back to back can wait
 * out its whole busy timeout although every write is short (#1951). The stores in one JVM that opened the same path
 * therefore take turns through a fair, first-come-first-served lock for the file before they begin a write (and an
 * opener before its migration): each waits for the writes queued ahead of it, up to the busy timeout, and only
 * then for SQLite's lock, which another process may hold - up to the busy timeout again. Between processes SQLite's
 * polling still decides the order, so a process writing steadily can starve another's write; raise `busyTimeout`
 * there.
 *
 * One store is one connection, and its calls are serialised: it is safe to call from any number of threads.
 * Every failure of the database itself is a `ProcessingError`, and so is any call after [[close]].
 *
 * '''Every call returns within twice the busy timeout''', plus the time its own SQL takes, as the `Checkpointer`
 * contract requires (#1919). A call waits for this store's earlier calls, and a write then for its turn at the file,
 * at most `busyTimeout` from the call's start; a write then waits for SQLite's own lock, which another process may
 * hold, at most `busyTimeout` again. A call that reaches either limit fails with a `ProcessingError`, changing
 * nothing. A read never waits for a writer in WAL mode, so it waits only for this store's earlier calls.
 * `awaitEventsAfter` polls, so it returns within its `timeout` plus that bound. What no limit here covers is the SQL
 * itself once it holds the connection: sqlite-jdbc's native calls cannot be timed out or interrupted, so a disk that
 * stalls inside a write - `synchronous = FULL` waits for the disk to sync every commit - holds that call, and the
 * store's later calls fail once they have waited `busyTimeout` for it. Keep the bound well inside
 * `ClaimPolicy.ttl - ClaimPolicy.renewEvery`: with the default 5 second busy timeout and 30 second `ttl`, it is.
 */
final class SqliteCheckpointer private (
  val path: Path,
  clock: Clock,
  connection: Connection,
  busyTimeout: FiniteDuration,
  gate: WriteGate
) extends Checkpointer
    with AutoCloseable:

  import SqliteCheckpointer.*

  private val lock                                 = new ReentrantLock()
  @volatile private var shut                       = false
  private[sqlite] given errorContext: ErrorContext = ErrorContext(path)

  // ---- claims ----

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = writing("claim") { c =>
    val current = readRow(c, threadId)
    val now     = clock.instant()
    current.flatMap(_.claim).filter(live => now.isBefore(live.expiresAt)) match
      case Some(live) =>
        // another tenant's holder, or one whose tenant is not recorded, is never named
        val holder =
          if live.of(request.tenantId) then GraphError.BusyHolder.Run(RunId(live.holder))
          else GraphError.BusyHolder.Unnamed
        Left(GraphError.ThreadBusy(threadId.value, current.flatMap(_.checkpointId), holder))
      case None =>
        val claim = Claim(
          request.holder.value,
          Some(request.tenantId.map(_.value)),
          nextToken(c),
          now.plusNanos(request.ttl.toNanos)
        )
        ensureRow(c, threadId)
        setClaim(c, threadId, Some(claim))
        // a claim still held here has expired: this one takes it over
        Right(granted(threadId, claim, current.flatMap(_.claim)))
  }

  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] = writing("renew") { c =>
    val current = readRow(c, threadId)
    current.flatMap(_.claim).filter(_.token == token.value) match
      case None => Left(stale(threadId, current, token))
      case Some(held) =>
        val renewed = held.copy(expiresAt = clock.instant().plusNanos(ttl.toNanos))
        setClaim(c, threadId, Some(renewed))
        Right(granted(threadId, renewed))
  }

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = writing("release") { c =>
    if readRow(c, threadId).flatMap(_.claim).exists(_.token == token.value) then
      setClaim(c, threadId, None)
      dropIfEmpty(c, threadId)
    Right(())
  }

  // ---- data ----

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = writing("commit") { c =>
    val current  = readRow(c, threadId)
    val latestId = current.flatMap(_.checkpointId)
    // the thread's own claim first, then the fence's, both in the transaction the commit is made in
    val fenced = Option
      .when(!current.flatMap(_.claim).exists(_.token == commit.token.value))(stale(threadId, current, commit.token))
      .orElse(commit.fence.flatMap { (other, otherToken) =>
        val above = readRow(c, other)
        Option.when(!above.flatMap(_.claim).exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    def invalid(reason: String) = GraphError.InvalidCommit(threadId.value, reason)
    // checked only once the token and the version have passed, so a stale writer learns nothing of the history
    def duplicate = commit.checkpoint.filter(c0 => inHistory(c, threadId, c0.id)).map { checkpoint =>
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
    // a queued attempt recorded before a delete never writes to the id again, whatever thread holds it (design 4.55)
    def orphaned =
      commit.checkpoint.filter(c0 => orphanedAttempt(c, threadId, c0.runId)).map { checkpoint =>
        invalid(
          s"run ${checkpoint.runId} is an attempt of a queue entry whose thread was deleted after it was recorded"
        )
      }
    fenced.orElse(conflict).orElse(duplicate).orElse(misdirected).orElse(unanchored).orElse(orphaned).toLeft(()).map {
      _ =>
        // a thread with a claim always has a row
        val nextSeq = current.fold(1L)(_.nextSeq)
        // what `prune` judges age by: the store's clock, never the producer's timestamps (schema 7)
        val committedAt = clock.instant()
        commit.checkpoint.foreach { checkpoint =>
          val json = ujson.write(Checkpoint.toJson(checkpoint))
          // read before this commit writes: no checkpoint yet means a new thread under the id (a tombstone's, or none);
          // a retraction below empties the history but leaves the thread the same one
          val fresh =
            query(c, "SELECT checkpoint_id FROM llm4s_checkpoint_threads WHERE thread_id = ?", threadId.value)(rows =>
              Option(rows.getString(1))
            ).headOption.flatten.isEmpty
          // positions only grow, also across a retraction, so the history's order is its primary key's
          val position = query(
            c,
            "SELECT COALESCE(MAX(position) + 1, 0) FROM llm4s_checkpoint_history WHERE thread_id = ?",
            threadId.value
          )(_.getLong(1)).head
          // a blocked turn is retracted back to the newest settled checkpoint, which stays
          if commit.retractTurn then
            update(
              c,
              """DELETE FROM llm4s_checkpoint_history WHERE thread_id = ? AND position > COALESCE(
              |  (SELECT MAX(position) FROM llm4s_checkpoint_history WHERE thread_id = ? AND status IN (?, ?)), -1)""".stripMargin,
              threadId.value,
              threadId.value,
              CheckpointStatus.Completed.toString,
              CheckpointStatus.Failed.toString
            )
          update(
            c,
            """INSERT INTO llm4s_checkpoint_history
            |(thread_id, position, checkpoint_id, status, created_second, created_nano, checkpoint_json,
            | committed_second, committed_nano, run_id)
            |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
            threadId.value,
            position,
            checkpoint.id,
            checkpoint.status.toString,
            checkpoint.createdAt.getEpochSecond,
            checkpoint.createdAt.getNano.toLong,
            json,
            committedAt.getEpochSecond,
            committedAt.getNano.toLong,
            checkpoint.runId
          )
          // the registry's columns (schema 8) follow the latest checkpoint
          update(
            c,
            """UPDATE llm4s_checkpoint_threads
            |SET checkpoint_id = ?, checkpoint_json = ?, tenant_id = ?, graph_id = ?, thread_status = ?, updated_nanos = ?
            |WHERE thread_id = ?""".stripMargin,
            checkpoint.id,
            json,
            checkpoint.tenantId,
            checkpoint.snapshot.graphId,
            registryStatus(checkpoint),
            epochNanos(committedAt),
            threadId.value
          )
          update(c, "DELETE FROM llm4s_checkpoint_pending_writes WHERE thread_id = ?", threadId.value)
          placeChild(c, threadId, checkpoint, fresh)
          // an open queue entry whose attempt this is was admitted, whatever a later retraction removes
          admitAttempt(c, threadId, checkpoint.runId)
        }
        appendWrites(c, threadId, commit.pendingWrites)
        commit.metadata.foreach(writeMetadata(c, threadId, _, committedAt))
        // released before this commit's own records are applied
        if commit.checkpoint.isDefined then
          query(
            c,
            "SELECT effect_key, task_id, task_lineage FROM llm4s_checkpoint_effects WHERE thread_id = ?",
            threadId.value
          )(row => (row.getString(1), TaskId(row.getString(2)), row.getString(3)))
            .filter((_, task, lineage) => commit.releasesEffect(task, lineage))
            .foreach { (key, _, _) =>
              update(
                c,
                "DELETE FROM llm4s_checkpoint_effects WHERE thread_id = ? AND effect_key = ?",
                threadId.value,
                key
              )
            }
        commit.effects.foreach { record =>
          update(
            c,
            """INSERT INTO llm4s_checkpoint_effects (thread_id, effect_key, task_id, task_lineage, writer_token, record_json)
            |VALUES (?, ?, ?, ?, ?, ?)
            |ON CONFLICT (thread_id, effect_key) DO UPDATE SET
            |  task_id = excluded.task_id, task_lineage = excluded.task_lineage, writer_token = excluded.writer_token,
            |  record_json = excluded.record_json""".stripMargin,
            threadId.value,
            record.key.value,
            record.taskId.value,
            record.lineage,
            commit.token.value,
            ujson.write(EffectRecord.toJson(record))
          )
        }
        val stored = commit.events.zipWithIndex.map { (draft, i) =>
          val seq = nextSeq + i
          seq -> upickle.default.write(EventRecord.committed(threadId.value, seq, commit.token, draft))
        }
        Using.resource(
          c.prepareStatement(
            """INSERT INTO llm4s_checkpoint_events (thread_id, seq, record_json, committed_second, committed_nano)
            |VALUES (?, ?, ?, ?, ?)""".stripMargin
          )
        ) { insert =>
          stored.foreach { (seq, json) =>
            bind(insert, threadId.value, seq, json, committedAt.getEpochSecond, committedAt.getNano.toLong)
            insert.executeUpdate(): Unit
          }
        }
        if stored.nonEmpty then
          update(
            c,
            "UPDATE llm4s_checkpoint_threads SET next_seq = ? WHERE thread_id = ?",
            nextSeq + stored.size,
            threadId.value
          )
        // handed back decoded from what was stored, as a later read would see them
        stored.map((_, json) => upickle.default.read[EventRecord](json))
    }
  }

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
    reading("latest") { c =>
      val checkpoint = query(
        c,
        "SELECT checkpoint_json FROM llm4s_checkpoint_threads WHERE thread_id = ? AND checkpoint_json IS NOT NULL",
        threadId.value
      )(_.getString(1)).headOption
      val writes = checkpoint.fold(Vector.empty[String]) { _ =>
        query(
          c,
          "SELECT write_json FROM llm4s_checkpoint_pending_writes WHERE thread_id = ? ORDER BY position",
          threadId.value
        )(_.getString(1))
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
    reading("effect") { c =>
      Right(
        query(
          c,
          "SELECT record_json FROM llm4s_checkpoint_effects WHERE thread_id = ? AND effect_key = ?",
          threadId.value,
          key.value
        )(_.getString(1)).headOption
      )
    }.flatMap {
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
        case None => Right(Long.MaxValue)
        case Some(id) =>
          positionOf(c, threadId, id).toRight(GraphError.CheckpointNotFound(threadId.value, id))
      from.map { position =>
        if limit <= 0 then Vector.empty
        else query(c, HistoryPageSql, threadId.value, position, limit)(_.getString(1))
      }
    }.flatMap(rows => sequence(rows.map(decodeCheckpoint("history", _))))

  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
    reading("checkpoint") { c =>
      Right(
        query(c, CheckpointByIdSql, threadId.value, checkpointId)(_.getString(1)).headOption
      )
    }.flatMap {
      case None       => Right(None)
      case Some(json) => decodeCheckpoint("checkpoint", json).map(Some(_))
    }

  /**
   * Removes what `policy` does not keep, in one write transaction, so it sees and changes one state of the thread
   * whatever other stores on the file are doing; it takes no claim, as the contract says.
   */
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = writing("prune") { c =>
    readRow(c, threadId) match
      case None => Right(())
      case Some(current) =>
        val now    = clock.instant()
        val cutoff = policy.maxAge.map(age => now.minusNanos(age.toNanos))
        // oldest first: (position, id, committed at) - a row from before schema 7 has its producer's time instead
        val history = query(
          c,
          """SELECT position, checkpoint_id, COALESCE(committed_second, created_second),
            |  CASE WHEN committed_second IS NULL THEN created_nano ELSE committed_nano END
            |FROM llm4s_checkpoint_history WHERE thread_id = ? ORDER BY position""".stripMargin,
          threadId.value
        )(rows => (rows.getLong(1), rows.getString(2), Instant.ofEpochSecond(rows.getLong(3), rows.getLong(4))))
        // the latest checkpoint is always kept, whatever its age
        val (older, latest) = history.splitAt(history.size - 1)
        val young           = older.filter((_, _, at) => cutoff.forall(cut => !at.isBefore(cut)))
        val kept            = policy.maxCheckpoints.fold(young ++ latest)(n => (young ++ latest).takeRight(n))
        // an open queue entry's admission evidence (rule 4): the newest checkpoint each of its runs committed
        val evidence = query(
          c,
          """SELECT MAX(h.position) FROM llm4s_checkpoint_history h
            |JOIN llm4s_checkpoint_queue_attempts a ON a.thread_id = h.thread_id AND a.run_id = h.run_id
            |JOIN llm4s_checkpoint_queue_entries e ON e.entry_id = a.entry_id AND e.state = 'open'
            |WHERE h.thread_id = ? GROUP BY h.run_id""".stripMargin,
          threadId.value
        )(_.getLong(1)).toSet
        val keptPositions = kept.map(_._1).toSet ++ evidence
        history.filterNot((position, _, _) => keptPositions(position)).foreach { (position, _, _) =>
          update(
            c,
            "DELETE FROM llm4s_checkpoint_history WHERE thread_id = ? AND position = ?",
            threadId.value,
            position
          )
        }
        val keptIds = kept.map(_._2).toSet
        decoding("prune", "event") {
          query(
            c,
            """SELECT seq, record_json, committed_second, committed_nano FROM llm4s_checkpoint_events
              |WHERE thread_id = ? ORDER BY seq""".stripMargin,
            threadId.value
          ) { rows =>
            val seq    = rows.getLong(1)
            val json   = rows.getString(2)
            val second = rows.getLong(3)
            (seq, json, Option.when(!rows.wasNull())(Instant.ofEpochSecond(second, rows.getLong(4))))
          }.map { (seq, json, committed) =>
            val event = upickle.default.read[EventRecord](json)
            // a row from before schema 7 has its producer's timestamp in place of its commit time
            (seq, event, committed.getOrElse(event.timestamp))
          }
        }.map { events =>
          // events are kept while their checkpoints are: from the oldest kept checkpoint's first event on
          val byCheckpoint =
            if kept.size == history.size then None
            else events.collectFirst { case (seq, e, _) if e.checkpointId.exists(keptIds) => seq }
          val byAge = cutoff.map { cut =>
            events.collectFirst { case (seq, _, at) if !at.isBefore(cut) => seq }.getOrElse(current.nextSeq)
          }
          val bySize = policy.maxEvents.map(n => current.nextSeq - n)
          val floor  = (Vector(current.earliestSeq) ++ byCheckpoint ++ byAge ++ bySize).max.min(current.nextSeq)
          update(c, "DELETE FROM llm4s_checkpoint_events WHERE thread_id = ? AND seq < ?", threadId.value, floor)
          update(c, "UPDATE llm4s_checkpoint_threads SET earliest_seq = ? WHERE thread_id = ?", floor, threadId.value)
        }
  }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    reading("eventsAfter") { c =>
      val row      = readRow(c, threadId)
      val first    = row.fold(1L)(_.firstSeq)
      val earliest = row.fold(1L)(_.earliestSeq)
      // a reader from the start reads the thread now under the id, from its first event
      if Checkpointer.deleted(afterSeq, first) then Left(GraphError.ThreadDeleted(threadId.value, afterSeq, first))
      else if math.max(afterSeq, first - 1) + 1 < earliest then
        Left(GraphError.ReplayUnavailable(threadId.value, earliest))
      else if limit <= 0 then Right(Vector.empty)
      else
        Right(
          query(
            c,
            "SELECT record_json FROM llm4s_checkpoint_events WHERE thread_id = ? AND seq > ? ORDER BY seq LIMIT ?",
            threadId.value,
            afterSeq,
            limit
          )(_.getString(1))
        )
    }.flatMap(rows => decoding("eventsAfter", "event")(rows.map(upickle.default.read[EventRecord](_))))

  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] =
    if runIds.isEmpty then Right(None)
    else
      reading("latestOfRuns") { c =>
        // one probe of the (thread, run, position) index per run, the newest of them kept
        val newest = runIds.toVector.flatMap { run =>
          query(c, LatestOfRunSql, threadId.value, run.value)(rows => rows.getLong(1) -> rows.getString(2))
        }
        newest.maxByOption(_._1) match
          case None            => Right(None)
          case Some((_, json)) => decodeCheckpoint("latestOfRuns", json).map(Some(_))
      }

  /**
   * The store's run queue (design 4.55), in the same file and over the same connection as its checkpoints: its
   * open entries learn of their thread's deletion in [[deleteThread]]'s own transaction, and [[prune]] keeps the
   * newest checkpoint of every run an open entry recorded. Closing the store closes it.
   */
  lazy val runQueue: org.llm4s.agent.service.RunQueue = new SqliteRunQueue(this, clock)

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = writing("compactEvents") { c =>
    readRow(c, threadId).foreach { current =>
      val floor = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
      update(c, "DELETE FROM llm4s_checkpoint_events WHERE thread_id = ? AND seq < ?", threadId.value, floor)
      update(c, "UPDATE llm4s_checkpoint_threads SET earliest_seq = ? WHERE thread_id = ?", floor, threadId.value)
    }
    Right(())
  }

  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
    reading("children") { c =>
      val from: Result[Long] = after match
        case None => Right(0L)
        case Some(cursor) =>
          query(
            c,
            """SELECT child_order FROM llm4s_checkpoint_threads
              |WHERE thread_id = ? AND parent_thread_id = ? AND child_order IS NOT NULL""".stripMargin,
            cursor.value,
            parent.value
          )(_.getLong(1)).headOption
            .toRight(ValidationError("after", s"'${cursor.value}' is not a known child of '${parent.value}'"))
      from.map { order =>
        if limit <= 0 then Vector.empty
        else
          // a tombstone keeps its place but is not listed
          query(
            c,
            """SELECT checkpoint_json FROM llm4s_checkpoint_threads
              |WHERE parent_thread_id = ? AND child_order > ? AND checkpoint_json IS NOT NULL
              |ORDER BY child_order LIMIT ?""".stripMargin,
            parent.value,
            order,
            limit
          )(_.getString(1))
      }
    }.flatMap(rows => sequence(rows.map(decodeCheckpoint("children", _))).map(_.flatMap(ChildThread.of)))

  def threads(query: ThreadQuery): Result[ThreadPage] =
    reading("threads")(c => Right(registry(c, threadsStatement(query, clock.instant()))))
      .flatMap(rows => summaries("threads", rows, query.limit))

  def incompleteThreads(
    tenantId: Option[TenantId],
    after: Option[ThreadCursor],
    limit: Int
  ): Result[ThreadPage] =
    ThreadQuery.checkLimit(limit).flatMap { limit =>
      reading("incompleteThreads") { c =>
        Right(registry(c, incompleteStatement(tenantId, after, limit, clock.instant())))
      }.flatMap(rows => summaries("incompleteThreads", rows, limit))
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
        rows.getLong(7),
        rows.getBoolean(8)
      )
    }

  /**
   * Deletes the thread in one write transaction, which first checks that `token` is the thread's current claim -
   * so the check and the delete see one state of the file, whatever other stores on it are doing. The thread's row
   * stays, emptied, as a tombstone (see [[SqliteSchema]]) once it has numbered an event, so that a new thread of the
   * id numbers on past it. A `fence` thread's claim is checked in the same transaction, after the thread's own.
   */
  def deleteThread(
    threadId: ThreadId,
    token: FencingToken,
    fence: Option[(ThreadId, FencingToken)]
  ): Result[Unit] = writing("deleteThread") { c =>
    val current = readRow(c, threadId)
    val refused = Option
      .when(!current.flatMap(_.claim).exists(_.token == token.value))(stale(threadId, current, token))
      .orElse(fence.flatMap { (other, otherToken) =>
        val above = readRow(c, other)
        Option.when(!above.flatMap(_.claim).exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    refused match
      case Some(error) => Left(error)
      case None =>
        update(c, "DELETE FROM llm4s_checkpoint_events WHERE thread_id = ?", threadId.value)
        update(c, "DELETE FROM llm4s_checkpoint_pending_writes WHERE thread_id = ?", threadId.value)
        update(c, "DELETE FROM llm4s_checkpoint_history WHERE thread_id = ?", threadId.value)
        update(c, "DELETE FROM llm4s_checkpoint_effects WHERE thread_id = ?", threadId.value)
        update(c, "DELETE FROM llm4s_checkpoint_thread_metadata WHERE thread_id = ?", threadId.value)
        // the thread's open queue entries learn of the delete in its own transaction (design 4.55), so none of them
        // runs on a new thread of the id, whatever the deleted thread numbered
        update(
          c,
          "UPDATE llm4s_checkpoint_queue_entries SET thread_deleted = 1 WHERE thread_id = ? AND state = 'open'",
          threadId.value
        )
        update(
          c,
          // every SET expression reads the row as it was: the next thread starts one past the deleted one's next
          // number, which is never used (see `Checkpointer.deleted`); a thread that numbered no event leaves nothing,
          // and a tombstone that numbered no event since its delete keeps its numbering
          """UPDATE llm4s_checkpoint_threads
            |SET checkpoint_id = NULL, checkpoint_json = NULL,
            |    next_seq = CASE WHEN next_seq > 1 AND next_seq <> first_seq THEN next_seq + 1 ELSE next_seq END,
            |    first_seq = CASE WHEN next_seq > 1 AND next_seq <> first_seq THEN next_seq + 1 ELSE next_seq END,
            |    earliest_seq = CASE WHEN next_seq > 1 AND next_seq <> first_seq THEN next_seq + 1 ELSE next_seq END,
            |    claim_holder = NULL, claim_token = NULL, claim_expires_second = NULL, claim_expires_nano = NULL,
            |    claim_tenant = NULL, claim_tenant_token = NULL,
            |    tenant_id = NULL, graph_id = NULL, thread_status = NULL, updated_nanos = NULL, metadata_json = NULL
            |WHERE thread_id = ?""".stripMargin,
          threadId.value
        )
        dropIfEmpty(c, threadId)
        Right(())
  }

  /**
   * Closes the connection. Later calls return a `ProcessingError`; closing again does nothing. A claim this store
   * granted stays in the file until it is released through another store or expires.
   */
  def close(): Unit = locked {
    if !shut then
      shut = true
      WriteGate.release(gate)
      connection.close()
  }

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = shut

  // ---- transactions ----

  private def locked[A](body: => A): A =
    lock.lock()
    Using.resource(Unlock(lock))(_ => body)

  /**
   * `body` on the connection, serialised with every other call; any exception is a `ProcessingError`. A call waits
   * for this store's earlier calls only until `deadline` (a `System.nanoTime` value), and then fails as a busy file
   * does, with a `ProcessingError` and nothing changed (#1919): a call stuck on the connection - one waiting out a
   * busy file, or a disk that stalls - must not hold every later call of the store with it.
   */
  private def guarded[A](operation: String, deadline: Long)(body: Connection => Result[A]): Result[A] =
    if !lockBy(deadline) then Left(failure(operation, storeBusy(busyTimeout), None))
    else
      Using.resource(Unlock(lock)) { _ =>
        if shut then Left(failure(operation, "the store is closed", None))
        else
          Try(body(connection)).toEither.left
            .map(e => failure(operation, Option(e.getMessage).getOrElse(e.getClass.getName), Some(e)))
            .flatten
      }

  /**
   * Takes the store's lock, waiting at most until `deadline` (a `System.nanoTime` value); whether it was taken. As
   * `lock()` does, it ignores an interrupt while it waits and sets the flag again afterwards: a call's interrupt
   * flag decides nothing here, as before the wait was bounded.
   */
  private def lockBy(deadline: Long): Boolean =
    @scala.annotation.tailrec
    def waiting(interrupted: Boolean): (Boolean, Boolean) =
      CancelledError.catchInterrupt(lock.tryLock(math.max(0L, deadline - System.nanoTime()), NANOSECONDS)) match
        case Right(taken) => (taken, interrupted)
        case Left(_)      => waiting(interrupted = true)
    // a free lock is taken at once, whatever the caller's interrupt flag says
    lock.tryLock() || {
      val (taken, interrupted) = waiting(Thread.interrupted())
      if interrupted then Thread.currentThread().interrupt()
      taken
    }

  /**
   * `body` in one `BEGIN IMMEDIATE` transaction, committed if it returns `Right` and rolled back otherwise - also
   * when it throws. `IMMEDIATE` takes the write lock before the first read, so the checks a write makes and the
   * write itself see the same state, and a busy file is waited for (up to the busy timeout) before anything is
   * read: a deferred transaction that read first could not wait for the lock.
   *
   * The transaction runs holding the file's [[WriteGate]], so the stores of this process write in turn rather than
   * by SQLite's polling; a wait for the gate that outlasts the busy timeout fails as a busy file does, and one that
   * is interrupted returns a `CancelledError`, with the interrupt flag set again.
   *
   * The waits are bounded together (#1919): for this store's earlier calls and then for the gate, at most
   * `busyTimeout` from the call's start, and then for SQLite's own lock, held by another process, at most
   * `busyTimeout` again - so a write waits at most twice `busyTimeout` in all before it fails, changing nothing.
   */
  private[sqlite] def writing[A](operation: String)(body: Connection => Result[A]): Result[A] =
    val deadline = System.nanoTime() + busyTimeout.toNanos
    guarded(operation, deadline) { c =>
      val written = CancelledError.catchInterrupt {
        gate.holding(math.max(0L, deadline - System.nanoTime()).nanos) {
          transaction(c, "BEGIN IMMEDIATE") { guard =>
            val result = body(c)
            if result.isRight then
              execute(c, "COMMIT")
              guard.committed = true
            result
          }
        }
      }
      written match
        case Right(Some(result)) => result
        case Right(None)         => Left(failure(operation, gateTimedOut(busyTimeout), None))
        case Left(interrupted) =>
          Thread.currentThread().interrupt()
          Left(CancelledError(s"sqlite-checkpointer.$operation", Some(interrupted)))
    }

  /**
   * `body` in one read transaction, so the rows it reads are from one snapshot of the file. In WAL mode a read never
   * waits for a writer, so it waits only for this store's earlier calls, at most `busyTimeout` (#1919).
   */
  private[sqlite] def reading[A](operation: String)(body: Connection => Result[A]): Result[A] =
    guarded(operation, System.nanoTime() + busyTimeout.toNanos) { c =>
      transaction(c, "BEGIN DEFERRED") { guard =>
        val result = body(c)
        execute(c, "COMMIT")
        guard.committed = true
        result
      }
    }

object SqliteCheckpointer:

  /** The newest checkpoint run `?` committed on thread `?`, by the `(thread_id, run_id, position)` index. */
  private[sqlite] val LatestOfRunSql: String =
    """SELECT position, checkpoint_json FROM llm4s_checkpoint_history INDEXED BY llm4s_checkpoint_history_run
      |WHERE thread_id = ? AND run_id = ? ORDER BY position DESC LIMIT 1""".stripMargin

  /** Opens (creating it if need be) the store in the file at `path`; claims expire by the system clock. */
  def open(path: Path): Result[SqliteCheckpointer] =
    open(path, Clock.systemUTC(), SqliteCheckpointerConfig.default)

  /** Opens (creating it if need be) the store in the file at `path`; claims expire by `clock`. */
  def open(path: Path, clock: Clock): Result[SqliteCheckpointer] =
    open(path, clock, SqliteCheckpointerConfig.default)

  /**
   * Opens the store in the SQLite file at `path`, creating the file if it does not exist (its directory must),
   * with claims expiring by `clock`. The file is switched to WAL mode, then brought to the current schema version in
   * one transaction. Any number of stores may open one file at once, a new one too: an opener that finds the file
   * busy with another's switch or migration retries until the busy timeout has passed. A file at a newer
   * schema version than this build knows, a file that is not a SQLite database, or one whose journal cannot be
   * switched to WAL is refused with a `ProcessingError`, and the connection is closed. An opener interrupted while
   * it waits for a busy file returns a `CancelledError`, with its thread's interrupt flag set, and closes the
   * connection too.
   */
  def open(path: Path, clock: Clock, config: SqliteCheckpointerConfig): Result[SqliteCheckpointer] =
    openWith(path, clock, config, url => DriverManager.getConnection(url))

  /**
   * [[open]] on a connection from `connect`, so a test can see that a failed open closes what it opened; `onBusy`
   * runs each time the open finds the file busy, just before it pauses to try again.
   */
  private[sqlite] def openWith(
    path: Path,
    clock: Clock,
    config: SqliteCheckpointerConfig,
    connect: String => Connection,
    onBusy: () => Unit = () => ()
  ): Result[SqliteCheckpointer] =
    given ErrorContext = ErrorContext(path)
    Try {
      Class.forName("org.sqlite.JDBC")
      connect(jdbcUrl(path))
    }.toEither.left.map(e => failure("open", Option(e.getMessage).getOrElse(e.getClass.getName), Some(e))).flatMap {
      connection =>
        val deadline = System.nanoTime() + config.busyTimeout.toNanos
        val gate     = WriteGate.acquire(path)
        // an interrupt while waiting out a busy file is not NonFatal, so `Try` would let it escape and leak the
        // connection: `attempt` returns it as a CancelledError, with the thread's interrupt flag set again
        val prepared = CancelledError
          .attempt("sqlite-checkpointer.open") {
            Try {
              prepare(connection, config, deadline, onBusy)
              // in turn with the writes of this process's stores on the file, as a write is
              gate
                .holding(math.max(deadline - System.nanoTime(), 0L).nanos) {
                  retryingBusy(deadline, onBusy) {
                    transaction(connection, "BEGIN IMMEDIATE") { guard =>
                      val migrated = SqliteSchema.migrate(connection)
                      if migrated.isRight then
                        execute(connection, "COMMIT")
                        guard.committed = true
                      migrated
                    }
                  }
                }
                .getOrElse(Left(gateTimedOut(config.busyTimeout)))
            }.toEither.left
              .map(e => failure("open", Option(e.getMessage).getOrElse(e.getClass.getName), Some(e)))
              .flatMap(_.left.map(problem => failure("open", problem, None)))
          }
          .map(_ => new SqliteCheckpointer(path, clock, connection, config.busyTimeout, gate))
        // a connection that is not handed out must not leak: an open handle keeps the file locked
        if prepared.isLeft then
          WriteGate.release(gate)
          Try(connection.close()): Unit
        prepared
    }

  /**
   * The JDBC URL for the file at `path`, as a `file:` URI. sqlite-jdbc reads everything after a `?` in a plain
   * `jdbc:sqlite:<path>` URL as connection settings, so a path containing `?` would open another file (and could set
   * pragmas); in a URI the path is percent-encoded - `?` as `%3F`, a space as `%20` - and SQLite decodes it.
   */
  private[sqlite] def jdbcUrl(path: Path): String = s"jdbc:sqlite:${path.toAbsolutePath.toUri.toASCIIString}"

  /**
   * Per-connection settings, and WAL, which is a property of the file and persists once set.
   *
   * Switching the journal to WAL is not a transaction, so SQLite does not wait for the busy timeout when another
   * connection holds the file - typically another store opening the same new file at the same moment - but fails at
   * once with `SQLITE_BUSY`. The switch is therefore retried, with a short pause, until `deadline` (the busy timeout
   * from the start of the open), as a transaction would have waited.
   */
  private def prepare(
    connection: Connection,
    config: SqliteCheckpointerConfig,
    deadline: Long,
    onBusy: () => Unit
  ): Unit =
    execute(connection, s"PRAGMA busy_timeout = ${DurationRounding.ceilMillisInt(config.busyTimeout)}")
    val mode = retryingBusy(deadline, onBusy) {
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("PRAGMA journal_mode = WAL")) { rows =>
          if rows.next() then rows.getString(1) else ""
        }
      }
    }
    require(mode.equalsIgnoreCase("wal"), s"the database's journal could not be switched to WAL (it is '$mode')")
    execute(connection, "PRAGMA synchronous = FULL")

  private def storeBusy(busyTimeout: FiniteDuration): String =
    s"the store was busy: its earlier calls held its connection for the whole busy timeout ($busyTimeout)"

  private def gateTimedOut(busyTimeout: FiniteDuration): String =
    s"the database file is busy: other stores in this process were writing it for the whole busy timeout ($busyTimeout)"

  /** `SQLITE_BUSY`: the low byte of the (possibly extended) result code sqlite-jdbc reports as the error code. */
  private val SqliteBusy = 5

  private def isBusy(e: Throwable): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(8).exists {
      case sql: java.sql.SQLException => (sql.getErrorCode & 0xff) == SqliteBusy
      case _                          => false
    }

  /**
   * `body`, run again after a short pause while it fails with `SQLITE_BUSY` and `deadline` (a `System.nanoTime`) has
   * not passed; any other failure, or a busy one past the deadline, is thrown as it was.
   */
  @scala.annotation.tailrec
  private def retryingBusy[A](deadline: Long, onBusy: () => Unit, pauseMillis: Long = 1)(body: => A): A =
    Try(body) match
      case scala.util.Success(value) => value
      case scala.util.Failure(e) if isBusy(e) && System.nanoTime() < deadline =>
        onBusy()
        Thread.sleep(pauseMillis + java.util.concurrent.ThreadLocalRandom.current().nextLong(pauseMillis + 1))
        retryingBusy(deadline, onBusy, math.min(pauseMillis * 2, 25L))(body)
      case scala.util.Failure(e) => throw e

  // ---- rows ----

  /**
   * A stored claim. `tenant` is the tenant it was requested for, or `None` when the row does not record it: a claim
   * a version-4 file held when it was migrated (see [[SqliteSchema]]). Such a claim is of no tenant: its holder is
   * never named.
   */
  final private case class Claim(holder: String, tenant: Option[Option[String]], token: Long, expiresAt: Instant):
    def of(tenantId: Option[TenantId]): Boolean = tenant.contains(tenantId.map(_.value))

  final private case class Row(
    checkpointId: Option[String],
    nextSeq: Long,
    earliestSeq: Long,
    firstSeq: Long,
    claim: Option[Claim]
  )

  final private[sqlite] case class ErrorContext(path: Path)

  private[sqlite] def failure(operation: String, message: String, cause: Option[Throwable])(using
    context: ErrorContext
  ): LLMError =
    ProcessingError(s"sqlite-checkpointer.$operation", s"$message (${context.path})", cause)

  private def undecodable(operation: String, what: String, problem: String, cause: Option[Throwable])(using
    ErrorContext
  ): LLMError =
    failure(operation, s"a stored $what does not decode: $problem", cause)

  /** `body`, decoding stored rows; a row that does not decode is a `ProcessingError` naming the operation and file. */
  private[sqlite] def decoding[A](operation: String, what: String)(body: => A)(using ErrorContext): Result[A] =
    Try(body).toEither.left.map(e =>
      undecodable(operation, what, Option(e.getMessage).getOrElse(e.getClass.getName), Some(e))
    )

  // a granted or renewed claim always records its tenant; the replaced one is named only if it is of that tenant
  private def granted(threadId: ThreadId, claim: Claim, replaced: Option[Claim] = None): RunClaim =
    val tenant = claim.tenant.flatten.map(TenantId(_))
    RunClaim(
      threadId,
      RunId(claim.holder),
      tenant,
      FencingToken(claim.token),
      claim.expiresAt,
      replaced.filter(r => claim.tenant.isDefined && r.of(tenant)).map(r => RunId(r.holder))
    )

  private def stale(threadId: ThreadId, current: Option[Row], token: FencingToken): GraphError =
    GraphError.StaleClaim(threadId.value, token, current.flatMap(_.claim).map(c => FencingToken(c.token)))

  private def readRow(c: Connection, threadId: ThreadId): Option[Row] =
    query(
      c,
      """SELECT checkpoint_id, next_seq, earliest_seq, first_seq, claim_holder, claim_token, claim_expires_second,
        |claim_expires_nano, claim_tenant, claim_tenant_token FROM llm4s_checkpoint_threads WHERE thread_id = ?""".stripMargin,
      threadId.value
    ) { rows =>
      val token = rows.getLong(6)
      val claim = Option.when(!rows.wasNull()) {
        val tenant      = Option(rows.getString(9))
        val tenantToken = rows.getLong(10)
        // the tenant is the claim's only if it was recorded with this claim's token: a row migrated from version 4
        // has none, and a build without the column that claimed since would have left another claim's behind
        val recorded = Option.when(!rows.wasNull() && tenantToken == token)(tenant)
        Claim(rows.getString(5), recorded, token, Instant.ofEpochSecond(rows.getLong(7), rows.getLong(8)))
      }
      Row(Option(rows.getString(1)), rows.getLong(2), rows.getLong(3), rows.getLong(4), claim)
    }.headOption

  private def ensureRow(c: Connection, threadId: ThreadId): Unit =
    update(
      c,
      "INSERT OR IGNORE INTO llm4s_checkpoint_threads (thread_id, next_seq, earliest_seq) VALUES (?, 1, 1)",
      threadId.value
    )

  private def setClaim(c: Connection, threadId: ThreadId, claim: Option[Claim]): Unit =
    update(
      c,
      """UPDATE llm4s_checkpoint_threads
        |SET claim_holder = ?, claim_token = ?, claim_expires_second = ?, claim_expires_nano = ?,
        |    claim_tenant = ?, claim_tenant_token = ?
        |WHERE thread_id = ?""".stripMargin,
      claim.map(_.holder),
      claim.map(_.token),
      claim.map(_.expiresAt.getEpochSecond),
      claim.map(_.expiresAt.getNano.toLong),
      claim.flatMap(_.tenant).flatten,
      claim.filter(_.tenant.isDefined).map(_.token),
      threadId.value
    )

  /** Removes the thread's row once it holds nothing a new thread of its id would not: as `InMemoryCheckpointer`. */
  private def dropIfEmpty(c: Connection, threadId: ThreadId): Unit =
    update(
      c,
      """DELETE FROM llm4s_checkpoint_threads
        |WHERE thread_id = ? AND checkpoint_id IS NULL AND claim_token IS NULL AND next_seq = 1 AND metadata_json IS NULL
        |  AND NOT EXISTS (SELECT 1 FROM llm4s_checkpoint_effects e WHERE e.thread_id = llm4s_checkpoint_threads.thread_id)""".stripMargin,
      threadId.value
    )

  // ---- the thread registry (schema 8) ----

  /** The status the registry stores for a thread whose latest checkpoint is `checkpoint`: `Deleted` once deleting. */
  private def registryStatus(checkpoint: Checkpoint): String =
    if checkpoint.deleting then ThreadStatus.Deleted.toString else checkpoint.status.toString

  /** An instant as the registry orders by it: nanoseconds since the epoch, which fit a `Long` until 2262. */
  private[sqlite] def epochNanos(at: Instant): Long =
    Math.addExact(Math.multiplyExact(at.getEpochSecond, 1000000000L), at.getNano.toLong)

  private[sqlite] def fromEpochNanos(nanos: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(nanos, 1000000000L), Math.floorMod(nanos, 1000000000L))

  /** Replaces the thread's metadata, inside the caller's write transaction: as JSON on its row, and by entry. */
  private def writeMetadata(c: Connection, threadId: ThreadId, metadata: ThreadMetadata, at: Instant): Unit =
    update(c, "DELETE FROM llm4s_checkpoint_thread_metadata WHERE thread_id = ?", threadId.value)
    metadata.entries.foreach { (key, value) =>
      update(
        c,
        "INSERT INTO llm4s_checkpoint_thread_metadata (thread_id, key, value) VALUES (?, ?, ?)",
        threadId.value,
        key,
        value
      )
    }
    // the commit's claim gives the thread a row; empty metadata is kept as none, so an emptied row can go
    update(
      c,
      "UPDATE llm4s_checkpoint_threads SET metadata_json = ?, updated_nanos = ? WHERE thread_id = ?",
      Option.unless(metadata.isEmpty)(ujson.write(metadata.toJson)),
      epochNanos(at),
      threadId.value
    )

  /** The statement and parameters of a page of `query`, its claims judged at `now`. */
  private[sqlite] def threadsStatement(query: ThreadQuery, now: Instant): (String, Vector[Any]) =
    registryStatement(query.tenantId, newestFirst = true, query.after, query.limit, now) { where =>
      query.status.foreach(statusFilter(where, _, now))
      query.agent.foreach(agent => where.add("graph_id = ?", agent))
      query.updatedSince.foreach(since => where.add("updated_nanos >= ?", epochNanos(since)))
      query.metadata.entries.toVector.sortBy(_._1).foreach { (key, value) =>
        where.add(
          "thread_id IN (SELECT thread_id FROM llm4s_checkpoint_thread_metadata WHERE key = ? AND value = ?)",
          key,
          value
        )
      }
    }

  /** The statement and parameters of a page of `tenantId`'s incomplete threads, their claims judged at `now`. */
  private[sqlite] def incompleteStatement(
    tenantId: Option[TenantId],
    after: Option[ThreadCursor],
    limit: Int,
    now: Instant
  ): (String, Vector[Any]) =
    registryStatement(tenantId, newestFirst = false, after, limit, now)(statusFilter(_, ThreadStatus.Idle, now))

  /**
   * One page of the registry, and one row more to tell whether another follows: the root threads of `tenantId` with a
   * checkpoint, past `after` in the page's order, narrowed by the conditions `filters` adds, through the registry's
   * partial indexes (schema 8). Each row says whether a live claim holds its thread at `now`.
   */
  private def registryStatement(
    tenantId: Option[TenantId],
    newestFirst: Boolean,
    after: Option[ThreadCursor],
    limit: Int,
    now: Instant
  )(filters: Conditions => Unit): (String, Vector[Any]) =
    val where = Conditions()
    // the partial indexes' own predicate, so every one of them serves the statement
    where.add("parent_thread_id IS NULL")
    where.add("thread_status IS NOT NULL")
    tenantId.fold(where.add("tenant_id IS NULL"))(t => where.add("tenant_id = ?", t.value))
    filters(where)
    val (order, direction) = if newestFirst then ("DESC", "<") else ("ASC", ">")
    after.foreach { cursor =>
      where.add(s"(updated_nanos, thread_id) $direction (?, ?)", epochNanos(cursor.updatedAt), cursor.threadId.value)
    }
    val sql =
      s"""SELECT thread_id, tenant_id, graph_id, thread_status, checkpoint_id, metadata_json, updated_nanos, $LiveClaim
         |FROM llm4s_checkpoint_threads WHERE ${where.sql}
         |ORDER BY updated_nanos $order, thread_id $order LIMIT ?""".stripMargin
    sql -> ((liveAt(now) ++ where.params) :+ (limit + 1))

  /** Whether a live claim holds the thread - its expiry after the time bound by [[liveAt]] - as an SQL condition. */
  private val LiveClaim: String =
    "(claim_token IS NOT NULL AND (claim_expires_second > ? OR (claim_expires_second = ? AND claim_expires_nano > ?)))"

  private def liveAt(now: Instant): Vector[Any] = Vector(now.getEpochSecond, now.getEpochSecond, now.getNano.toLong)

  /**
   * The condition a status filter adds: the stored status (`Deleted` while a cascade delete is under way) and whether
   * a live claim holds the thread at `now`, as `ThreadStatus.of` decides.
   */
  private def statusFilter(where: Conditions, status: ThreadStatus, now: Instant): Unit =
    status match
      case ThreadStatus.Deleted => where.add("thread_status = ?", ThreadStatus.Deleted.toString)
      case ThreadStatus.Running =>
        // through the partial index of claimed threads
        where.add("claim_token IS NOT NULL")
        where.add(s"thread_status <> ? AND $LiveClaim", (ThreadStatus.Deleted.toString +: liveAt(now))*)
      case ThreadStatus.Idle =>
        where.add(s"thread_status = ? AND NOT $LiveClaim", (CheckpointStatus.Running.toString +: liveAt(now))*)
      case other => where.add(s"thread_status = ? AND NOT $LiveClaim", (other.toString +: liveAt(now))*)

  /** A `WHERE` clause built a condition at a time, with its parameters in order. */
  final private class Conditions:
    private val clauses = Vector.newBuilder[String]
    private val values  = Vector.newBuilder[Any]
    def add(clause: String, params: Any*): Unit =
      clauses += clause
      values ++= params
    def sql: String         = clauses.result().mkString(" AND ")
    def params: Vector[Any] = values.result()

  /** A registry row as read: `claimed`, whether a live claim held the thread by the store's clock when it was read. */
  final private case class RegistryRow(
    threadId: String,
    tenant: Option[String],
    graphId: String,
    status: String,
    checkpointId: String,
    metadata: Option[String],
    updatedNanos: Long,
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
        fromEpochNanos(row.updatedNanos)
      )
    }).map(ThreadPage.of(_, limit))

  /** A page of the history, newest first: a backwards range of the `(thread, position)` primary key. */
  private[sqlite] val HistoryPageSql: String =
    """SELECT checkpoint_json FROM llm4s_checkpoint_history
      |WHERE thread_id = ? AND position < ? ORDER BY position DESC LIMIT ?""".stripMargin

  /** A checkpoint by id, through the `(thread, checkpoint id)` unique index. */
  private[sqlite] val CheckpointByIdSql: String =
    "SELECT checkpoint_json FROM llm4s_checkpoint_history WHERE thread_id = ? AND checkpoint_id = ?"

  /** A checkpoint's position by id, through the `(thread, checkpoint id)` unique index. */
  private[sqlite] val PositionByIdSql: String =
    "SELECT position FROM llm4s_checkpoint_history WHERE thread_id = ? AND checkpoint_id = ?"

  /**
   * Whether run `runId` is an attempt recorded before a delete of the thread while its entry was open: fenced from
   * the id until the entry is reclaimed, open or closed, since its worker may still hold a claim taken before the
   * close (design 4.55). By the attempts' `(thread_id, run_id)` index.
   */
  private def orphanedAttempt(c: Connection, threadId: ThreadId, runId: String): Boolean =
    query(
      c,
      """SELECT 1 FROM llm4s_checkpoint_queue_attempts a
        |JOIN llm4s_checkpoint_queue_entries e ON e.entry_id = a.entry_id
        |WHERE a.thread_id = ? AND a.run_id = ? AND e.thread_deleted = 1 LIMIT 1""".stripMargin,
      threadId.value,
      runId
    )(_ => ()).nonEmpty

  /**
   * Marks every open entry that recorded run `runId` as an attempt on the thread admitted: the run committed a
   * checkpoint, and the mark outlives any retraction of it (design 4.55). In the commit's own transaction.
   */
  private def admitAttempt(c: Connection, threadId: ThreadId, runId: String): Unit =
    update(
      c,
      """UPDATE llm4s_checkpoint_queue_entries SET admitted = 1
        |WHERE state = 'open' AND admitted = 0 AND entry_id IN (
        |  SELECT entry_id FROM llm4s_checkpoint_queue_attempts WHERE thread_id = ? AND run_id = ?)""".stripMargin,
      threadId.value,
      runId
    )

  private def inHistory(c: Connection, threadId: ThreadId, id: String): Boolean =
    positionOf(c, threadId, id).isDefined

  /** The position of checkpoint `id` in the thread's history; by the `(thread, checkpoint id)` index. */
  private def positionOf(c: Connection, threadId: ThreadId, id: String): Option[Long] =
    query(c, PositionByIdSql, threadId.value, id)(_.getLong(1)).headOption

  /** A stored checkpoint decoded: a format this build cannot read is the contract's refusal, anything else a bad row. */
  private def decodeCheckpoint(operation: String, json: String)(using ErrorContext): Result[Checkpoint] =
    decoding(operation, "checkpoint")(ujson.read(json)).flatMap { parsed =>
      Checkpoint.fromJson(parsed).left.map {
        case refusal: GraphError => refusal
        case other               => undecodable(operation, "checkpoint", other.message, None)
      }
    }

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

  /**
   * Records the parent `checkpoint`'s origin names, inside the caller's write transaction: the thread keeps its place
   * among that parent's children while the parent stays the same, takes a place past every other under a new one,
   * and has none as a top-level thread.
   */
  private def placeChild(c: Connection, threadId: ThreadId, checkpoint: Checkpoint, fresh: Boolean): Unit =
    val parent = checkpoint.origin.map(_.parent.value)
    val current =
      query(c, "SELECT parent_thread_id FROM llm4s_checkpoint_threads WHERE thread_id = ?", threadId.value)(rows =>
        Option(rows.getString(1))
      ).headOption.flatten
    // a new thread under the id takes a new place: its tombstone's was only a paging cursor
    if current != parent || fresh then
      // one past the file's highest place: a tombstone keeps its place, so places only grow while children are kept
      val order = parent.map { _ =>
        query(c, "SELECT COALESCE(MAX(child_order), 0) + 1 FROM llm4s_checkpoint_threads")(_.getLong(1)).head
      }
      update(
        c,
        "UPDATE llm4s_checkpoint_threads SET parent_thread_id = ?, child_order = ? WHERE thread_id = ?",
        parent,
        order,
        threadId.value
      )

  /** The next fencing token for the whole file; inside the caller's write transaction. */
  private def nextToken(c: Connection): Long =
    update(c, "UPDATE llm4s_checkpoint_meta SET value = value + 1 WHERE key = 'last_token'")
    query(c, "SELECT value FROM llm4s_checkpoint_meta WHERE key = 'last_token'")(_.getLong(1)).head

  private def appendWrites(c: Connection, threadId: ThreadId, writes: Vector[PendingWrite]): Unit =
    if writes.nonEmpty then
      val first = query(
        c,
        "SELECT COALESCE(MAX(position) + 1, 0) FROM llm4s_checkpoint_pending_writes WHERE thread_id = ?",
        threadId.value
      )(_.getLong(1)).head
      Using.resource(
        c.prepareStatement(
          "INSERT INTO llm4s_checkpoint_pending_writes (thread_id, position, checkpoint_id, write_json) VALUES (?, ?, ?, ?)"
        )
      ) { insert =>
        writes.zipWithIndex.foreach { (write, i) =>
          bind(insert, threadId.value, first + i, write.checkpointId, upickle.default.write(write))
          insert.executeUpdate(): Unit
        }
      }

  // ---- JDBC ----

  private[sqlite] def bind(statement: PreparedStatement, params: Any*): Unit =
    params.zipWithIndex.foreach { (param, i) =>
      param match
        case None        => statement.setObject(i + 1, null)
        case Some(value) => bind1(statement, i + 1, value)
        case value       => bind1(statement, i + 1, value)
    }

  private def bind1(statement: PreparedStatement, index: Int, value: Any): Unit = value match
    case s: String => statement.setString(index, s)
    case l: Long   => statement.setLong(index, l)
    case i: Int    => statement.setInt(index, i)
    case other     => statement.setObject(index, other)

  private[sqlite] def update(c: Connection, sql: String, params: Any*): Unit =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, params*)
      statement.executeUpdate(): Unit
    }

  private[sqlite] def query[A](c: Connection, sql: String, params: Any*)(read: ResultSet => A): Vector[A] =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, params*)
      Using.resource(statement.executeQuery()) { rows =>
        Iterator.continually(rows).takeWhile(_.next()).map(read).toVector
      }
    }

  private def execute(c: Connection, sql: String): Unit =
    Using.resource(c.createStatement())(_.execute(sql): Unit)

  /**
   * Begins a transaction with `begin` and runs `body`, which commits it and sets `committed`; if `body` returns
   * without committing, or throws, the transaction is rolled back. Plain SQL on an autocommit connection, rather
   * than `setAutoCommit(false)`: sqlite-jdbc then begins the next transaction straight after each commit, so a
   * `BEGIN` that timed out on a busy file would leave the connection believing a transaction was open.
   */
  private def transaction[A](c: Connection, begin: String)(body: RollbackUnlessCommitted => A): A =
    execute(c, begin)
    Using.resource(RollbackUnlessCommitted(c))(body)

  final private class RollbackUnlessCommitted(c: Connection) extends AutoCloseable:
    var committed = false
    def close(): Unit =
      if !committed then Try(execute(c, "ROLLBACK")): Unit

  final private class Unlock(lock: ReentrantLock) extends AutoCloseable:
    def close(): Unit = lock.unlock()
