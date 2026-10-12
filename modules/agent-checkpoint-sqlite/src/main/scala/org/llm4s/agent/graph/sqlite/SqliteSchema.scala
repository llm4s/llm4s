package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.Checkpoint

import java.sql.Connection
import scala.util.Using

/**
 * The tables a [[SqliteCheckpointer]] keeps, and their migrations. Every table is prefixed `llm4s_checkpoint_`, so
 * the store can share a database file with other tables.
 *
 *  - `llm4s_checkpoint_schema`: one row, the schema version the file is at.
 *  - `llm4s_checkpoint_meta`: the last fencing token issued, for the whole file. It survives a restart and
 *    `deleteThread`, so a token is never issued twice.
 *  - `llm4s_checkpoint_threads`: one row per thread that has a checkpoint, a claim, or an event number spent - the
 *    latest checkpoint as JSON with its id (the thread's version), the next event number and the replay floor,
 *    and the claim (holder, token, expiry as epoch seconds and nanoseconds).
 *  - `llm4s_checkpoint_history`: every checkpoint of every thread's history, latest included, as JSON, keyed by
 *    `(thread, position)` - the position counts up along the thread, so paging newest first reads the primary key
 *    backwards - and unique by `(thread, checkpoint id)`, so a read by id and the duplicate-id check use that index.
 *    The checkpoint's status and creation time sit beside it for retraction and retention.
 *  - `llm4s_checkpoint_pending_writes`: the latest checkpoint's pending writes as JSON, in commit order.
 *  - `llm4s_checkpoint_events`: the durable event log, one JSON `EventRecord` per `(thread, seq)`.
 *
 * Opening a store brings the file to [[Current]] in one transaction, applying each step `n -> n + 1` in turn, and
 * refuses a file whose version is newer than this build knows. Version 0 is a file with none of these tables.
 */
private[sqlite] object SqliteSchema:

  /** The schema version this build writes. */
  val Current: Int = 1

  /** A migration from the version it is keyed by to the next. */
  type Step = Connection => Unit

  /** The steps, keyed by the version each upgrades from. */
  val steps: Map[Int, Step] = Map(0 -> createTables)

  private def createTables(connection: Connection): Unit =
    statements(
      connection,
      """CREATE TABLE llm4s_checkpoint_meta (
        |  key   TEXT PRIMARY KEY,
        |  value INTEGER NOT NULL
        |)""".stripMargin,
      "INSERT INTO llm4s_checkpoint_meta (key, value) VALUES ('last_token', 0)",
      """CREATE TABLE llm4s_checkpoint_threads (
        |  thread_id            TEXT PRIMARY KEY,
        |  checkpoint_id        TEXT,
        |  checkpoint_json      TEXT,
        |  next_seq             INTEGER NOT NULL,
        |  earliest_seq         INTEGER NOT NULL,
        |  claim_holder         TEXT,
        |  claim_token          INTEGER,
        |  claim_expires_second INTEGER,
        |  claim_expires_nano   INTEGER
        |)""".stripMargin,
      historyTable,
      """CREATE TABLE llm4s_checkpoint_pending_writes (
        |  thread_id     TEXT NOT NULL,
        |  position      INTEGER NOT NULL,
        |  checkpoint_id TEXT NOT NULL,
        |  write_json    TEXT NOT NULL,
        |  PRIMARY KEY (thread_id, position)
        |) WITHOUT ROWID""".stripMargin,
      """CREATE TABLE llm4s_checkpoint_events (
        |  thread_id   TEXT NOT NULL,
        |  seq         INTEGER NOT NULL,
        |  record_json TEXT NOT NULL,
        |  PRIMARY KEY (thread_id, seq)
        |) WITHOUT ROWID""".stripMargin
    )

  /**
   * The history table (#1702). Part of version 1 from the start, but a file a pre-release build of version 1 wrote
   * lacks it, so opening a version-1 file also creates it if it is missing ([[ensureHistory]]). `IF NOT EXISTS`, so
   * the statement is safe on every file; its `UNIQUE (thread_id, checkpoint_id)` is the by-id index.
   */
  private val historyTable: String =
    """CREATE TABLE IF NOT EXISTS llm4s_checkpoint_history (
      |  thread_id       TEXT NOT NULL,
      |  position        INTEGER NOT NULL,
      |  checkpoint_id   TEXT NOT NULL,
      |  status          TEXT NOT NULL,
      |  created_second  INTEGER NOT NULL,
      |  created_nano    INTEGER NOT NULL,
      |  checkpoint_json TEXT NOT NULL,
      |  PRIMARY KEY (thread_id, position),
      |  UNIQUE (thread_id, checkpoint_id)
      |) WITHOUT ROWID""".stripMargin

  private def tableExists(connection: Connection, name: String): Boolean =
    Using.resource(connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      query =>
        query.setString(1, name)
        Using.resource(query.executeQuery())(_.next())
    }

  /**
   * Creates the history table on a version-1 file that lacks it - one written by a pre-release build - and seeds it
   * with each thread's latest checkpoint, so `history` and `checkpoint` see what `latest` sees. Call inside the
   * opening transaction; a file that has the table is left as it is.
   */
  private[sqlite] def ensureHistory(connection: Connection): Unit =
    if tableExists(connection, "llm4s_checkpoint_threads") && !tableExists(connection, "llm4s_checkpoint_history") then
      statements(connection, historyTable)
      val latest = Using.resource(connection.createStatement()) { statement =>
        Using.resource(
          statement.executeQuery(
            "SELECT thread_id, checkpoint_json FROM llm4s_checkpoint_threads WHERE checkpoint_json IS NOT NULL"
          )
        )(rows => Iterator.continually(rows).takeWhile(_.next()).map(r => r.getString(1) -> r.getString(2)).toVector)
      }
      Using.resource(
        connection.prepareStatement(
          """INSERT INTO llm4s_checkpoint_history
            |(thread_id, position, checkpoint_id, status, created_second, created_nano, checkpoint_json)
            |VALUES (?, 0, ?, ?, ?, ?, ?)""".stripMargin
        )
      ) { insert =>
        latest.foreach { (thread, json) =>
          // a checkpoint this build cannot decode keeps no history row; `latest` reports it as before
          Checkpoint.fromJson(ujson.read(json)).foreach { checkpoint =>
            insert.setString(1, thread)
            insert.setString(2, checkpoint.id)
            insert.setString(3, checkpoint.status.toString)
            insert.setLong(4, checkpoint.createdAt.getEpochSecond)
            insert.setLong(5, checkpoint.createdAt.getNano.toLong)
            insert.setString(6, json)
            insert.executeUpdate(): Unit
          }
        }
      }

  /** The version the file is at; 0 for a file without the schema table. Call inside a transaction. */
  def version(connection: Connection): Int =
    statements(connection, "CREATE TABLE IF NOT EXISTS llm4s_checkpoint_schema (version INTEGER NOT NULL)")
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery("SELECT MAX(version) FROM llm4s_checkpoint_schema")) { rows =>
        if rows.next() then rows.getInt(1) else 0
      }
    }

  /**
   * Brings the file from its version to `target` with `steps`, inside the caller's transaction, and returns the
   * version it was at; `Left` naming both versions, with nothing changed, if the file is newer than `target` or a
   * step is missing.
   */
  def migrate(connection: Connection, target: Int = Current, steps: Map[Int, Step] = steps): Either[String, Int] =
    val found = version(connection)
    if found > target then
      Left(s"the database is at checkpoint schema version $found, newer than the $target this build supports")
    else
      (found until target).find(v => !steps.contains(v)) match
        case Some(missing) => Left(s"no migration from checkpoint schema version $missing to ${missing + 1}")
        case None =>
          (found until target).foreach(v => steps(v)(connection))
          if target >= 1 then ensureHistory(connection)
          if found != target then
            statements(connection, "DELETE FROM llm4s_checkpoint_schema")
            Using.resource(connection.prepareStatement("INSERT INTO llm4s_checkpoint_schema (version) VALUES (?)")) {
              insert =>
                insert.setInt(1, target)
                insert.executeUpdate(): Unit
            }
          Right(found)

  private def statements(connection: Connection, sql: String*): Unit =
    Using.resource(connection.createStatement())(statement => sql.foreach(statement.executeUpdate(_): Unit))
