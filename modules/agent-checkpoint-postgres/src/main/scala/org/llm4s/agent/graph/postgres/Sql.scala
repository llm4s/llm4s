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

  def ensureRow(t: Tables): String =
    s"""INSERT INTO ${t.threads} (thread_id, next_seq, earliest_seq, first_seq) VALUES (?, 1, 1, 1)
       |ON CONFLICT (thread_id) DO NOTHING""".stripMargin

  def setClaim(t: Tables): String =
    s"""UPDATE ${t.threads}
       |SET claim_holder = ?, claim_token = ?, claim_expires_at = ?, claim_tenant = ?
       |WHERE thread_id = ?""".stripMargin

  def nextToken(t: Tables): String = s"SELECT nextval('${t.tokens}')"

  def dropIfEmpty(t: Tables): String =
    s"""DELETE FROM ${t.threads} th
       |WHERE th.thread_id = ? AND th.checkpoint_id IS NULL AND th.claim_token IS NULL AND th.next_seq = 1
       |  AND th.metadata IS NULL
       |  AND NOT EXISTS (SELECT 1 FROM ${t.effects} e WHERE e.thread_id = th.thread_id)""".stripMargin

  /**
   * Empties the row of a deleted thread into its tombstone. Every SET expression reads the row as it was: the next
   * thread starts one past the deleted one's next number, which is never used (`Checkpointer.deleted`); a thread that
   * numbered no event leaves nothing, and a tombstone that numbered no event since its delete keeps its numbering.
   * The child place stays, so a page boundary survives.
   */
  def tombstone(t: Tables): String =
    val moved = "CASE WHEN next_seq > 1 AND next_seq <> first_seq THEN next_seq + 1 ELSE next_seq END"
    s"""UPDATE ${t.threads}
       |SET checkpoint_id = NULL, checkpoint_json = NULL,
       |    next_seq = $moved, first_seq = $moved, earliest_seq = $moved,
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
    s"""INSERT INTO ${t.history} (thread_id, position, checkpoint_id, status, committed_at, checkpoint_json, run_id)
       |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin

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

  /** The history oldest first, with each checkpoint's commit time: what `prune` decides on. */
  def historyAges(t: Tables): String =
    s"SELECT position, checkpoint_id, committed_at FROM ${t.history} WHERE thread_id = ? ORDER BY position"

  /**
   * The run-id lookup (design 4.55): the newest checkpoint any of the runs bound first (a `text[]`) committed on the
   * thread bound second - one backwards probe of the `(thread_id, run_id, position)` index per run.
   */
  def latestOfRuns(t: Tables): String =
    s"""SELECT h.checkpoint_json FROM unnest(?::text[]) AS r(run_id)
       |CROSS JOIN LATERAL (
       |  SELECT position, checkpoint_json FROM ${t.history}
       |  WHERE thread_id = ? AND run_id = r.run_id ORDER BY position DESC LIMIT 1) h
       |ORDER BY h.position DESC LIMIT 1""".stripMargin

  /** Marks the thread's open queue entries as having outlived their thread: `deleteThread`, in its transaction. */
  def queueThreadDeleted(t: Tables): String =
    s"UPDATE ${t.queueEntries} SET thread_deleted = true WHERE thread_id = ? AND state = 'open'"

  /** An open queue entry's attempt, as run `?` on thread `?`, whose thread a delete marked. */
  def orphanedAttempt(t: Tables): String =
    s"""SELECT 1 FROM ${t.queueAttempts} a JOIN ${t.queueEntries} e ON e.entry_id = a.entry_id
       |WHERE a.thread_id = ? AND a.run_id = ? AND e.state = 'open' AND e.thread_deleted LIMIT 1""".stripMargin

  /** Whether a run id already names a checkpoint or a queue attempt on the thread (bound twice). */
  def runIdUsed(t: Tables): String =
    s"""SELECT EXISTS (SELECT 1 FROM ${t.queueAttempts} WHERE thread_id = ? AND run_id = ?)
       |    OR EXISTS (SELECT 1 FROM ${t.history} WHERE thread_id = ? AND run_id = ?)""".stripMargin

  /** The positions `prune` keeps for open queue entries: the newest checkpoint of each run they recorded on the thread. */
  def queueEvidence(t: Tables): String =
    s"""SELECT MAX(h.position) FROM ${t.history} h
       |JOIN ${t.queueAttempts} a ON a.thread_id = h.thread_id AND a.run_id = h.run_id
       |JOIN ${t.queueEntries} e ON e.entry_id = a.entry_id AND e.state = 'open'
       |WHERE h.thread_id = ? GROUP BY h.run_id""".stripMargin

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
