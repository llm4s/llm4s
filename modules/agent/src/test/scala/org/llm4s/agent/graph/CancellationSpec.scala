package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.CancelledError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

class CancellationSpec extends AnyFlatSpec with Matchers with EitherValues {

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

  "TaskExecutor.bounded" should "return results in task order" in {
    val tasks = (1 to 20).toVector.map(i => () => { Thread.sleep((20 - i).toLong); i })
    TaskExecutor.bounded(4).runAll(tasks) shouldBe (1 to 20).toVector
  }
}
