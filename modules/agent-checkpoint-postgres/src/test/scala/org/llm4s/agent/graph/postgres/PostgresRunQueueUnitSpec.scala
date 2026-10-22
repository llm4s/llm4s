package org.llm4s.agent.graph.postgres

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ OffsetDateTime, ZoneOffset }
import scala.concurrent.duration.*

/**
 * `PostgresCheckpointer.runQueue`'s statements and their order (design 4.55), and the store's own queue statements -
 * the commit's deletion fence and admission mark, `prune`'s evidence, `deleteThread`'s marks and the run-id lookup -
 * against scripted connections ([[ScriptedPostgres]]): what each call locks, reads and writes, with which parameters,
 * and what it answers for each row it reads. The queue against a real server is `modules/it`'s `@Docker`
 * `PostgresRunQueueContractSpec`.
 */
class PostgresRunQueueUnitSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val t                      = PostgresSchema.Tables("llm4s")
  private val now                    = OffsetDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneOffset.UTC)
  private val later                  = now.plusSeconds(30)
  private val earlier                = now.minusSeconds(30)
  private val agent                  = AgentId.unsafe("a")
  private val thread                 = ThreadId("t1")
  private val input                  = ujson.write(QueuedInput.toJson(QueuedInput.query("q")))
  private val tryLock                = "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))"
  private val lockSql                = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
  private def lockName(name: String) = s"""llm4s.agent.queue:"llm4s":$name"""

  /** A thread row in `Sql.row`'s columns, claimed with token 9 and no checkpoint. */
  private val claimed: Vector[Any] = Vector(null, 1L, 1L, 1L, "holder", 9L, later, null, null)

  /** An entry row in `PostgresRunQueue.Columns`' order. */
  private def entry(
    id: Long,
    threadId: String = "t1",
    policy: String = "Enqueue",
    tenant: String = null,
    key: String = null,
    attempts: Int = 0,
    maxAttempts: Int = 3,
    deleted: Boolean = false,
    holder: String = null,
    token: Any = null,
    expires: OffsetDateTime = null,
    outcome: String = null,
    admitted: Boolean = false,
    trace: String = null
  ): Vector[Any] =
    Vector(
      id,
      threadId,
      "a",
      tenant,
      input,
      policy,
      policy == "Interrupt",
      key,
      maxAttempts,
      attempts,
      now,
      deleted,
      holder,
      token,
      expires,
      outcome,
      if outcome == null then null else now,
      if outcome == null then null else 4L,
      admitted,
      trace
    )

  private def leased(id: Long, token: Long) = Lease(EntryId.unsafe(id), LeaseToken(token), "w", later.toInstant)

  private def script(answer: PartialFunction[(String, Vector[Any]), Vector[Vector[Any]]]) =
    ScriptedPostgres(now, answer)

  // ---- submissions ----

  "PostgresRunQueue.submit" should "insert under the thread's lock, from the sequence, and answer the entry's position" in {
    val s = script {
      case (sql, _) if sql == Sql.nextQueueEntryId(t)            => Vector(Vector(7L))
      case (sql, Vector("t1")) if sql == Sql.openQueueEntries(t) => Vector(entry(5), entry(7))
    }
    val ticket = s
      .store()
      .runQueue
      .submit(Submission(thread, agent, QueuedInput.query("q"), policy = BusyThreadPolicy.Enqueue))
      .value
    ticket shouldBe Ticket(EntryId.unsafe(7), thread, Some(1), existing = false)
    // no key: only the thread's lock, taken before anything is read
    s.madeWith(lockSql) shouldBe Vector(Vector(lockName("thread:t1")))
    s.made.indexWhere(_._1 == lockSql) should be < s.made.indexWhere(_._1 == Sql.openQueueEntries(t))
    s.madeWith(Sql.insertQueueEntry(t)) shouldBe Vector(
      Vector(
        7L,
        "t1",
        "a",
        null,
        input,
        "Enqueue",
        java.lang.Boolean.FALSE,
        "-",
        null,
        RunQueueSettings.DefaultMaxAttempts,
        now,
        null
      )
    )
  }

  it should "store the submitter's trace context as its traceparent, and read it back onto the entry" in {
    val context = org.llm4s.trace.TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
    val s = script {
      case (sql, _) if sql == Sql.nextQueueEntryId(t)            => Vector(Vector(7L))
      case (sql, Vector("t1")) if sql == Sql.openQueueEntries(t) => Vector(entry(7, trace = context.traceParent))
      case (sql, Vector(7L)) if sql == Sql.queueEntry(t)         => Vector(entry(7, trace = context.traceParent))
    }
    val queue = s.store().runQueue
    queue
      .submit(
        Submission(thread, agent, QueuedInput.query("q"), policy = BusyThreadPolicy.Enqueue).withTraceContext(context)
      )
      .value
    s.madeWith(Sql.insertQueueEntry(t)).map(_.last) shouldBe Vector(context.traceParent)
    queue.entry(None, EntryId.unsafe(7)).value.trace shouldBe Some(context)
  }

  it should "refuse a Reject submission to a thread with an open entry, inserting nothing" in {
    val s = script {
      case (sql, _) if sql == Sql.openQueueEntries(t) => Vector(entry(5))
    }
    s.store().runQueue.submit(Submission(thread, agent, QueuedInput.query("q"))).left.value shouldBe
      GraphError.ThreadBusy("t1", None, GraphError.BusyHolder.Unnamed)
    s.madeWith(Sql.insertQueueEntry(t)) shouldBe empty
    s.madeWith(Sql.nextQueueEntryId(t)) shouldBe empty
  }

  it should "close the thread's unstarted Interrupt entries when an Interrupt entry is submitted, and keep a started one" in {
    val s = script {
      case (sql, _) if sql == Sql.nextQueueEntryId(t) => Vector(Vector(9L))
      case (sql, _) if sql == Sql.openQueueEntries(t) =>
        Vector(entry(3, policy = "Interrupt", attempts = 1), entry(5, policy = "Interrupt"), entry(6))
    }
    s.store()
      .runQueue
      .submit(Submission(thread, agent, QueuedInput.query("q"), policy = BusyThreadPolicy.Interrupt))
      .value
    val closed = s.madeWith(Sql.closeUnstartedQueueEntry(t))
    closed.map(_.last) shouldBe Vector(5L)
    closed.head.take(1) shouldBe Vector("interrupted")
    EntryOutcome.fromJson(ujson.read(closed.head(1).toString)).value shouldBe EntryOutcome.Interrupted(
      Some(EntryId.unsafe(9))
    )
    // the close is guarded on the locked row: only while it is still open with no attempt
    Sql.closeUnstartedQueueEntry(t) should endWith("AND state = 'open' AND attempt_count = 0")
    s.madeWith(Sql.insertQueueEntry(t)).map(_(6)) shouldBe Vector(java.lang.Boolean.TRUE)
  }

  it should "lock the key before the thread, and answer a held key's entry or refuse a different submission under it" in {
    val key = SubmissionKey.of("k").value
    val s = script {
      case (sql, Vector("t:acme", "k")) if sql == Sql.queueEntryByKey(t) =>
        Vector(entry(4, tenant = "acme", key = "k"))
      case (sql, _) if sql == Sql.openQueueEntries(t) => Vector(entry(4, tenant = "acme", key = "k"))
    }
    val queue = s.store().runQueue
    val same =
      Submission(thread, agent, QueuedInput.query("q"), Some(TenantId("acme")), BusyThreadPolicy.Enqueue, Some(key))
    queue.submit(same).value shouldBe Ticket(EntryId.unsafe(4), thread, Some(0), existing = true)
    s.madeWith(lockSql).take(2) shouldBe Vector(Vector(lockName("key:t:acme:k")), Vector(lockName("thread:t1")))
    queue.submit(same.withPolicy(BusyThreadPolicy.Interrupt)).left.value shouldBe
      QueueError.SubmissionKeyReused(key, EntryId.unsafe(4))
    s.madeWith(Sql.insertQueueEntry(t)) shouldBe empty
    // the held entry is read locked, so a reclaim running at once either deletes it first or skips it
    Sql.queueEntryByKey(t) should endWith("FOR UPDATE")
  }

  // ---- reads ----

  "PostgresRunQueue.entry" should "answer TicketExpired for an issued id it does not hold for the tenant, TicketNotFound past them" in {
    val s = script {
      case (sql, Vector(4L)) if sql == Sql.queueEntry(t)      => Vector(entry(4, tenant = "acme"))
      case (sql, _) if sql == Sql.issuedQueueEntries(t)       => Vector(Vector(10L))
      case (sql, Vector(4L)) if sql == Sql.queueAttemptsOf(t) => Vector(Vector("r1", now, "w"))
    }
    val queue = s.store().runQueue
    val read  = queue.entry(Some(TenantId("acme")), EntryId.unsafe(4)).value
    read.attempts shouldBe Vector(Attempt(RunId("r1"), now.toInstant, "w"))
    read.tenantId shouldBe Some(TenantId("acme"))
    queue.entry(None, EntryId.unsafe(4)).left.value shouldBe QueueError.TicketExpired(EntryId.unsafe(4))
    queue.entry(None, EntryId.unsafe(10)).left.value shouldBe QueueError.TicketExpired(EntryId.unsafe(10))
    queue.entry(None, EntryId.unsafe(11)).left.value shouldBe QueueError.TicketNotFound(EntryId.unsafe(11))
  }

  it should "list only the tenant's open entries, each at its place in the thread's order" in {
    val s = script {
      case (sql, _) if sql == Sql.openQueueEntries(t) =>
        Vector(entry(2, tenant = "acme"), entry(3), entry(5, tenant = "acme"))
    }
    s.store().runQueue.openEntries(Some(TenantId("acme")), thread).value.map(e => e.id.value -> e.position) shouldBe
      Vector(2L -> Some(0), 5L -> Some(2))
  }

  // ---- take ----

  "PostgresRunQueue.take" should "read nothing for no agents" in {
    val s = script(PartialFunction.empty)
    s.store().runQueue.take(TakeRequest("w", Set.empty, 30.seconds)).value shouldBe None
    s.made.map(_._1).filter(_.contains("checkpoint_queue")) shouldBe empty
  }

  it should "scan past a full batch of heads whose threads are locked, and lease the first free one" in {
    val first = (1L to PostgresRunQueue.Candidates.toLong).map(i => Vector[Any](i, s"busy-$i")).toVector
    val s = script {
      case (sql, Vector(_, _, 0L, _)) if sql == Sql.queueCandidates(t) => first
      case (sql, Vector(_, _, after, _))
          if sql == Sql.queueCandidates(t) && after == PostgresRunQueue.Candidates.toLong =>
        Vector(Vector(100L, "free"))
      case (sql, Vector(name: String)) if sql == tryLock            => Vector(Vector(name.endsWith("thread:free")))
      case (sql, Vector(100L, _, _)) if sql == Sql.queueTakeable(t) => Vector(Vector(1))
      case (sql, _) if sql == Sql.nextLeaseToken(t)                 => Vector(Vector(42L))
      case (sql, Vector(100L)) if sql == Sql.queueEntry(t) =>
        Vector(entry(100, threadId = "free", holder = "w", token = 42L, expires = later))
    }
    val taken = s.store().runQueue.take(TakeRequest("w", Set(agent), 30.seconds)).value.value
    taken.lease shouldBe Lease(EntryId.unsafe(100), LeaseToken(42), "w", later.toInstant)
    taken.entry.threadId shouldBe ThreadId("free")
    // two batches: the first from the start, the second past its last id; the agents and the time bound each time
    s.madeWith(Sql.queueCandidates(t)) shouldBe Vector(
      Vector(Vector("a"), now, 0L, now),
      Vector(Vector("a"), now, PostgresRunQueue.Candidates.toLong, now)
    )
    // every busy head's thread lock was tried, never waited for, and none of them was checked or leased
    s.madeWith(tryLock).size shouldBe PostgresRunQueue.Candidates + 1
    s.madeWith(Sql.queueTakeable(t)).map(_.head) shouldBe Vector(100L)
    s.madeWith(Sql.siblingLeases(t)) shouldBe Vector(Vector("free", 100L))
    s.madeWith(Sql.endSiblingLeases(t)) shouldBe Vector(Vector("free", 100L))
    s.madeWith(Sql.setLease(t)) shouldBe Vector(Vector("w", 42L, later, 100L))
    s.made.indexWhere(_._1 == Sql.endSiblingLeases(t)) should be < s.made.indexWhere(_._1 == Sql.setLease(t))
    Sql.queueCandidates(t) should include("FOR UPDATE OF e SKIP LOCKED")
  }

  it should "stop at a batch shorter than the limit, and skip a head whose sibling still holds a live lease" in {
    val s = script {
      case (sql, _) if sql == Sql.queueCandidates(t) => Vector(Vector(8L, "held"))
      case (sql, _) if sql == tryLock                => Vector(Vector(true))
      case (sql, _) if sql == Sql.queueTakeable(t)   => Vector(Vector(1))
      // the sibling's lease, locked now, has not expired
      case (sql, _) if sql == Sql.siblingLeases(t) => Vector(Vector(later))
    }
    s.store().runQueue.take(TakeRequest("w", Set(agent), 30.seconds)).value shouldBe None
    s.madeWith(Sql.queueCandidates(t)).size shouldBe 1
    s.madeWith(Sql.setLease(t)) shouldBe empty
    s.madeWith(Sql.endSiblingLeases(t)) shouldBe empty
  }

  // ---- calls under a lease ----

  private def leaseScript(
    row: Vector[Any],
    extra: PartialFunction[(String, Vector[Any]), Vector[Vector[Any]]] = PartialFunction.empty
  ) =
    script(extra.orElse {
      case (sql, Vector(5L)) if sql == Sql.lockedQueueEntry(t) => Vector(row)
      case (sql, Vector(5L)) if sql == Sql.queueEntry(t)       => Vector(row)
      case (sql, Vector(5L)) if sql == Sql.queueEntryThread(t) => Vector(Vector("t1"))
      case (sql, _) if sql == Sql.runIdUsed(t)                 => Vector(Vector(false))
      case (sql, _) if sql == Sql.openQueueEntries(t)          => Vector(row)
    })

  "A call under a lease" should "renew, release and close only under the entry's current lease, locking its row first" in {
    val s     = leaseScript(entry(5, holder = "w", token = 42L, expires = earlier))
    val queue = s.store().runQueue
    queue.renew(leased(5, 42), 30.seconds).value.expiresAt shouldBe later.toInstant
    s.madeWith(Sql.renewLease(t)) shouldBe Vector(Vector(later, 5L))
    queue.renew(leased(5, 41), 30.seconds).left.value shouldBe
      QueueError.LeaseLost(EntryId.unsafe(5), LeaseToken(41), Some(LeaseToken(42)))
    // a stale lease releases nothing, quietly
    queue.release(leased(5, 41)).value shouldBe (())
    s.madeWith(Sql.releaseLease(t)) shouldBe empty
    queue.release(leased(5, 42)).value shouldBe (())
    s.madeWith(Sql.releaseLease(t)) shouldBe Vector(Vector(5L))
    queue.close(leased(5, 42), EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed))).value
    val closed = s.madeWith(Sql.closeQueueEntry(t))
    closed.map(c => (c.head, c(2), c(3))) shouldBe Vector(("done", now, 5L))
    // each of the five calls so far locked the entry's row before deciding
    s.madeWith(Sql.lockedQueueEntry(t)).size shouldBe 5
    queue.close(leased(5, 41), EntryOutcome.Done(None, None)).left.value shouldBe a[QueueError.LeaseLost]
  }

  it should "refuse every call on a closed entry with EntryClosed, release included, and a missing one as LeaseLost" in {
    val outcome = ujson.write(EntryOutcome.toJson(EntryOutcome.Failed(FailureReason.RunFailed("boom"))))
    val s       = leaseScript(entry(5, holder = null, outcome = outcome))
    val queue   = s.store().runQueue
    val closed  = QueueError.EntryClosed(EntryId.unsafe(5), EntryOutcome.Failed(FailureReason.RunFailed("boom")))
    queue.release(leased(5, 42)).left.value shouldBe closed
    queue.renew(leased(5, 42), 30.seconds).left.value shouldBe closed
    queue.recordAttempt(leased(5, 42), RunId("r")).left.value shouldBe closed
    queue.close(leased(5, 42), EntryOutcome.Done(None, None)).left.value shouldBe closed
    queue.renew(leased(6, 42), 30.seconds).left.value shouldBe QueueError.LeaseLost(
      EntryId.unsafe(6),
      LeaseToken(42),
      None
    )
    s.made.map(_._1).filter(_.startsWith("UPDATE")) shouldBe empty
  }

  "PostgresRunQueue.recordAttempt" should "take the thread's lock, then the entry's row, and record the attempt and its count" in {
    val s = leaseScript(entry(5, holder = "w", token = 42L, expires = later, attempts = 1))
    s.store().runQueue.recordAttempt(leased(5, 42), RunId("r2")).value shouldBe a[AttemptStart.Recorded]
    val order = s.made.map(_._1)
    order.indexOf(lockSql) should be < order.indexOf(Sql.lockedQueueEntry(t))
    s.madeWith(lockSql) shouldBe Vector(Vector(lockName("thread:t1")))
    // the run id is checked against the thread's attempts and history alike
    s.madeWith(Sql.runIdUsed(t)) shouldBe Vector(Vector("t1", "r2", "t1", "r2"))
    s.madeWith(Sql.insertQueueAttempt(t)) shouldBe Vector(Vector(5L, 2, "t1", "r2", now, "w"))
    s.madeWith(Sql.countQueueAttempt(t)) shouldBe Vector(Vector(5L))
  }

  it should "refuse a used run id, answer Exhausted at maxAttempts, and close an entry whose thread was deleted" in {
    val used = leaseScript(
      entry(5, holder = "w", token = 42L),
      { case (sql, _) if sql == Sql.runIdUsed(t) => Vector(Vector(true)) }
    )
    used.store().runQueue.recordAttempt(leased(5, 42), RunId("r")).left.value shouldBe a[ValidationError]
    used.madeWith(Sql.insertQueueAttempt(t)) shouldBe empty

    val spent = leaseScript(entry(5, holder = "w", token = 42L, attempts = 3, maxAttempts = 3))
    spent.store().runQueue.recordAttempt(leased(5, 42), RunId("r")).value shouldBe a[AttemptStart.Exhausted]
    spent.madeWith(Sql.insertQueueAttempt(t)) shouldBe empty

    val deleted = leaseScript(entry(5, holder = "w", token = 42L, deleted = true))
    deleted.store().runQueue.recordAttempt(leased(5, 42), RunId("r")).value shouldBe a[AttemptStart.ThreadDeleted]
    deleted.madeWith(Sql.closeQueueEntry(t)).map(_.head) shouldBe Vector("cancelled")
    deleted.madeWith(Sql.insertQueueAttempt(t)) shouldBe empty
  }

  // ---- reclaim ----

  "PostgresRunQueue.reclaim" should "remove the closed entries older than the lifetime, attempts first, and nothing for no limit" in {
    val s = script {
      case (sql, _) if sql == Sql.reclaimableQueueEntries(t) => Vector(Vector(3L), Vector(4L))
    }
    val queue = s.store().runQueue
    queue.reclaim(1.hour, 0).value shouldBe 0
    s.made.map(_._1).filter(_.contains("checkpoint_queue")) shouldBe empty
    queue.reclaim(1.hour, 10).value shouldBe 2
    s.madeWith(Sql.reclaimableQueueEntries(t)) shouldBe Vector(Vector(now.minusHours(1), 10))
    s.madeWith(Sql.deleteQueueAttempts(t)) shouldBe Vector(Vector(Vector("3", "4")))
    s.madeWith(Sql.deleteQueueEntries(t)) shouldBe Vector(Vector(Vector("3", "4")))
    s.made.indexWhere(_._1 == Sql.deleteQueueAttempts(t)) should be < s.made.indexWhere(
      _._1 == Sql.deleteQueueEntries(t)
    )
    Sql.reclaimableQueueEntries(t) should endWith("FOR UPDATE SKIP LOCKED")
  }

  // ---- the store's side of the queue ----

  private def checkpoint(id: String, run: String): Checkpoint =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      None,
      "t1",
      run,
      CheckpointStatus.Running,
      now.toInstant,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
      None,
      Some(FencingToken(9))
    )

  "PostgresCheckpointer.commit" should "refuse a checkpoint of a deleted entry's attempt, writing nothing" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t)             => Vector(claimed)
      case (sql, Vector("t1", "r1")) if sql == Sql.orphanedAttempt(t) => Vector(Vector(1))
    }
    s.store().commit(thread, Commit(FencingToken(9), Some(checkpoint("c1", "r1")))).left.value shouldBe
      a[GraphError.InvalidCommit]
    s.madeWith(Sql.insertHistory(t)) shouldBe empty
    s.madeWith(Sql.admitAttempt(t)) shouldBe empty
    // the fence holds whether or not the entry is still open
    (Sql.orphanedAttempt(t) should not).include("state")
  }

  it should "mark the entries that recorded its run admitted, in the commit, after the history row" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t) => Vector(claimed)
      case (sql, _) if sql == Sql.nextPosition(t)         => Vector(Vector(0L))
    }
    s.store().commit(thread, Commit(FencingToken(9), Some(checkpoint("c1", "r1")))).value shouldBe empty
    s.madeWith(Sql.orphanedAttempt(t)) shouldBe Vector(Vector("t1", "r1"))
    s.madeWith(Sql.insertHistory(t)).map(_.last) shouldBe Vector("r1")
    s.madeWith(Sql.admitAttempt(t)) shouldBe Vector(Vector("t1", "r1"))
    s.made.indexWhere(_._1 == Sql.insertHistory(t)) should be < s.made.indexWhere(_._1 == Sql.admitAttempt(t))
    // only an open entry is marked, and never unmarked
    Sql.admitAttempt(t) should include("state = 'open' AND NOT admitted")
  }

  it should "write a checkpoint's events and metadata beside the mark, and stamp nothing for a commit with none" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t) => Vector(claimed)
      case (sql, _) if sql == Sql.nextPosition(t)         => Vector(Vector(0L))
    }
    val store = s.store()
    val draft = EventDraft("r1", Some("c1"), None, None, now.toInstant, RunEvent.Custom("e", 1, ujson.Obj()))
    val commit = Commit(FencingToken(9), Some(checkpoint("c1", "r1")))
      .withEvents(Vector(draft))
      .withMetadata(ThreadMetadata(Map("k" -> "v")))
    store.commit(thread, commit).value.map(e => (e.seq, e.runId)) shouldBe Vector(1L -> "r1")
    s.madeWith(Sql.admitAttempt(t)) shouldBe Vector(Vector("t1", "r1"))
    s.madeWith(Sql.insertEvent(t)).map(_.take(3)) shouldBe Vector(Vector("t1", 1L, "c1"))
    s.madeWith(Sql.setNextSeq(t)) shouldBe Vector(Vector(2L, "t1"))
    s.madeWith(Sql.setMetadata(t)).map(_.last) shouldBe Vector("t1")
    // a commit of nothing reads no clock and marks nothing
    val before = s.made.size
    store.commit(thread, Commit(FencingToken(9), None)).value shouldBe empty
    s.made.drop(before).map(_._1) should not contain s"SELECT ${PostgresCheckpointer.ServerClock}"
    s.madeWith(Sql.admitAttempt(t)).size shouldBe 1
  }

  "PostgresCheckpointer.prune" should "keep the newest checkpoint of each run an open entry recorded, whatever the policy" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t) => Vector(claimed)
      case (sql, _) if sql == Sql.historyAges(t) =>
        Vector(Vector(0L, "c0", now, "queued"), Vector(1L, "c1", now, "direct"), Vector(2L, "c2", now, "direct"))
      case (sql, Vector("t1")) if sql == Sql.queueEvidence(t) => Vector(Vector(0L))
      case (sql, _) if sql == Sql.firstEventOf(t)             => Vector(Vector(null))
    }
    s.store().prune(thread, RetentionPolicy(maxCheckpoints = Some(1))).value
    // c2 by the policy, c0 as the queue's evidence: only c1 goes
    s.madeWith(Sql.deleteHistoryAt(t)) shouldBe Vector(Vector("t1", 1L))
    s.madeWith(Sql.queueEvidence(t)) shouldBe Vector(Vector("t1"))
    // the events' floor still follows the policy's checkpoints alone
    s.madeWith(Sql.firstEventOf(t)) shouldBe Vector(Vector("t1", Vector("c2")))
  }

  "PostgresCheckpointer.deleteThread" should "mark the thread's open queue entries in the delete's own transaction" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t) => Vector(claimed)
    }
    s.store().deleteThread(thread, FencingToken(9), None).value
    s.madeWith(Sql.queueThreadDeleted(t)) shouldBe Vector(Vector("t1"))
    s.made.indexWhere(_._1 == Sql.queueThreadDeleted(t)) should be < s.made.indexWhere(_._1 == Sql.tombstone(t))
    Sql.queueThreadDeleted(t) should include("state = 'open'")
  }

  it should "mark nothing for a delete refused as stale" in {
    val s = script {
      case (sql, Vector("t1")) if sql == Sql.lockedRow(t) => Vector(claimed)
    }
    s.store().deleteThread(thread, FencingToken(8), None).left.value shouldBe a[GraphError.StaleClaim]
    s.madeWith(Sql.queueThreadDeleted(t)) shouldBe empty
  }

  "PostgresCheckpointer.latestOfRuns" should "probe the runs as one text array, and read nothing for none" in {
    val json = ujson.write(Checkpoint.toJson(checkpoint("c1", "r1")))
    val s = script {
      case (sql, Vector(_, "t1")) if sql == Sql.latestOfRuns(t) => Vector(Vector(json))
    }
    val store = s.store()
    store.latestOfRuns(thread, Set.empty).value shouldBe None
    s.madeWith(Sql.latestOfRuns(t)) shouldBe empty
    store.latestOfRuns(thread, Set(RunId("r1"))).value.map(_.id) shouldBe Some("c1")
    store.latestOfRuns(ThreadId("t2"), Set(RunId("r1"))).value shouldBe None
    s.madeWith(Sql.latestOfRuns(t)).head shouldBe Vector(Vector("r1"), "t1")
  }
}
