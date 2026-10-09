package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError }
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding

import java.nio.file.Path
import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet }
import java.time.{ Clock, Instant }
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Try, Using }

/**
 * A durable [[org.llm4s.agent.graph.Checkpointer]] in one SQLite database file: threads, their latest checkpoints,
 * pending writes, event logs and run claims survive a restart, and several runtimes - in this process or in
 * others on the same host - can share the file, each through a store of its own.
 *
 * Open it with [[SqliteCheckpointer.open]] and close it with [[close]]. It meets the `Checkpointer` contract (and
 * passes `CheckpointerContract` from `llm4s-agent-testkit`):
 *
 *  - '''Atomic, fenced commits.''' Every call that writes is one `BEGIN IMMEDIATE` transaction: a `Commit`'s
 *    checkpoint, pending writes and events land together or not at all, and the claim's token and the thread's
 *    version are checked inside the same transaction, so two stores over one file cannot both pass the checks.
 *  - '''Claims expire by `clock`''', the clock this store was opened with: the clock of the process that grants or
 *    renews a claim decides when it lapses. Stores in processes on different hosts sharing one file therefore
 *    judge expiry by different clocks, and skew between them changes a claim's effective `ttl` (see
 *    `docs/guide/agents/durable-checkpointers.md`); fencing keeps writes safe regardless.
 *  - '''Fencing tokens''' come from one counter for the whole file, stored in it, so they keep increasing across
 *    restarts and [[deleteThread]], and are never issued twice.
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
 * One store is one connection, and its calls are serialised: it is safe to call from any number of threads.
 * Every failure of the database itself is a `ProcessingError`, and so is any call after [[close]].
 */
final class SqliteCheckpointer private (val path: Path, clock: Clock, connection: Connection)
    extends Checkpointer
    with AutoCloseable:

  import SqliteCheckpointer.*

  private val lock           = new ReentrantLock()
  @volatile private var shut = false
  private given ErrorContext = ErrorContext(path)

  // ---- claims ----

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = writing("claim") { c =>
    val current = readRow(c, threadId)
    val now     = clock.instant()
    current.flatMap(_.claim).filter(live => now.isBefore(live.expiresAt)) match
      case Some(live) =>
        Left(GraphError.ThreadBusy(threadId.value, current.flatMap(_.checkpointId), Some(live.holder)))
      case None =>
        val claim = Claim(request.holder.value, nextToken(c), now.plusNanos(request.ttl.toNanos))
        ensureRow(c, threadId)
        setClaim(c, threadId, Some(claim))
        Right(granted(threadId, claim))
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
    val fenced = Option.when(!current.flatMap(_.claim).exists(_.token == commit.token.value))(
      stale(threadId, current, commit.token)
    )
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    val resultingId = commit.checkpoint.map(_.id).orElse(latestId)
    val misdirected = commit.pendingWrites.find(w => !resultingId.contains(w.checkpointId)).map { write =>
      GraphError.InvalidCommit(
        threadId.value,
        s"pending write for task ${write.taskId} names checkpoint ${write.checkpointId}, not ${resultingId.getOrElse("<none>")}"
      )
    }
    fenced.orElse(conflict).orElse(misdirected).toLeft(()).map { _ =>
      // a thread with a claim always has a row
      val nextSeq = current.fold(1L)(_.nextSeq)
      commit.checkpoint.foreach { checkpoint =>
        update(
          c,
          "UPDATE llm4s_checkpoint_threads SET checkpoint_id = ?, checkpoint_json = ? WHERE thread_id = ?",
          checkpoint.id,
          ujson.write(Checkpoint.toJson(checkpoint)),
          threadId.value
        )
        update(c, "DELETE FROM llm4s_checkpoint_pending_writes WHERE thread_id = ?", threadId.value)
      }
      appendWrites(c, threadId, commit.pendingWrites)
      val stored = commit.events.zipWithIndex.map { (draft, i) =>
        val seq = nextSeq + i
        seq -> upickle.default.write(EventRecord.committed(threadId.value, seq, draft))
      }
      Using.resource(
        c.prepareStatement(
          "INSERT INTO llm4s_checkpoint_events (thread_id, seq, record_json) VALUES (?, ?, ?)"
        )
      ) { insert =>
        stored.foreach { (seq, json) =>
          bind(insert, threadId.value, seq, json)
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
          parsed <- decoding("latest", "checkpoint")(ujson.read(json))
          // a format this build cannot read is the contract's refusal; anything else is a row that does not decode
          checkpoint <- Checkpoint.fromJson(parsed).left.map {
            case refusal: GraphError => refusal
            case other               => undecodable("latest", "checkpoint", other.message, None)
          }
          decoded <- decoding("latest", "pending write")(writes.map(upickle.default.read[PendingWrite](_)))
        yield Some(StoredCheckpoint(checkpoint, decoded))
    }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    reading("eventsAfter") { c =>
      val earliest = readRow(c, threadId).fold(1L)(_.earliestSeq)
      if afterSeq + 1 < earliest then Left(GraphError.ReplayUnavailable(threadId.value, earliest))
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

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = writing("compactEvents") { c =>
    readRow(c, threadId).foreach { current =>
      val floor = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
      update(c, "DELETE FROM llm4s_checkpoint_events WHERE thread_id = ? AND seq < ?", threadId.value, floor)
      update(c, "UPDATE llm4s_checkpoint_threads SET earliest_seq = ? WHERE thread_id = ?", floor, threadId.value)
    }
    Right(())
  }

  def deleteThread(threadId: ThreadId): Result[Unit] = writing("deleteThread") { c =>
    update(c, "DELETE FROM llm4s_checkpoint_events WHERE thread_id = ?", threadId.value)
    update(c, "DELETE FROM llm4s_checkpoint_pending_writes WHERE thread_id = ?", threadId.value)
    update(c, "DELETE FROM llm4s_checkpoint_threads WHERE thread_id = ?", threadId.value)
    Right(())
  }

  /**
   * Closes the connection. Later calls return a `ProcessingError`; closing again does nothing. A claim this store
   * granted stays in the file until it is released through another store or expires.
   */
  def close(): Unit = locked {
    if !shut then
      shut = true
      connection.close()
  }

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = shut

  // ---- transactions ----

  private def locked[A](body: => A): A =
    lock.lock()
    Using.resource(Unlock(lock))(_ => body)

  /** `body` on the connection, serialised with every other call; any exception is a `ProcessingError`. */
  private def guarded[A](operation: String)(body: Connection => Result[A]): Result[A] = locked {
    if shut then Left(failure(operation, "the store is closed", None))
    else
      Try(body(connection)).toEither.left
        .map(e => failure(operation, Option(e.getMessage).getOrElse(e.getClass.getName), Some(e)))
        .flatten
  }

  /**
   * `body` in one `BEGIN IMMEDIATE` transaction, committed if it returns `Right` and rolled back otherwise - also
   * when it throws. `IMMEDIATE` takes the write lock before the first read, so the checks a write makes and the
   * write itself see the same state, and a busy file is waited for (up to the busy timeout) before anything is
   * read: a deferred transaction that read first could not wait for the lock.
   */
  private def writing[A](operation: String)(body: Connection => Result[A]): Result[A] =
    guarded(operation) { c =>
      transaction(c, "BEGIN IMMEDIATE") { guard =>
        val result = body(c)
        if result.isRight then
          execute(c, "COMMIT")
          guard.committed = true
        result
      }
    }

  /** `body` in one read transaction, so the rows it reads are from one snapshot of the file. */
  private def reading[A](operation: String)(body: Connection => Result[A]): Result[A] =
    guarded(operation) { c =>
      transaction(c, "BEGIN DEFERRED") { guard =>
        val result = body(c)
        execute(c, "COMMIT")
        guard.committed = true
        result
      }
    }

object SqliteCheckpointer:

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

  /** [[open]] on a connection from `connect`, so a test can see that a failed open closes what it opened. */
  private[sqlite] def openWith(
    path: Path,
    clock: Clock,
    config: SqliteCheckpointerConfig,
    connect: String => Connection
  ): Result[SqliteCheckpointer] =
    given ErrorContext = ErrorContext(path)
    Try {
      Class.forName("org.sqlite.JDBC")
      connect(jdbcUrl(path))
    }.toEither.left.map(e => failure("open", Option(e.getMessage).getOrElse(e.getClass.getName), Some(e))).flatMap {
      connection =>
        val deadline = System.nanoTime() + config.busyTimeout.toNanos
        // an interrupt while waiting out a busy file is not NonFatal, so `Try` would let it escape and leak the
        // connection: `attempt` returns it as a CancelledError, with the thread's interrupt flag set again
        val prepared = CancelledError
          .attempt("sqlite-checkpointer.open") {
            Try {
              prepare(connection, config, deadline)
              retryingBusy(deadline) {
                transaction(connection, "BEGIN IMMEDIATE") { guard =>
                  val migrated = SqliteSchema.migrate(connection)
                  if migrated.isRight then
                    execute(connection, "COMMIT")
                    guard.committed = true
                  migrated
                }
              }
            }.toEither.left
              .map(e => failure("open", Option(e.getMessage).getOrElse(e.getClass.getName), Some(e)))
              .flatMap(_.left.map(problem => failure("open", problem, None)))
          }
          .map(_ => new SqliteCheckpointer(path, clock, connection))
        // a connection that is not handed out must not leak: an open handle keeps the file locked
        if prepared.isLeft then Try(connection.close()): Unit
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
  private def prepare(connection: Connection, config: SqliteCheckpointerConfig, deadline: Long): Unit =
    execute(connection, s"PRAGMA busy_timeout = ${DurationRounding.ceilMillisInt(config.busyTimeout)}")
    val mode = retryingBusy(deadline) {
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("PRAGMA journal_mode = WAL")) { rows =>
          if rows.next() then rows.getString(1) else ""
        }
      }
    }
    require(mode.equalsIgnoreCase("wal"), s"the database's journal could not be switched to WAL (it is '$mode')")
    execute(connection, "PRAGMA synchronous = FULL")

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
  private def retryingBusy[A](deadline: Long, pauseMillis: Long = 1)(body: => A): A =
    Try(body) match
      case scala.util.Success(value) => value
      case scala.util.Failure(e) if isBusy(e) && System.nanoTime() < deadline =>
        Thread.sleep(pauseMillis + java.util.concurrent.ThreadLocalRandom.current().nextLong(pauseMillis + 1))
        retryingBusy(deadline, math.min(pauseMillis * 2, 25L))(body)
      case scala.util.Failure(e) => throw e

  // ---- rows ----

  final private case class Claim(holder: String, token: Long, expiresAt: Instant)

  final private case class Row(checkpointId: Option[String], nextSeq: Long, earliestSeq: Long, claim: Option[Claim])

  final private case class ErrorContext(path: Path)

  private def failure(operation: String, message: String, cause: Option[Throwable])(using
    context: ErrorContext
  ): LLMError =
    ProcessingError(s"sqlite-checkpointer.$operation", s"$message (${context.path})", cause)

  private def undecodable(operation: String, what: String, problem: String, cause: Option[Throwable])(using
    ErrorContext
  ): LLMError =
    failure(operation, s"a stored $what does not decode: $problem", cause)

  /** `body`, decoding stored rows; a row that does not decode is a `ProcessingError` naming the operation and file. */
  private def decoding[A](operation: String, what: String)(body: => A)(using ErrorContext): Result[A] =
    Try(body).toEither.left.map(e =>
      undecodable(operation, what, Option(e.getMessage).getOrElse(e.getClass.getName), Some(e))
    )

  private def granted(threadId: ThreadId, claim: Claim): RunClaim =
    RunClaim(threadId, RunId(claim.holder), FencingToken(claim.token), claim.expiresAt)

  private def stale(threadId: ThreadId, current: Option[Row], token: FencingToken): GraphError =
    GraphError.StaleClaim(threadId.value, token.value, current.flatMap(_.claim).map(_.token))

  private def readRow(c: Connection, threadId: ThreadId): Option[Row] =
    query(
      c,
      """SELECT checkpoint_id, next_seq, earliest_seq, claim_holder, claim_token, claim_expires_second, claim_expires_nano
        |FROM llm4s_checkpoint_threads WHERE thread_id = ?""".stripMargin,
      threadId.value
    ) { rows =>
      val token = rows.getLong(5)
      val claim = Option.when(!rows.wasNull()) {
        Claim(rows.getString(4), token, Instant.ofEpochSecond(rows.getLong(6), rows.getLong(7)))
      }
      Row(Option(rows.getString(1)), rows.getLong(2), rows.getLong(3), claim)
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
        |SET claim_holder = ?, claim_token = ?, claim_expires_second = ?, claim_expires_nano = ?
        |WHERE thread_id = ?""".stripMargin,
      claim.map(_.holder),
      claim.map(_.token),
      claim.map(_.expiresAt.getEpochSecond),
      claim.map(_.expiresAt.getNano.toLong),
      threadId.value
    )

  /** Removes the thread's row once it holds nothing a new thread of its id would not: as `InMemoryCheckpointer`. */
  private def dropIfEmpty(c: Connection, threadId: ThreadId): Unit =
    update(
      c,
      """DELETE FROM llm4s_checkpoint_threads
        |WHERE thread_id = ? AND checkpoint_id IS NULL AND claim_token IS NULL AND next_seq = 1""".stripMargin,
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

  private def bind(statement: PreparedStatement, params: Any*): Unit =
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

  private def update(c: Connection, sql: String, params: Any*): Unit =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, params*)
      statement.executeUpdate(): Unit
    }

  private def query[A](c: Connection, sql: String, params: Any*)(read: ResultSet => A): Vector[A] =
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
