package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, LinkedBlockingQueue, TimeUnit }

class RunHandleSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t")
  private val out    = StateKey.appending[String]("out")

  /** A one-node graph whose node opens `started`, then blocks until `release` opens or it is interrupted. */
  private def blocking(started: CountDownLatch, release: CountDownLatch = new CountDownLatch(1)) = {
    val b = GraphBuilder("one-node", "v1")
    val node = b.node[String]("n", writes = Set(out)) { (input, _, _) =>
      started.countDown()
      release.await(60, TimeUnit.SECONDS): Unit // an interrupt throws out of the node
      continue(Command.empty.update(out, input))
    }
    b.compile(node)(_.get(out).map(_.mkString)).value
  }

  /** The same graph, completing at once; it can continue a thread `blocking` ran on. */
  private val instant: CompiledGraph[String, String] = blocking(new CountDownLatch(1), new CountDownLatch(0))

  /** `ask` suspends with its input as the question; `answer` records the answer. */
  private val asking: CompiledGraph[String, String] = {
    val b      = GraphBuilder("asking", "v1")
    val answer = b.declareResume[String, String]("answer")
    b.implement(answer.node, writes = Set(out))((resumed, _, _) => continue(Command.empty.update(out, resumed.answer)))
    val ask = b.node[String]("ask")((question, _, _) => NodeResult.Suspend(StateUpdate.empty, question, answer))
    b.compile(ask)(_.get(out).map(_.mkString)).value
  }

  private def await[O](handle: RunHandle[O]): RunResult[O] = handle.await().value

  /** The thread's latest checkpoint and event log, to show a refused call changed nothing. */
  private def snapshot(store: Checkpointer): (Option[String], Vector[EventRecord]) =
    (store.latest(thread).value.map(_.checkpoint.id), store.eventsAfter(thread, 0L, 1000).value)

  private def refusedUnchanged[O](store: Checkpointer)(call: => Result[RunHandle[O]]): LLMError = {
    val before = snapshot(store)
    val error  = call.left.value
    snapshot(store) shouldBe before
    error
  }

  "Admission" should "refuse each call that cannot run, leaving the thread unchanged" in {
    // IncompleteRun: a cancelled run leaves a Running checkpoint
    val incomplete = InMemoryCheckpointer()
    val rt1        = GraphRuntime(incomplete)
    val started    = new CountDownLatch(1)
    val handle     = rt1.start(thread, blocking(started), "x").value
    started.await(5, TimeUnit.SECONDS) shouldBe true
    // ThreadBusy while it is live, for every entry point
    refusedUnchanged(incomplete)(rt1.start(thread, instant, "y")) shouldBe a[GraphError.ThreadBusy]
    refusedUnchanged(incomplete)(rt1.recover(instant, thread)) shouldBe a[GraphError.ThreadBusy]
    refusedUnchanged(incomplete)(rt1.resume(asking, thread, Map.empty)) shouldBe a[GraphError.ThreadBusy]
    handle.cancel()
    await(handle).failed._2 shouldBe a[GraphError.Cancelled]
    refusedUnchanged(incomplete)(rt1.start(thread, instant, "y")) shouldBe a[GraphError.IncompleteRun]
    refusedUnchanged(incomplete)(rt1.resume(asking, thread, Map.empty)) shouldBe a[GraphError.NotSuspended]

    // PendingInterrupts and TenantMismatch on a suspended thread
    val suspendedStore = InMemoryCheckpointer()
    val rt2            = GraphRuntime(suspendedStore)
    await(rt2.start(thread, asking, "q?").value).suspended
    refusedUnchanged(suspendedStore)(rt2.start(thread, asking, "again")) shouldBe a[GraphError.PendingInterrupts]
    refusedUnchanged(suspendedStore)(rt2.recover(asking, thread)) shouldBe a[GraphError.PendingInterrupts]
    refusedUnchanged(suspendedStore)(
      rt2.resume(asking, thread, Map(InterruptId("0.1") -> ujson.Str("a")), RunConfig().withTenantId(TenantId("x")))
    ) shouldBe a[GraphError.TenantMismatch]

    // NothingToRecover and TenantMismatch on a completed thread
    val completed = InMemoryCheckpointer()
    val rt3       = GraphRuntime(completed)
    refusedUnchanged(completed)(rt3.recover(instant, thread)) shouldBe a[GraphError.NothingToRecover]
    await(rt3.start(thread, instant, "a").value).completed._2 shouldBe "a"
    refusedUnchanged(completed)(rt3.recover(instant, thread)) shouldBe a[GraphError.NothingToRecover]
    refusedUnchanged(completed)(rt3.resume(asking, thread, Map.empty)) shouldBe a[GraphError.NotSuspended]
    refusedUnchanged(completed)(
      rt3.start(thread, instant, "b", RunConfig().withTenantId(TenantId("x")))
    ) shouldBe a[GraphError.TenantMismatch]
  }

  "cancel" should "stop a run whose node is blocked, leaving it recoverable" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val started = new CountDownLatch(1)
    val handle  = runtime.start(thread, blocking(started), "x").value
    started.await(5, TimeUnit.SECONDS) shouldBe true
    handle.status shouldBe RunStatus.Running
    handle.cancel()

    val latest = store.latest(thread).value.get
    await(handle).failed._2 shouldBe GraphError.Cancelled(Some("t"), Some(latest.checkpoint.id))
    handle.status shouldBe RunStatus.Failed
    latest.checkpoint.status shouldBe CheckpointStatus.Running
    store.eventsAfter(thread, 0L, 100).value.last.event shouldBe RunEvent.RunCancelled

    await(runtime.recover(instant, thread).value).completed._2 shouldBe "x"
  }

  it should "end a run cancelled straight after start, as cancelled or completed, and free the thread" in {
    (1 to 100).foreach { i =>
      val runtime = GraphRuntime.inMemory()
      val handle  = runtime.start(thread, instant, "x").value
      handle.cancel()
      handle.await().value match {
        case RunResult.Failed(_, _: GraphError.Cancelled) =>
          runtime.recover(instant, thread).value.await().value.completed._2 shouldBe "x"
        case RunResult.Completed(_, output, _) =>
          output shouldBe "x"
          runtime.start(thread, instant, "y").value.await().value.completed._2 shouldBe "xy"
        case other => fail(s"iteration $i: $other")
      }
    }
  }

  it should "always cancel a run cancelled straight after start whose node blocks" in {
    (1 to 100).foreach { _ =>
      val runtime = GraphRuntime.inMemory()
      val handle  = runtime.start(thread, blocking(new CountDownLatch(1)), "x").value
      handle.cancel()
      await(handle).failed._2 shouldBe a[GraphError.Cancelled]
      runtime.start(thread, instant, "y").left.value shouldBe a[GraphError.IncompleteRun]
    }
  }

  it should "do nothing once the run has ended, and await returns the same result every time" in {
    val runtime = GraphRuntime.inMemory()
    val handle  = runtime.start(thread, instant, "x").value
    val first   = await(handle)
    handle.cancel()
    handle.cancel()
    (await(handle) eq first) shouldBe true
    first.completed._2 shouldBe "x"
    handle.status shouldBe RunStatus.Completed
  }

  "await" should "return CancelledError when the awaiting thread is interrupted, without cancelling the run" in {
    val runtime = GraphRuntime.inMemory()
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val handle  = runtime.start(thread, blocking(started, release), "x").value
    started.await(5, TimeUnit.SECONDS) shouldBe true

    val outcome = new LinkedBlockingQueue[(Result[RunResult[String]], Boolean)]()
    val waiter =
      Thread.ofVirtual().start(() => outcome.offer(handle.await() -> Thread.currentThread().isInterrupted): Unit)
    while waiter.getState != Thread.State.WAITING do Thread.onSpinWait()
    waiter.interrupt()
    val (awaited, flag) = Option(outcome.poll(5, TimeUnit.SECONDS)).getOrElse(fail("await did not return"))
    awaited.left.value shouldBe a[CancelledError]
    flag shouldBe true

    handle.status shouldBe RunStatus.Running
    release.countDown()
    val again = new LinkedBlockingQueue[RunResult[String]]()
    Thread.ofVirtual().start(() => again.put(await(handle)))
    Option(again.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no result")).completed._2 shouldBe "x"
  }

  "status" should "be Running while a node blocks, then follow the result" in {
    val runtime = GraphRuntime.inMemory()
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val handle  = runtime.start(thread, blocking(started, release), "x").value
    started.await(5, TimeUnit.SECONDS) shouldBe true
    handle.status shouldBe RunStatus.Running
    release.countDown()
    await(handle).completed
    handle.status shouldBe RunStatus.Completed

    val suspending = GraphRuntime.inMemory().start(thread, asking, "q?").value
    await(suspending).suspended
    suspending.status shouldBe RunStatus.Suspended

    val busy = GraphRuntime.inMemory()
    val held = busy.start(thread, blocking(new CountDownLatch(1)), "x").value
    held.cancel()
    await(held).failed
    held.status shouldBe RunStatus.Failed
  }

  "A thread" should "be busy while its run is live and free once await returns" in {
    val runtime = GraphRuntime.inMemory()
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val handle  = runtime.start(thread, blocking(started, release), "x").value
    started.await(5, TimeUnit.SECONDS) shouldBe true
    runtime.start(thread, instant, "y").left.value shouldBe a[GraphError.ThreadBusy]
    release.countDown()
    await(handle).completed
    await(runtime.start(thread, instant, "y").value).completed._2 shouldBe "xy"
    handle.threadId shouldBe thread
  }

  "handle.subscribe" should "replay exactly this run's events after it has ended" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    await(runtime.start(thread, instant, "a", RunConfig().withRunId(RunId("first"))).value).completed
    val second = runtime.start(thread, instant, "b", RunConfig().withRunId(RunId("second"))).value
    second.runId shouldBe RunId("second")
    await(second).completed

    val events = new LinkedBlockingQueue[StreamEvent]()
    val sub    = second.subscribe()(events.put)
    val seen = Iterator
      .continually(Option(events.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no event within 5s")))
      .collect { case StreamEvent.Durable(record) => record }
      .takeWhile(_.event != RunEvent.RunCompleted)
      .toVector
    sub.value.cancel()
    seen.head.event shouldBe a[RunEvent.RunStarted]
    seen.map(_.runId).distinct shouldBe Vector("second")
    seen.head.seq shouldBe store.eventsAfter(thread, 0L, 100).value.find(_.runId == "second").get.seq
  }

  /** A store whose `commit` throws on the commits numbered in `throwOn`. */
  private def throwingStore(throwOn: Int*): Checkpointer = new Checkpointer {
    private val commits = new AtomicInteger()
    val underlying      = InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit) =
      if throwOn.contains(commits.incrementAndGet()) then throw new RuntimeException("store exploded")
      else underlying.commit(threadId, commit)
    def latest(threadId: ThreadId) = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
  }

  "A run" should "fail with RunCrashed when something unexpected throws, and release its thread" in {
    // the claim reads the clock twice on the caller's thread; the run thread's first read throws
    val reads = new AtomicInteger()
    val clock = new Clock {
      def getZone: ZoneId                        = ZoneOffset.UTC
      override def withZone(zone: ZoneId): Clock = this
      override def instant(): Instant =
        if reads.incrementAndGet() > 2 then throw new IllegalStateException("clock exploded") else Instant.EPOCH
    }
    val runtime = GraphRuntime(InMemoryCheckpointer(), clock)
    val handle  = runtime.start(thread, instant, "x").value
    await(handle).failed._2 match {
      case GraphError.RunCrashed("t", cause) => cause.getMessage shouldBe "clock exploded"
      case other                             => fail(s"expected RunCrashed, got $other")
    }
    handle.status shouldBe RunStatus.Failed
    // released: refused for the thread's state, not ThreadBusy
    runtime.start(thread, instant, "y").left.value shouldBe a[GraphError.IncompleteRun]
  }

  it should "report a store that throws as CheckpointWriteFailed in every durability mode" in {
    for durability <- Durability.values do
      withClue(s"$durability: ") {
        // OnExit commits only the claim and its exit commit, so its 2nd commit is the one that throws
        val runtime = GraphRuntime(throwingStore(if durability == Durability.OnExit then 2 else 3))
        val handle  = runtime.start(thread, instant, "x", durability = durability).value
        await(handle).failed._2 shouldBe a[GraphError.CheckpointWriteFailed]
        runtime.start(thread, instant, "y").left.value shouldBe a[GraphError.IncompleteRun]
      }
  }

  "Admission" should "refuse a claim whose commit throws, releasing the thread" in {
    val runtime = GraphRuntime(throwingStore(1))
    runtime.start(thread, instant, "x").left.value match {
      case GraphError.CheckpointWriteFailed("t", cause, None) => cause.message should include("store exploded")
      case other                                              => fail(s"expected CheckpointWriteFailed, got $other")
    }
    await(runtime.start(thread, instant, "y").value).completed._2 shouldBe "y" // the store works now
  }
}
