package org.llm4s.agent.service

import org.llm4s.agent.{ Agent, AgentId, AgentStatus, CompletionFixture, SpecTools }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  ApprovalMiddleware,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse
}
import org.llm4s.agent.graph.tool.{ AgentTool, LockKey, ToolContent, ToolOutcome }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, Semaphore, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Cancel from any machine and the busy-thread policy (Stage 5 W3, design 4.57): [[AgentWorker]] stopping its runs for
 * a cancel or an interrupt asked through the queue, settling them, and the policies at submission - over the in-memory
 * store, with real agents.
 */
class AgentWorkerStopSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with Eventually
    with BeforeAndAfterEach {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val agentId = AgentId.of("assistant").value

  /** A short lease renewed often, so a stop asked through the queue reaches the worker within a test. */
  private val shortLease = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 100.millis))

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

  /** The text of the last user message `messages` ends its conversation with. */
  private def lastUser(messages: Vector[Message]): String =
    messages.collect { case u: UserMessage => u.content }.last

  /** Answers with the last user message's text, so a test can tell which input each model call was for. */
  private def echoing(n: Int, conversation: Conversation): Result[Completion] =
    Right(CompletionFixture.simple(s"answer $n to ${lastUser(conversation.messages.toVector)}"))

  private def agentOver(store: Checkpointer, client: LLMClient, tools: AgentTool[?]*): Agent = {
    val builder = Agent.builder("assistant", client).withRuntime(new GraphRuntime(store))
    (if (tools.isEmpty) builder else builder.withTools(SpecTools.set(tools*))).build().value
  }

  private def workerOf(store: InMemoryCheckpointer, agent: Agent, queueSettings: RunQueueSettings = shortLease) = {
    val worker = AgentWorker(store.runQueue, store, Seq(QueuedAgent(agent)), fast, queueSettings).value
    started.add(worker)
    worker
  }

  private def submit(
    store: InMemoryCheckpointer,
    thread: ThreadId,
    query: String = "hello",
    policy: BusyThreadPolicy = BusyThreadPolicy.Enqueue
  ): Result[Ticket] =
    store.runQueue.submit(Submission(thread, agentId, QueuedInput.query(query), policy = policy))

  private def closed(store: InMemoryCheckpointer, id: EntryId): QueueEntry =
    store.runQueue.awaitClosed(None, id, 10.seconds).value

  private def thread(): ThreadId = ThreadId(s"stop-${java.util.UUID.randomUUID().toString.take(8)}")

  private def requested(note: String): CancelReason = CancelReason.Requested(Some(note))

  /** The settled checkpoint a stop of kind `kind` left: the thread at rest, `Failed`, with that outcome. */
  private def settledAs(store: InMemoryCheckpointer, on: ThreadId, kind: String): Checkpoint = {
    val latest = store.latest(on).value.value.checkpoint
    latest.status shouldBe CheckpointStatus.Failed
    latest.outcome.value.asInstanceOf[RunOutcome.Failed].kind shouldBe kind
    latest
  }

  // ---- cancel ----

  "A cancel asked through the queue" should "stop the running entry, settle its thread and let the next entry start" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val worker  = workerOf(store, agentOver(store, client))
    val on      = thread()
    val first   = submit(store, on, "first").value.entryId
    val second  = submit(store, on, "second").value.entryId
    worker.start()
    entered.acquire()
    // from another machine: only the queue is shared
    val asked = store.runQueue.cancel(None, CancelTarget.Entry(first), requested("changed my mind")).value.entries
    asked.map(_.stopRequest) shouldBe Vector(Some(StopRequest.Cancel(requested("changed my mind"))))
    asked.head.isOpen shouldBe true
    closed(store, first).outcome.value shouldBe EntryOutcome.Cancelled(requested("changed my mind"))
    closed(store, second).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    // the cancelled run settled itself under its own claim: a Failed checkpoint of its own run, then the next turn
    val history  = store.history(on, None, 100).value
    val settled  = history.find(_.status == CheckpointStatus.Failed).value
    val attempt1 = store.runQueue.entry(None, first).value.attempts.head.runId
    settled.runId shouldBe attempt1.value
    settled.outcome.value.asInstanceOf[RunOutcome.Failed].kind shouldBe AgentWorker.CancelledKind
    lastUser(client.sent.asScala.last) shouldBe "second"
  }

  it should "give each tool call cut off a synthetic cancelled result, so the next turn's history is valid" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val slow = SpecTools.tool("slow") { (_, _) =>
      entered.release()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit
      ToolOutcome.Success(ToolContent.Json(ujson.Str("late")))
    }
    val client =
      new FnClient((n, c) => if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "slow"))) else echoing(n, c))
    val worker = workerOf(store, agentOver(store, client, slow))
    val on     = thread()
    val first  = submit(store, on, "first").value.entryId
    worker.start()
    entered.acquire()
    store.runQueue.cancel(None, CancelTarget.Thread(on), requested("stop")).value.entries.map(_.id) shouldBe Vector(
      first
    )
    closed(store, first).outcome.value shouldBe EntryOutcome.Cancelled(requested("stop"))
    settledAs(store, on, AgentWorker.CancelledKind)
    val second = submit(store, on, "second").value.entryId
    closed(store, second).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    client.sent.asScala.last.collect { case t: ToolMessage => t.toolCallId -> t.content } shouldBe Vector(
      "c1" -> AgentWorker.CancelledResult
    )
  }

  it should "close the entry with the run's own outcome when the run reached rest before the cancel reached it" in {
    val store  = InMemoryCheckpointer()
    val gate   = new CountDownLatch(1)
    val inCall = new Semaphore(0)
    val client = new FnClient((n, c) => {
      inCall.release()
      gate.await(10, TimeUnit.SECONDS)
      echoing(n, c)
    })
    // renewals too rare to deliver the cancel before the run ends: the worker reads it when it closes the entry
    val slow   = RunQueueSettings(lease = LeasePolicy(ttl = 30.seconds, renewEvery = 10.seconds))
    val worker = workerOf(store, agentOver(store, client), slow)
    val on     = thread()
    val id     = submit(store, on).value.entryId
    worker.start()
    inCall.acquire()
    store.runQueue.cancel(None, CancelTarget.Entry(id), requested("too late")).value
    gate.countDown()
    closed(store, id).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
  }

  it should "close a queued entry before it runs" in {
    val store  = InMemoryCheckpointer()
    val client = new FnClient(echoing)
    val on     = thread()
    val id     = submit(store, on).value.entryId
    val asked  = store.runQueue.cancel(None, CancelTarget.Entry(id), requested("never mind")).value.entries
    asked.head.outcome shouldBe Some(EntryOutcome.Cancelled(requested("never mind")))
    workerOf(store, agentOver(store, client)).start()
    val next = submit(store, on, "next").value.entryId
    closed(store, next).outcome.value shouldBe a[EntryOutcome.Done]
    client.calls shouldBe 1
    lastUser(client.sent.asScala.head) shouldBe "next"
  }

  it should "be acted on by the next worker when the entry's worker died, settling the thread with no new attempt" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val agent   = agentOver(store, client)
    val on      = thread()
    val id      = submit(store, on).value.entryId
    // a worker that died mid-run: its attempt admitted, the thread left Running, its lease left to expire
    val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val run    = RunId.random()
    store.runQueue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
    val running = agent.start(on, "hello", RunConfig().withRunId(run)).value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    store.runQueue.cancel(None, CancelTarget.Entry(id), requested("abandoned")).value.entries.head.isOpen shouldBe true
    workerOf(store, agent).start()
    val entry = closed(store, id)
    entry.outcome.value shouldBe EntryOutcome.Cancelled(requested("abandoned"))
    entry.attempts should have size 1
    settledAs(store, on, AgentWorker.CancelledKind)
    client.calls shouldBe 1
  }

  it should "leave a local cancel of a direct run as it was: the thread stays Running for its owner" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val agent   = agentOver(store, new FnClient((_, _) => blocked(entered)))
    val on      = thread()
    val running = agent.start(on, "hello").value
    entered.acquire()
    running.cancel()
    running.await().left.value shouldBe a[GraphError.Cancelled]
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  // ---- interrupt ----

  "An Interrupt submission" should "cut the running entry's model call and run next, ahead of the queued entries" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val worker  = workerOf(store, agentOver(store, client))
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    val queued  = submit(store, on, "queued").value.entryId
    worker.start()
    entered.acquire()
    val interrupting = submit(store, on, "interrupting", BusyThreadPolicy.Interrupt).value
    interrupting.position shouldBe Some(1)
    closed(store, running).outcome.value shouldBe EntryOutcome.Interrupted(Some(interrupting.entryId))
    closed(store, interrupting.entryId).outcome.value shouldBe a[EntryOutcome.Done]
    closed(store, queued).outcome.value shouldBe a[EntryOutcome.Done]
    client.sent.asScala.toVector.drop(1).map(lastUser) shouldBe Vector("interrupting", "queued")
    store
      .history(on, None, 100)
      .value
      .exists(c =>
        c.outcome.exists {
          case RunOutcome.Failed(kind, _) => kind == AgentWorker.InterruptedKind
          case _                          => false
        }
      ) shouldBe true
  }

  it should "cut a model call resumed through a wrapModelCall middleware question, as it cuts a plain one (#2364)" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    // asks once before the first model call; the call resumed after the answer runs at the question's node
    val asked = new AtomicBoolean(false)
    val gate = new AgentMiddleware.Asking[String, Boolean] {
      val id: MiddlewareId = MiddlewareId("spend")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        answered(context) match {
          case Some(_)                                  => next(request)
          case None if asked.compareAndSet(false, true) => ask("spend tokens?")
          case None                                     => next(request)
        }
    }
    val agent =
      Agent.builder("assistant", client).withRuntime(new GraphRuntime(store)).withMiddleware(gate).build().value
    val on     = thread()
    val parked = agent.run(on, "running").value
    val id = parked.status match {
      case AgentStatus.Suspended(_, _, questions, _, _) => questions.head._1
      case other                                        => fail(s"expected Suspended, got $other")
    }
    client.calls shouldBe 0
    val worker  = workerOf(store, agent)
    val resumed = store.runQueue.submit(Submission(on, agentId, QueuedInput.Resume(Map(parked.reply(id, true))))).value
    worker.start()
    entered.acquire()
    val interrupting = submit(store, on, "interrupting", BusyThreadPolicy.Interrupt).value.entryId
    closed(store, resumed.entryId).outcome.value shouldBe EntryOutcome.Interrupted(Some(interrupting))
    closed(store, interrupting).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe
      Some(CheckpointStatus.Completed)
    client.sent.asScala.toVector.map(lastUser) shouldBe Vector("running", "interrupting")
    store
      .history(on, None, 100)
      .value
      .exists(c =>
        c.outcome.exists {
          case RunOutcome.Failed(kind, _) => kind == AgentWorker.InterruptedKind
          case _                          => false
        }
      ) shouldBe true
  }

  it should "let a started tool call finish and keep its result, and start no call not yet started" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val gate    = new CountDownLatch(1)
    val ran     = new ConcurrentLinkedQueue[String]()
    // both calls take one lock, so the second runs only after the first: it has not started when the interrupt lands
    val step = AgentTool(SpecTools.spec("step").withLocks(_ => Set(LockKey("shared")))) { (args, _) =>
      ran.add(args.text)
      entered.release()
      gate.await(10, TimeUnit.SECONDS)
      ToolOutcome.Success(ToolContent.Json(ujson.Str(s"did ${args.text}")))
    }
    val client = new FnClient((n, c) =>
      if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "step", "one"), SpecTools.call("c2", "step", "two")))
      else echoing(n, c)
    )
    val worker  = workerOf(store, agentOver(store, client, step))
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    worker.start()
    entered.acquire()
    val interrupting = submit(store, on, "interrupting", BusyThreadPolicy.Interrupt).value.entryId
    // the worker learns of the interrupt at its next renewal; the started call is still running then
    eventually(store.runQueue.entry(None, running).value.stopRequest shouldBe Some(StopRequest.Interrupt(interrupting)))
    Thread.sleep(500)
    gate.countDown()
    closed(store, running).outcome.value shouldBe EntryOutcome.Interrupted(Some(interrupting))
    closed(store, interrupting).outcome.value shouldBe a[EntryOutcome.Done]
    ran.asScala.toVector shouldBe Vector("one")
    // no model call for the interrupted turn after its tools: the next call is the interrupting input's
    client.calls shouldBe 2
    val seen = client.sent.asScala.last
    seen.collect { case t: ToolMessage => t.toolCallId -> t.content } shouldBe Vector(
      "c1" -> "did one",
      "c2" -> AgentWorker.InterruptedResult
    )
    lastUser(seen) shouldBe "interrupting"
  }

  it should "name the latest interrupting entry in the settled checkpoint when a second arrives during a tool call" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val gate    = new CountDownLatch(1)
    val step = SpecTools.tool("step") { (_, _) =>
      entered.release()
      gate.await(10, TimeUnit.SECONDS)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("done")))
    }
    val client =
      new FnClient((n, c) => if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "step"))) else echoing(n, c))
    val worker  = workerOf(store, agentOver(store, client, step))
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    worker.start()
    entered.acquire()
    val earlier = submit(store, on, "earlier", BusyThreadPolicy.Interrupt).value.entryId
    Thread.sleep(400) // the worker applies the first interrupt at a renewal
    val later = submit(store, on, "later", BusyThreadPolicy.Interrupt).value.entryId
    Thread.sleep(400)
    gate.countDown()
    closed(store, running).outcome.value shouldBe EntryOutcome.Interrupted(Some(later))
    closed(store, earlier).outcome.value shouldBe EntryOutcome.Interrupted(Some(later))
    val settled = store.history(on, None, 100).value.find(_.status == CheckpointStatus.Failed).value
    settled.outcome.value.asInstanceOf[RunOutcome.Failed].message should include(s"queue entry ${later.value}")
  }

  "A plain cancel after a boundary stop" should "withdraw the stop's settlement, leaving the thread Running" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val step = SpecTools.tool("step") { (_, _) =>
      entered.release()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit
      ToolOutcome.Success(ToolContent.Json(ujson.Str("late")))
    }
    val client =
      new FnClient((n, c) => if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "step"))) else echoing(n, c))
    val agent = agentOver(store, client, step)
    val on    = thread()
    val run   = agent.start(on, "hello").value
    entered.acquire()
    // an interrupt waiting for the tool call, then a drain's or a lost lease's plain cancel
    run.stopSettling(
      AgentWorker.InterruptedResult,
      RunOutcome.Failed(AgentWorker.InterruptedKind, "x"),
      atBoundary = true
    )
    run.cancel()
    run.await().left.value shouldBe a[GraphError.Cancelled]
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  "A settling stop after a plain cancel" should "install nothing: the cancel won, so the thread stays Running" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val agent   = agentOver(store, new FnClient((_, _) => blocked(entered)))
    val on      = thread()
    val run     = agent.start(on, "hello").value
    entered.acquire()
    run.cancel()
    run.stopSettling(AgentWorker.CancelledResult, RunOutcome.Failed(AgentWorker.CancelledKind, "x"), atBoundary = false)
    run.stopSettling(
      AgentWorker.InterruptedResult,
      RunOutcome.Failed(AgentWorker.InterruptedKind, "y"),
      atBoundary = true
    )
    run.await().left.value shouldBe a[GraphError.Cancelled]
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  "An entry whose run an interrupt settled" should "close interrupted by the entry the checkpoint names, not a later one" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val agent   = agentOver(store, client)
    val on      = thread()
    val id      = submit(store, on, "running").value.entryId
    // a worker that settled its run for interrupt `first` and died before closing the entry
    val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val runId  = RunId.random()
    store.runQueue.recordAttempt(leased.lease, runId).value shouldBe a[AttemptStart.Recorded]
    val run = agent.start(on, "running", RunConfig().withRunId(runId)).value
    entered.acquire()
    val first = submit(store, on, "first", BusyThreadPolicy.Interrupt).value.entryId
    run.stopSettling(
      AgentWorker.InterruptedResult,
      RunOutcome.Failed(AgentWorker.InterruptedKind, s"${AgentWorker.InterruptedBy}${first.value}"),
      atBoundary = true
    )
    run.await().isLeft shouldBe true
    settledAs(store, on, AgentWorker.InterruptedKind)
    // a later interrupt replaces the entry's request after the checkpoint was committed
    val later = submit(store, on, "later", BusyThreadPolicy.Interrupt).value.entryId
    store.runQueue.entry(None, id).value.stopRequest shouldBe Some(StopRequest.Interrupt(later))
    workerOf(store, agent).start()
    closed(store, id).outcome.value shouldBe EntryOutcome.Interrupted(Some(first))
    closed(store, later).outcome.value shouldBe a[EntryOutcome.Done]
  }

  it should "close with the stop it settled even once a direct run has taken the thread on past it" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val agent   = agentOver(store, client)
    val on      = thread()
    val id      = submit(store, on, "running").value.entryId
    val leased  = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val runId   = RunId.random()
    store.runQueue.recordAttempt(leased.lease, runId).value shouldBe a[AttemptStart.Recorded]
    val run = agent.start(on, "running", RunConfig().withRunId(runId)).value
    entered.acquire()
    val reason = CancelReason.Requested(Some("stop"))
    store.runQueue.cancel(None, CancelTarget.Entry(id), reason).value
    run.stopSettling(AgentWorker.CancelledResult, RunOutcome.Failed(AgentWorker.CancelledKind, "x"), atBoundary = false)
    run.await().isLeft shouldBe true
    // the thread is at rest, so its owner runs it directly before the entry is closed
    agent.start(on, "direct").value.await().value
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
    workerOf(store, agent).start()
    closed(store, id).outcome.value shouldBe EntryOutcome.Cancelled(reason)
  }

  "A drained run an interrupt was waiting for" should "be given back for recovery, its thread left Running" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val step = SpecTools.tool("step") { (_, _) =>
      entered.release()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit
      ToolOutcome.Success(ToolContent.Json(ujson.Str("late")))
    }
    val client =
      new FnClient((n, c) => if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "step"))) else echoing(n, c))
    val worker = AgentWorker(
      store.runQueue,
      store,
      Seq(QueuedAgent(agentOver(store, client, step))),
      fast.withDrainGrace(100.millis),
      shortLease
    ).value
    started.add(worker)
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    worker.start()
    entered.acquire()
    val interrupting = submit(store, on, "interrupting", BusyThreadPolicy.Interrupt).value.entryId
    Thread.sleep(400) // the worker applies the interrupt at a renewal; the tool call is still running
    worker.shutdown().cancelled shouldBe Vector(running)
    val entry = store.runQueue.entry(None, running).value
    entry.isOpen shouldBe true
    entry.stopRequest shouldBe Some(StopRequest.Interrupt(interrupting))
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  it should "of two, run the later first, closing the earlier before it runs" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val worker  = workerOf(store, agentOver(store, client))
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    worker.start()
    entered.acquire()
    val earlier = submit(store, on, "earlier", BusyThreadPolicy.Interrupt).value.entryId
    val later   = submit(store, on, "later", BusyThreadPolicy.Interrupt).value.entryId
    closed(store, earlier).outcome.value shouldBe EntryOutcome.Interrupted(Some(later))
    closed(store, running).outcome.value shouldBe EntryOutcome.Interrupted(Some(later))
    closed(store, later).outcome.value shouldBe a[EntryOutcome.Done]
    client.sent.asScala.toVector.drop(1).map(lastUser) shouldBe Vector("later")
  }

  it should "not interrupt a direct run: it waits for it, and Reject is refused while the direct run holds the thread" in {
    val store  = InMemoryCheckpointer()
    val gate   = new CountDownLatch(1)
    val inCall = new Semaphore(0)
    val client = new FnClient((n, c) => {
      if (n == 1) {
        inCall.release()
        gate.await(10, TimeUnit.SECONDS)
      }
      echoing(n, c)
    })
    val agent  = agentOver(store, client)
    val on     = thread()
    val direct = agent.start(on, "direct").value
    inCall.acquire()
    submit(store, on, "rejected", BusyThreadPolicy.Reject).left.value shouldBe a[GraphError.ThreadBusy]
    val waiting = submit(store, on, "waiting", BusyThreadPolicy.Interrupt).value.entryId
    workerOf(store, agent).start()
    Thread.sleep(200)
    store.runQueue.entry(None, waiting).value.isOpen shouldBe true
    gate.countDown()
    direct.await().value.answer shouldBe defined
    closed(store, waiting).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(
      CheckpointStatus.Completed
    )
  }

  "A queued start" should "be refused while the thread's interrupts are pending, whatever the policy" in {
    val store = InMemoryCheckpointer()
    val echo  = SpecTools.tool("echo")((args, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(args.text))))
    val client =
      new FnClient((n, c) => if (n == 1) Right(SpecTools.calling(SpecTools.call("c1", "echo"))) else echoing(n, c))
    val agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withTools(SpecTools.set(echo))
        .withMiddleware(new ApprovalMiddleware(_ => Some("reviewed")))
        .build()
        .value
    val on = thread()
    workerOf(store, agent).start()
    closed(store, submit(store, on, "asks").value.entryId).outcome.value
      .asInstanceOf[EntryOutcome.Done]
      .status shouldBe Some(CheckpointStatus.Suspended)
    // a suspended thread is not busy: every policy is accepted, and the runtime refuses each start
    BusyThreadPolicy.values.toVector.foreach { policy =>
      val id = submit(store, on, s"start ${policy.toString}", policy).value.entryId
      closed(store, id).outcome.value.asInstanceOf[EntryOutcome.Failed].reason shouldBe a[FailureReason.RunFailed]
    }
    store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Suspended
  }

  // ---- the service's delete ----

  "RunSubmitter.deleteThread" should "close the queued entries, cancel the running one, and then delete the thread" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val client  = new FnClient((n, c) => if (n == 1) blocked(entered) else echoing(n, c))
    val worker  = workerOf(store, agentOver(store, client))
    val on      = thread()
    val running = submit(store, on, "running").value.entryId
    val queued  = submit(store, on, "queued").value.entryId
    worker.start()
    entered.acquire()
    val submitter = new RunSubmitter(store.runQueue, store, shortLease)
    submitter.deleteThread(new GraphRuntime(store), None, on, 10.seconds).value
    store.runQueue.entry(None, running).value.outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
    store.runQueue.entry(None, queued).value.outcome shouldBe Some(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
    store.latest(on).value shouldBe None
    client.calls shouldBe 1
  }

  it should "leave the thread when the running entry does not close in time" in {
    val store = InMemoryCheckpointer()
    val on    = thread()
    // an attempt recorded by a worker that is not running: no one acts on the cancel
    submit(store, on).value
    val leased = store.runQueue.take(TakeRequest("away", Set(agentId), 1.minute)).value.value
    store.runQueue.recordAttempt(leased.lease, RunId.random()).value shouldBe a[AttemptStart.Recorded]
    val submitter = new RunSubmitter(store.runQueue, store, shortLease)
    submitter.deleteThread(new GraphRuntime(store), None, on, 200.millis).left.value shouldBe a[GraphError.ThreadBusy]
  }

  "RunSubmitter" should "count the entries it closes itself: a cancel before they run, an Interrupt's earlier one" in {
    import org.llm4s.agent.metrics.{ AgentMetrics, RecordingMetrics }
    val store     = InMemoryCheckpointer()
    val metrics   = new RecordingMetrics
    val submitter = new RunSubmitter(store.runQueue, store, shortLease, metrics)
    val on        = thread()
    val queued    = submitter.submit(on, agentId, QueuedInput.query("q"), policy = BusyThreadPolicy.Enqueue).value
    submitter.submit(on, agentId, QueuedInput.query("a"), policy = BusyThreadPolicy.Interrupt).value
    submitter.submit(on, agentId, QueuedInput.query("b"), policy = BusyThreadPolicy.Interrupt).value
    submitter.cancel(None, CancelTarget.Entry(queued.entryId), Some("n")).value
    // a second cancel of a closed entry counts nothing more
    submitter.cancel(None, CancelTarget.Entry(queued.entryId)).value
    metrics.series(AgentMetrics.QueueClosed) shouldBe Map(
      Vector("assistant", "interrupted", "none")    -> 1.0,
      Vector("assistant", "cancelled", "requested") -> 1.0
    )
  }

  /**
   * `inner`, but its first `submit` or `cancel` runs `first` before it: another caller's call landing between this one's
   * start and its store call, as two racing calls do (#2368).
   */
  private def interleaved(inner: RunQueue)(first: => Unit): RunQueue = new RunQueue {
    private val pending                                = new java.util.concurrent.atomic.AtomicBoolean(true)
    private def before(): Unit                         = if (pending.getAndSet(false)) first
    def submit(s: Submission)                          = { before(); inner.submit(s) }
    def entry(t: Option[TenantId], id: EntryId)        = inner.entry(t, id)
    def openEntries(t: Option[TenantId], th: ThreadId) = inner.openEntries(t, th)
    def take(r: TakeRequest)                           = inner.take(r)
    def release(l: Lease)                              = inner.release(l)
    def recordAttempt(l: Lease, r: RunId)              = inner.recordAttempt(l, r)
    def close(l: Lease, o: EntryOutcome)               = inner.close(l, o)
    def reclaim(d: FiniteDuration, n: Int)             = inner.reclaim(d, n)
    def openRuns(ts: Vector[ThreadId])                 = inner.openRuns(ts)
    def depth(as: Set[AgentId])                        = inner.depth(as)
    def renew(l: Lease, ttl: FiniteDuration)           = inner.renew(l, ttl)
    def cancel(t: Option[TenantId], c: CancelTarget, r: CancelReason) = { before(); inner.cancel(t, c, r) }
  }

  it should "count an entry two racing cancels close once" in {
    import org.llm4s.agent.metrics.{ AgentMetrics, RecordingMetrics }
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val on      = thread()
    val queued  = submit(store, on).value.entryId
    val other   = new RunSubmitter(store.runQueue, store, shortLease, metrics)
    // the other cancel closes the entry after this one has started and before its store call
    val racing = interleaved(store.runQueue)(other.cancel(None, CancelTarget.Entry(queued)).value: Unit)
    new RunSubmitter(racing, store, shortLease, metrics).cancel(None, CancelTarget.Entry(queued)).value
    metrics.series(AgentMetrics.QueueClosed) shouldBe Map(Vector("assistant", "cancelled", "requested") -> 1.0)
  }

  it should "count each entry two racing Interrupt submissions close once, under its own agent" in {
    import org.llm4s.agent.metrics.{ AgentMetrics, RecordingMetrics }
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val on      = thread()
    val second  = AgentId.of("second").value
    val other   = new RunSubmitter(store.runQueue, store, shortLease, metrics)
    store.runQueue.submit(Submission(on, agentId, QueuedInput.query("e"), None, BusyThreadPolicy.Interrupt)).value
    // the other Interrupt lands first and closes the earlier entry; this one then closes the other's
    val racing = interleaved(store.runQueue)(
      other.submit(on, second, QueuedInput.query("b"), policy = BusyThreadPolicy.Interrupt).value: Unit
    )
    new RunSubmitter(racing, store, shortLease, metrics)
      .submit(on, agentId, QueuedInput.query("a"), policy = BusyThreadPolicy.Interrupt)
      .value
      .interrupted
      .map(_.agentId) shouldBe Vector(second)
    metrics.series(AgentMetrics.QueueClosed) shouldBe Map(
      Vector("assistant", "interrupted", "none") -> 1.0,
      Vector("second", "interrupted", "none")    -> 1.0
    )
  }

  "RunSubmitter.cancel" should "cancel only the caller's tenant's entries" in {
    val store  = InMemoryCheckpointer()
    val on     = thread()
    val mine   = TenantId("mine")
    val theirs = TenantId("theirs")
    val id = store.runQueue
      .submit(Submission(on, agentId, QueuedInput.query("q"), Some(mine), BusyThreadPolicy.Enqueue))
      .value
      .entryId
    val submitter = new RunSubmitter(store.runQueue, store, shortLease)
    submitter.cancel(Some(theirs), CancelTarget.Entry(id)).left.value shouldBe a[QueueError.TicketExpired]
    submitter.cancel(Some(theirs), CancelTarget.Thread(on)).value shouldBe empty
    store.runQueue.entry(Some(mine), id).value.isOpen shouldBe true
    submitter.cancel(Some(mine), CancelTarget.Thread(on), Some("mine")).value.head.outcome shouldBe Some(
      EntryOutcome.Cancelled(CancelReason.Requested(Some("mine")))
    )
  }
}
