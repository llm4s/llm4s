package org.llm4s.agent.graph.sqlite

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
