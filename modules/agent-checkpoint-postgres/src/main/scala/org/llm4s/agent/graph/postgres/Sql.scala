package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.postgres.PostgresSchema.Tables

/**
 * Every statement a [[PostgresCheckpointer]] runs, built for the schema's tables. Kept apart from the store so the
 * statements can be read, and tested, as text: each names its tables through [[PostgresSchema.Tables]], so none can
 * reach outside the store's schema.
 */
private[postgres] object Sql:

  private val rowColumns =
    "checkpoint_id, next_seq, earliest_seq, first_seq, claim_holder, claim_token, claim_expires_at, claim_tenant, " +
      "parent_thread_id"

  /** A thread's row, read without a lock (by a read-only snapshot). */
  def row(t: Tables): String = s"SELECT $rowColumns FROM ${t.threads} WHERE thread_id = ?"

  /** A thread's row, locked for the rest of the transaction. */
  def lockedRow(t: Tables): String = s"${row(t)} FOR UPDATE"

  /**
   * A thread's row, locked for the rest of the transaction - or no row if another transaction holds its lock: a sweep
   * reclaiming tombstones under the exclusive reclamation lock skips a row in use rather than wait for it (design 4.53).
   */
  def lockedRowIfFree(t: Tables): String = s"${row(t)} FOR UPDATE SKIP LOCKED"

  /** A row for a new id, numbering from the reclamation mark (schema 3, design 4.53). */
  def ensureRow(t: Tables): String =
    s"""INSERT INTO ${t.threads} (thread_id, next_seq, earliest_seq, first_seq)
       |SELECT ?, first_seq, first_seq, first_seq FROM ${t.reclaimed}
       |ON CONFLICT (thread_id) DO NOTHING""".stripMargin

  /** The first event number of a thread whose id has no row (schema 3). */
  def reclaimedFirst(t: Tables): String = s"SELECT first_seq FROM ${t.reclaimed}"

  def raiseReclaimed(t: Tables): String = s"UPDATE ${t.reclaimed} SET first_seq = GREATEST(first_seq, ?)"

  /**
   * The transaction-scoped advisory lock, keyed by the name bound, that orders reclaiming tombstones before creating a
   * row: a sweep takes it exclusively while it reclaims, a claim on an id with no row shares it while it creates one,
   * so a row is never created from a reclamation mark a reclamation in flight is about to raise.
   */
  val lockReclamation: String  = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
  val shareReclamation: String = "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))"

  /** A page of the store's threads, tombstones included, by primary key: from the start, and after an id. */
  def sweepPage(t: Tables): String = s"SELECT thread_id FROM ${t.threads} ORDER BY thread_id LIMIT ?"
  def sweepPageAfter(t: Tables): String =
    s"SELECT thread_id FROM ${t.threads} WHERE thread_id > ? ORDER BY thread_id LIMIT ?"

  /**
   * Removes the thread's row if it is only a tombstone, deleted before the cutoff bound second, with nothing stored
   * under the id since - no checkpoint, event, metadata, effect or pending write, and no claim but one that expired
   * before the cutoff (bound third) - returning its first event number.
   */
  def reclaim(t: Tables): String =
    s"""DELETE FROM ${t.threads} th
       |WHERE th.thread_id = ? AND th.checkpoint_id IS NULL AND th.next_seq = th.first_seq AND th.metadata IS NULL
       |  AND th.deleted_at IS NOT NULL AND th.deleted_at < ?
       |  AND (th.claim_token IS NULL OR th.claim_expires_at < ?)
       |  AND NOT EXISTS (SELECT 1 FROM ${t.effects} e WHERE e.thread_id = th.thread_id)
       |  AND NOT EXISTS (SELECT 1 FROM ${t.pendingWrites} w WHERE w.thread_id = th.thread_id)
       |RETURNING th.first_seq""".stripMargin

  def setClaim(t: Tables): String =
    s"""UPDATE ${t.threads}
       |SET claim_holder = ?, claim_token = ?, claim_expires_at = ?, claim_tenant = ?
       |WHERE thread_id = ?""".stripMargin

  def nextToken(t: Tables): String = s"SELECT nextval('${t.tokens}')"

  def dropIfEmpty(t: Tables): String =
    s"""DELETE FROM ${t.threads} th
       |WHERE th.thread_id = ? AND th.checkpoint_id IS NULL AND th.claim_token IS NULL AND th.next_seq = th.first_seq
       |  AND th.deleted_at IS NULL AND th.metadata IS NULL
       |  AND NOT EXISTS (SELECT 1 FROM ${t.effects} e WHERE e.thread_id = th.thread_id)""".stripMargin

  /**
   * Empties the row of a deleted thread into its tombstone. Every SET expression reads the row as it was: the next
   * thread starts one past the deleted one's next number, which is never used (`Checkpointer.deleted`); a thread that
   * numbered no event leaves nothing, and a tombstone that numbered no event since its delete keeps its numbering.
   * The child place stays, so a page boundary survives. A tombstone records when it was deleted (bound first, schema
   * 3); a row that numbered no event and was never deleted records nothing, so it can go.
   */
  def tombstone(t: Tables): String =
    val moved = "CASE WHEN next_seq <> first_seq THEN next_seq + 1 ELSE next_seq END"
    s"""UPDATE ${t.threads}
       |SET checkpoint_id = NULL, checkpoint_json = NULL,
       |    next_seq = $moved, first_seq = $moved, earliest_seq = $moved,
       |    deleted_at = CASE WHEN next_seq <> first_seq OR deleted_at IS NOT NULL THEN ? ELSE NULL END,
       |    claim_holder = NULL, claim_token = NULL, claim_expires_at = NULL, claim_tenant = NULL,
       |    tenant_id = NULL, graph_id = NULL, thread_status = NULL, updated_at = NULL, metadata = NULL
       |WHERE thread_id = ?""".stripMargin

  // ---- checkpoints ----

  def latest(t: Tables): String =
    s"SELECT checkpoint_json FROM ${t.threads} WHERE thread_id = ? AND checkpoint_json IS NOT NULL"

  /** The latest checkpoint, and the registry's columns that follow it (schema 2). */
  def setLatest(t: Tables): String =
    s"""UPDATE ${t.threads}
       |SET checkpoint_id = ?, checkpoint_json = ?, tenant_id = ?, graph_id = ?, thread_status = ?, updated_at = ?
       |WHERE thread_id = ?""".stripMargin

  // ---- the thread registry (schema 2) ----

  /** The thread's metadata as a JSON object, NULL for none, and when the store committed it. */
  def setMetadata(t: Tables): String =
    s"UPDATE ${t.threads} SET metadata = CAST(? AS jsonb), updated_at = ? WHERE thread_id = ?"

  /** The predicate every registry index shares: root threads with a registered latest checkpoint. */
  val Registered: String = "parent_thread_id IS NULL AND thread_status IS NOT NULL"

  /** Whether a live claim holds the thread at the time bound: its expiry after it (NULL, so false, for none). */
  val LiveClaim: String = "claim_expires_at > ?"

  /**
   * A page of the registry, and one row more to tell whether another follows, given its `WHERE` clause: newest first
   * by commit time, then thread id - compared byte by byte (`COLLATE "C"`), as the indexes are - or oldest first. The
   * last column is whether a live claim holds the thread, at the time bound first.
   */
  def registryPage(t: Tables, where: String, newestFirst: Boolean): String =
    val order = if newestFirst then "DESC" else "ASC"
    s"""SELECT thread_id, tenant_id, graph_id, thread_status, checkpoint_id, metadata::text, updated_at,
       |  COALESCE($LiveClaim, false)
       |FROM ${t.threads} WHERE $where
       |ORDER BY updated_at $order, thread_id COLLATE "C" $order LIMIT ?""".stripMargin

  def nextPosition(t: Tables): String =
    s"SELECT COALESCE(MAX(position) + 1, 0) FROM ${t.history} WHERE thread_id = ?"

  def insertHistory(t: Tables): String =
    s"""INSERT INTO ${t.history} (thread_id, position, checkpoint_id, status, committed_at, checkpoint_json)
       |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin

  /** A blocked turn retracted back to the newest settled checkpoint (statuses bound as the 3rd and 4th parameters). */
  def retract(t: Tables): String =
    s"""DELETE FROM ${t.history} WHERE thread_id = ? AND position > COALESCE(
       |  (SELECT MAX(position) FROM ${t.history} WHERE thread_id = ? AND status IN (?, ?)), -1)""".stripMargin

  /** A page of the history, newest first: a backwards range of the `(thread, position)` primary key. */
  def historyPage(t: Tables): String =
    s"""SELECT checkpoint_json FROM ${t.history}
       |WHERE thread_id = ? AND position < ? ORDER BY position DESC LIMIT ?""".stripMargin

  def checkpointById(t: Tables): String =
    s"SELECT checkpoint_json FROM ${t.history} WHERE thread_id = ? AND checkpoint_id = ?"

  def positionById(t: Tables): String = s"SELECT position FROM ${t.history} WHERE thread_id = ? AND checkpoint_id = ?"

  /**
   * The history oldest first, with each checkpoint's commit time: what `prune` decides on; and the run that committed
   * each when `withRuns` (a sweep's protected thread), empty otherwise.
   */
  def historyAges(t: Tables, withRuns: Boolean = false): String =
    val run = if withRuns then "checkpoint_json::json ->> 'runId'" else "''"
    s"SELECT position, checkpoint_id, committed_at, $run FROM ${t.history} WHERE thread_id = ? ORDER BY position"

  def deleteHistoryAt(t: Tables): String = s"DELETE FROM ${t.history} WHERE thread_id = ? AND position = ?"

  // ---- pending writes ----

  def pendingWrites(t: Tables): String =
    s"SELECT write_json FROM ${t.pendingWrites} WHERE thread_id = ? ORDER BY position"

  def deletePendingWrites(t: Tables): String = s"DELETE FROM ${t.pendingWrites} WHERE thread_id = ?"

  def nextWritePosition(t: Tables): String =
    s"SELECT COALESCE(MAX(position) + 1, 0) FROM ${t.pendingWrites} WHERE thread_id = ?"

  def insertWrite(t: Tables): String =
    s"INSERT INTO ${t.pendingWrites} (thread_id, position, checkpoint_id, write_json) VALUES (?, ?, ?, ?)"

  // ---- events ----

  def insertEvent(t: Tables): String =
    s"INSERT INTO ${t.events} (thread_id, seq, checkpoint_id, committed_at, record_json) VALUES (?, ?, ?, ?, ?)"

  def setNextSeq(t: Tables): String = s"UPDATE ${t.threads} SET next_seq = ? WHERE thread_id = ?"

  def eventsAfter(t: Tables): String =
    s"SELECT record_json FROM ${t.events} WHERE thread_id = ? AND seq > ? ORDER BY seq LIMIT ?"

  /** The first event recorded against any of the checkpoints bound (as a `text[]`) second; NULL if none. */
  def firstEventOf(t: Tables): String =
    s"SELECT MIN(seq) FROM ${t.events} WHERE thread_id = ? AND checkpoint_id = ANY (?)"

  /** The first event committed at or after the time bound second; NULL if none. */
  def firstEventSince(t: Tables): String =
    s"SELECT MIN(seq) FROM ${t.events} WHERE thread_id = ? AND committed_at >= ?"

  def deleteEventsBefore(t: Tables): String = s"DELETE FROM ${t.events} WHERE thread_id = ? AND seq < ?"

  def setFloor(t: Tables): String = s"UPDATE ${t.threads} SET earliest_seq = ? WHERE thread_id = ?"

  // ---- effects ----

  def effect(t: Tables): String = s"SELECT record_json FROM ${t.effects} WHERE thread_id = ? AND effect_key = ?"

  def effectTasks(t: Tables): String =
    s"SELECT effect_key, task_id, task_lineage FROM ${t.effects} WHERE thread_id = ?"

  def deleteEffect(t: Tables): String = s"DELETE FROM ${t.effects} WHERE thread_id = ? AND effect_key = ?"

  def upsertEffect(t: Tables): String =
    s"""INSERT INTO ${t.effects} (thread_id, effect_key, task_id, task_lineage, writer_token, record_json)
       |VALUES (?, ?, ?, ?, ?, ?)
       |ON CONFLICT (thread_id, effect_key) DO UPDATE SET
       |  task_id = excluded.task_id, task_lineage = excluded.task_lineage, writer_token = excluded.writer_token,
       |  record_json = excluded.record_json""".stripMargin

  // ---- children ----

  /** The transaction-scoped advisory lock a commit takes before it draws a child place under the parent bound. */
  val lockChildren: String = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"

  def nextChildOrder(t: Tables): String = s"SELECT nextval('${t.childOrder}')"

  def setParent(t: Tables): String =
    s"UPDATE ${t.threads} SET parent_thread_id = ?, child_order = ? WHERE thread_id = ?"

  def childOrderOf(t: Tables): String =
    s"""SELECT child_order FROM ${t.threads}
       |WHERE thread_id = ? AND parent_thread_id = ? AND child_order IS NOT NULL""".stripMargin

  /** A page of a parent's children, oldest first; a tombstone keeps its place but is not listed. */
  def childrenPage(t: Tables): String =
    s"""SELECT checkpoint_json FROM ${t.threads}
       |WHERE parent_thread_id = ? AND child_order > ? AND checkpoint_json IS NOT NULL
       |ORDER BY child_order LIMIT ?""".stripMargin
