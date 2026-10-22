package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.Checkpoint

import org.slf4j.LoggerFactory

import java.sql.Connection
import scala.util.{ Try, Using }

/**
 * The tables a [[SqliteCheckpointer]] keeps, and their migrations. Every table is prefixed `llm4s_checkpoint_`, so
 * the store can share a database file with other tables.
 *
 *  - `llm4s_checkpoint_schema`: one row, the schema version the file is at.
 *  - `llm4s_checkpoint_meta`: the last fencing token issued, for the whole file. It survives a restart and
 *    `deleteThread`, so a token is never issued twice. From version 9 also `reclaimed_first`, the first event number
 *    of a thread whose id has no row ([[addReclamation]]).
 *  - `llm4s_checkpoint_threads`: one row per thread that has a checkpoint, a claim, or an event number spent - the
 *    latest checkpoint as JSON with its id (the thread's version), the next event number and the replay floor,
 *    the first event number of the thread now under the id (`first_seq`, version 2), and the claim (holder, token,
 *    expiry as epoch seconds and nanoseconds, and from version 5 its tenant: `claim_tenant`, with
 *    `claim_tenant_token` the token of the claim it was recorded for, so the tenant is the claim's only when the two
 *    tokens agree), and from version 6 the parent the latest checkpoint's origin names (`parent_thread_id`) with the
 *    thread's place among that parent's children (`child_order`, one past the file's highest, which a tombstone keeps), indexed
 *    by `(parent_thread_id, child_order)`, and from version 8 the thread registry's columns ([[addThreadRegistry]]).
 *    `deleteThread` keeps the row of a thread that numbered any event,
 *    emptied, as a tombstone: `next_seq`, `first_seq` and the replay floor move one past the deleted thread's next
 *    number, so a new thread of the same id numbers its events past the deleted one's, and a read from inside the
 *    deleted log is told so (`GraphError.ThreadDeleted`). The tombstone holds no data of the deleted thread.
 *  - `llm4s_checkpoint_history`: every checkpoint of every thread's history, latest included, as JSON, keyed by
 *    `(thread, position)` - the position counts up along the thread, so paging newest first reads the primary key
 *    backwards - and unique by `(thread, checkpoint id)`, so a read by id and the duplicate-id check use that index.
 *    The checkpoint's status, creation time and (from version 7) commit time sit beside it for retraction and retention.
 *  - `llm4s_checkpoint_pending_writes`: the latest checkpoint's pending writes as JSON, in commit order.
 *  - `llm4s_checkpoint_events`: the durable event log, one JSON `EventRecord` per `(thread, seq)`, and from version 7
 *    when the store committed it, which `prune` judges age by.
 *  - `llm4s_checkpoint_effects` (version 3): the thread's effect records (design §4.24), one JSON `EffectRecord`
 *    per `(thread, effect key)`, with the task it belongs to - its id and, from version 4, its lineage - which
 *    decide when a commit releases it (`Commit.releasesEffect`), and the token of the commit that wrote it.
 *  - `llm4s_checkpoint_thread_metadata` (version 8): each thread's metadata (design 4.52), entry by entry, indexed by
 *    `(key, value)` for the registry's equality search; `deleteThread` removes a thread's.
 *  - the run queue (version 10, design 4.55, [[addRunQueue]]): `llm4s_checkpoint_queue_entries`, one row per entry,
 *    and `llm4s_checkpoint_queue_attempts`, one per attempt; and `run_id` on every history row, indexed by
 *    `(thread_id, run_id, position)` for the run-id lookup (`latestOfRuns`).
 *
 * Opening a store brings the file to [[Current]] in one transaction, applying each step `n -> n + 1` in turn, and
 * refuses a file whose version is newer than this build knows. Version 0 is a file with none of these tables.
 */
private[sqlite] object SqliteSchema:

  private val logger = LoggerFactory.getLogger(getClass)

  /** The schema version this build writes. */
  val Current: Int = 10

  /** A migration from the version it is keyed by to the next. */
  type Step = Connection => Unit

  /** The steps, keyed by the version each upgrades from. */
  val steps: Map[Int, Step] = Map(
    0 -> createTables,
    1 -> addFirstSeq,
    2 -> addEffects,
    3 -> addEffectLineages,
    4 -> addClaimTenants,
    5 -> addChildThreads,
    6 -> addCommitTimes,
    7 -> addThreadRegistry,
    8 -> addReclamation,
    9 -> addRunQueue
  )

  /**
   * Version 9: tombstone reclamation (Stage 5 slice S4, design 4.53). Each thread's row gains `deleted_nanos`, when
   * the store last deleted a thread of the id (epoch nanoseconds by the store's clock), which a sweep judges a
   * tombstone's age by and which keeps a tombstone from being dropped as an empty row; `llm4s_checkpoint_meta` gains
   * `reclaimed_first`, the first event number of a thread whose id has no row - `1` until a sweep reclaims a tombstone,
   * then the highest first number of every tombstone reclaimed.
   *
   * A version-8 file's rows whose `first_seq` is past 1 are its tombstones and the threads created again over one: they
   * are stamped with the time of the migration by the system clock, so a tombstone's age counts from the upgrade.
   */
  private def addReclamation(connection: Connection): Unit =
    statements(
      connection,
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN deleted_nanos INTEGER",
      s"UPDATE llm4s_checkpoint_threads SET deleted_nanos = ${SqliteSchema.nowNanos} WHERE first_seq > 1",
      "INSERT INTO llm4s_checkpoint_meta (key, value) VALUES ('reclaimed_first', 1)"
    )

  /** The system clock's time, as an SQL expression in epoch nanoseconds. */
  private[sqlite] val nowNanos: String = "CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER) * 1000000"

  /** The partial index predicate every registry index shares: root threads with a registered latest checkpoint. */
  private val Registered = "parent_thread_id IS NULL AND thread_status IS NOT NULL"

  /**
   * Version 8: the thread registry (Stage 5 slice S3, design 4.52). Each thread's row gains what `threads` and
   * `incompleteThreads` filter and order by - its latest checkpoint's tenant (`tenant_id`), graph (`graph_id`) and
   * status (`thread_status`, `Deleted` while a cascade delete is under way), when the store last committed a
   * checkpoint or metadata to it (`updated_nanos`, epoch nanoseconds by the store's clock), and its metadata as JSON
   * (`metadata_json`, NULL for none) - and `llm4s_checkpoint_thread_metadata` holds the metadata entry by entry, for
   * equality search. Partial indexes on root threads serve each filter newest first and oldest first.
   *
   * A version-7 file's threads are backfilled a row at a time from their latest checkpoint, `updated_nanos` from its
   * commit time (or, for a row from before schema 7, its creation time); they have no metadata. A latest checkpoint
   * this build cannot read is logged and left out of the registry, as [[ensureHistory]] leaves it out of the history.
   */
  /**
   * Version 10: the run queue (design 4.55). Every history row gets the run that committed it (`run_id`), backfilled
   * from its JSON (NULL for a row whose JSON cannot be read, so one damaged row does not stop the file opening), indexed by `(thread_id, run_id, position)` so the run-id lookup reads one index entry per run.
   *
   * `llm4s_checkpoint_queue_entries` holds one row per entry: its id (`AUTOINCREMENT`, so an id is never reused, and
   * the meta table's `last_entry` the highest issued, which tells a reclaimed id from one never issued), thread, agent,
   * tenant, input and policy, its submission key under `key_scope` (the tenant, or `-` for none) - unique while held -
   * its `max_attempts` and `attempt_count`, whether `deleteThread` deleted its thread while it was open
   * (`thread_deleted`, set in the delete's own transaction), whether one of its attempts' runs has committed a
   * checkpoint (`admitted`, set in that commit's transaction), whether it is an `Interrupt` entry, its lease (holder, token, expiry in epoch nanoseconds), its state
   * (`open` or the outcome's), outcome JSON, close time, the thread's last event number at the close (`closed_seq`)
   * and the trace context it carries (`trace_parent`). It is indexed by thread for the run order, by agent for `take`,
   * and by close time for `reclaim`.
   * `llm4s_checkpoint_queue_attempts` holds `(entry_id, attempt)` with the attempt's thread, run id, start time and
   * holder, indexed by `(thread_id, run_id)` for `prune`'s evidence.
   */
  private def addRunQueue(connection: Connection): Unit =
    ensureHistory(connection)
    statements(
      connection,
      "ALTER TABLE llm4s_checkpoint_history ADD COLUMN run_id TEXT",
      // a row whose JSON cannot be read keeps no run id: its reads already fail as undecodable, and the file still opens
      """UPDATE llm4s_checkpoint_history SET run_id = CASE
        |  WHEN json_valid(checkpoint_json) AND json_type(checkpoint_json, '$.runId') = 'text'
        |  THEN json_extract(checkpoint_json, '$.runId') END""".stripMargin,
      "CREATE INDEX llm4s_checkpoint_history_run ON llm4s_checkpoint_history (thread_id, run_id, position)",
      "INSERT OR IGNORE INTO llm4s_checkpoint_meta (key, value) VALUES ('last_entry', 0)",
      "INSERT OR IGNORE INTO llm4s_checkpoint_meta (key, value) VALUES ('last_lease', 0)",
      """CREATE TABLE llm4s_checkpoint_queue_entries (
        |  entry_id            INTEGER PRIMARY KEY AUTOINCREMENT,
        |  thread_id           TEXT NOT NULL,
        |  agent_id            TEXT NOT NULL,
        |  tenant_id           TEXT,
        |  input_json          TEXT NOT NULL,
        |  policy              TEXT NOT NULL,
        |  is_interrupt        INTEGER NOT NULL,
        |  key_scope           TEXT NOT NULL,
        |  submission_key      TEXT,
        |  max_attempts        INTEGER NOT NULL,
        |  attempt_count       INTEGER NOT NULL,
        |  submitted_nanos     INTEGER NOT NULL,
        |  thread_deleted      INTEGER NOT NULL,
        |  admitted            INTEGER NOT NULL DEFAULT 0,
        |  trace_parent        TEXT,
        |  lease_holder        TEXT,
        |  lease_token         INTEGER,
        |  lease_expires_nanos INTEGER,
        |  state               TEXT NOT NULL,
        |  outcome_json        TEXT,
        |  closed_nanos        INTEGER,
        |  closed_seq          INTEGER
        |)""".stripMargin,
      """CREATE UNIQUE INDEX llm4s_checkpoint_queue_entries_key
        |ON llm4s_checkpoint_queue_entries (key_scope, submission_key) WHERE submission_key IS NOT NULL""".stripMargin,
      """CREATE INDEX llm4s_checkpoint_queue_entries_thread
        |ON llm4s_checkpoint_queue_entries (thread_id, entry_id) WHERE state = 'open'""".stripMargin,
      """CREATE INDEX llm4s_checkpoint_queue_entries_agent
        |ON llm4s_checkpoint_queue_entries (agent_id, entry_id) WHERE state = 'open'""".stripMargin,
      """CREATE INDEX llm4s_checkpoint_queue_entries_closed
        |ON llm4s_checkpoint_queue_entries (closed_nanos, entry_id) WHERE state <> 'open'""".stripMargin,
      """CREATE TABLE llm4s_checkpoint_queue_attempts (
        |  entry_id      INTEGER NOT NULL,
        |  attempt       INTEGER NOT NULL,
        |  thread_id     TEXT NOT NULL,
        |  run_id        TEXT NOT NULL,
        |  started_nanos INTEGER NOT NULL,
        |  holder        TEXT NOT NULL,
        |  PRIMARY KEY (entry_id, attempt)
        |) WITHOUT ROWID""".stripMargin,
      "CREATE INDEX llm4s_checkpoint_queue_attempts_run ON llm4s_checkpoint_queue_attempts (thread_id, run_id, entry_id)"
    )

  private def addThreadRegistry(connection: Connection): Unit =
    ensureHistory(connection)
    statements(
      connection,
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN tenant_id TEXT",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN graph_id TEXT",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN thread_status TEXT",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN updated_nanos INTEGER",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN metadata_json TEXT",
      """CREATE TABLE llm4s_checkpoint_thread_metadata (
        |  thread_id TEXT NOT NULL,
        |  key       TEXT NOT NULL,
        |  value     TEXT NOT NULL,
        |  PRIMARY KEY (thread_id, key)
        |) WITHOUT ROWID""".stripMargin,
      "CREATE INDEX llm4s_checkpoint_thread_metadata_entry ON llm4s_checkpoint_thread_metadata (key, value, thread_id)",
      s"""CREATE INDEX llm4s_checkpoint_threads_registry
         |ON llm4s_checkpoint_threads (tenant_id, updated_nanos, thread_id) WHERE $Registered""".stripMargin,
      s"""CREATE INDEX llm4s_checkpoint_threads_registry_status
         |ON llm4s_checkpoint_threads (tenant_id, thread_status, updated_nanos, thread_id) WHERE $Registered""".stripMargin,
      s"""CREATE INDEX llm4s_checkpoint_threads_registry_agent
         |ON llm4s_checkpoint_threads (tenant_id, graph_id, updated_nanos, thread_id) WHERE $Registered""".stripMargin,
      s"""CREATE INDEX llm4s_checkpoint_threads_registry_claimed
         |ON llm4s_checkpoint_threads (tenant_id, updated_nanos, thread_id)
         |WHERE $Registered AND claim_token IS NOT NULL""".stripMargin
    )
    // the ids first, so no cursor is open on the table while its rows are updated; then one checkpoint at a time
    val threads = Using.resource(connection.createStatement()) { select =>
      Using.resource(
        select.executeQuery("SELECT thread_id FROM llm4s_checkpoint_threads WHERE checkpoint_json IS NOT NULL")
      )(rows => Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toVector)
    }
    Using.resources(
      connection.prepareStatement("SELECT checkpoint_json FROM llm4s_checkpoint_threads WHERE thread_id = ?"),
      connection.prepareStatement(
        """SELECT COALESCE(committed_second, created_second),
          |  CASE WHEN committed_second IS NULL THEN created_nano ELSE committed_nano END
          |FROM llm4s_checkpoint_history WHERE thread_id = ? AND checkpoint_id = ?""".stripMargin
      ),
      connection.prepareStatement(
        """UPDATE llm4s_checkpoint_threads SET tenant_id = ?, graph_id = ?, thread_status = ?, updated_nanos = ?
          |WHERE thread_id = ?""".stripMargin
      )
    ) { (latest, committed, backfill) =>
      threads.foreach { thread =>
        latest.setString(1, thread)
        val json = Using.resource(latest.executeQuery())(rows => if rows.next() then rows.getString(1) else null)
        decoded(json) match
          case Left(problem) =>
            logger.warn(
              "Thread {}'s latest checkpoint cannot be read, so it is not in the registry: {}",
              thread,
              problem
            )
          case Right(checkpoint) =>
            committed.setString(1, thread)
            committed.setString(2, checkpoint.id)
            val at = Using.resource(committed.executeQuery()) { found =>
              if found.next() then java.time.Instant.ofEpochSecond(found.getLong(1), found.getLong(2))
              else checkpoint.createdAt
            }
            backfill.setString(1, checkpoint.tenantId.orNull)
            backfill.setString(2, checkpoint.snapshot.graphId)
            backfill.setString(3, if checkpoint.deleting then "Deleted" else checkpoint.status.toString)
            backfill.setLong(4, Math.addExact(Math.multiplyExact(at.getEpochSecond, 1000000000L), at.getNano.toLong))
            backfill.setString(5, thread)
            backfill.executeUpdate(): Unit
      }
    }

  /**
   * Version 7: when the store committed each checkpoint and event, by its clock (Stage 5 rule 1, design 4.50), which
   * `prune` judges `maxAge` against instead of the producer's `createdAt` and `timestamp`. A row a version-6 file
   * holds has none (NULL), and its producer's timestamp stands in for it: such a row was written by a build that
   * judged age that way, and nothing better is known. The history table of a file a pre-release version-1 build
   * wrote is created first ([[ensureHistory]]), and a column already there is left as it is.
   */
  private def addCommitTimes(connection: Connection): Unit =
    ensureHistory(connection)
    for
      table  <- Vector("llm4s_checkpoint_history", "llm4s_checkpoint_events")
      column <- Vector("committed_second", "committed_nano")
      if !columnExists(connection, table, column)
    do statements(connection, s"ALTER TABLE $table ADD COLUMN $column INTEGER")

  private def columnExists(connection: Connection, table: String, column: String): Boolean =
    Using.resource(connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) { query =>
      query.setString(1, table)
      query.setString(2, column)
      Using.resource(query.executeQuery())(_.next())
    }

  /**
   * Version 6: child threads (#2045). `parent_thread_id` is the parent the thread's latest checkpoint's origin names,
   * NULL for a top-level thread, and `child_order` its place among that parent's children, one past the file's
   * highest; `children` pages the index on both. Every thread of a version-5 file is top-level - format 7 had no
   * origin - so both are NULL, and nothing is backfilled.
   */
  private def addChildThreads(connection: Connection): Unit =
    statements(
      connection,
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN parent_thread_id TEXT",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN child_order INTEGER",
      "CREATE INDEX llm4s_checkpoint_threads_parent ON llm4s_checkpoint_threads (parent_thread_id, child_order)"
    )

  /**
   * Version 5: the tenant of each claim (#1926), so that a refusal or a takeover names the claim's holder only to a
   * caller of the same tenant. `claim_tenant` is the tenant, NULL for a claim of no tenant, and `claim_tenant_token`
   * the token of the claim it was recorded for. A claim a version-4 file holds has no `claim_tenant_token`, so its
   * tenant reads as unknown: it matches no tenant, and its holder is never named - not in a refusal, and not as
   * `takenOverFrom` once it lapses. Such a claim is no older than its `ttl`, so this lasts at most that long.
   */
  private def addClaimTenants(connection: Connection): Unit =
    statements(
      connection,
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN claim_tenant TEXT",
      "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN claim_tenant_token INTEGER"
    )

  /**
   * Version 4: the lineage of the task each effect record belongs to (#1921 follow-up), which decides with its
   * id when a commit releases the record. A version-3 file's records read with an empty lineage, which no task
   * of a restored run has, so they never answer for one (format 6 never shipped).
   */
  private def addEffectLineages(connection: Connection): Unit =
    statements(connection, "ALTER TABLE llm4s_checkpoint_effects ADD COLUMN task_lineage TEXT NOT NULL DEFAULT ''")

  /**
   * Version 3: effect records, the replay evidence of `ReplayGuard` (#1921). A version-2 file holds none: no build
   * that wrote one performed a guarded effect.
   */
  private def addEffects(connection: Connection): Unit =
    statements(
      connection,
      """CREATE TABLE llm4s_checkpoint_effects (
        |  thread_id    TEXT NOT NULL,
        |  effect_key   TEXT NOT NULL,
        |  task_id      TEXT NOT NULL,
        |  writer_token INTEGER NOT NULL,
        |  record_json  TEXT NOT NULL,
        |  PRIMARY KEY (thread_id, effect_key)
        |) WITHOUT ROWID""".stripMargin
    )

  /**
   * Version 2: the first event number of the thread now under each id, so that a thread deleted and created again
   * numbers on past the deleted one's events. Every thread a version-1 file holds numbers its events from 1 - a
   * version-1 delete restarted an id's numbering - so its `first_seq` is 1.
   */
  private def addFirstSeq(connection: Connection): Unit =
    statements(connection, "ALTER TABLE llm4s_checkpoint_threads ADD COLUMN first_seq INTEGER NOT NULL DEFAULT 1")

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
   *
   * The latest checkpoints are read a row at a time, each inserted as it is read, so seeding holds one checkpoint in
   * memory, never the whole file's. A checkpoint this build cannot read - text that is not JSON, or JSON it cannot
   * decode - keeps no history row and is logged; it never fails the open, and `latest` reports it as before (#1849).
   */
  private[sqlite] def ensureHistory(connection: Connection): Unit =
    if tableExists(connection, "llm4s_checkpoint_threads") && !tableExists(connection, "llm4s_checkpoint_history") then
      statements(connection, historyTable)
      Using.resources(
        connection.createStatement(),
        connection.prepareStatement(
          """INSERT INTO llm4s_checkpoint_history
            |(thread_id, position, checkpoint_id, status, created_second, created_nano, checkpoint_json)
            |VALUES (?, 0, ?, ?, ?, ?, ?)""".stripMargin
        )
      ) { (select, insert) =>
        Using.resource(
          select.executeQuery(
            "SELECT thread_id, checkpoint_json FROM llm4s_checkpoint_threads WHERE checkpoint_json IS NOT NULL"
          )
        ) { rows =>
          while rows.next() do
            val thread = rows.getString(1)
            val json   = rows.getString(2)
            decoded(json) match
              case Left(problem) =>
                logger.warn(
                  "Thread {}'s latest checkpoint cannot be read, so it is not seeded into the history: {}",
                  thread,
                  problem
                )
              case Right(checkpoint) =>
                insert.setString(1, thread)
                insert.setString(2, checkpoint.id)
                insert.setString(3, checkpoint.status.toString)
                insert.setLong(4, checkpoint.createdAt.getEpochSecond)
                insert.setLong(5, checkpoint.createdAt.getNano.toLong)
                insert.setString(6, json)
                insert.executeUpdate(): Unit
        }
      }

  /**
   * A stored checkpoint's JSON text decoded, or why it cannot be: never a throw. The reason is a fixed category and an
   * error class, never a decoder's message, which can quote the stored text into the log.
   */
  private def decoded(json: String): Either[String, Checkpoint] =
    Try(ujson.read(json)).toEither.left
      .map(e => s"not JSON (${e.getClass.getSimpleName})")
      .flatMap(value => Checkpoint.fromJson(value).left.map(e => s"not a checkpoint (${e.getClass.getSimpleName})"))

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
