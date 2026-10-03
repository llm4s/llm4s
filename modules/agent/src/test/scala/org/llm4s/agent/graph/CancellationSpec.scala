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
    lazy val f: Fixture = Fixture(
      blockOn = Set("b", "c"),
      onBlock = () => while !f.finished.contains("a") do Thread.onSpinWait()
    )
    val (result, flag) = interruptWhen(f.started)(f.graph.run(Vector("a", "b", "c")))
    result.failed._2 shouldBe GraphError.Cancelled(None, None)
    flag shouldBe true
    f.interrupted.toArray.toSet shouldBe Set("b", "c") // every blocked sibling saw the interrupt and was joined
    f.finished.toArray.toSet shouldBe Set("a")
    f.running.get shouldBe 2 // the blocked tasks threw before decrementing
  }

  it should "cancel a single-task superstep, which runs inline on the calling thread" in {
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
    val (result, flag) = interruptWhen(started)(g.run(()))
    result.failed._2 shouldBe GraphError.Cancelled(None, None)
    flag shouldBe true
    interrupted.get shouldBe 1
  }

  it should "run a superstep's tasks concurrently, bounded by the default limit" in {
    val f     = Fixture(blockOn = Set.empty, work = () => Thread.sleep(5))
    val items = (1 to 40).map(i => s"i$i").toVector
    f.graph.run(items).completed._2.size shouldBe 40
    f.maxRunning.get should be > 1
    f.maxRunning.get should be <= TaskExecutor.DefaultLimit
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
    val (result, flag) = onVirtualThread(g.run(()))
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
    val (result, flag) = onVirtualThread(g.run(()))
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
        val (result, flag) =
          interruptWhen(f.started)(runtime.start(thread, f.graph, Vector("a", "b"), RunId("run-1"), durability).value)

        val (_, error) = result.failed
        error shouldBe a[GraphError.Cancelled]
        flag shouldBe true
        val latest = store.latest(thread).value.get
        latest.checkpoint.status shouldBe CheckpointStatus.Running
        val last = store.eventsAfter(thread, 0L, 100).value.last
        last.event shouldBe RunEvent.RunCancelled
        last.checkpointId shouldBe Some(latest.checkpoint.id)
        error shouldBe GraphError.Cancelled(Some(thread.value), Some(latest.checkpoint.id))

        // the claim was released, and recovery finishes the run without running "a" again
        val g = Fixture(blockOn = Set.empty)
        runtime.recover(g.graph, thread, RunId("run-2")).value.completed._2 shouldBe Vector("A", "B")
        g.finished.toArray.toSet shouldBe Set("b")
      }
  }

  it should "record nothing for a task interrupted mid-run" in {
    val f       = afterOthers(blockOn = Set("b"), others = 1)
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    interruptWhen(f.started)(runtime.start(thread, f.graph, Vector("a", "b"), RunId("run-1")).value)
    store.latest(thread).value.get.pendingWrites.map(_.nodeId) shouldBe Vector("worker") // a's only
    store.eventsAfter(thread, 0L, 100).value.map(_.event).collect { case e: RunEvent.TaskFailed => e } shouldBe empty
  }

  it should "be stopped by a timeout wrapped around the call, with no task left running" in {
    val f       = Fixture(blockOn = Set("b", "c"))
    val runtime = GraphRuntime(InMemoryCheckpointer())
    val outcome = Try(ox.timeout(2.seconds)(runtime.start(thread, f.graph, Vector("a", "b", "c"), RunId("run-1"))))
    outcome.failed.get shouldBe a[TimeoutException]
    f.interrupted.toArray.toSet shouldBe Set("b", "c") // both stopped and joined before timeout returned
    runtime.recover(Fixture(Set.empty).graph, thread, RunId("run-2")).value.completed._2 shouldBe Vector("A", "B", "C")
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
    val (result, flag) =
      interruptWhen(started)(GraphRuntime(InMemoryCheckpointer()).start(thread, g, Vector("x", "y"), RunId("r")).value)
    result.failed._2 shouldBe a[GraphError.Cancelled]
    flag shouldBe true
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
    val (result, flag) =
      interruptWhen(f.started)(runtime.start(thread, f.graph, Vector("a", "b"), RunId("run-1"), Durability.Async).value)
    result.failed._2 match {
      case GraphError.CheckpointWriteFailed(_, _, Some(_: GraphError.Cancelled)) => succeed
      case other => fail(s"expected CheckpointWriteFailed with the cancellation, got $other")
    }
    flag shouldBe true
    // the thread claim was released: the next call is refused for the thread's state, not ThreadBusy
    runtime.start(thread, f.graph, Vector("z"), RunId("run-2")).left.value shouldBe a[GraphError.IncompleteRun]
  }

  it should "drain an Async queue before returning when interrupted after its last superstep" in {
    // the writer holds the run's final (Completed) checkpoint commit until the caller has been
    // interrupted, so the interrupt lands while the queue is not yet drained
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
    @volatile var outcome: Option[(RunResult[Vector[String]], Option[CheckpointStatus], Boolean)] = None
    val runner = Thread.ofVirtual().start { () =>
      val result = runtime.start(thread, f.graph, Vector("a"), RunId("run-1"), Durability.Async).value
      outcome = Some((result, slow.latest(thread).value.map(_.checkpoint.status), Thread.currentThread().isInterrupted))
    }
    arrived.await(10, TimeUnit.SECONDS) shouldBe true
    runner.interrupt() // the run has submitted its last commit and is closing the committer
    release.countDown()
    runner.join(10_000)
    runner.isAlive shouldBe false
    val (result, latestStatus, flag) = outcome.get
    result.completed._2 shouldBe Vector("A") // not CheckpointWriteFailed: an interrupt is not a write failure
    latestStatus shouldBe Some(CheckpointStatus.Completed) // durable before start returned
    flag shouldBe true
  }

  "TaskExecutor.bounded" should "return results in task order" in {
    val tasks = (1 to 20).toVector.map(i => () => { Thread.sleep((20 - i).toLong); i })
    TaskExecutor.bounded(4).runAll(tasks) shouldBe (1 to 20).toVector
  }
}
