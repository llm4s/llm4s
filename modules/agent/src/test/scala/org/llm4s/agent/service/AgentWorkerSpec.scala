package org.llm4s.agent.service

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, SpecTools }
import org.llm4s.agent.delegation.{ TaskRequest, TypedAgent }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.time.{ Clock, Instant, ZoneOffset }
import java.util.concurrent.{ ConcurrentLinkedQueue, Semaphore }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** [[AgentWorker]] over the in-memory store, with real agents (Stage 5 W2, design 4.56). */
class AgentWorkerSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with Eventually
    with BeforeAndAfterEach {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val agentId = AgentId.of("assistant").value

  /** A short lease, so an abandoned one expires within a test. */
  private val shortLease = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 150.millis))

  private val fast = WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds)

  private val started = new ConcurrentLinkedQueue[AgentWorker]()

  override def afterEach(): Unit = {
    started.asScala.foreach(_.shutdown())
    started.clear()
  }

  /** A model answering each call with `answer(n, conversation)`, `n` from 1. */
  final private class FnClient(answer: (Int, Conversation) => Result[Completion]) extends LLMClient {
    private val count = new AtomicInteger(0)
    val sent          = new ConcurrentLinkedQueue[Vector[Message]]()
    def calls: Int    = count.get

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      sent.add(conversation.messages.toVector)
      answer(count.incrementAndGet(), conversation)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  /** A call that blocks until it is interrupted, releasing `entered` as it starts. */
  private def blocked(entered: Semaphore): Result[Completion] = {
    entered.release()
    CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
      case Left(e) =>
        Thread.currentThread().interrupt()
        Left(CancelledError("model call", Some(e)))
      case Right(_) => Left(ValidationError("blocking", "was never interrupted"))
    }
  }

  private def answering(text: String): FnClient = new FnClient((_, _) => Right(CompletionFixture.simple(text)))

  private def agentOver(store: Checkpointer, client: LLMClient, id: String = "assistant"): Agent =
    Agent.builder(id, client).withRuntime(new GraphRuntime(store)).build().value

  private def workerOf(
    store: InMemoryCheckpointer,
    agents: Seq[QueuedAgent],
    settings: WorkerSettings = fast,
    queueSettings: RunQueueSettings = shortLease,
    queue: Option[RunQueue] = None
  ): AgentWorker = {
    val worker = AgentWorker(queue.getOrElse(store.runQueue), store, agents, settings, queueSettings).value
    started.add(worker)
    worker
  }

  private def submit(
    store: InMemoryCheckpointer,
    thread: ThreadId,
    input: QueuedInput = QueuedInput.query("hello"),
    maxAttempts: Int = 3,
    agent: AgentId = agentId
  ): EntryId =
    store.runQueue
      .submit(Submission(thread, agent, input, policy = BusyThreadPolicy.Enqueue, maxAttempts = maxAttempts))
      .value
      .entryId

  private def closed(store: InMemoryCheckpointer, id: EntryId): QueueEntry =
    store.runQueue.awaitClosed(None, id, 10.seconds).value

  private def thread(): ThreadId = ThreadId(s"worker-${java.util.UUID.randomUUID().toString.take(8)}")

  /**
   * An attempt that died mid-run: the entry leased as a dead worker for `ttl`, its attempt recorded and run on
   * `agent` until its model call blocks, then cancelled - the thread is left `Running` by that run, admitted, and the
   * dead worker's lease is left to expire.
   */
  private def diedMidRun(store: InMemoryCheckpointer, agent: Agent, on: ThreadId, entered: Semaphore): RunId = {
    val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val run    = RunId.random()
    store.runQueue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
    val running = agent.start(on, "hello", RunConfig().withRunId(run)).value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
    run
  }

  // ---- building ----

  "AgentWorker.apply" should "refuse no agents, and two agents with one id" in {
    val store = InMemoryCheckpointer()
    val agent = QueuedAgent(agentOver(store, answering("x")))
    AgentWorker(store.runQueue, store, Nil).left.value shouldBe a[ValidationError]
    AgentWorker(store.runQueue, store, Seq(agent, agent)).left.value shouldBe a[ValidationError]
  }

  // ---- running ----

  "An AgentWorker" should "run a queued start to its end and close its entry from the thread's state" in {
    val store  = InMemoryCheckpointer()
    val client = answering("hello back")
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, client))))
    val on     = thread()
    val id     = submit(store, on)
    worker.start()
    val entry = closed(store, id)
    val done  = entry.outcome.value.asInstanceOf[EntryOutcome.Done]
    done.status shouldBe Some(CheckpointStatus.Completed)
    entry.attempts should have size 1
    val latest = store.latest(on).value.value.checkpoint
    done.checkpointId shouldBe Some(latest.id)
    latest.runId shouldBe entry.attempts.head.runId.value
  }

  it should "run a queued resume and a queued recover" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("recovered")))
    val agent   = agentOver(store, client)
    val on      = thread()
    // a direct run that stopped mid-turn: its owner queues the recover
    val running = agent.start(on, "hello").value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    val worker = workerOf(store, Seq(QueuedAgent(agent)))
    worker.start()
    val recovered = closed(store, submit(store, on, QueuedInput.Recover))
    recovered.outcome.value shouldBe a[EntryOutcome.Done]
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
    // a resume of a thread that is not suspended is refused by the runtime: closed failed, not retried
    val resumed = closed(store, submit(store, on, QueuedInput.Resume(Map(InterruptId("i") -> ujson.True))))
    resumed.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.RunFailed]
    resumed.attempts should have size 1
  }

  it should "run a typed agent's queued start, rendering its input" in {
    val store  = InMemoryCheckpointer()
    val client = answering("report")
    val bound  = TypedAgent.text("writer", "Writes").bind(client, new GraphRuntime(store)).value
    val worker = workerOf(store, Seq(QueuedAgent.typed(bound)))
    val on     = thread()
    val id     = submit(store, on, QueuedInput.typed(TaskRequest("write it")), agent = bound.id)
    worker.start()
    closed(store, id).outcome.value shouldBe a[EntryOutcome.Done]
    client.sent.asScala.head.collect { case u: UserMessage => u.content }.mkString should include("write it")
  }

  it should "close an entry whose input does not decode failed, without retrying it" in {
    val store  = InMemoryCheckpointer()
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    val id     = submit(store, thread(), QueuedInput.Start(ujson.Num(1)))
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.RunFailed]
    entry.attempts should have size 1
  }

  it should "run at most its concurrency of entries at once" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((_, _) => blocked(entered))
    val worker  = workerOf(store, Seq(QueuedAgent(agentOver(store, client))), fast.withConcurrency(2))
    val ids     = Vector.fill(3)(submit(store, thread()))
    worker.start()
    entered.acquire(2)
    Thread.sleep(200)
    worker.health().running shouldBe 2
    ids.map(id => store.runQueue.entry(None, id).value.attempts.size).sum shouldBe 2
  }

  // ---- deferring and recovering ----

  it should "defer to a live claim on the thread without recording an attempt, and run once it is released" in {
    val store  = InMemoryCheckpointer()
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    val on     = thread()
    val claim  = store.claim(on, ClaimRequest(RunId("direct"), None, 30.seconds)).value
    val id     = submit(store, on)
    val tried  = new AtomicInteger(0)
    worker.beforeAttempt = _ => tried.incrementAndGet(): Unit
    worker.start()
    Thread.sleep(300)
    tried.get shouldBe 0
    store.runQueue.entry(None, id).value.attempts shouldBe empty
    store.release(on, claim.token).value
    closed(store, id).attempts should have size 1
  }

  it should "recover a dead worker's entry once its lease expires, continuing the thread" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("finished")))
    val agent   = agentOver(store, client)
    val on      = thread()
    val id      = submit(store, on)
    val first   = diedMidRun(store, agent, on, entered)
    val worker  = workerOf(store, Seq(QueuedAgent(agent)))
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    entry.attempts.map(_.runId).head shouldBe first
    entry.attempts should have size 2
    // the input was admitted once: one user message on the thread
    client.sent.asScala.last.collect { case u: UserMessage => u }.size shouldBe 1
  }

  it should "settle an exhausted entry's admitted thread, closing the turn's calls, and then run the next entry" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val calls   = new AtomicInteger(0)
    val slow = SpecTools.tool("slow") { (_, _) =>
      entered.release()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit
      ToolOutcome.Success(ToolContent.Json(ujson.Str("late")))
    }
    val client = new FnClient((_, _) =>
      if (calls.incrementAndGet() == 1) Right(SpecTools.calling(SpecTools.call("c1", "slow")))
      else Right(CompletionFixture.simple("next answer"))
    )
    val agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withTools(SpecTools.set(slow))
        .build()
        .value
    val on    = thread()
    val first = submit(store, on, maxAttempts = 1)
    diedMidRun(store, agent, on, entered)
    val second = submit(store, on, QueuedInput.query("again"))
    val worker = workerOf(store, Seq(QueuedAgent(agent)))
    worker.start()
    closed(store, first).outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(1))
    closed(store, second).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    val history = store.history(on, None, 100).value
    history.map(_.status) should contain(CheckpointStatus.Failed)
    // the second turn's model call saw a valid history: the dangling call answered with the synthetic result
    val seen = client.sent.asScala.last
    seen.collect { case t: ToolMessage => t.toolCallId -> t.content } shouldBe Vector(
      "c1" -> AgentWorker.ExhaustedResult
    )
  }

  it should "close an exhausted entry whose input was never admitted, without touching the thread" in {
    val store  = InMemoryCheckpointer()
    val on     = thread()
    val id     = submit(store, on, maxAttempts = 1)
    val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 200.millis)).value.value
    store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    worker.start()
    closed(store, id).outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(1))
    store.latest(on).value shouldBe None
  }

  // ---- graph versions ----

  it should "leave an entry its graph cannot take on for another worker, taking the other threads' entries past it" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    // a thread of another graph: this worker's agent cannot take it on
    agentOver(store, answering("x"), "writer").run(other, "hi").value
    val stuck  = submit(store, other)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))))
    worker.start()
    val runnable = submit(store, thread())
    closed(store, runnable).outcome.value shouldBe a[EntryOutcome.Done]
    val left = store.runQueue.entry(None, stuck).value
    left.isOpen shouldBe true
    left.attempts shouldBe empty
  }

  it should "close an entry no worker could take on Failed(Unrunnable) once it has waited unrunnableAfter" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    agentOver(store, answering("x"), "writer").run(other, "hi").value
    val stuck  = submit(store, other)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))), fast.withUnrunnableAfter(1.milli))
    Thread.sleep(5)
    worker.start()
    val entry = closed(store, stuck)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.Unrunnable]
    entry.attempts shouldBe empty
  }

  // ---- the lease ----

  it should "cancel a run whose lease renewal is refused, and write nothing more under the lease" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((_, _) => blocked(entered))
    val refuse  = new AtomicBoolean(false)
    val closes  = new AtomicInteger(0)
    val inner   = store.runQueue
    val queue = new RunQueue {
      def submit(s: Submission)                          = inner.submit(s)
      def entry(t: Option[TenantId], id: EntryId)        = inner.entry(t, id)
      def openEntries(t: Option[TenantId], th: ThreadId) = inner.openEntries(t, th)
      def take(r: TakeRequest)                           = inner.take(r)
      def release(l: Lease)                              = { closes.incrementAndGet(); inner.release(l) }
      def recordAttempt(l: Lease, r: RunId)              = inner.recordAttempt(l, r)
      def close(l: Lease, o: EntryOutcome)               = { closes.incrementAndGet(); inner.close(l, o) }
      def reclaim(d: FiniteDuration, n: Int)             = inner.reclaim(d, n)
      def openRuns(ts: Vector[ThreadId])                 = inner.openRuns(ts)
      def renew(l: Lease, ttl: FiniteDuration): Result[Lease] =
        if (refuse.get) Left(QueueError.LeaseLost(l.entryId, l.token, None)) else inner.renew(l, ttl)
    }
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, client))), queue = Some(queue))
    val id     = submit(store, thread())
    worker.start()
    entered.acquire()
    refuse.set(true)
    eventually(worker.health().running shouldBe 0)
    closes.get shouldBe 0
    store.runQueue.entry(None, id).value.isOpen shouldBe true
  }

  // ---- drain ----

  it should "drain: cancel runs past the grace period and give their entries back once they exited" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("done")))
    val agent   = agentOver(store, client)
    val worker  = workerOf(store, Seq(QueuedAgent(agent)), fast.withDrainGrace(100.millis))
    val on      = thread()
    val id      = submit(store, on)
    worker.start()
    entered.acquire()
    val report = worker.shutdown()
    report shouldBe DrainReport(Vector.empty, Vector(id), Vector.empty)
    worker.currentState shouldBe WorkerState.Stopped
    val entry = store.runQueue.entry(None, id).value
    entry.isOpen shouldBe true
    entry.leaseHolder shouldBe None
    // another worker recovers it at once, not after the lease's ttl
    val next = workerOf(store, Seq(QueuedAgent(agent)))
    next.start()
    closed(store, id).attempts should have size 2
  }

  it should "drain: let a run finish within the grace period" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val release = new Semaphore(0)
    val client = new FnClient((_, _) => {
      entered.release()
      release.acquire()
      Right(CompletionFixture.simple("done"))
    })
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, client))))
    val id     = submit(store, thread())
    worker.start()
    entered.acquire()
    val draining = new AtomicReference[DrainReport]()
    val drainer  = Thread.ofVirtual().start(() => draining.set(worker.shutdown()))
    Thread.sleep(100)
    release.release()
    drainer.join(10_000)
    draining.get shouldBe DrainReport(Vector(id), Vector.empty, Vector.empty)
    store.runQueue.entry(None, id).value.outcome.value shouldBe a[EntryOutcome.Done]
  }

  it should "drain: keep the lease of a run that does not exit by the second bound" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val release = new Semaphore(0)
    // a provider that ignores its interrupt
    val client = new FnClient((_, _) => {
      entered.release()
      // every interrupt swallowed, until the test lets it go
      while (CancelledError.catchInterrupt(release.acquire()).isLeft) {}
      Right(CompletionFixture.simple("late"))
    })
    val worker =
      workerOf(
        store,
        Seq(QueuedAgent(agentOver(store, client))),
        fast.withDrainGrace(50.millis).withExitTimeout(100.millis)
      )
    val id = submit(store, thread())
    worker.start()
    entered.acquire()
    worker.shutdown() shouldBe DrainReport(Vector.empty, Vector.empty, Vector(id))
    store.runQueue.entry(None, id).value.leaseHolder shouldBe Some(worker.holder)
    release.release()
  }

  it should "take nothing once it is stopped, and answer an empty report on a second shutdown" in {
    val store  = InMemoryCheckpointer()
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    worker.start()
    worker.shutdown()
    worker.shutdown() shouldBe DrainReport(Vector.empty, Vector.empty, Vector.empty)
    val id = submit(store, thread())
    Thread.sleep(100)
    store.runQueue.entry(None, id).value.leaseHolder shouldBe None
  }

  // ---- health ----

  it should "answer ready only while it runs and its store answers" in {
    val store  = InMemoryCheckpointer()
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    worker.health().ready shouldBe false
    worker.health().state shouldBe WorkerState.NotStarted
    worker.start()
    worker.health() shouldBe WorkerHealth(ready = true, WorkerState.Running, 0, Vector.empty)
    worker.shutdown()
    worker.health().ready shouldBe false
  }

  it should "answer not ready when its store fails" in {
    val store  = InMemoryCheckpointer()
    val broken = new AtomicBoolean(false)
    val failing = new ForwardingCheckpointer(store) {
      override def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
        if (broken.get) Left(ValidationError("store", "down")) else super.latest(threadId)
    }
    val worker = AgentWorker(store.runQueue, failing, Seq(QueuedAgent(agentOver(store, answering("x")))), fast).value
    started.add(worker)
    worker.start()
    worker.health().ready shouldBe true
    broken.set(true)
    val health = worker.health()
    health.ready shouldBe false
    health.problems.exists(_.contains("checkpoint store")) shouldBe true
  }

  // ---- retention ----

  /** A clock a test moves by hand. */
  final private class MovableClock(start: Instant) extends Clock {
    private val now                            = new AtomicReference(start)
    def advance(by: FiniteDuration)            = now.updateAndGet(_.plusNanos(by.toNanos)): Unit
    override def getZone                       = ZoneOffset.UTC
    override def withZone(z: java.time.ZoneId) = this
    override def instant(): Instant            = now.get
  }

  "AgentWorker.sweepOnce" should "reclaim closed entries past the ticket lifetime and keep the rest" in {
    val clock  = new MovableClock(Instant.parse("2026-10-22T12:00:00Z"))
    val store  = InMemoryCheckpointer(clock)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    val old    = submit(store, thread())
    val leased = store.runQueue.take(TakeRequest("w", Set(agentId), 30.seconds)).value.value
    store.runQueue.close(leased.lease, EntryOutcome.Done(None, None)).value
    val open = submit(store, thread())
    clock.advance(25.hours)
    val report = worker.sweepOnce().value
    report.entries shouldBe 1
    report.skipped shouldBe false
    store.runQueue.entry(None, old).left.value shouldBe QueueError.TicketExpired(old)
    store.runQueue.entry(None, open).value.isOpen shouldBe true
  }

  it should "keep the newest checkpoint of an open entry's runs through a pruning sweep" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("x")))
    val agent   = agentOver(store, client)
    val on      = thread()
    submit(store, on)
    val run = diedMidRun(store, agent, on, entered)
    val worker = workerOf(
      store,
      Seq(QueuedAgent(agent)),
      fast.withSweep(SweepPolicy(RetentionPolicy(maxCheckpoints = Some(1))))
    )
    worker.sweepOnce().value.examined should be >= 1
    store.latestOfRuns(on, Set(run)).value should not be empty
  }

  it should "skip a pass while another instance holds the sweep" in {
    val store  = InMemoryCheckpointer()
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    val other  = store.claim(AgentWorker.SweepLock, ClaimRequest(RunId("other-sweeper"), None, 30.seconds)).value
    worker.sweepOnce().value.skipped shouldBe true
    store.release(AgentWorker.SweepLock, other.token).value
    worker.sweepOnce().value.skipped shouldBe false
  }
}
