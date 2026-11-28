package org.llm4s.agent.service

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, SpecTools }
import org.llm4s.agent.delegation.{ TaskRequest, TypedAgent }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.{ CancelledError, ProcessingError, ValidationError }
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
    with TestTimeLimit
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

  private val fast = WorkerSettings(
    pollInterval = 20.millis,
    sweepEvery = None,
    drainGrace = 2.seconds,
    retryDelay = 50.millis,
    maxRetryDelay = 200.millis
  )

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

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def contextWindow: Int     = 8192
    override def reserveCompletion: Int = 1024
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

  it should "run each attempt in the submitter's trace" in {
    val store   = InMemoryCheckpointer()
    val worker  = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))))
    val on      = thread()
    val context = org.llm4s.trace.TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
    val id = store.runQueue
      .submit(
        Submission(on, agentId, QueuedInput.query("q"), policy = BusyThreadPolicy.Enqueue).withTraceContext(context)
      )
      .value
      .entryId
    worker.start()
    closed(store, id).outcome.value shouldBe a[EntryOutcome.Done]
    store.latest(on).value.value.checkpoint.trace.map(_.traceId) shouldBe Some(context.traceId)
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

  // ---- a store failure during admission (#3084) ----

  /** `store`, whose next `latest` or `commit` fails once armed, as a store blip would. */
  final private class BlippingCheckpointer(store: Checkpointer) extends ForwardingCheckpointer(store) {
    val latestBlip = new AtomicBoolean(false)
    val commitBlip = new AtomicBoolean(false)
    override def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
      if (latestBlip.compareAndSet(true, false))
        Left(ProcessingError("test-checkpointer.latest", "the call did not finish within its call timeout"))
      else super.latest(threadId)
    override def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (commitBlip.compareAndSet(true, false)) Left(ProcessingError("test-checkpointer.commit", "the store is busy"))
      else super.commit(threadId, commit)
  }

  it should "give a start another attempt when the store fails as its run is admitted" in {
    val store  = InMemoryCheckpointer()
    val blips  = new BlippingCheckpointer(store)
    val client = answering("done")
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(blips, client))))
    val id     = submit(store, thread())
    blips.latestBlip.set(true)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    entry.attempts should have size 2
    client.calls shouldBe 1
  }

  it should "give a start another attempt when its admission's commit fails" in {
    val store  = InMemoryCheckpointer()
    val blips  = new BlippingCheckpointer(store)
    val client = answering("done")
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(blips, client))))
    val id     = submit(store, thread())
    blips.commitBlip.set(true)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    entry.attempts should have size 2
    client.calls shouldBe 1
  }

  it should "give a queued recover another attempt when the store fails as it is admitted" in {
    val store   = InMemoryCheckpointer()
    val blips   = new BlippingCheckpointer(store)
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("recovered")))
    val agent   = agentOver(blips, client)
    val on      = thread()
    val running = agent.start(on, "hello").value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    val worker = workerOf(store, Seq(QueuedAgent(agent)))
    val id     = submit(store, on, QueuedInput.Recover)
    blips.latestBlip.set(true)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe a[EntryOutcome.Done]
    entry.attempts should have size 2
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
  }

  it should "give an admitted entry's recovery another attempt when the store fails as it is admitted" in {
    val store   = InMemoryCheckpointer()
    val blips   = new BlippingCheckpointer(store)
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("finished")))
    val agent   = agentOver(blips, client)
    val on      = thread()
    val id      = submit(store, on, maxAttempts = 5)
    val first   = diedMidRun(store, agent, on, entered)
    blips.latestBlip.set(true)
    val worker = workerOf(store, Seq(QueuedAgent(agent)))
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    entry.attempts.map(_.runId).head shouldBe first
    entry.attempts should have size 3
  }

  it should "close an entry Failed(AttemptsExhausted) when every admission meets a store failure" in {
    val store = InMemoryCheckpointer()
    val down = new ForwardingCheckpointer(store) {
      override def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
        Left(ProcessingError("test-checkpointer.latest", "down"))
    }
    val client = answering("never")
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(down, client))))
    val id     = submit(store, thread(), maxAttempts = 2)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe FailureReason.AttemptsExhausted(2)
    client.calls shouldBe 0
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
      ToolOutcome.success(ToolContent.Json(ujson.Str("late")))
    }
    val client = new FnClient((_, _) =>
      if (calls.incrementAndGet() == 1) Right(SpecTools.calling(SpecTools.call("c1", "slow")))
      else Right(CompletionFixture.simple("next answer"))
    )
    val agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withToolSet(SpecTools.set(slow))
        .build()
        .value
    val on    = thread()
    val first = submit(store, on, maxAttempts = 1)
    diedMidRun(store, agent, on, entered)
    val second = submit(store, on, QueuedInput.query("again"))
    // the dead worker's crash is the one it may have (#3287): its next take dead-letters it
    val worker = workerOf(store, Seq(QueuedAgent(agent)), fast.withMaxCrashes(1))
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
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))), fast.withMaxCrashes(1))
    worker.start()
    closed(store, id).outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(1))
    store.latest(on).value shouldBe None
  }

  // ---- graph versions ----

  /**
   * A thread left `Running` by a direct run of `assistant` at another version of its graph (another system prompt),
   * cancelled in its model call: a worker's `assistant` cannot take that run on, but a worker at the old version could.
   */
  private def inFlightAtOtherVersion(store: InMemoryCheckpointer, on: ThreadId): Unit = {
    val entered = new Semaphore(0)
    val older = Agent
      .builder("assistant", new FnClient((_, _) => blocked(entered)))
      .withSystemPrompt("an older version")
      .withRuntime(new GraphRuntime(store))
      .build()
      .value
    val running = older.start(on, "hi").value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  it should "leave an entry its graph cannot take on for another worker, taking the other threads' entries past it" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    // a run in flight at another version of the graph: this worker's agent cannot take it on
    inFlightAtOtherVersion(store, other)
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
    inFlightAtOtherVersion(store, other)
    val stuck  = submit(store, other)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))), fast.withUnrunnableAfter(1.milli))
    Thread.sleep(5)
    worker.start()
    val entry = closed(store, stuck)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.Unrunnable]
    entry.attempts shouldBe empty
  }

  it should "close an entry for a thread of another agent's graph Failed(Unrunnable) at once (#3256)" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    // a thread the agent `writer` created: no version of `assistant` can ever take it on
    agentOver(store, answering("x"), "writer").run(other, "hi").value
    val stuck  = submit(store, other)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))))
    worker.start()
    val entry = closed(store, stuck)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason match {
      case FailureReason.Unrunnable(message) => message should include("writer")
      case other                             => fail(s"unexpected $other")
    }
    entry.attempts shouldBe empty
  }

  it should "act on a cancel of an entry it cannot take on whose input was never admitted, without waiting (#3256)" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    inFlightAtOtherVersion(store, other)
    val stuck = submit(store, other)
    // taken once by a worker whose graph cannot run it, so the entry has started and a cancel is a stop request
    val leased = store.runQueue.take(TakeRequest("earlier", Set(agentId), 100.millis)).value.value
    store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
    store.runQueue.cancel(None, CancelTarget.Entry(stuck), CancelReason.Requested(Some("stop"))).value
    Thread.sleep(150)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))))
    worker.start()
    closed(store, stuck).outcome.value shouldBe EntryOutcome.Cancelled(CancelReason.Requested(Some("stop")))
  }

  it should "take a runnable entry behind more passed-over heads than one take can skip (#3096)" in {
    val store = InMemoryCheckpointer()
    // runs in flight at another version, each with a head this worker passes over for a whole lease ttl
    val stuck = (0 until TakeRequest.MaxSkip + 40).map { _ =>
      val on = thread()
      inFlightAtOtherVersion(store, on)
      submit(store, on)
    }
    val runnable = submit(store, thread())
    // a default lease: each head is passed over for 30 seconds, so none comes back while the test runs
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))), queueSettings = RunQueueSettings())
    worker.start()
    store.runQueue.awaitClosed(None, runnable, 15.seconds).value.outcome.value shouldBe a[EntryOutcome.Done]
    stuck.map(store.runQueue.entry(None, _).value.isOpen).distinct shouldBe Vector(true)
  }

  it should "keep taking after a take throws (#3091)" in {
    val store  = InMemoryCheckpointer()
    val thrown = new AtomicInteger(0)
    val queue = new ForwardingRunQueue(store.runQueue) {
      override def take(r: TakeRequest) =
        if (thrown.getAndIncrement() < 2) throw new IllegalStateException("a store bug") else super.take(r)
    }
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))), queue = Some(queue))
    val id     = submit(store, thread())
    worker.start()
    closed(store, id).outcome.value shouldBe a[EntryOutcome.Done]
    thrown.get should be > 2
  }

  it should "close an entry whose thread does not decode at once, spending no attempt (#3264)" in {
    val store = InMemoryCheckpointer()
    val on    = thread()
    val corrupt = new ForwardingCheckpointer(store) {
      override def latest(threadId: ThreadId) =
        if (threadId == on) Left(GraphError.StoreCorrupt("test store", "latest", "checkpoint", "not JSON"))
        else super.latest(threadId)
    }
    val id = submit(store, on, maxAttempts = 5)
    val worker = AgentWorker(store.runQueue, corrupt, Seq(QueuedAgent(agentOver(store, answering("x")))), fast, shortLease).value
    started.add(worker)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.Unrunnable]
    entry.attempts shouldBe empty
  }

  it should "dead-letter an entry whose thread cannot be read, each failed read spending an attempt (#3093)" in {
    val store = InMemoryCheckpointer()
    val on    = thread()
    val broken = new ForwardingCheckpointer(store) {
      override def latest(threadId: ThreadId) =
        if (threadId == on) Left(ProcessingError("store", "a stored checkpoint does not decode"))
        else super.latest(threadId)
    }
    val id = submit(store, on, maxAttempts = 2)
    val worker = AgentWorker(
      store.runQueue,
      broken,
      Seq(QueuedAgent(agentOver(store, answering("x")))),
      fast,
      RunQueueSettings(lease = LeasePolicy(ttl = 300.millis, renewEvery = 100.millis))
    ).value
    started.add(worker)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(2))
    entry.attempts should have size 2
  }

  it should "close an entry for another tenant's thread at once, busy or not, judging nothing of that thread" in {
    val store = InMemoryCheckpointer()
    val other = thread()
    // tenant b's thread, of a graph this worker's agent cannot take on
    agentOver(store, answering("x"), "writer").run(other, "hi", RunConfig().withTenantId(TenantId("b"))).value
    // and busy: the entry must not defer to b's run, which a would then learn of
    store.claim(other, ClaimRequest(RunId("b-run"), Some(TenantId("b")), 30.seconds)).value
    val id = store.runQueue
      .submit(
        Submission(other, agentId, QueuedInput.query("q"), Some(TenantId("a")), BusyThreadPolicy.Enqueue)
      )
      .value
      .entryId
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("y")))))
    worker.start()
    val entry = store.runQueue.awaitClosed(Some(TenantId("a")), id, 10.seconds).value
    entry.outcome.value shouldBe EntryOutcome.Failed(
      FailureReason.RunFailed(GraphError.TenantMismatch(other.value, Some("a")).message)
    )
    entry.attempts shouldBe empty
  }

  it should "record no attempt while the probe claim cannot be given back" in {
    val store   = InMemoryCheckpointer()
    val failing = new AtomicBoolean(true)
    val refused = new Semaphore(0)
    val sticky = new ForwardingCheckpointer(store) {
      override def release(threadId: ThreadId, token: FencingToken): Result[Unit] =
        if (failing.get) { refused.release(); Left(ValidationError("store", "release failed")) }
        else super.release(threadId, token)
    }
    val worker = AgentWorker(
      store.runQueue,
      sticky,
      Seq(QueuedAgent(agentOver(store, answering("x")))),
      fast,
      RunQueueSettings(lease = LeasePolicy(ttl = 300.millis, renewEvery = 100.millis))
    ).value.withProbeTtl(200.millis) // a probe claim that is not given back holds the thread this long, not 5 s
    started.add(worker)
    val id = submit(store, thread())
    worker.start()
    // the worker has probed, and failed to give the claim back, twice - the second once the first claim had lapsed:
    // it has had its chances to record an attempt
    refused.tryAcquire(2, 10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    store.runQueue.entry(None, id).value.attempts shouldBe empty
    failing.set(false)
    closed(store, id).outcome.value shouldBe a[EntryOutcome.Done]
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
      def depth(as: Set[org.llm4s.agent.AgentId])        = inner.depth(as)
      def allDepths()                                    = inner.allDepths()
      def closeUntaken(age: FiniteDuration, limit: Int)  = inner.closeUntaken(age, limit)
      def cancel(t: Option[TenantId], c: CancelTarget, r: CancelReason) = inner.cancel(t, c, r)
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

  // #3241: a run longer than the grace period, cut by every drain, is never dead-lettered for it
  it should "drain: give a cut attempt back, so an entry with one attempt left survives the drain (#3241)" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n <= 2) blocked(entered) else Right(CompletionFixture.simple("done")))
    val agent   = agentOver(store, client)
    val id      = submit(store, thread(), maxAttempts = 1)
    (1 to 2).foreach { _ =>
      val worker = workerOf(store, Seq(QueuedAgent(agent)), fast.withDrainGrace(100.millis))
      worker.start()
      entered.acquire()
      worker.shutdown().cancelled shouldBe Vector(id)
      store.runQueue.entry(None, id).value.isOpen shouldBe true
    }
    workerOf(store, Seq(QueuedAgent(agent))).start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe a[EntryOutcome.Done]
    entry.attempts should have size 3
  }

  // #3241, Codex on #3286: a refund is for the slot's own attempt only. A slot that re-takes an entry whose earlier
  // attempt's lease lapsed, and is drained before it records one, releases it plainly: nothing is refunded.
  it should "drain: refund nothing for an earlier holder's attempt, when the slot recorded none of its own (#3241)" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, _) => if (n == 1) blocked(entered) else Right(CompletionFixture.simple("done")))
    val agent   = agentOver(store, client)
    val on      = thread()
    val id      = submit(store, on, maxAttempts = 2)
    diedMidRun(store, agent, on, entered)
    val refunds = new AtomicInteger(0)
    val queue = new ForwardingRunQueue(store.runQueue) {
      override private[llm4s] def releaseDrained(l: Lease) = { refunds.incrementAndGet(); super.releaseDrained(l) }
    }
    // the slot pauses before it decides; the drain marks it drained; only then does it decide
    val deciding = new java.util.concurrent.CountDownLatch(1)
    val drained  = new java.util.concurrent.CountDownLatch(1)
    val worker   = workerOf(store, Seq(QueuedAgent(agent)), fast.withDrainGrace(20.millis), queue = Some(queue))
    worker.beforeDecide = _ => {
      deciding.countDown()
      drained.await(10, java.util.concurrent.TimeUnit.SECONDS): Unit
    }
    worker.onDrained = _ => drained.countDown()
    worker.start()
    deciding.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    val drain = Thread.ofVirtual().start(() => worker.shutdown(): Unit)
    drain.join()
    drained.getCount shouldBe 0
    refunds.get shouldBe 0
    val entry = store.runQueue.entry(None, id).value
    entry.isOpen shouldBe true
    // the earlier holder's attempt was a crash, given back by the take (#3287); the drain gave back nothing more
    entry.crashes shouldBe 1
    entry.maxAttempts shouldBe 3
    entry.attempts should have size 1
  }

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
    // leased, so the sweep does not close it as untaken (#3093)
    val open = submit(store, thread())
    store.runQueue.take(TakeRequest("w", Set(agentId), 30.hours)).value.value.entry.id shouldBe open
    clock.advance(25.hours)
    val report = worker.sweepOnce().value
    report.entries shouldBe 1
    report.skipped shouldBe false
    store.runQueue.entry(None, old).left.value shouldBe QueueError.TicketExpired(old)
    store.runQueue.entry(None, open).value.isOpen shouldBe true
  }

  it should "close a head no worker took within unrunnableAfter, of an agent no worker serves (#3093)" in {
    val clock  = new MovableClock(Instant.parse("2026-10-22T12:00:00Z"))
    val store  = InMemoryCheckpointer(clock)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("x")))), fast.withUnrunnableAfter(1.hour))
    val typo   = AgentId.of("asistant").value
    val on     = thread()
    val stray  = submit(store, on, agent = typo)
    val behind = submit(store, on, agent = typo)
    store.runQueue.allDepths().value(typo).waiting shouldBe 2L
    clock.advance(30.minutes)
    worker.sweepOnce().value
    store.runQueue.entry(None, stray).value.isOpen shouldBe true
    clock.advance(31.minutes)
    worker.sweepOnce().value
    val failed = store.runQueue.entry(None, stray).value
    failed.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.Unrunnable]
    // the next head is closed only once it has been the head, unless it too was submitted long enough ago
    store.runQueue.entry(None, behind).value.isOpen shouldBe true
  }

  it should "reclaim closed entries although a page of the checkpoint sweep failed, and report the sweep's error" in {
    // #3086: a failing page must not keep the queue's closed entries, inputs included, past their lifetime
    val clock = new MovableClock(Instant.parse("2026-10-22T12:00:00Z"))
    val store = InMemoryCheckpointer(clock)
    val down  = ValidationError("store", "the sweep's page could not be read")
    val failing = new ForwardingCheckpointer(store) {
      override def sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard): Result[SweepPage] =
        Left(down)
    }
    val worker =
      AgentWorker(store.runQueue, failing, Seq(QueuedAgent(agentOver(store, answering("x")))), fast).value
    started.add(worker)
    val old    = submit(store, thread())
    val leased = store.runQueue.take(TakeRequest("w", Set(agentId), 30.seconds)).value.value
    store.runQueue.close(leased.lease, EntryOutcome.Done(None, None)).value
    clock.advance(25.hours)
    worker.sweepOnce().left.value shouldBe down
    store.runQueue.entry(None, old).left.value shouldBe QueueError.TicketExpired(old)
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

  // ---- reserved thread ids (#3239) ----

  "The worker's reserved thread ids" should "be refused as a run's thread and as a queued submission's (#3239)" in {
    val store = InMemoryCheckpointer()
    val agent = agentOver(store, answering("x"))
    Seq(AgentWorker.SweepLock, AgentWorker.HealthProbe).foreach { reserved =>
      agent.run(reserved, "hi").left.value.message should include("reserved")
      new RunSubmitter(store.runQueue, store)
        .submit(reserved, agentId, QueuedInput.query("hi"))
        .left
        .value
        .message should
        include("reserved")
      store.latest(reserved).value shouldBe None
    }
    // so the sweep is never skipped for a caller's run
    val worker = workerOf(store, Seq(QueuedAgent(agent)))
    worker.sweepOnce().value.skipped shouldBe false
  }

  // ---- a slot's run thread and the loops (#3240) ----

  it should "give a slot back when its run thread dies of a fatal throwable, so the next entry runs (#3240)" in {
    val store = InMemoryCheckpointer()
    val overflowing = new org.llm4s.agent.guardrails.InputGuardrail {
      val name: String = "Overflowing"
      def validate(value: String): Result[String] =
        if (value == "boom") throw new StackOverflowError("a guardrail's regex on a long input") else Right(value)
    }
    val agent =
      Agent
        .builder("assistant", answering("fine"))
        .withRuntime(new GraphRuntime(store))
        .withInputGuardrails(overflowing)
    val worker = workerOf(store, Seq(QueuedAgent(agent.build().value)), fast.withConcurrency(1))
    val boom   = submit(store, thread(), QueuedInput.query("boom"), maxAttempts = 2)
    worker.start()
    // the one slot comes back: an entry behind the fatal one runs, while the fatal one's lease runs out
    val next = submit(store, thread())
    closed(store, next).outcome.value shouldBe a[EntryOutcome.Done]
    store.runQueue.entry(None, boom).value.isOpen shouldBe true
  }

  // ---- retries (#3257) ----

  "A failed attempt" should "be given back for a retry that waits out its backoff, growing per attempt (#3257)" in {
    val store   = InMemoryCheckpointer()
    val started = new ConcurrentLinkedQueue[Long]()
    val client = new FnClient((n, _) => {
      started.add(System.nanoTime())
      if (n <= 2) Left(ProcessingError("provider", "overloaded")) else Right(CompletionFixture.simple("at last"))
    })
    val worker = workerOf(
      store,
      Seq(QueuedAgent(agentOver(store, client))),
      fast.withMaxRetryDelay(2.seconds).withRetryDelay(400.millis)
    )
    val id = submit(store, thread(), maxAttempts = 3)
    worker.start()
    closed(store, id).outcome.value shouldBe a[EntryOutcome.Done]
    val at = started.asScala.toVector
    at should have size 3
    // the first retry waits at least half of 400ms, the second at least half of 800ms
    (at(1) - at(0)).nanos should be >= 200.millis
    (at(2) - at(1)).nanos should be >= 400.millis
  }

  it should "not be retried when its error is one no retry can mend, closing the entry and settling the thread" in {
    val store = InMemoryCheckpointer()
    val client = new FnClient((_, _) =>
      Left(org.llm4s.error.AuthenticationError(org.llm4s.types.ProviderModelTypes.ProviderId("fixture"), "bad key"))
    )
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, client))))
    val on     = thread()
    val id     = submit(store, on, maxAttempts = 5)
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.RunFailed]
    entry.attempts should have size 1
    client.calls shouldBe 1
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Failed
  }

  "An entry whose attempts keep crashing" should "be dead-lettered at maxCrashes, its crashes never spending maxAttempts (#3287)" in {
    val store = InMemoryCheckpointer()
    val on    = thread()
    val id    = submit(store, on, maxAttempts = 2)
    // three workers die mid-attempt: each lease lapses with its attempt open
    (1 to 3).foreach { n =>
      val leased = store.runQueue.take(TakeRequest(s"dead-$n", Set(agentId), 50.millis)).value.value
      store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
      Thread.sleep(80)
    }
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("never")))), fast.withMaxCrashes(3))
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(3))
    entry.crashes shouldBe 3
    // every crash was given back, so none of them spent maxAttempts
    entry.maxAttempts shouldBe 5
  }

  it should "leave an entry at maxCrashes whose run this worker's graph cannot take on for another worker (#3287)" in {
    val store = InMemoryCheckpointer()
    val on    = thread()
    inFlightAtOtherVersion(store, on)
    val id     = submit(store, on, maxAttempts = 5)
    val leased = store.runQueue.take(TakeRequest("dead", Set(agentId), 50.millis)).value.value
    store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
    Thread.sleep(80)
    // at its crash bound, but passed over as unrunnable rather than settled by a graph that cannot restore it
    val worker = workerOf(
      store,
      Seq(QueuedAgent(agentOver(store, answering("y")))),
      fast.withMaxCrashes(1).withUnrunnableAfter(1.milli)
    )
    worker.start()
    closed(store, id).outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.Unrunnable]
  }

  it should "run once a crash is given back, however few attempts it had (#3287)" in {
    val store  = InMemoryCheckpointer()
    val id     = submit(store, thread(), maxAttempts = 1)
    val leased = store.runQueue.take(TakeRequest("dead", Set(agentId), 50.millis)).value.value
    store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
    Thread.sleep(80)
    val worker = workerOf(store, Seq(QueuedAgent(agentOver(store, answering("done")))))
    worker.start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe a[EntryOutcome.Done]
    entry.crashes shouldBe 1
  }

  "WorkerSettings.retryAfter" should "double per attempt up to the cap, less at most half (#3257)" in {
    val s = fast.withMaxRetryDelay(5.seconds).withRetryDelay(1.second)
    s.retryAfter(1, 0.0) shouldBe 1.second
    s.retryAfter(2, 0.0) shouldBe 2.seconds
    s.retryAfter(3, 0.0) shouldBe 4.seconds
    s.retryAfter(4, 0.0) shouldBe 5.seconds
    s.retryAfter(50, 0.0) shouldBe 5.seconds
    s.retryAfter(1, 0.999999) should be >= 500.millis
    an[IllegalArgumentException] should be thrownBy fast.withMaxRetryDelay(10.millis)
  }

  // ---- how a run's outcome closes its entry (#3255) ----

  "A queued turn a guardrail blocks" should "close its entry Failed(Blocked) naming the guardrail, never RunFailed (#3255)" in {
    val store = InMemoryCheckpointer()
    val refusing = new org.llm4s.agent.guardrails.InputGuardrail {
      val name: String = "NoBoom"
      def validate(value: String): Result[String] =
        if (value == "boom") Left(ValidationError("query", "no booms")) else Right(value)
    }
    val agent =
      Agent.builder("assistant", answering("fine")).withRuntime(new GraphRuntime(store)).withInputGuardrails(refusing)
    val worker = workerOf(store, Seq(QueuedAgent(agent.build().value)))
    val boom   = submit(store, thread(), QueuedInput.query("boom"))
    worker.start()
    val entry = closed(store, boom)
    entry.outcome.value shouldBe EntryOutcome.Failed(FailureReason.Blocked("NoBoom"))
    entry.attempts should have size 1
  }

  it should "keep sweeping after a pass throws, and report a sweep that is running (#3240)" in {
    val store  = InMemoryCheckpointer()
    val passes = new AtomicInteger(0)
    val throwing = new ForwardingCheckpointer(store) {
      override def sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard) =
        if (passes.getAndIncrement() < 2) throw new IllegalStateException("a store bug")
        else super.sweep(policy, after, guard)
    }
    val worker = AgentWorker(
      store.runQueue,
      throwing,
      Seq(QueuedAgent(agentOver(store, answering("x")))),
      fast.withSweepEvery(30.millis),
      shortLease
    ).value
    started.add(worker)
    worker.start()
    eventually(passes.get should be > 3)
    worker.health().problems.filter(_.contains("sweep")) shouldBe empty
  }
}
