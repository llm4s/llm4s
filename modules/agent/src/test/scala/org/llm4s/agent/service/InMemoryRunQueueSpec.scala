package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * `InMemoryCheckpointer.runQueue` in this module's own terms (design 4.55): the `RunQueueContract` in
 * `llm4s-agent-testkit` runs against it too, and this suite pins its bookkeeping - keys, leases, the deletion fence and
 * the admission mark through reclamation - and the queue's small types, alongside `RunQueueSpec`.
 */
class InMemoryRunQueueSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val start  = Instant.parse("2026-10-21T12:00:00Z")
  private val agent  = AgentId.of("assistant").value
  private val other  = AgentId.of("other").value
  private val thread = ThreadId("queued")
  private val ttl    = 30.seconds

  /** A clock the test moves. */
  final private class MovingClock extends Clock {
    @volatile private var current: Instant     = start
    def instant(): Instant                     = current
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit      = current = current.plusNanos(by.toNanos)
  }

  final private class Fixture {
    val clock                   = new MovingClock
    val store                   = InMemoryCheckpointer(clock)
    val queue: InMemoryRunQueue = store.runQueue

    def submit(
      input: QueuedInput = QueuedInput.query("q"),
      policy: BusyThreadPolicy = BusyThreadPolicy.Enqueue,
      tenant: Option[TenantId] = None,
      key: Option[String] = None,
      on: ThreadId = thread,
      maxAttempts: Int = 3
    ): Result[Ticket] =
      queue.submit(Submission(on, agent, input, tenant, policy, key.map(SubmissionKey.of(_).value), maxAttempts))

    def take(holder: String = "w1"): Option[LeasedEntry] = queue.take(TakeRequest(holder, Set(agent), ttl)).value

    def attempt(lease: Lease, run: String): QueueEntry = queue.recordAttempt(lease, RunId(run)).value match {
      case AttemptStart.Recorded(e) => e
      case other                    => fail(s"expected the attempt recorded, got $other")
    }

    def commit(
      run: String,
      id: String,
      status: CheckpointStatus = CheckpointStatus.Running
    ): Result[Vector[EventRecord]] = {
      val claim  = store.claim(thread, ClaimRequest(RunId(run), None, ttl)).value
      val parent = store.latest(thread).value.map(_.checkpoint.id)
      val checkpoint = Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        thread.value,
        run,
        status,
        start,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
      )
      val result = store.commit(thread, Commit(claim.token, Some(checkpoint)))
      store.release(thread, claim.token).value
      result
    }

    def delete(): Unit = {
      val claim = store.claim(thread, ClaimRequest(RunId("deleter"), None, ttl)).value
      store.deleteThread(thread, claim.token, None).value
    }
  }

  // ---- submissions ----

  "InMemoryRunQueue" should "refuse a Reject submission to a busy thread, and answer a held key's ticket" in {
    val f     = Fixture()
    val first = f.submit(key = Some("k")).value
    first shouldBe Ticket(first.entryId, thread, Some(0), existing = false)
    f.submit(policy = BusyThreadPolicy.Reject).left.value shouldBe
      GraphError.ThreadBusy(thread.value, None, GraphError.BusyHolder.Unnamed)
    f.submit(key = Some("k")).value shouldBe first.copy(existing = true)
    val reused = f.submit(input = QueuedInput.Recover, key = Some("k")).left.value
    reused shouldBe QueueError.SubmissionKeyReused(SubmissionKey.of("k").value, first.entryId)
    reused.message should include("'k'")
    // a key is the tenant's own
    f.submit(key = Some("k"), tenant = Some(TenantId("t"))).value.existing shouldBe false
  }

  it should "close an unstarted Interrupt entry when a later one is submitted, and keep a started one first" in {
    val f      = Fixture()
    val queued = f.submit().value.entryId
    val early  = f.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    val later  = f.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    f.queue.entry(None, early).value.outcome shouldBe Some(EntryOutcome.Interrupted(Some(later)))
    f.queue.openEntries(None, thread).value.map(_.id) shouldBe Vector(later, queued)
    val leased = f.take().value
    leased.entry.id shouldBe later
    f.attempt(leased.lease, "r1")
    val latest = f.submit(policy = BusyThreadPolicy.Interrupt).value
    latest.position shouldBe Some(1)
    f.queue.openEntries(None, thread).value.map(_.id) shouldBe Vector(later, latest.entryId, queued)
    f.queue.openEntries(Some(TenantId("t")), thread).value shouldBe empty
  }

  // ---- reads ----

  it should "tell an entry it issued and no longer holds for the tenant from one it never issued" in {
    val f  = Fixture()
    val id = f.submit(tenant = Some(TenantId("t"))).value.entryId
    f.queue.entry(Some(TenantId("t")), id).value.id shouldBe id
    f.queue.entry(None, id).left.value shouldBe QueueError.TicketExpired(id)
    f.queue.entry(None, EntryId.unsafe(id.value + 1)).left.value shouldBe QueueError.TicketNotFound(
      EntryId.unsafe(id.value + 1)
    )
    QueueError.TicketExpired(id).message should include("expired")
    QueueError.TicketNotFound(id).message should include(id.value.toString)
  }

  it should "wait for a close, answer the entry as it is after the timeout, and end the wait when interrupted" in {
    val f  = Fixture()
    val id = f.submit().value.entryId
    f.queue.awaitClosed(None, id, 10.millis).value.isOpen shouldBe true
    val leased = f.take().value
    val waited = new AtomicReference[Result[QueueEntry]]()
    val waiter = Thread.ofVirtual().start(() => waited.set(f.queue.awaitClosed(None, id, 5.seconds)))
    Thread.sleep(50)
    f.queue.close(leased.lease, EntryOutcome.Cancelled(CancelReason.Requested(Some("why")))).value
    waiter.join(5000)
    waited.get.value.outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.Requested(Some("why"))))
    val open        = f.submit().value.entryId
    val interrupted = new AtomicReference[Result[QueueEntry]]()
    val sleeper     = Thread.ofVirtual().start(() => interrupted.set(f.queue.awaitClosed(None, open, 1.minute)))
    Thread.sleep(50)
    sleeper.interrupt()
    sleeper.join(5000)
    interrupted.get.left.value shouldBe a[CancelledError]
  }

  // ---- leases ----

  it should "take nothing for another agent, nothing while a live lease holds the thread, and take an expired one over" in {
    val f = Fixture()
    f.submit().value
    f.queue.take(TakeRequest("w", Set(other), ttl)).value shouldBe None
    val first = f.take().value
    f.take("w2") shouldBe None
    f.queue.renew(first.lease, ttl).value.expiresAt shouldBe start.plusSeconds(30)
    f.clock.advance(31.seconds)
    val second = f.take("w2").value
    second.lease.token.value should be > first.lease.token.value
    val lost = f.queue.renew(first.lease, ttl).left.value
    lost shouldBe QueueError.LeaseLost(first.lease.entryId, first.lease.token, Some(second.lease.token))
    lost.message should include("not its current lease")
    f.queue.recordAttempt(first.lease, RunId("r")).left.value shouldBe a[QueueError.LeaseLost]
    f.queue.close(first.lease, EntryOutcome.Done(None, None)).left.value shouldBe a[QueueError.LeaseLost]
    // a stale lease releases nothing, quietly; the current one gives the entry back at once
    f.queue.release(first.lease).value shouldBe (())
    f.queue.release(second.lease).value shouldBe (())
    f.take("w3").value.entry.leaseHolder shouldBe Some("w3")
  }

  it should "end the expired lease of a head an Interrupt entry displaced when it takes the new head" in {
    val f = Fixture()
    f.submit().value
    val displaced = f.take().value
    val head      = f.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    // the displaced entry's live lease holds the thread
    f.take("w2") shouldBe None
    f.clock.advance(31.seconds)
    f.take("w2").value.entry.id shouldBe head
    f.queue.renew(displaced.lease, ttl).left.value shouldBe
      QueueError.LeaseLost(displaced.lease.entryId, displaced.lease.token, None)
    f.queue.recordAttempt(displaced.lease, RunId("r")).left.value shouldBe a[QueueError.LeaseLost]
  }

  it should "refuse lease calls on a closed entry with EntryClosed, release included" in {
    val f      = Fixture()
    val leased = { f.submit().value; f.take().value }
    val closed = f.queue.close(leased.lease, EntryOutcome.Failed(FailureReason.Unrunnable("no agent"))).value
    closed.isOpen shouldBe false
    closed.position shouldBe None
    val refused = f.queue.release(leased.lease).left.value
    refused shouldBe QueueError.EntryClosed(
      leased.lease.entryId,
      EntryOutcome.Failed(FailureReason.Unrunnable("no agent"))
    )
    refused.message should include("failed")
    f.queue.renew(leased.lease, ttl).left.value shouldBe a[QueueError.EntryClosed]
    f.queue.recordAttempt(leased.lease, RunId("r")).left.value shouldBe a[QueueError.EntryClosed]
    val unknown = leased.lease.copy(entryId = EntryId.unsafe(99))
    f.queue.renew(unknown, ttl).left.value shouldBe QueueError.LeaseLost(unknown.entryId, unknown.token, None)
  }

  // ---- attempts ----

  it should "refuse a used run id, record none past maxAttempts, and close an entry whose thread was deleted" in {
    val f = Fixture()
    f.commit("direct", "c1").value
    f.submit(maxAttempts = 1).value
    val leased = f.take().value
    f.queue.recordAttempt(leased.lease, RunId("direct")).left.value shouldBe a[ValidationError]
    f.attempt(leased.lease, "r1")
    f.queue.recordAttempt(leased.lease, RunId("r1")).left.value shouldBe a[ValidationError]
    f.queue.recordAttempt(leased.lease, RunId("r2")).value shouldBe a[AttemptStart.Exhausted]
    f.queue.close(leased.lease, EntryOutcome.Failed(FailureReason.AttemptsExhausted(1))).value
    f.submit().value
    val next = f.take().value
    f.delete()
    f.queue.recordAttempt(next.lease, RunId("r3")).value match {
      case AttemptStart.ThreadDeleted(e) => e.outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
      case other                         => fail(s"unexpected $other")
    }
  }

  it should "mark an entry admitted once one of its runs commits, and keep the mark through a retraction" in {
    val f = Fixture()
    f.commit("direct", "settled", CheckpointStatus.Completed).value
    val id     = f.submit().value.entryId
    val leased = f.take().value
    f.attempt(leased.lease, "r1").admitted shouldBe false
    f.commit("r1", "admitted").value
    f.queue.entry(None, id).value.admitted shouldBe true
    // a retraction removes the run's checkpoint, never the mark
    val claim  = f.store.claim(thread, ClaimRequest(RunId("recovery"), None, ttl)).value
    val parent = f.store.latest(thread).value.map(_.checkpoint.id)
    val blocked = Checkpoint(
      Checkpoint.CurrentFormat,
      "blocked",
      parent,
      thread.value,
      "recovery",
      CheckpointStatus.Failed,
      start,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )
    f.store.commit(thread, Commit(claim.token, Some(blocked), retractTurn = true)).value
    f.store.release(thread, claim.token).value
    f.store.latestOfRuns(thread, Set(RunId("r1"))).value shouldBe None
    f.queue.entry(None, id).value.admitted shouldBe true
  }

  // ---- reclamation ----

  it should "reclaim closed entries oldest first, freeing their keys and the deletion fence on their attempts" in {
    val f      = Fixture()
    val first  = f.submit(key = Some("k")).value.entryId
    val leased = f.take().value
    f.attempt(leased.lease, "r1")
    f.delete()
    // the attempt recorded before the delete stays fenced, also once its entry is closed
    f.queue.recordAttempt(leased.lease, RunId("r2")).value shouldBe a[AttemptStart.ThreadDeleted]
    f.commit("r1", "late").left.value shouldBe a[GraphError.InvalidCommit]
    f.queue.reclaim(1.hour, 10).value shouldBe 0
    f.clock.advance(2.hours)
    f.queue.reclaim(1.hour, 0).value shouldBe 0
    f.queue.reclaim(1.hour, 10).value shouldBe 1
    f.queue.entry(None, first).left.value shouldBe QueueError.TicketExpired(first)
    // once the entry is gone, the run id names nothing on the thread and the key a new entry
    f.commit("r1", "after").value
    f.submit(key = Some("k")).value.existing shouldBe false
  }

  // ---- the default wait ----

  "RunQueue.awaitClosed" should "poll the entry until it closes, the timeout passes or the waiting thread is interrupted" in {
    val f = Fixture()
    // a queue that keeps the trait's polling wait, over the in-memory one
    val polling = new RunQueue {
      def submit(submission: Submission)                              = f.queue.submit(submission)
      def entry(tenantId: Option[TenantId], entryId: EntryId)         = f.queue.entry(tenantId, entryId)
      def openEntries(tenantId: Option[TenantId], threadId: ThreadId) = f.queue.openEntries(tenantId, threadId)
      def take(request: TakeRequest)                                  = f.queue.take(request)
      def renew(lease: Lease, ttl: FiniteDuration)                    = f.queue.renew(lease, ttl)
      def release(lease: Lease)                                       = f.queue.release(lease)
      def recordAttempt(lease: Lease, runId: RunId)                   = f.queue.recordAttempt(lease, runId)
      def close(lease: Lease, outcome: EntryOutcome)                  = f.queue.close(lease, outcome)
      def reclaim(ticketLifetime: FiniteDuration, limit: Int)         = f.queue.reclaim(ticketLifetime, limit)
    }
    val id = polling.submit(Submission(thread, agent, QueuedInput.query("q"))).value.entryId
    polling.awaitClosed(None, id, 150.millis).value.isOpen shouldBe true
    val leased = polling.take(TakeRequest("w", Set(agent), ttl)).value.get
    val waited = new AtomicReference[Result[QueueEntry]]()
    val waiter = Thread.ofVirtual().start(() => waited.set(polling.awaitClosed(None, id, 5.seconds)))
    Thread.sleep(150)
    polling.close(leased.lease, EntryOutcome.Done(None, None)).value
    waiter.join(5000)
    waited.get.value.outcome shouldBe Some(EntryOutcome.Done(None, None))
    val open        = polling.submit(Submission(thread, agent, QueuedInput.query("q"))).value.entryId
    val interrupted = new AtomicReference[Result[QueueEntry]]()
    val sleeper     = Thread.ofVirtual().start(() => interrupted.set(polling.awaitClosed(None, open, 1.minute)))
    Thread.sleep(150)
    sleeper.interrupt()
    sleeper.join(5000)
    interrupted.get.left.value shouldBe a[CancelledError]
    polling.awaitClosed(None, EntryId.unsafe(999), 1.second).left.value shouldBe a[QueueError.TicketNotFound]
  }

  // ---- the queue's small types ----

  "EntryOutcome" should "survive its stored JSON in every state, and refuse JSON that is none" in {
    val outcomes = Vector(
      EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed)),
      EntryOutcome.Done(None, None),
      EntryOutcome.Failed(FailureReason.AttemptsExhausted(5)),
      EntryOutcome.Failed(FailureReason.RunFailed("boom")),
      EntryOutcome.Failed(FailureReason.Unrunnable("no agent")),
      EntryOutcome.Cancelled(CancelReason.ThreadDeleted),
      EntryOutcome.Cancelled(CancelReason.Requested(Some("note"))),
      EntryOutcome.Cancelled(CancelReason.Requested(None)),
      EntryOutcome.Interrupted(Some(EntryId.unsafe(3))),
      EntryOutcome.Interrupted(None)
    )
    outcomes.foreach(o => EntryOutcome.fromJson(EntryOutcome.toJson(o)).value shouldBe o)
    outcomes.map(_.state).distinct shouldBe Vector("done", "failed", "cancelled", "interrupted")
    EntryOutcome.fromJson(ujson.Obj("state" -> "failed", "reason" -> "other")).left.value should include("unknown")
    EntryOutcome.fromJson(ujson.Str("done")).isLeft shouldBe true
  }

  "QueuedInput" should "name its kind and refuse a stored input it cannot read" in {
    QueuedInput.query("q").kind shouldBe "start"
    QueuedInput.Resume(Map.empty).kind shouldBe "resume"
    QueuedInput.fromJson(ujson.Obj("kind" -> "resume")).isLeft shouldBe true
    QueuedInput.fromJson(ujson.Str("start")).left.value should include("<none>")
  }

  "Submission" should "set each field, and tell a retry of itself from a different submission" in {
    val base = Submission(thread, agent, QueuedInput.query("q"))
    val key  = SubmissionKey.of("k").value
    val set = base
      .withTenantId(TenantId("t"))
      .withPolicy(BusyThreadPolicy.Interrupt)
      .withSubmissionKey(key)
      .withMaxAttempts(2)
    (set.tenantId, set.policy, set.submissionKey, set.maxAttempts) shouldBe
      (Some(TenantId("t")), BusyThreadPolicy.Interrupt, Some(key), 2)
    set.withTenantId(None).withSubmissionKey(None).tenantId shouldBe None
    set.sameAs(set.withMaxAttempts(5)) shouldBe true
    set.sameAs(set.withPolicy(BusyThreadPolicy.Enqueue)) shouldBe false
    an[IllegalArgumentException] should be thrownBy base.withMaxAttempts(0)
  }

  "The queue's ids and settings" should "encode as plain JSON values and order by number" in {
    upickle.default.write(EntryId.unsafe(4)) shouldBe "4"
    upickle.default.read[EntryId]("4") shouldBe EntryId.unsafe(4)
    val key = SubmissionKey.of("k").value
    upickle.default.read[SubmissionKey](upickle.default.write(key)) shouldBe key
    Vector(LeaseToken(3), LeaseToken(1)).sorted shouldBe Vector(LeaseToken(1), LeaseToken(3))
    Vector(EntryId.unsafe(3), EntryId.unsafe(1)).sorted shouldBe Vector(EntryId.unsafe(1), EntryId.unsafe(3))
    val settings = RunQueueSettings.default.withMaxAttempts(2).withTicketLifetime(1.hour).withLease(LeasePolicy.default)
    settings shouldBe RunQueueSettings(2, 1.hour, LeasePolicy.default)
    LeasePolicy.default.withTtl(1.minute).withRenewEvery(20.seconds) shouldBe LeasePolicy(1.minute, 20.seconds)
    an[IllegalArgumentException] should be thrownBy LeasePolicy.default.withRenewEvery(1.minute)
  }

  "RunSubmitter" should "submit with its settings' maxAttempts and the defaults, and load its settings" in {
    val store     = InMemoryCheckpointer()
    val submitter = new RunSubmitter(store.runQueue, store)
    val ticket    = submitter.submit(thread, agent, QueuedInput.query("q")).value
    val entry     = submitter.entry(None, ticket.entryId).value
    (entry.maxAttempts, entry.policy, entry.tenantId, entry.submissionKey) shouldBe
      (RunQueueSettings.default.maxAttempts, BusyThreadPolicy.Reject, None, None)
    org.llm4s.config.RunQueueConfigLoader.load().value shouldBe RunQueueSettings.default
  }
}
