package org.llm4s.agent.testkit

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.trace.TraceContext
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
 * The cases are the ones the `RunQueue` Scaladoc states: submission - entries read back as submitted (the submitter's
 * trace context included, kept through a reopen and a retried key), ids in
 * submission order, the per-thread run order of rule 5 (an attempted entry first, then `Interrupt` entries, then the
 * rest, each by id; a later `Interrupt` closing an earlier one that has not started), `Reject` refused while the thread
 * has an open entry or a live claim, and submission keys looked up or inserted per tenant; cancel and interrupt (W3) -
 * an entry not started closed at once, a started one asked to stop durably, the request answered with its renewals
 * and takes, a cancel never replaced by an interrupt, per tenant; tenant scoping and the `TicketExpired` /
 * `TicketNotFound` answers; leases - only a thread's head taken, the oldest head first, one live lease per thread,
 * takeover once a lease expires by the store's clock with a greater token, renewal, release and every call under a
 * replaced lease refused; attempts - recorded with their run ids, a reused run id refused, `maxAttempts` bounding
 * them, and a thread deleted since submission closing its entry `Cancelled(ThreadDeleted)`; closes, their outcomes read
 * back, `awaitClosed` and reclamation by ticket lifetime; rule 4's evidence - `Checkpointer.prune` keeping the newest
 * checkpoint of an open entry's runs; one lease of many takes raced at once from as many queues over the storage; and,
 * for metrics (design 4.59), a take's `expiredLease` mark and each agent's `depth`.
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
      tryCommit(run, id, on, status).value

    /** [[commitAs]], answering the commit's result; `retract` retracts the turn, as a blocked run's close does. */
    def tryCommit(
      run: String,
      id: String,
      on: ThreadId = thread,
      status: CheckpointStatus = CheckpointStatus.Running,
      retract: Boolean = false
    ): Result[Vector[EventRecord]] =
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
      val result =
        checkpointer.commit(on, Commit(claim.token, Some(checkpoint), events = Vector(event), retractTurn = retract))
      checkpointer.release(on, claim.token).value
      result

    /** Deletes `on` under a fresh claim. */
    def deleteThread(on: ThreadId = thread): Unit =
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

  it should "keep the submitter's trace context with the entry, the first submission's under a retried key" in {
    val c       = Case()
    val context = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", sampled = false)
    val key     = SubmissionKey.of(s"key-${unique()}").toOption.get
    val traced  = Submission(c.thread, c.agent, QueuedInput.query("hello"), policy = BusyThreadPolicy.Enqueue)
    val id      = c.queue.submit(traced.withSubmissionKey(key).withTraceContext(context)).value.entryId
    val plain   = c.submit().value.entryId
    c.entry(id).trace shouldBe Some(context)
    c.entry(plain).trace shouldBe None
    // a retry under the key carries its own request's context: the same submission, answered with the first's entry
    val retried = c.queue.submit(traced.withSubmissionKey(key).withTraceContext(TraceContext.random())).value
    retried shouldBe Ticket(id, c.thread, Some(0), existing = true)
    c.reopened()
    c.entry(id).trace shouldBe Some(context)
    c.take().value.entry.trace shouldBe Some(context)
    c.entry(id).attemptConfig(RunId("attempt-1")).traceContext shouldBe Some(context)
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

  // ---- the busy-thread policy and cancel (W3) ----

  it should "refuse a Reject submission to a thread a live claim holds, until the claim is released or expires" in {
    val c     = Case()
    val busy  = GraphError.ThreadBusy(c.thread.value, None, GraphError.BusyHolder.Unnamed)
    val claim = c.checkpointer.claim(c.thread, ClaimRequest(RunId(s"direct-${unique()}"), None, ttl)).value
    c.submit(policy = BusyThreadPolicy.Reject).refused shouldBe busy
    c.ids() shouldBe empty
    c.checkpointer.release(c.thread, claim.token).value
    c.submit(policy = BusyThreadPolicy.Reject).value.position shouldBe Some(0)
    // a live claim does not refuse the other policies: they wait for it
    val other = c.newThread()
    c.checkpointer.claim(other, ClaimRequest(RunId(s"direct-${unique()}"), None, 5.seconds)).value
    c.submit(on = other, policy = BusyThreadPolicy.Reject).refused shouldBe
      GraphError.ThreadBusy(other.value, None, GraphError.BusyHolder.Unnamed)
    c.submit(on = other, policy = BusyThreadPolicy.Interrupt).value.position shouldBe Some(0)
    val expiring = c.newThread()
    c.checkpointer.claim(expiring, ClaimRequest(RunId(s"direct-${unique()}"), None, 5.seconds)).value
    c.submit(on = expiring, policy = BusyThreadPolicy.Reject).refused shouldBe a[GraphError.ThreadBusy]
    c.advance(6.seconds)
    c.submit(on = expiring, policy = BusyThreadPolicy.Reject).value.position shouldBe Some(0)
  }

  it should "close a cancelled entry that has not started at once, so it is never taken, also one that is leased" in {
    val c      = Case()
    val reason = CancelReason.Requested(Some("never mind"))
    val queued = c.submit().value.entryId
    c.queue.cancel(None, CancelTarget.Entry(queued), reason).value.map(_.outcome) shouldBe
      Vector(Some(EntryOutcome.Cancelled(reason)))
    c.take() shouldBe None
    c.ids() shouldBe empty
    // a closed entry is answered as it is
    c.queue.cancel(None, CancelTarget.Entry(queued), CancelReason.ThreadDeleted).value.map(_.outcome) shouldBe
      Vector(Some(EntryOutcome.Cancelled(reason)))
    // leased, but no attempt recorded: its worker finds it closed
    val leased = c.newThread()
    val id     = c.submit(on = leased).value.entryId
    val lease  = c.take().value.lease
    c.queue.cancel(None, CancelTarget.Entry(id), reason).value.head.isOpen shouldBe false
    c.queue.recordAttempt(lease, RunId(s"run-${unique()}")).refused shouldBe
      QueueError.EntryClosed(id, EntryOutcome.Cancelled(reason))
  }

  it should "ask a started entry to stop, durably, answering the request with the entry's renewals and takes" in {
    val c      = Case()
    val reason = CancelReason.Requested(Some("stop"))
    val id     = c.submit().value.entryId
    val leased = c.take().value
    leased.lease.stop shouldBe None
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val asked = c.queue.cancel(None, CancelTarget.Entry(id), reason).value
    asked.map(e => (e.id, e.isOpen, e.stopRequest)) shouldBe Vector((id, true, Some(StopRequest.Cancel(reason))))
    c.queue.renew(leased.lease, ttl).value.stop shouldBe Some(StopRequest.Cancel(reason))
    c.reopened()
    c.entry(id).stopRequest shouldBe Some(StopRequest.Cancel(reason))
    // a second cancel keeps the first's reason
    c.queue.cancel(None, CancelTarget.Entry(id), CancelReason.ThreadDeleted).value.head.stopRequest shouldBe
      Some(StopRequest.Cancel(reason))
    // the worker that takes it over once its lease has expired is told at the take
    c.advance(31.seconds)
    val taken = c.take("worker-2").value
    taken.lease.stop shouldBe Some(StopRequest.Cancel(reason))
    taken.entry.stopRequest shouldBe Some(StopRequest.Cancel(reason))
    c.queue.close(taken.lease, EntryOutcome.Cancelled(reason)).value.outcome shouldBe
      Some(EntryOutcome.Cancelled(reason))
  }

  it should "cancel a thread's open entries for the caller's tenant only: the queued closed, the started asked" in {
    val c       = Case()
    val tenant  = Some(TenantId(s"t-${unique()}"))
    val other   = Some(TenantId(s"o-${unique()}"))
    val reason  = CancelReason.Requested(None)
    val running = c.submit(tenant = tenant).value.entryId
    val leased  = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val queued  = c.submit(tenant = tenant).value.entryId
    val foreign = c.submit(tenant = other).value.entryId
    c.queue.cancel(other, CancelTarget.Entry(running), reason).refused shouldBe QueueError.TicketExpired(running)
    c.queue.cancel(None, CancelTarget.Thread(c.thread), reason).value shouldBe empty
    c.queue.cancel(tenant, CancelTarget.Thread(c.newThread()), reason).value shouldBe empty
    c.queue.cancel(tenant, CancelTarget.Thread(c.thread), reason).value.map(_.id) shouldBe Vector(running, queued)
    c.entry(queued, tenant).outcome shouldBe Some(EntryOutcome.Cancelled(reason))
    c.entry(running, tenant).stopRequest shouldBe Some(StopRequest.Cancel(reason))
    c.entry(running, tenant).isOpen shouldBe true
    c.entry(foreign, other).isOpen shouldBe true
    c.entry(foreign, other).stopRequest shouldBe None
  }

  it should "refuse an attempt at a leased head an Interrupt entry displaced, ending its lease" in {
    val c         = Case()
    val queued    = c.submit().value.entryId
    val leased    = c.take().value
    val interrupt = c.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).refused shouldBe
      QueueError.LeaseLost(queued, leased.lease.token, None)
    c.entry(queued).attempts shouldBe empty
    c.entry(queued).leaseHolder shouldBe None
    c.take("worker-2").value.entry.id shouldBe interrupt
  }

  it should "let an Interrupt entry reach only its own tenant's entries of the thread" in {
    val c       = Case()
    val mine    = Some(TenantId(s"m-${unique()}"))
    val theirs  = Some(TenantId(s"o-${unique()}"))
    val running = c.submit(tenant = mine).value.entryId
    val leased  = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val queued = c.submit(tenant = mine, policy = BusyThreadPolicy.Interrupt).value.entryId
    c.submit(tenant = theirs, policy = BusyThreadPolicy.Interrupt).value
    c.entry(running, mine).stopRequest shouldBe Some(StopRequest.Interrupt(queued))
    c.entry(queued, mine).isOpen shouldBe true
  }

  it should "ask a started entry to stop for the latest Interrupt entry, never over a cancel" in {
    val c       = Case()
    val reason  = CancelReason.Requested(Some("stop"))
    val running = c.submit().value.entryId
    val leased  = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value
    val waiting = c.submit().value.entryId
    val first   = c.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    c.entry(running).stopRequest shouldBe Some(StopRequest.Interrupt(first))
    c.queue.renew(leased.lease, ttl).value.stop shouldBe Some(StopRequest.Interrupt(first))
    // an entry that has not started is not asked: it has not run
    c.entry(waiting).stopRequest shouldBe None
    val second = c.submit(policy = BusyThreadPolicy.Interrupt).value.entryId
    c.entry(first).outcome shouldBe Some(EntryOutcome.Interrupted(Some(second)))
    c.entry(running).stopRequest shouldBe Some(StopRequest.Interrupt(second))
    c.ids() shouldBe Vector(running, second, waiting)
    c.queue.cancel(None, CancelTarget.Entry(running), reason).value
    c.entry(running).stopRequest shouldBe Some(StopRequest.Cancel(reason))
    c.submit(policy = BusyThreadPolicy.Interrupt).value
    c.entry(running).stopRequest shouldBe Some(StopRequest.Cancel(reason))
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

  it should "pass over the heads a take skips, a skipped head still holding its own thread back" in {
    val c       = Case()
    val skipped = c.submit().value
    c.submit().value
    val next    = c.submit(on = c.newThread()).value
    val passing = c.queue.take(TakeRequest("worker-1", Set(c.agent), ttl, Set(skipped.entryId))).value
    passing.value.entry.id shouldBe next.entryId
    c.queue.take(TakeRequest("worker-1", Set(c.agent), ttl, Set(skipped.entryId))).value shouldBe None
    c.take().value.entry.id shouldBe skipped.entryId
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
    c.deleteThread()
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
    c.deleteThread()
    val leased = c.take().value
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.ThreadDeleted]
  }

  it should "refuse every commit of an attempt recorded before its thread was deleted, whatever thread holds the id" in {
    val c = Case()
    c.commitAs("direct", "c1")
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value shouldBe a[AttemptStart.Recorded]
    // the delete lands between the attempt's record and its admission
    c.deleteThread()
    def admit(as: String, id: String): Result[Vector[EventRecord]] =
      val claim  = c.checkpointer.claim(c.thread, ClaimRequest(RunId(as), None, ttl)).value
      val parent = c.checkpointer.latest(c.thread).value.map(_.checkpoint.id)
      val checkpoint = Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        c.thread.value,
        as,
        CheckpointStatus.Running,
        start,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
        None,
        Some(claim.token)
      )
      val result = c.checkpointer.commit(c.thread, Commit(claim.token, Some(checkpoint)))
      c.checkpointer.release(c.thread, claim.token).value
      result
    admit(run, "admitted").refused shouldBe a[GraphError.InvalidCommit]
    c.checkpointer.latest(c.thread).value shouldBe None
    // a direct run founds the new thread as ever, and the entry still closes at its next attempt
    admit("direct-again", "fresh").value
    // nor onto the new thread a direct run founded meanwhile
    admit(run, "late").refused shouldBe a[GraphError.InvalidCommit]
    c.checkpointer.latest(c.thread).value.map(_.checkpoint.id) shouldBe Some("fresh")
    c.queue.recordAttempt(leased.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.ThreadDeleted]
  }

  it should "keep refusing an attempt recorded before its thread's delete once another worker closed its entry" in {
    val c = Case()
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value shouldBe a[AttemptStart.Recorded]
    c.deleteThread()
    // the first worker's lease lapses; another takes the entry over, and its attempt closes it
    c.advance(ttl + 1.second)
    val again = c.take("worker-2").value
    c.queue.recordAttempt(again.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.ThreadDeleted]
    c.entry(again.entry.id).outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
    // the first worker, still holding a claim on the id, never writes the deleted entry's input
    c.tryCommit(run, "late").refused shouldBe a[GraphError.InvalidCommit]
    c.checkpointer.latest(c.thread).value shouldBe None
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
    c.deleteThread()
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
    entry.admitted shouldBe false
    def plan() =
      AttemptPlan.of(
        entry,
        c.checkpointer.latestOfRuns(c.thread, entry.runIds).value,
        c.checkpointer.latest(c.thread).value.map(_.checkpoint)
      )
    plan() shouldBe AttemptPlan.Start
    c.commitAs(run, "admitted")
    plan() shouldBe AttemptPlan.Recover
    c.entry(entry.id).admitted shouldBe true
    c.commitAs("direct", "moved-on", status = CheckpointStatus.Completed)
    plan() shouldBe AttemptPlan.Close(EntryOutcome.Done(Some("moved-on"), Some(CheckpointStatus.Completed)))
  }

  it should "keep an entry admitted when a direct run's blocked turn retracts its run's checkpoints" in {
    val c = Case()
    c.commitAs("direct", "settled", status = CheckpointStatus.Completed)
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value shouldBe a[AttemptStart.Recorded]
    // the attempt admits the input and dies mid-run
    c.commitAs(run, "admitted")
    // a direct recovery's turn is blocked: its closing checkpoint retracts the turn, the attempt's checkpoint with it
    c.tryCommit("recovery", "blocked", status = CheckpointStatus.Failed, retract = true).value
    c.checkpointer.latestOfRuns(c.thread, Set(RunId(run))).value shouldBe None
    // the next attempt still knows the input was admitted, and closes the entry rather than run it again
    c.advance(ttl + 1.second)
    val again = c.take("worker-2").value
    val entry = c.queue.recordAttempt(again.lease, RunId(s"run-${unique()}")).value match
      case AttemptStart.Recorded(e) => e
      case other                    => fail(s"expected the attempt recorded, got $other")
    entry.admitted shouldBe true
    val plan = AttemptPlan.of(
      entry,
      c.checkpointer.latestOfRuns(c.thread, entry.runIds).value,
      c.checkpointer.latest(c.thread).value.map(_.checkpoint)
    )
    plan match
      case AttemptPlan.Close(EntryOutcome.Failed(FailureReason.RunFailed(message))) => message should include("blocked")
      case other => fail(s"expected the entry closed as the thread's run failed, got $other")
  }

  it should "not mark an entry admitted for a run it did not record, nor a closed one" in {
    val c     = Case()
    val first = c.submit().value.entryId
    val held  = c.take().value
    val run   = s"run-${unique()}"
    c.queue.recordAttempt(held.lease, RunId(run)).value
    c.queue.close(held.lease, EntryOutcome.Done(None, None)).value
    val second = c.submit().value.entryId
    c.commitAs("direct", "c1")
    c.commitAs(run, "late")
    c.entry(first).admitted shouldBe false
    c.entry(second).admitted shouldBe false
  }

  // ---- the store's sweep (design 4.53) ----

  /** Sweeps the whole store, a page at a time, with no guard. */
  private def sweepAll(c: Case, policy: SweepPolicy): Unit =
    @scala.annotation.tailrec
    def from(after: Option[ThreadId]): Unit =
      c.checkpointer.sweep(policy, after, SweepGuard.none).value.next match
        case None       => ()
        case Some(next) => from(Some(next))
    from(None)

  it should "keep the newest checkpoint of an open entry's runs through a sweep whose guard protects nothing" in {
    val c = Case()
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value
    c.commitAs(run, "admitted")
    (1 to 3).foreach(n => c.commitAs("direct", s"direct-$n"))
    sweepAll(c, SweepPolicy(RetentionPolicy(maxCheckpoints = Some(1)), batchSize = SweepPolicy.MaxBatchSize))
    c.checkpointer.latestOfRuns(c.thread, Set(RunId(run))).value.map(_.id) shouldBe Some("admitted")
    c.checkpointer.history(c.thread, None, 100).value.map(_.id) shouldBe Vector("direct-3", "admitted")
  }

  it should "answer the attempts of every tenant's open entries by thread, for the sweep's guard" in {
    val c       = Case()
    val quiet   = c.newThread()
    val waiting = c.newThread()
    val closed  = c.newThread()
    c.submit().value
    c.submit(on = waiting, tenant = Some(TenantId("t2"))).value
    c.submit(on = closed).value
    val leased = c.take().value
    val run    = RunId(s"run-${unique()}")
    c.queue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
    c.take().value.entry.threadId shouldBe waiting
    val other = c.take().value
    other.entry.threadId shouldBe closed
    c.queue.recordAttempt(other.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.Recorded]
    c.queue.close(other.lease, EntryOutcome.Done(None, None)).value
    c.queue.openRuns(Vector(c.thread, quiet, waiting, closed)).value shouldBe
      Map(c.thread -> Set(run), waiting -> Set.empty[RunId])
    c.queue.openRuns(Vector.empty).value shouldBe Map.empty
  }

  it should "say whether a take replaced an expired lease, never for a fresh or a given-back entry" in {
    val c = Case()
    c.submit().value
    val first = c.take().value
    first.expiredLease shouldBe false
    c.queue.release(first.lease).value
    val again = c.take().value
    again.expiredLease shouldBe false
    c.advance(31.seconds)
    val takeover = c.take("worker-2").value
    takeover.expiredLease shouldBe true
    takeover.entry.id shouldBe first.entry.id
  }

  it should "count each agent's waiting and leased open entries, and the oldest waiting one's age, by its clock" in {
    val c     = Case()
    val none  = AgentId.of(s"c-${unique()}").toOption.get
    val other = c.newThread()
    val third = c.newThread()
    c.submit().value
    c.submit().value
    c.advance(5.seconds)
    c.submit(on = other).value
    c.submit(on = third, by = c.other).value
    c.queue.depth(Set.empty).value shouldBe Map.empty
    val before = c.queue.depth(Set(c.agent, c.other, none)).value
    before.keySet shouldBe Set(c.agent, c.other)
    (before(c.agent).waiting, before(c.agent).leased) shouldBe (3L, 0L)
    (before(c.other).waiting, before(c.other).leased) shouldBe (1L, 0L)
    if exactExpiry then
      before(c.agent).oldestWaiting shouldBe Some(5.seconds)
      before(c.other).oldestWaiting shouldBe Some(Duration.Zero)
    // the oldest head leased: the thread's second entry, submitted with it, is now the oldest waiting
    c.advance(5.seconds)
    val leased = c.take().value
    val during = c.queue.depth(Set(c.agent)).value(c.agent)
    (during.waiting, during.leased) shouldBe (2L, 1L)
    if exactExpiry then during.oldestWaiting shouldBe Some(10.seconds)
    else during.oldestWaiting.value should be >= 10.seconds
    // an expired lease waits again; a closed entry is not counted
    c.advance(31.seconds)
    val lapsed = c.queue.depth(Set(c.agent)).value(c.agent)
    (lapsed.waiting, lapsed.leased) shouldBe (3L, 0L)
    val retaken = c.take().value
    retaken.entry.id shouldBe leased.entry.id
    c.queue.close(retaken.lease, EntryOutcome.Done(None, None)).value
    val after = c.queue.depth(Set(c.agent)).value(c.agent)
    (after.waiting, after.leased) shouldBe (2L, 0L)
  }

  it should "keep refusing a deleted entry's attempt once a sweep has reclaimed the thread's tombstone" in {
    val c = Case()
    c.commitAs("direct", "c1")
    c.submit().value
    val leased = c.take().value
    val run    = s"run-${unique()}"
    c.queue.recordAttempt(leased.lease, RunId(run)).value shouldBe a[AttemptStart.Recorded]
    c.deleteThread()
    c.advance(2.hours)
    sweepAll(c, SweepPolicy(tombstoneAge = Some(1.hour), batchSize = SweepPolicy.MaxBatchSize))
    // the tombstone is gone, but the fence lives in the queue
    c.tryCommit(run, "late").refused shouldBe a[GraphError.InvalidCommit]
    c.checkpointer.latest(c.thread).value shouldBe None
    val again = c.take("worker-2").value
    c.queue.recordAttempt(again.lease, RunId(s"run-${unique()}")).value shouldBe a[AttemptStart.ThreadDeleted]
  }

object RunQueueContract:

  /** A store's checkpointer and the run queue in the same database. */
  final case class Stores(checkpointer: Checkpointer, queue: RunQueue)
