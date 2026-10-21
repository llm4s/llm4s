package org.llm4s.agent.testkit

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.{ Assertion, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, CyclicBarrier, Executors, TimeUnit }
import scala.annotation.unused
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The contract every [[org.llm4s.agent.service.RunQueue]] must meet (design 4.55), as a ScalaTest suite. Mix it into a
 * spec and implement [[newStores]] - a store's checkpointer and the queue that lives in the same database:
 *
 * {{{
 * class SqliteRunQueueContractSpec extends AnyFlatSpec with RunQueueContract:
 *   protected def newStores(clock: java.time.Clock): RunQueueContract.Stores =
 *     val store = SqliteCheckpointer.open(freshDatabaseFile(), clock).toOption.get
 *     RunQueueContract.Stores(store, store.runQueue)
 * }}}
 *
 * The cases are the ones the `RunQueue` Scaladoc states: submission - entries read back as submitted, ids in
 * submission order, the per-thread run order of rule 5 (an attempted entry first, then `Interrupt` entries, then the
 * rest, each by id; a later `Interrupt` closing an earlier one that has not started), `Reject` refused while the thread
 * has an open entry, and submission keys looked up or inserted per tenant; tenant scoping and the `TicketExpired` /
 * `TicketNotFound` answers; leases - only a thread's head taken, the oldest head first, one live lease per thread,
 * takeover once a lease expires by the store's clock with a greater token, renewal, release and every call under a
 * replaced lease refused; attempts - recorded with their run ids, a reused run id refused, `maxAttempts` bounding
 * them, and a thread deleted since submission closing its entry `Cancelled(ThreadDeleted)`; closes, their outcomes read
 * back, `awaitClosed` and reclamation by ticket lifetime; rule 4's evidence - `Checkpointer.prune` keeping the newest
 * checkpoint of an open entry's runs; and one lease of many takes raced at once from as many queues over the storage.
 *
 * Lease expiry and close times are judged by the store's clock. By default the store must use the `clock` it is given,
 * and the suite moves a [[ManualClock]]; a store that judges time by a database server's clock overrides
 * [[advanceStoreClock]] and sets [[exactExpiry]] to `false`, as for `CheckpointerContract`. A durable store overrides
 * [[reopen]] and [[sibling]].
 *
 * Cases may share one database: each uses thread ids and agent ids of its own, and takes only its own agents' entries.
 */
trait RunQueueContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:
  import RunQueueContract.Stores

  /** A store's checkpointer and run queue for one case, judging time by `clock`. */
  protected def newStores(clock: Clock): Stores

  /** Closes `stores` and opens their storage again, as a restarted process would. The default hands them back. */
  protected def reopen(stores: Stores, @unused clock: ManualClock): Stores = stores

  /** Other stores over the same storage, as another process would open; the default hands `stores` back. */
  protected def sibling(stores: Stores, @unused clock: ManualClock): Stores = stores

  /** Closes stores a case opened besides its own ([[sibling]]). The default closes an `AutoCloseable` checkpointer. */
  protected def closeExtra(stores: Stores): Unit = stores.checkpointer match
    case closeable: AutoCloseable => closeable.close()
    case _                        => ()

  /** Makes the store's time move forward by `by`; the default moves `clock`. */
  protected def advanceStoreClock(@unused stores: Stores, clock: ManualClock, by: FiniteDuration): Unit =
    clock.advance(by)

  /** Whether lease expiries and close times are exactly the injected clock's; `false` for a store's own clock. */
  protected def exactExpiry: Boolean = true

  /** How long a case waits for a background step before it fails rather than hangs. */
  protected def contractWait: FiniteDuration = 10.seconds

  private val start = Instant.parse("2026-10-21T12:00:00Z")
  private val ttl   = 30.seconds

  private def unique(): String = java.util.UUID.randomUUID().toString.replace("-", "").take(20)

  final private class Case:
    val clock: ManualClock                = ManualClock(start)
    @volatile private var current         = newStores(clock)
    val agent: AgentId                    = AgentId.of(s"a-${unique()}").toOption.get
    val other: AgentId                    = AgentId.of(s"b-${unique()}").toOption.get
    val thread: ThreadId                  = newThread()
    def newThread(): ThreadId             = ThreadId(s"queue-contract-${unique()}")
    def queue: RunQueue                   = current.queue
    def checkpointer: Checkpointer        = current.checkpointer
    def reopened(): Unit                  = current = reopen(current, clock)
    def another(): Stores                 = sibling(current, clock)
    def advance(by: FiniteDuration): Unit = advanceStoreClock(current, clock, by)

    def submit(
      on: ThreadId = thread,
      input: QueuedInput = QueuedInput.query("hello"),
      policy: BusyThreadPolicy = BusyThreadPolicy.Enqueue,
      tenant: Option[TenantId] = None,
      key: Option[String] = None,
      maxAttempts: Int = 3,
      by: AgentId = agent
    ): Result[Ticket] =
      queue.submit(
        Submission(on, by, input, tenant, policy, key.map(SubmissionKey.of(_).toOption.get), maxAttempts)
      )

    def take(holder: String = "worker-1", agents: Set[AgentId] = Set(agent)): Option[LeasedEntry] =
      queue.take(TakeRequest(holder, agents, ttl)).value

    def entry(id: EntryId, tenant: Option[TenantId] = None): QueueEntry = queue.entry(tenant, id).value

    def ids(on: ThreadId = thread, tenant: Option[TenantId] = None): Vector[EntryId] =
      queue.openEntries(tenant, on).value.map(_.id)

    /** Commits checkpoint `id` on `on` as run `run`, under a fresh claim it then releases, with one event. */
    def commitAs(run: String, id: String, on: ThreadId = thread, status: CheckpointStatus = CheckpointStatus.Running) =
      val claim  = checkpointer.claim(on, ClaimRequest(RunId(run), None, ttl)).value
      val parent = checkpointer.latest(on).value.map(_.checkpoint.id)
      val checkpoint = Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        on.value,
        run,
        status,
        start,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
        None,
        Some(claim.token)
      )
      val event = EventDraft(run, Some(id), None, None, start, RunEvent.Custom("contract", 1, ujson.Obj()))
      checkpointer.commit(on, Commit(claim.token, Some(checkpoint), events = Vector(event))).value
      checkpointer.release(on, claim.token).value

    /** Deletes `on` under a fresh claim. */
    def delete(on: ThreadId = thread): Unit =
      val claim = checkpointer.claim(on, ClaimRequest(RunId(s"deleter-${unique()}"), None, ttl)).value
      checkpointer.deleteThread(on, claim.token, None).value

  extension [A](result: Result[A])
    private def refused: org.llm4s.error.LLMError = result match
      case Left(error)  => error
      case Right(value) => fail(s"expected a refusal, but the call succeeded with $value")

  private def same[A](actual: A, expected: A): Assertion =
    if actual == expected then succeed else fail(s"$actual was not equal to $expected")

  behavior.of("A RunQueue")

  // ---- submission ----

  it should "read an entry back as it was submitted, open, with no attempts" in {
    val c       = Case()
    val tenant  = Some(TenantId(s"t-${unique()}"))
    val history = Vector(org.llm4s.llmconnect.model.UserMessage("before"))
    val ticket  = c.submit(input = QueuedInput.query("hi", history), tenant = tenant, maxAttempts = 4).value
    ticket.threadId shouldBe c.thread
    ticket.position shouldBe Some(0)
    ticket.existing shouldBe false
    val entry = c.entry(ticket.entryId, tenant)
    entry.id shouldBe ticket.entryId
    entry.threadId shouldBe c.thread
    entry.agentId shouldBe c.agent
    entry.tenantId shouldBe tenant
    same(entry.input, QueuedInput.query("hi", history))
    entry.policy shouldBe BusyThreadPolicy.Enqueue
    entry.maxAttempts shouldBe 4
    entry.attempts shouldBe empty
    entry.isOpen shouldBe true
    entry.outcome shouldBe None
    entry.closedAt shouldBe None
    entry.leaseHolder shouldBe None
    entry.position shouldBe Some(0)
    if exactExpiry then entry.submittedAt shouldBe start
  }

  it should "keep resume answers and recover inputs as submitted" in {
    val c       = Case()
    val answers = Map(InterruptId("i-1") -> ujson.Obj("approved" -> true), InterruptId("i-2") -> ujson.Str("text"))
    val resume  = c.submit(input = QueuedInput.Resume(answers)).value
    val recover = c.submit(input = QueuedInput.Recover).value
    same(c.entry(resume.entryId).input, QueuedInput.Resume(answers))
    c.entry(recover.entryId).input shouldBe QueuedInput.Recover
  }

  it should "keep what it stores apart from the input a caller passed" in {
    val c     = Case()
    val value = ujson.Obj("text" -> "kept")
    val id    = c.submit(input = QueuedInput.Start(value)).value.entryId
    value("text") = "changed"
    same(c.entry(id).input, QueuedInput.Start(ujson.Obj("text" -> "kept")))
  }

  it should "issue entry ids in submission order and run a thread's entries in that order" in {
    val c      = Case()
    val first  = c.submit().value
    val second = c.submit().value
    val third  = c.submit().value
    first.entryId.value should be < second.entryId.value
    second.entryId.value should be < third.entryId.value
    (first.position, second.position, third.position) shouldBe ((Some(0), Some(1), Some(2)))
    c.ids() shouldBe Vector(first.entryId, second.entryId, third.entryId)
    c.entry(third.entryId).position shouldBe Some(2)
  }

  it should "refuse a Reject submission to a thread with an open entry, naming no holder" in {
    val c     = Case()
    val first = c.submit(policy = BusyThreadPolicy.Reject).value
    c.submit(policy = BusyThreadPolicy.Reject).refused shouldBe
      GraphError.ThreadBusy(c.thread.value, None, GraphError.BusyHolder.Unnamed)
    c.ids() shouldBe Vector(first.entryId)
    // once the entry is closed, the thread takes a Reject submission again
    val leased = c.take().value
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).value
    c.submit(policy = BusyThreadPolicy.Reject).value.position shouldBe Some(0)
  }

  it should "run an Interrupt entry ahead of the thread's queued entries, which keep their order" in {
    val c         = Case()
    val first     = c.submit().value
    val second    = c.submit().value
    val interrupt = c.submit(policy = BusyThreadPolicy.Interrupt).value
    interrupt.position shouldBe Some(0)
    c.ids() shouldBe Vector(interrupt.entryId, first.entryId, second.entryId)
    c.take().value.entry.id shouldBe interrupt.entryId
  }

  it should "close an earlier Interrupt entry that has not started when a later one is submitted" in {
    val c       = Case()
    val queued  = c.submit().value
    val earlier = c.submit(policy = BusyThreadPolicy.Interrupt).value
    val later   = c.submit(policy = BusyThreadPolicy.Interrupt).value
    val closed  = c.entry(earlier.entryId)
    closed.outcome shouldBe Some(EntryOutcome.Interrupted(Some(later.entryId)))
    closed.isOpen shouldBe false
    c.ids() shouldBe Vector(later.entryId, queued.entryId)
  }

  it should "keep an Interrupt entry that has started when a later one is submitted, running it first" in {
    val c       = Case()
    val earlier = c.submit(policy = BusyThreadPolicy.Interrupt).value
    val leased  = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val later = c.submit(policy = BusyThreadPolicy.Interrupt).value
    c.entry(earlier.entryId).isOpen shouldBe true
    c.ids() shouldBe Vector(earlier.entryId, later.entryId)
  }

  it should "run an entry that has recorded an attempt before an Interrupt entry submitted after it" in {
    val c       = Case()
    val running = c.submit().value
    val leased  = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val interrupt = c.submit(policy = BusyThreadPolicy.Interrupt).value
    c.ids() shouldBe Vector(running.entryId, interrupt.entryId)
    c.entry(interrupt.entryId).position shouldBe Some(1)
  }

  // ---- submission keys ----

  it should "answer a retried submission under its key with the first one's ticket" in {
    val c      = Case()
    val key    = s"key-${unique()}"
    val first  = c.submit(key = Some(key)).value
    val second = c.submit(key = Some(key)).value
    second.entryId shouldBe first.entryId
    second.existing shouldBe true
    second.position shouldBe Some(0)
    c.ids() shouldBe Vector(first.entryId)
    c.entry(first.entryId).submissionKey.map(_.value) shouldBe Some(key)
  }

  it should "refuse a different submission under a key the tenant holds" in {
    val c     = Case()
    val key   = s"key-${unique()}"
    val first = c.submit(key = Some(key)).value
    c.submit(input = QueuedInput.query("other"), key = Some(key)).refused shouldBe
      QueueError.SubmissionKeyReused(SubmissionKey.of(key).toOption.get, first.entryId)
    c.submit(on = c.newThread(), key = Some(key)).refused shouldBe a[QueueError.SubmissionKeyReused]
  }

  it should "hold submission keys per tenant" in {
    val c    = Case()
    val key  = s"key-${unique()}"
    val a    = c.submit(key = Some(key), tenant = Some(TenantId(s"a-${unique()}"))).value
    val b    = c.submit(key = Some(key), tenant = Some(TenantId(s"b-${unique()}"))).value
    val none = c.submit(key = Some(key)).value
    Set(a.entryId, b.entryId, none.entryId) should have size 3
  }

  it should "answer a key's closed entry until it is reclaimed, then take the key for a new entry" in {
    val c      = Case()
    val key    = s"key-${unique()}"
    val first  = c.submit(key = Some(key)).value
    val leased = c.take().value
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).value
    val again = c.submit(key = Some(key)).value
    again shouldBe Ticket(first.entryId, c.thread, None, existing = true)
    c.advance(2.hours)
    c.queue.reclaim(1.hour, 10000).value should be >= 1
    val fresh = c.submit(key = Some(key)).value
    fresh.existing shouldBe false
    fresh.entryId should not be first.entryId
  }

  // ---- tenants and tickets ----

  it should "answer an entry only to its own tenant, as if another tenant's were reclaimed" in {
    val c      = Case()
    val tenant = Some(TenantId(s"t-${unique()}"))
    val id     = c.submit(tenant = tenant).value.entryId
    c.queue.entry(None, id).refused shouldBe QueueError.TicketExpired(id)
    c.queue.entry(Some(TenantId(s"x-${unique()}")), id).refused shouldBe QueueError.TicketExpired(id)
    c.queue.awaitClosed(None, id, 10.millis).refused shouldBe QueueError.TicketExpired(id)
    c.queue.openEntries(None, c.thread).value shouldBe empty
    c.queue.openEntries(tenant, c.thread).value.map(_.id) shouldBe Vector(id)
  }

  it should "answer TicketNotFound for an id it never issued" in {
    val c     = Case()
    val id    = c.submit().value.entryId
    val never = EntryId.of(id.value + 1000000000L).toOption.get
    c.queue.entry(None, never).refused shouldBe QueueError.TicketNotFound(never)
  }

  it should "keep its entries across a reopen" in {
    val c  = Case()
    val id = c.submit(key = Some(s"key-${unique()}")).value.entryId
    c.reopened()
    c.entry(id).isOpen shouldBe true
    c.take().value.entry.id shouldBe id
  }

  // ---- leases ----

  it should "take the oldest head of its agents' threads, and nothing once none is free" in {
    val c      = Case()
    val one    = c.submit().value
    val behind = c.submit().value
    val two    = c.submit(on = c.newThread()).value
    val first  = c.take().value
    first.entry.id shouldBe one.entryId
    first.lease.holder shouldBe "worker-1"
    first.entry.leaseHolder shouldBe Some("worker-1")
    // the first thread's next entry waits for the head: one run per thread
    c.take().value.entry.id shouldBe two.entryId
    c.take() shouldBe None
    c.entry(behind.entryId).leaseHolder shouldBe None
  }

  it should "take only entries of the requested agents, a thread's other agent's head holding it back" in {
    val c       = Case()
    val blocked = c.submit(by = c.other).value
    c.submit().value
    c.take() shouldBe None
    c.take(agents = Set(c.other)).value.entry.id shouldBe blocked.entryId
    c.take(agents = Set.empty) shouldBe None
  }

  it should "lease for ttl by the store's clock, and let another holder take an expired lease over" in {
    val c     = Case()
    val id    = c.submit().value.entryId
    val first = c.take().value
    if exactExpiry then first.lease.expiresAt shouldBe start.plusSeconds(30)
    c.take() shouldBe None
    c.advance(31.seconds)
    val second = c.take("worker-2").value
    second.entry.id shouldBe id
    second.lease.token.value should be > first.lease.token.value
    second.lease.holder shouldBe "worker-2"
    val lost = QueueError.LeaseLost(id, first.lease.token, Some(second.lease.token))
    c.queue.renew(first.lease, ttl).refused shouldBe lost
    c.queue.recordAttempt(first.lease, RunId("late")).refused shouldBe lost
    c.queue.close(first.lease, EntryOutcome.Done(None, None)).refused shouldBe lost
    c.queue.release(first.lease).value
    c.entry(id).leaseHolder shouldBe Some("worker-2")
  }

  it should "end a displaced head's expired lease when an Interrupt entry ahead of it is taken" in {
    val c         = Case()
    val queued    = c.submit().value.entryId
    val displaced = c.take().value
    val interrupt = c.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    c.take() shouldBe None
    c.advance(31.seconds)
    val head = c.take("worker-2").value
    head.entry.id shouldBe interrupt
    val lost = QueueError.LeaseLost(queued, displaced.lease.token, None)
    c.queue.renew(displaced.lease, ttl).refused shouldBe lost
    c.queue.recordAttempt(displaced.lease, RunId(s"run-${unique()}")).refused shouldBe lost
    c.entry(queued).leaseHolder shouldBe None
    c.entry(queued).isOpen shouldBe true
  }

  it should "renew a lease, expired or not, while no other replaced it" in {
    val c = Case()
    c.submit().value
    val first = c.take().value
    c.advance(40.seconds)
    val renewed = c.queue.renew(first.lease, ttl).value
    renewed.token shouldBe first.lease.token
    renewed.expiresAt.isAfter(first.lease.expiresAt) shouldBe true
    if exactExpiry then renewed.expiresAt shouldBe start.plusSeconds(70)
    c.take() shouldBe None
  }

  it should "give a released entry back to be taken at once, still open" in {
    val c     = Case()
    val id    = c.submit().value.entryId
    val first = c.take().value
    c.queue.release(first.lease).value
    val entry = c.entry(id)
    entry.isOpen shouldBe true
    entry.leaseHolder shouldBe None
    val again = c.take("worker-2").value
    again.entry.id shouldBe id
    again.lease.token.value should be > first.lease.token.value
  }

  it should "grant one lease of many takes raced at once from as many queues over the storage" in {
    val c        = Case()
    val id       = c.submit().value.entryId
    val stores   = Vector.fill(8)(c.another())
    val barrier  = new CyclicBarrier(stores.size)
    val pool     = Executors.newFixedThreadPool(stores.size)
    val won      = new ConcurrentLinkedQueue[LeasedEntry]()
    val finished = new CountDownLatch(stores.size)
    stores.zipWithIndex.foreach { (s, i) =>
      pool.execute { () =>
        scala.util.Try(barrier.await(contractWait.toSeconds, TimeUnit.SECONDS))
        scala.util
          .Try(s.queue.take(TakeRequest(s"racer-$i", Set(c.agent), ttl)))
          .toOption
          .flatMap(_.toOption)
          .flatten
          .foreach(won.add)
        finished.countDown()
      }
    }
    val allDone = finished.await(contractWait.toSeconds, TimeUnit.SECONDS)
    pool.shutdownNow()
    stores.filterNot(s => s.queue eq c.queue).foreach(closeExtra)
    allDone shouldBe true
    won.asScala.toVector.map(_.entry.id) shouldBe Vector(id)
  }

  // ---- attempts ----

  it should "record each attempt's run id, time and holder, refusing a run id the entry used" in {
    val c      = Case()
    val id     = c.submit().value.entryId
    val leased = c.take().value
    val run    = RunId(s"run-${unique()}")
    val entry = c.queue.recordAttempt(leased.lease, run).value match
      case AttemptStart.Recorded(e) => e
      case other                    => fail(s"expected the attempt recorded, got $other")
    entry.attempts.map(a => (a.runId, a.holder)) shouldBe Vector(run -> "worker-1")
    if exactExpiry then entry.attempts.head.startedAt shouldBe start
    entry.runIds shouldBe Set(run)
    c.queue.recordAttempt(leased.lease, run).refused shouldBe a[ValidationError]
    c.entry(id).attempts.map(_.runId) shouldBe Vector(run)
  }

  it should "record no attempt past maxAttempts, answering Exhausted with the entry open" in {
    val c  = Case()
    val id = c.submit(maxAttempts = 2).value.entryId
    val runs = (1 to 2).map { n =>
      val leased = c.take(s"worker-$n").value
      val run    = RunId(s"run-${unique()}")
      c.queue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
      c.advance(31.seconds)
      run
    }
    val last = c.take("worker-3").value
    c.queue.recordAttempt(last.lease, RunId("third")).value match
      case AttemptStart.Exhausted(e) => e.runIds shouldBe runs.toSet
      case other                     => fail(s"expected Exhausted, got $other")
    c.entry(id).attempts should have size 2
    c.entry(id).isOpen shouldBe true
    val failed = c.queue.close(last.lease, EntryOutcome.Failed(FailureReason.AttemptsExhausted(2))).value
    failed.outcome shouldBe Some(EntryOutcome.Failed(FailureReason.AttemptsExhausted(2)))
  }

  it should "close an entry Cancelled(ThreadDeleted) when its thread was deleted after the submission" in {
    val c = Case()
    c.commitAs("direct", "c1")
    val id = c.submit().value.entryId
    c.delete()
    val leased = c.take().value
    c.queue.recordAttempt(leased.lease, RunId("late")).value match
      case AttemptStart.ThreadDeleted(e) =>
        e.outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
        e.attempts shouldBe empty
      case other => fail(s"expected ThreadDeleted, got $other")
    c.entry(id).isOpen shouldBe false
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).refused shouldBe a[QueueError.EntryClosed]
  }

  it should "close an entry Cancelled(ThreadDeleted) when its thread, never committed to, was deleted after submission" in {
    val c = Case()
    c.submit().value
    // a thread with a claim and nothing else: its delete leaves no tombstone and numbers nothing
    c.delete()
    val leased = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.ThreadDeleted]
  }

  it should "refuse an attempt's run id that already names a run or another entry's attempt on the thread" in {
    val c = Case()
    c.commitAs("direct", "c1")
    c.submit().value
    val first = c.take().value
    c.queue.recordAttempt(first.lease, RunId("direct")).refused shouldBe a[ValidationError]
    val run = RunId(s"run-${unique()}")
    c.queue.recordAttempt(first.lease, run).value shouldBe a[AttemptStart.Recorded]
    c.queue.close(first.lease, EntryOutcome.Done(None, None)).value
    c.submit().value
    val second = c.take().value
    c.queue.recordAttempt(second.lease, run).refused shouldBe a[ValidationError]
    second.entry.attempts shouldBe empty
    // another thread's run ids are its own
    c.submit(on = c.newThread()).value
    val elsewhere = c.take().value
    c.queue.recordAttempt(elsewhere.lease, run).value shouldBe a[AttemptStart.Recorded]
  }

  it should "not take a delete before the submission for one after it" in {
    val c = Case()
    c.commitAs("direct", "c1")
    c.delete()
    c.submit().value
    val leased = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.Recorded]
  }

  // ---- closing ----

  it should "close an entry once, with its outcome and time, and then take the thread's next entry" in {
    val c      = Case()
    val first  = c.submit().value.entryId
    val second = c.submit().value.entryId
    val leased = c.take().value
    c.advance(5.seconds)
    val outcome = EntryOutcome.Done(Some("c9"), Some(CheckpointStatus.Suspended))
    val closed  = c.queue.close(leased.lease, outcome).value
    closed.outcome shouldBe Some(outcome)
    closed.isOpen shouldBe false
    closed.position shouldBe None
    closed.leaseHolder shouldBe None
    if exactExpiry then closed.closedAt shouldBe Some(start.plusSeconds(5)) else closed.closedAt shouldBe defined
    c.entry(first).outcome shouldBe Some(outcome)
    c.queue.close(leased.lease, outcome).refused shouldBe QueueError.EntryClosed(first, outcome)
    c.queue.renew(leased.lease, ttl).refused shouldBe QueueError.EntryClosed(first, outcome)
    // a worker that would give the entry back learns it is terminal, not requeued
    c.queue.release(leased.lease).refused shouldBe QueueError.EntryClosed(first, outcome)
    c.ids() shouldBe Vector(second)
    c.entry(second).position shouldBe Some(0)
    c.take().value.entry.id shouldBe second
  }

  it should "record the thread's last event number when an entry closes" in {
    val c = Case()
    c.commitAs("direct", "c1")
    c.commitAs("direct", "c2")
    val id     = c.submit().value.entryId
    val leased = c.take().value
    c.entry(id).closedAtSeq shouldBe None
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).value.closedAtSeq shouldBe Some(2L)
    c.commitAs("direct", "c3")
    c.entry(id).closedAtSeq shouldBe Some(2L)
    val fresh  = c.submit(on = c.newThread()).value.entryId
    val second = c.take().value
    second.entry.id shouldBe fresh
    c.queue.close(second.lease, EntryOutcome.Done(None, None)).value.closedAtSeq shouldBe Some(0L)
  }

  it should "read every outcome back as it was closed with" in {
    val c = Case()
    val outcomes = Vector(
      EntryOutcome.Done(None, None),
      EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed)),
      EntryOutcome.Failed(FailureReason.AttemptsExhausted(3)),
      EntryOutcome.Failed(FailureReason.RunFailed("it failed")),
      EntryOutcome.Failed(FailureReason.Unrunnable("no worker")),
      EntryOutcome.Cancelled(CancelReason.ThreadDeleted),
      EntryOutcome.Cancelled(CancelReason.Requested(Some("stop"))),
      EntryOutcome.Cancelled(CancelReason.Requested(None)),
      EntryOutcome.Interrupted(None)
    )
    outcomes.foreach { outcome =>
      val id     = c.submit(on = c.newThread()).value.entryId
      val leased = c.take().value
      leased.entry.id shouldBe id
      c.queue.close(leased.lease, outcome).value
      c.reopened()
      c.entry(id).outcome shouldBe Some(outcome)
    }
  }

  it should "answer awaitClosed when the entry closes, or as it is after the timeout" in {
    val c      = Case()
    val id     = c.submit().value.entryId
    val leased = c.take().value
    c.queue.awaitClosed(None, id, 50.millis).value.isOpen shouldBe true
    val waiter = new java.util.concurrent.atomic.AtomicReference[Result[QueueEntry]]()
    val done   = new CountDownLatch(1)
    val thread = Thread.ofVirtual().start { () =>
      waiter.set(c.queue.awaitClosed(None, id, contractWait))
      done.countDown()
    }
    Thread.sleep(50)
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).value
    done.await(contractWait.toSeconds, TimeUnit.SECONDS) shouldBe true
    waiter.get.value.outcome shouldBe Some(EntryOutcome.Done(None, None))
    thread.join()
  }

  it should "end an awaitClosed wait when its thread is interrupted" in {
    val c      = Case()
    val id     = c.submit().value.entryId
    val waiter = new java.util.concurrent.atomic.AtomicReference[Result[QueueEntry]]()
    val thread = Thread.ofVirtual().start(() => waiter.set(c.queue.awaitClosed(None, id, contractWait)))
    Thread.sleep(50)
    thread.interrupt()
    thread.join(contractWait.toMillis)
    thread.isAlive shouldBe false
    waiter.get.refused shouldBe a[CancelledError]
  }

  // ---- retention ----

  it should "reclaim closed entries once their ticket lifetime has passed, never an open one" in {
    val c      = Case()
    val closed = c.submit().value.entryId
    val open   = c.submit(on = c.newThread()).value.entryId
    val leased = c.take().value
    leased.entry.id shouldBe closed
    c.queue.close(leased.lease, EntryOutcome.Done(None, None)).value
    c.advance(30.minutes)
    c.queue.reclaim(1.hour, 10000).value
    c.entry(closed).isOpen shouldBe false
    c.advance(31.minutes)
    c.queue.reclaim(1.hour, 10000).value should be >= 1
    c.queue.entry(None, closed).refused shouldBe QueueError.TicketExpired(closed)
    c.queue.awaitClosed(None, closed, 10.millis).refused shouldBe QueueError.TicketExpired(closed)
    c.entry(open).isOpen shouldBe true
    c.queue.reclaim(1.hour, 0).value shouldBe 0
  }

  // ---- rule 4's evidence ----

  it should "keep the newest checkpoint of an open entry's runs through prune, and let it go once closed" in {
    val c      = Case()
    val id     = c.submit().value.entryId
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value
    c.commitAs(run, "admitted")
    c.commitAs(run, "own-later")
    (1 to 3).foreach(n => c.commitAs("direct", s"direct-$n"))
    c.checkpointer.prune(c.thread, RetentionPolicy(maxCheckpoints = Some(1))).value
    c.checkpointer.latestOfRuns(c.thread, Set(RunId(run))).value.map(_.id) shouldBe Some("own-later")
    c.checkpointer.history(c.thread, None, 100).value.map(_.id) shouldBe Vector("direct-3", "own-later")
    c.queue.close(leased.lease, EntryOutcome.Done(Some("direct-3"), Some(CheckpointStatus.Running))).value
    c.checkpointer.prune(c.thread, RetentionPolicy(maxCheckpoints = Some(1))).value
    c.checkpointer.latestOfRuns(c.thread, Set(RunId(run))).value shouldBe None
    c.entry(id).outcome shouldBe defined
  }

  it should "classify an attempt by the run-id lookup, whatever ran on the thread since" in {
    val c = Case()
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    val entry = c.queue.recordAttempt(leased.lease, RunId(run)).value match
      case AttemptStart.Recorded(e) => e
      case other                    => fail(s"expected the attempt recorded, got $other")
    def plan() =
      AttemptPlan.of(
        entry,
        c.checkpointer.latestOfRuns(c.thread, entry.runIds).value,
        c.checkpointer.latest(c.thread).value.map(_.checkpoint)
      )
    plan() shouldBe AttemptPlan.Start
    c.commitAs(run, "admitted")
    plan() shouldBe AttemptPlan.Recover
    c.commitAs("direct", "moved-on", status = CheckpointStatus.Completed)
    plan() shouldBe AttemptPlan.Close(EntryOutcome.Done(Some("moved-on"), Some(CheckpointStatus.Completed)))
  }

object RunQueueContract:

  /** A store's checkpointer and the run queue in the same database. */
  final case class Stores(checkpointer: Checkpointer, queue: RunQueue)
