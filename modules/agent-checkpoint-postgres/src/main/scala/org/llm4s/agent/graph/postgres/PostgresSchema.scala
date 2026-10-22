package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{ LLMError, ProcessingError }

import java.sql.Connection
import scala.util.Using

/**
 * The tables a [[PostgresCheckpointer]] keeps in its PostgreSQL schema (`llm4s` by default), and their migrations.
 * Every name is qualified by the schema and prefixed `checkpoint_`, so later tables of other stores (a run queue,
 * a thread registry) can live in the same schema.
 *
 *  - `checkpoint_schema`: one row, the schema version the store's tables are at.
 *  - `checkpoint_fencing_tokens`: the sequence every claim's fencing token comes from, store-wide. A sequence never
 *    goes back, so tokens increase across releases, takeovers, restarts and `deleteThread`. A value is drawn only
 *    while the thread's row is locked, so a thread's tokens increase in the order its claims commit.
 *  - `checkpoint_child_order`: the sequence a child thread's place among its parent's children comes from. A value
 *    is drawn under a transaction-scoped advisory lock on the parent, so a parent's children are placed in the order
 *    their first commits land.
 *  - `checkpoint_threads`: one row per thread that has a checkpoint, a claim, an effect record or an event number
 *    spent: the latest checkpoint as JSON with its id (the thread's version), the next event number, the replay floor
 *    (`earliest_seq`) and the first event number of the thread now under the id (`first_seq`), the claim (holder,
 *    token, expiry by the database's clock, tenant), and the parent the latest checkpoint's origin names with the
 *    thread's place among that parent's children, and from version 2 the thread registry's columns and metadata
 *    ([[addThreadRegistry]]). Every write to a thread locks this row first (`FOR UPDATE`), so the
 *    checks a write makes and the write itself see one state of the thread. `deleteThread` keeps the row of a thread
 *    that numbered any event, emptied, as a tombstone, exactly as the SQLite store does.
 *  - `checkpoint_history`: every checkpoint of every thread's history, latest included, as JSON, keyed by
 *    `(thread, position)` and unique by `(thread, checkpoint id)`, with its status, for retraction, and `committed_at`,
 *    the database's clock when the commit wrote it, for retention (never the producer's `createdAt`: rule 1).
 *  - `checkpoint_pending_writes`: the latest checkpoint's pending writes as JSON, in commit order.
 *  - `checkpoint_events`: the durable event log, one JSON `EventRecord` per `(thread, seq)`, with the checkpoint it
 *    names and `committed_at` beside it, so `prune` decides by the database's clock without decoding the log.
 *  - `checkpoint_effects`: the thread's effect records (design §4.24), one JSON `EffectRecord` per
 *    `(thread, effect key)`, with its task's id and lineage and the token of the commit that wrote it.
 *  - `checkpoint_reclaimed` (version 3): one row, the first event number of a thread whose id has no row - the
 *    reclamation mark a retention sweep raises as it reclaims tombstones ([[addReclamation]]).
 *
 * Opening a store brings the schema to [[Current]] in one transaction under a transaction-scoped advisory lock on the
 * schema's name, so instances that start together migrate it once; it refuses a schema whose version is newer than
 * this build knows with [[org.llm4s.agent.graph.GraphError.UnsupportedStoreSchema GraphError.UnsupportedStoreSchema]],
 * changing nothing. A schema already at [[Current]] is only read, so a role without `CREATE` can open it. Version 0
 * is a schema without these tables.
 */
private[postgres] object PostgresSchema:

  /** The schema version this build writes. */
  val Current: Int = 3

  /** A migration from the version it is keyed by to the next, given the tables' names. */
  type Step = Tables => Vector[String]

  /** The steps, keyed by the version each upgrades from. */
  val steps: Map[Int, Step] = Map(0 -> createTables, 1 -> addThreadRegistry, 2 -> addReclamation)

  /**
   * Version 3: tombstone reclamation (Stage 5 slice S4, design 4.53). Each thread's row gains `deleted_at`, when the
   * store last deleted a thread of the id by the database's clock, which a sweep judges a tombstone's age by and which
   * keeps a tombstone from being dropped as an empty row; `checkpoint_reclaimed` holds one row, `first_seq`, the first
   * event number of a thread whose id has no row - `1` until a sweep reclaims a tombstone, then the highest first
   * number of every tombstone reclaimed.
   *
   * A version-2 schema's rows whose `first_seq` is past 1 are its tombstones and the threads created again over one:
   * they are stamped with the migration's time by the database's clock, so a tombstone's age counts from the upgrade.
   */
  private def addReclamation(t: Tables): Vector[String] = Vector(
    s"ALTER TABLE ${t.threads} ADD COLUMN deleted_at timestamptz",
    s"UPDATE ${t.threads} SET deleted_at = clock_timestamp() WHERE first_seq > 1",
    s"CREATE TABLE ${t.reclaimed} (first_seq bigint NOT NULL)",
    s"INSERT INTO ${t.reclaimed} (first_seq) VALUES (1)"
  )

  /**
   * Version 2: the thread registry (Stage 5 slice S3, design 4.52). Each thread's row gains what `threads` and
   * `incompleteThreads` filter and order by: its latest checkpoint's tenant (`tenant_id`), graph (`graph_id`) and
   * status (`thread_status`, `Deleted` while a cascade delete is under way), when the store last committed a
   * checkpoint or metadata to it (`updated_at`, by the database's clock), and its metadata (`metadata`, a JSON object,
   * NULL for none). Partial indexes on root threads serve each filter newest first and oldest first - thread ids
   * compared byte by byte (`COLLATE "C"`), whatever the database's collation - and a GIN index metadata containment.
   * The indexes lead with `tenant_key`, which the database generates from `tenant_id` (`=` and the tenant, or empty
   * for none), because an index scan is in the order of the columns after one compared by equality, not by `IS NULL`.
   *
   * A version-1 schema's threads are backfilled from their latest checkpoint's JSON (written by this build line,
   * format 8: `tenantId` absent or a string, `deleting` absent or a boolean), `updated_at` from its commit time. They
   * have no metadata.
   */
  private def addThreadRegistry(t: Tables): Vector[String] =
    val registered = "WHERE parent_thread_id IS NULL AND thread_status IS NOT NULL"
    Vector(
      s"""ALTER TABLE ${t.threads}
         |  ADD COLUMN tenant_id     text,
         |  ADD COLUMN graph_id      text,
         |  ADD COLUMN thread_status text,
         |  ADD COLUMN updated_at    timestamptz,
         |  ADD COLUMN metadata      jsonb,
         |  ADD COLUMN tenant_key    text GENERATED ALWAYS AS (COALESCE('=' || tenant_id, '')) STORED""".stripMargin,
      s"""UPDATE ${t.threads} th
         |SET tenant_id = src.j ->> 'tenantId',
         |    graph_id = src.j -> 'snapshot' ->> 'graphId',
         |    thread_status = CASE WHEN COALESCE((src.j ->> 'deleting')::boolean, false) THEN 'Deleted'
         |                         ELSE src.j ->> 'status' END,
         |    updated_at = COALESCE(
         |      (SELECT h.committed_at FROM ${t.history} h
         |       WHERE h.thread_id = th.thread_id AND h.checkpoint_id = th.checkpoint_id),
         |      (src.j ->> 'createdAt')::timestamptz)
         |FROM (SELECT thread_id, checkpoint_json::jsonb AS j FROM ${t.threads} WHERE checkpoint_json IS NOT NULL) src
         |WHERE src.thread_id = th.thread_id""".stripMargin,
      s"""CREATE INDEX checkpoint_threads_registry
         |ON ${t.threads} (tenant_key, updated_at, thread_id COLLATE "C") $registered""".stripMargin,
      s"""CREATE INDEX checkpoint_threads_registry_status
         |ON ${t.threads} (tenant_key, thread_status, updated_at, thread_id COLLATE "C") $registered""".stripMargin,
      s"""CREATE INDEX checkpoint_threads_registry_agent
         |ON ${t.threads} (tenant_key, graph_id, updated_at, thread_id COLLATE "C") $registered""".stripMargin,
      s"""CREATE INDEX checkpoint_threads_registry_claimed
         |ON ${t.threads} (tenant_key, updated_at, thread_id COLLATE "C")
         |$registered AND claim_expires_at IS NOT NULL""".stripMargin,
      s"""CREATE INDEX checkpoint_threads_registry_metadata
         |ON ${t.threads} USING gin (metadata jsonb_path_ops) $registered""".stripMargin
    )

  /** The qualified name of each table and sequence in `schema`. */
  final case class Tables(schema: String):
    private def q(name: String): String = s""""$schema".$name"""
    val quotedSchema: String            = s""""$schema""""
    val version: String                 = q("checkpoint_schema")
    val tokens: String                  = q("checkpoint_fencing_tokens")
    val childOrder: String              = q("checkpoint_child_order")
    val threads: String                 = q("checkpoint_threads")
    val history: String                 = q("checkpoint_history")
    val pendingWrites: String           = q("checkpoint_pending_writes")
    val events: String                  = q("checkpoint_events")
    val effects: String                 = q("checkpoint_effects")
    val reclaimed: String               = q("checkpoint_reclaimed")

  /** Version 1: every table, as the store's Scaladoc describes them. */
  private def createTables(t: Tables): Vector[String] = Vector(
    s"CREATE SEQUENCE ${t.tokens} AS bigint MINVALUE 1 NO CYCLE",
    s"CREATE SEQUENCE ${t.childOrder} AS bigint MINVALUE 1 NO CYCLE",
    s"""CREATE TABLE ${t.threads} (
       |  thread_id         text PRIMARY KEY,
       |  checkpoint_id     text,
       |  checkpoint_json   text,
       |  next_seq          bigint NOT NULL,
       |  earliest_seq      bigint NOT NULL,
       |  first_seq         bigint NOT NULL,
       |  claim_holder      text,
       |  claim_token       bigint,
       |  claim_expires_at  timestamptz,
       |  claim_tenant      text,
       |  parent_thread_id  text,
       |  child_order       bigint
       |)""".stripMargin,
    s"CREATE INDEX checkpoint_threads_parent ON ${t.threads} (parent_thread_id, child_order)",
    s"""CREATE TABLE ${t.history} (
       |  thread_id        text NOT NULL,
       |  position         bigint NOT NULL,
       |  checkpoint_id    text NOT NULL,
       |  status           text NOT NULL,
       |  committed_at     timestamptz NOT NULL,
       |  checkpoint_json  text NOT NULL,
       |  PRIMARY KEY (thread_id, position),
       |  UNIQUE (thread_id, checkpoint_id)
       |)""".stripMargin,
    s"""CREATE TABLE ${t.pendingWrites} (
       |  thread_id      text NOT NULL,
       |  position       bigint NOT NULL,
       |  checkpoint_id  text NOT NULL,
       |  write_json     text NOT NULL,
       |  PRIMARY KEY (thread_id, position)
       |)""".stripMargin,
    s"""CREATE TABLE ${t.events} (
       |  thread_id      text NOT NULL,
       |  seq            bigint NOT NULL,
       |  checkpoint_id  text,
       |  committed_at   timestamptz NOT NULL,
       |  record_json    text NOT NULL,
       |  PRIMARY KEY (thread_id, seq)
       |)""".stripMargin,
    s"""CREATE TABLE ${t.effects} (
       |  thread_id     text NOT NULL,
       |  effect_key    text NOT NULL,
       |  task_id       text NOT NULL,
       |  task_lineage  text NOT NULL,
       |  writer_token  bigint NOT NULL,
       |  record_json   text NOT NULL,
       |  PRIMARY KEY (thread_id, effect_key)
       |)""".stripMargin
  )

  /**
   * The versions to apply, in order, to bring a schema at `found` to `target`: empty when it is there already, and
   * `Left` - refusing it, with nothing to apply - when it is newer than `target` or a step is missing.
   */
  def plan(schema: String, found: Int, target: Int, steps: Map[Int, Step]): Either[LLMError, Vector[Int]] =
    if found > target then Left(GraphError.UnsupportedStoreSchema(description(schema), found, target))
    else
      (found until target).find(v => !steps.contains(v)) match
        case Some(missing) =>
          Left(
            ProcessingError(
              "postgres-checkpointer.open",
              s"this build has no migration of ${description(schema)} from checkpoint schema version $missing to ${missing + 1}"
            )
          )
        case None => Right((found until target).toVector)

  /** How an error names the store's storage. */
  def description(schema: String): String = s"""PostgreSQL schema "$schema""""

  /**
   * The key of the transaction-scoped advisory lock openers of `schema` take before they read or migrate it: one
   * per schema, so stores in two schemas of one database never wait for each other.
   */
  def lockName(schema: String): String = s"llm4s.agent.checkpoint:$schema"

  /**
   * Brings `schema` to `target` inside the caller's transaction, under the schema's advisory lock, and returns the
   * version it was at; `Left` with nothing changed if it is newer than `target` or a step is missing. A schema
   * already at `target` is read, never written: no DDL runs.
   */
  def migrate(
    connection: Connection,
    schema: String,
    target: Int = Current,
    steps: Map[Int, Step] = steps
  ): Either[LLMError, Int] =
    val tables = Tables(schema)
    Using.resource(connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) { lock =>
      lock.setString(1, lockName(schema))
      Using.resource(lock.executeQuery())(_.next()): Unit
    }
    val found = version(connection, schema)
    plan(schema, found, target, steps).map { versions =>
      if versions.nonEmpty then
        if !exists(connection, "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)", schema) then
          execute(connection, s"CREATE SCHEMA ${tables.quotedSchema}")
        execute(connection, s"CREATE TABLE IF NOT EXISTS ${tables.version} (version integer NOT NULL)")
        versions.foreach(v => steps(v)(tables).foreach(execute(connection, _)))
        execute(connection, s"DELETE FROM ${tables.version}")
        Using.resource(connection.prepareStatement(s"INSERT INTO ${tables.version} (version) VALUES (?)")) { insert =>
          insert.setInt(1, target)
          insert.executeUpdate(): Unit
        }
      found
    }

  /** The version `schema` is at; 0 for one without the version table (or no schema at all). */
  def version(connection: Connection, schema: String): Int =
    val table = Tables(schema).version
    if !exists(connection, "SELECT to_regclass(?) IS NOT NULL", table) then 0
    else
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery(s"SELECT COALESCE(MAX(version), 0) FROM $table")) { rows =>
          if rows.next() then rows.getInt(1) else 0
        }
      }

  private def exists(connection: Connection, sql: String, params: String*): Boolean =
    Using.resource(connection.prepareStatement(sql)) { query =>
      params.zipWithIndex.foreach((p, i) => query.setString(i + 1, p))
      Using.resource(query.executeQuery())(rows => rows.next() && rows.getBoolean(1))
    }

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement())(_.execute(sql): Unit)
