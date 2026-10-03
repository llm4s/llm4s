package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, LinkedBlockingQueue, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration.DurationInt
import scala.util.Try

class CancellationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t-1")

  /**
   * plan fans out to workers; a worker whose item is in `blockOn` runs `onBlock`, then waits
   * (interruptibly) until interrupted, recording that it saw the interrupt; the others run `work`
   * and complete.
   */
  final private class Fixture(blockOn: Set[String], onBlock: () => Unit = () => (), work: () => Unit = () => ()) {
    val started     = new CountDownLatch(blockOn.size)
    val interrupted = ConcurrentHashMap.newKeySet[String]()
    val finished    = ConcurrentHashMap.newKeySet[String]()
    val running     = new AtomicInteger()
    val maxRunning  = new AtomicInteger()
    val results     = StateKey.appending[String]("results")

    val graph: CompiledGraph[Vector[String], Vector[String]] = {
      val b = GraphBuilder("cancel", "v1")
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
        maxRunning.accumulateAndGet(running.incrementAndGet(), math.max)
        if blockOn(item) then
          onBlock()
          started.countDown()
          CancelledError.catchInterrupt(Thread.sleep(60_000)).left.foreach { e =>
            interrupted.add(item)
            throw e
          }
        work()
        running.decrementAndGet()
        finished.add(item)
        continue(Command.empty.update(results, item.toUpperCase))
      }
      val done = b.node[Unit]("done")((_, _, _) => continue(Command.empty))
      val join = b.dynamicJoin("workers", done)
      val plan = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, worker, items)))
      b.compile(plan)(_.get(results)).value
    }
  }

  /** Runs `body` on a virtual thread, interrupts it once `ready` opens, and returns its result and flag. */
  private def interruptWhen[A](ready: CountDownLatch)(body: => A): (A, Boolean) = {
    @volatile var outcome: Option[(A, Boolean)] = None
    val runner = Thread.ofVirtual().start(() => outcome = Some(body -> Thread.currentThread().isInterrupted))
    ready.await(10, TimeUnit.SECONDS) shouldBe true
    runner.interrupt()
    runner.join(10_000)
    runner.isAlive shouldBe false
    outcome.get
  }

  /** Admits a run, cancels it once `ready` opens, and returns its result. */
  private def cancelWhen[O](ready: CountDownLatch)(admitted: org.llm4s.types.Result[RunHandle[O]]): RunResult[O] = {
    val handle = admitted.value
    ready.await(10, TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value
  }

  /** Runs `body` on a virtual thread, so an interrupt flag it leaves cannot reach the test thread. */
  private def onVirtualThread[A](body: => A): (A, Boolean) = {
    @volatile var outcome: Option[(A, Boolean)] = None
    val runner = Thread.ofVirtual().start(() => outcome = Some(body -> Thread.currentThread().isInterrupted))
    runner.join(10_000)
    runner.isAlive shouldBe false
    outcome.get
  }

  "An in-memory run" should "stop and join every task when its thread is interrupted" in {
    // The blocked workers wait for "a" to finish before signalling, so the interrupt cannot overtake it.
    val aDone = new CountDownLatch(1)
    val f = Fixture(
      blockOn = Set("b", "c"),
      onBlock = () => aDone.await(10, TimeUnit.SECONDS): Unit,
      work = () => aDone.countDown()
    )
    val (result, flag) = interruptWhen(f.started)(drive(f.graph, f.graph.start(Vector("a", "b", "c"))))
    result.failed._2 shouldBe GraphError.Cancelled(None, None)
    flag shouldBe true
    f.interrupted.toArray.toSet shouldBe Set("b", "c") // every blocked sibling saw the interrupt and was joined
    f.finished.toArray.toSet shouldBe Set("a")
    f.running.get shouldBe 2 // the blocked tasks threw before decrementing
  }

  it should "cancel a single-task superstep" in {
    val started     = new CountDownLatch(1)
    val interrupted = new AtomicInteger()
    val b           = GraphBuilder("inline", "v1")
    val node = b.node[Unit]("n") { (_, _, _) =>
      started.countDown()
      CancelledError.catchInterrupt(Thread.sleep(60_000)).left.foreach { e =>
        interrupted.incrementAndGet()
        throw e
      }
      continue(Command.empty)
    }
    val g              = b.compile(node)(_ => Right(())).value
    val (result, flag) = interruptWhen(started)(drive(g, g.start(())))
    result.failed._2 shouldBe GraphError.Cancelled(None, None)
    flag shouldBe true
    interrupted.get shouldBe 1
  }

  /** A one-node graph whose node swallows its interrupt and returns normally. */
  private def swallowingGraph(started: CountDownLatch, swallowed: AtomicInteger): CompiledGraph[Unit, Unit] = {
    val b = GraphBuilder("swallow", "v1")
    val node = b.node[Unit]("n") { (_, _, _) =>
      started.countDown()
      CancelledError.catchInterrupt(Thread.sleep(60_000)).left.foreach(_ => swallowed.incrementAndGet())
      continue(Command.empty)
    }
    b.compile(node)(_ => Right(())).value
  }

  it should "stay cancelled when a lone task swallows its interrupt" in {
    val started        = new CountDownLatch(1)
    val swallowed      = new AtomicInteger()
    val g              = swallowingGraph(started, swallowed)
    val (result, flag) = interruptWhen(started)(drive(g, g.start(())))
    result.failed._2 shouldBe GraphError.Cancelled(None, None)
    flag shouldBe true
    swallowed.get shouldBe 1
  }

  it should "run a superstep's tasks concurrently, bounded by the default limit" in {
    val f     = Fixture(blockOn = Set.empty, work = () => Thread.sleep(5))
    val items = (1 to 40).map(i => s"i$i").toVector
    drive(f.graph, f.graph.start(items)).completed._2.size shouldBe 40
    f.maxRunning.get should be > 1
    f.maxRunning.get should be <= RunBudgets.default.maxConcurrency
  }

  "GraphError.Cancelled" should "name the thread and the checkpoint to recover from" in {
    GraphError.Cancelled(None, None).message shouldBe "Run was cancelled"
    GraphError
      .Cancelled(Some("t1"), Some("cp-3"))
      .message shouldBe "Run on thread 't1' was cancelled; recover from cp-3"
  }

  "A task" should "report a node that throws InterruptedException as a cancellation" in {
    val b              = GraphBuilder("throws", "v1")
    val node           = b.node[Unit]("n")((_, _, _) => throw new InterruptedException("stop"))
    val g              = b.compile(node)(_ => Right(())).value
    val (result, flag) = onVirtualThread(drive(g, g.start(())))
    val (_, error)     = result.failed
    flag shouldBe true
    error shouldBe GraphError.Cancelled(None, None)
  }

  it should "be cancelled if its thread is interrupted when its node returns" in {
    val b    = GraphBuilder("late", "v1")
    val seen = StateKey.appending[String]("seen")
    val node = b.node[Unit]("n", writes = Set(seen)) { (_, _, _) =>
      Thread.currentThread().interrupt()
      continue(Command.empty.update(seen, "written"))
    }
    val g              = b.compile(node)(_.get(seen)).value
    val (result, flag) = onVirtualThread(drive(g, g.start(())))
    val (state, error) = result.failed
    flag shouldBe true
    error shouldBe GraphError.Cancelled(None, None)
    state.get(seen).value shouldBe empty
  }

  /**
   * A fixture whose one blocked worker first waits for the threads of `others` unblocked workers
   * to end, so their results are recorded before the run is interrupted.
   */
  private def afterOthers(blockOn: Set[String], others: Int, onBlock: () => Unit = () => ()): Fixture = {
    val threads = new LinkedBlockingQueue[Thread]()
    Fixture(
      blockOn,
      onBlock = () => {
        (1 to others).foreach(_ => threads.take().join())
        onBlock()
      },
      work = () => threads.put(Thread.currentThread())
    )
  }

  "A durable run" should "cancel cleanly in every durability mode, and recover without re-running finished work" in {
    for durability <- Durability.values do
      withClue(s"$durability: ") {
        val f       = afterOthers(blockOn = Set("b"), others = 1)
        val store   = InMemoryCheckpointer()
        val runtime = GraphRuntime(store)
        val result =
          cancelWhen(f.started)(
            runtime.start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")), durability)
          )

        val (_, error) = result.failed
        error shouldBe a[GraphError.Cancelled]
        val latest = store.latest(thread).value.get
        latest.checkpoint.status shouldBe CheckpointStatus.Running
        val last = store.eventsAfter(thread, 0L, 100).value.last
        last.event shouldBe RunEvent.RunCancelled
        last.checkpointId shouldBe Some(latest.checkpoint.id)
        error shouldBe GraphError.Cancelled(Some(thread.value), Some(latest.checkpoint.id))

        // the claim was released, and recovery finishes the run without running "a" again
        val g = Fixture(blockOn = Set.empty)
        runtime
          .recover(thread, g.graph, RunConfig().withRunId(RunId("run-2")))
          .awaited
          .value
          .completed
          ._2 shouldBe Vector(
          "A",
          "B"
        )
        g.finished.toArray.toSet shouldBe Set("b")
      }
  }

  it should "stay cancelled when a lone task swallows its interrupt" in {
    val started   = new CountDownLatch(1)
    val swallowed = new AtomicInteger()
    val runtime   = GraphRuntime(InMemoryCheckpointer())
    val result =
      cancelWhen(started)(
        runtime.start(thread, swallowingGraph(started, swallowed), (), RunConfig().withRunId(RunId("run-1")))
      )
    result.failed._2 shouldBe a[GraphError.Cancelled]
    swallowed.get shouldBe 1
  }

  it should "record nothing for a task interrupted mid-run" in {
    val f       = afterOthers(blockOn = Set("b"), others = 1)
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    cancelWhen(f.started)(runtime.start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1"))))
    store.latest(thread).value.get.pendingWrites.map(_.nodeId) shouldBe Vector("worker") // a's only
    store.eventsAfter(thread, 0L, 100).value.map(_.event).collect { case e: RunEvent.TaskFailed => e } shouldBe empty
  }

  it should "keep running when a timeout around await expires, until cancelled, with no task left running" in {
    val f       = Fixture(blockOn = Set("b", "c"))
    val runtime = GraphRuntime(InMemoryCheckpointer())
    val handle  = runtime.start(thread, f.graph, Vector("a", "b", "c"), RunConfig().withRunId(RunId("run-1"))).value
    f.started.await(10, TimeUnit.SECONDS) shouldBe true
    Try(ox.timeout(100.millis)(handle.await())).failed.get shouldBe a[TimeoutException]
    handle.status shouldBe RunStatus.Running // interrupting the awaiting thread did not cancel the run
    f.interrupted.isEmpty shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]
    f.interrupted.toArray.toSet shouldBe Set("b", "c") // both stopped and joined before the run ended
    runtime
      .recover(thread, Fixture(Set.empty).graph, RunConfig().withRunId(RunId("run-2")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("A", "B", "C")
  }

  it should "still cancel when a task swallows its interrupt" in {
    val results = StateKey.appending[String]("results")
    val started = new CountDownLatch(1)
    val b       = GraphBuilder("swallow", "v1")
    val stubborn = b.node[String]("stubborn", writes = Set(results)) { (item, _, _) =>
      started.countDown()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit // returns normally, the flag cleared
      continue(Command.empty.update(results, item))
    }
    val done = b.node[Unit]("done")((_, _, _) => continue(Command.empty))
    val join = b.dynamicJoin("j", done)
    val plan = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, stubborn, items)))
    val g    = b.compile(plan)(_.get(results)).value
    val result =
      cancelWhen(started)(
        GraphRuntime(InMemoryCheckpointer()).start(thread, g, Vector("x", "y"), RunConfig().withRunId(RunId("r")))
      )
    result.failed._2 shouldBe a[GraphError.Cancelled]
  }

  it should "report a failed drain of an Async queue as CheckpointWriteFailed, keeping the cancellation" in {
    // a store that starts failing once the blocked worker is running: the claim and the first
    // superstep commit, then the queue cannot drain after cancellation
    val broken = new AtomicBoolean(false)
    val failing = new Checkpointer {
      val underlying = InMemoryCheckpointer()
      def commit(threadId: ThreadId, commit: Commit) =
        if broken.get then Left(ValidationError("store", "down")) else underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) = underlying.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    }
    val f       = afterOthers(blockOn = Set("b"), others = 1, onBlock = () => broken.set(true))
    val runtime = GraphRuntime(failing)
    val result =
      cancelWhen(f.started)(
        runtime.start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")), Durability.Async)
      )
    result.failed._2 match {
      case GraphError.CheckpointWriteFailed(_, _, Some(_: GraphError.Cancelled)) => succeed
      case other => fail(s"expected CheckpointWriteFailed with the cancellation, got $other")
    }
    // the thread claim was released: the next call is refused for the thread's state, not ThreadBusy
    runtime
      .start(thread, f.graph, Vector("z"), RunConfig().withRunId(RunId("run-2")))
      .left
      .value shouldBe a[GraphError.IncompleteRun]
  }

  it should "drain an Async queue before ending when cancelled after its last superstep" in {
    // the writer holds the run's final (Completed) checkpoint commit until the run has been
    // cancelled, so the interrupt lands while the queue is not yet drained
    val arrived = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val slow = new Checkpointer {
      val underlying = InMemoryCheckpointer()
      def commit(threadId: ThreadId, commit: Commit) =
        if commit.checkpoint.exists(_.status == CheckpointStatus.Completed) then {
          arrived.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
        underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) = underlying.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    }
    val f       = Fixture(blockOn = Set.empty)
    val runtime = GraphRuntime(slow)
    val handle =
      runtime.start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1")), Durability.Async).value
    arrived.await(10, TimeUnit.SECONDS) shouldBe true
    handle.cancel() // the run has submitted its last commit and is closing the committer
    release.countDown()
    // not CheckpointWriteFailed: an interrupt is not a write failure
    awaitResult(handle).value.completed._2 shouldBe Vector("A")
    slow.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Completed)
  }

  it should "not be cancelled by interrupting a thread awaiting it" in {
    val f               = Fixture(blockOn = Set("b"))
    val runtime         = GraphRuntime(InMemoryCheckpointer())
    val handle          = runtime.start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1"))).value
    val (awaited, flag) = interruptWhen(f.started)(handle.await())
    awaited.left.value shouldBe a[CancelledError]
    flag shouldBe true
    f.interrupted.isEmpty shouldBe true
    handle.status shouldBe RunStatus.Running
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]
  }

  /**
   * A store that holds the first commit matching `gate` until `release` opens, as a database call
   * blocks, and reacts to an interrupt during it as a JDBC driver might: by returning a failed
   * commit (`failOnInterrupt`) or by throwing `InterruptedException`. Every other commit, and a
   * held one that is released without an interrupt, goes straight to an in-memory store.
   */
  final private class GatedStore(gate: Commit => Boolean, failOnInterrupt: Boolean) extends Checkpointer {
    val underlying    = InMemoryCheckpointer()
    val arrived       = new CountDownLatch(1)
    val release       = new CountDownLatch(1)
    private val armed = new AtomicBoolean(true)
    def commit(threadId: ThreadId, commit: Commit) =
      if gate(commit) && armed.compareAndSet(true, false) then {
        arrived.countDown()
        // an interrupt that lands with the release still counts, whichever the latch saw first
        val waited      = CancelledError.catchInterrupt(release.await(10, TimeUnit.SECONDS))
        val interrupted = waited.isLeft || Thread.interrupted()
        if !interrupted then underlying.commit(threadId, commit)
        else if failOnInterrupt then Left(ValidationError("store", "interrupted"))
        else throw new InterruptedException("store interrupted")
      } else underlying.commit(threadId, commit)
    def latest(threadId: ThreadId) = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
  }

  private def eventsOf(store: Checkpointer): Vector[EventRecord] = store.eventsAfter(thread, 0L, 1000).value

  /** The first superstep checkpoint; on a new thread only the claim has no parent. */
  private def superstepCommit: Commit => Boolean =
    _.checkpoint.exists(c => c.status == CheckpointStatus.Running && c.parent.isDefined)

  "A cancel during the terminal commit" should "lose: the run ends with the outcome it was committing" in {
    for failOnInterrupt <- Seq(true, false) do
      withClue(s"failOnInterrupt = $failOnInterrupt: ") {
        val store = GatedStore(_.checkpoint.exists(_.status == CheckpointStatus.Completed), failOnInterrupt)
        val handle =
          GraphRuntime(store).start(thread, Fixture(Set.empty).graph, Vector("a"), RunConfig()).value
        store.arrived.await(10, TimeUnit.SECONDS) shouldBe true
        handle.cancel() // the run has submitted its Completed checkpoint: this cancel must not interrupt it
        store.release.countDown()
        awaitResult(handle).value.completed._2 shouldBe Vector("A")
        store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Completed)
        eventsOf(store).map(_.event) should not contain RunEvent.RunCancelled
      }
  }

  "A cancel during a superstep's commit" should "end the run Cancelled, commit RunCancelled and stay recoverable" in {
    for failOnInterrupt <- Seq(true, false) do
      withClue(s"failOnInterrupt = $failOnInterrupt: ") {
        val store   = GatedStore(superstepCommit, failOnInterrupt)
        val runtime = GraphRuntime(store)
        val handle  = runtime.start(thread, Fixture(Set.empty).graph, Vector("a"), RunConfig()).value
        store.arrived.await(10, TimeUnit.SECONDS) shouldBe true
        handle.cancel()
        store.release.countDown()
        val error = awaitResult(handle).value.failed._2
        val claim = store.latest(thread).value.get.checkpoint // the interrupted commit never landed
        claim.status shouldBe CheckpointStatus.Running
        error shouldBe GraphError.Cancelled(Some(thread.value), Some(claim.id))
        val last = eventsOf(store).last
        last.event shouldBe RunEvent.RunCancelled
        last.checkpointId shouldBe Some(claim.id)
        runtime.recover(thread, Fixture(Set.empty).graph).awaited.value.completed._2 shouldBe Vector("A")
      }
  }

  "A deadline during a superstep's commit" should "end the run DeadlineExceeded, commit RunTimedOut and stay recoverable" in {
    for failOnInterrupt <- Seq(true, false) do
      withClue(s"failOnInterrupt = $failOnInterrupt: ") {
        // the held commit is never released: only the deadline's interrupt ends the wait
        val store   = GatedStore(superstepCommit, failOnInterrupt)
        val runtime = GraphRuntime(store)
        val config  = RunConfig().withBudgets(RunBudgets(timeout = Some(300.millis)))
        val handle  = runtime.start(thread, Fixture(Set.empty).graph, Vector("a"), config).value
        val error   = awaitResult(handle).value.failed._2
        val claim   = store.latest(thread).value.get.checkpoint
        error shouldBe GraphError.DeadlineExceeded(thread.value, Some(claim.id))
        org.llm4s.error.LLMError.isRecoverable(error) shouldBe true
        eventsOf(store).last.event shouldBe RunEvent.RunTimedOut
        eventsOf(store).map(_.event) should not contain RunEvent.RunCancelled
        runtime.recover(thread, Fixture(Set.empty).graph).awaited.value.completed._2 shouldBe Vector("A")
      }
  }

  "A cancel during a failing run's closing commit" should "lose: the run reports its own failure" in {
    val b      = GraphBuilder("failing", "v1")
    val node   = b.node[Unit]("n")((_, _, _) => NodeResult.Fail(ValidationError("work", "boom")))
    val g      = b.compile(node)(_ => Right(())).value
    val failed = (c: Commit) => c.events.exists(_.event.isInstanceOf[RunEvent.RunFailed])
    // Sync commits RunFailed on its own; OnExit carries it in the exit commit
    for
      durability      <- Seq(Durability.Sync, Durability.OnExit)
      failOnInterrupt <- Seq(true, false)
    do
      withClue(s"$durability, failOnInterrupt = $failOnInterrupt: ") {
        val store  = GatedStore(failed, failOnInterrupt)
        val handle = GraphRuntime(store).start(thread, g, (), RunConfig(), durability).value
        store.arrived.await(10, TimeUnit.SECONDS) shouldBe true
        handle.cancel() // the run has decided its outcome: this cancel must not interrupt its commit
        store.release.countDown()
        awaitResult(handle).value.failed._2 match {
          case GraphError.NodeFailed(_, _, cause) => cause shouldBe ValidationError("work", "boom")
          case other                              => fail(s"expected the node's own failure, got $other")
        }
        val events = eventsOf(store).map(_.event)
        events.collect { case f: RunEvent.RunFailed => f } should not be empty
        events should not contain RunEvent.RunCancelled
      }
  }

  "TaskExecutor.bounded" should "return results in task order" in {
    val tasks = (1 to 20).toVector.map(i => () => { Thread.sleep((20 - i).toLong); i })
    TaskExecutor.bounded(4).runAll(tasks) shouldBe (1 to 20).toVector
  }
}
