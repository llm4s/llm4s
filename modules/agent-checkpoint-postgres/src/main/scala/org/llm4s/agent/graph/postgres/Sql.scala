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
       |    claim_holder = NULL, claim_token = NULL, claim_expires_at = NULL, claim_tenant = NULL
       |WHERE thread_id = ?""".stripMargin

  // ---- checkpoints ----

  def latest(t: Tables): String =
    s"SELECT checkpoint_json FROM ${t.threads} WHERE thread_id = ? AND checkpoint_json IS NOT NULL"

  def setLatest(t: Tables): String =
    s"UPDATE ${t.threads} SET checkpoint_id = ?, checkpoint_json = ? WHERE thread_id = ?"

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

  /** The history oldest first, with each checkpoint's commit time: what `prune` decides on. */
  def historyAges(t: Tables): String =
    s"SELECT position, checkpoint_id, committed_at FROM ${t.history} WHERE thread_id = ? ORDER BY position"

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
