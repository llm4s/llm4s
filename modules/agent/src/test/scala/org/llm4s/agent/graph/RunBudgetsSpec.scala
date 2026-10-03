package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentHashMap, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*

class RunBudgetsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t")

  /** The run's result, awaited on another thread so that a hung run fails the test within 5s. */
  private def await[O](handle: RunHandle[O]): RunResult[O] = {
    val done = new LinkedBlockingQueue[Result[RunResult[O]]]()
    Thread.ofVirtual().start(() => done.offer(handle.await()): Unit)
    Option(done.poll(5, TimeUnit.SECONDS)).getOrElse(fail("await did not return within 5s")).value
  }

  private def events(store: Checkpointer): Vector[RunEvent] =
    store.eventsAfter(thread, 0L, 10_000).value.map(_.event)

  private def budgets(b: RunBudgets): RunConfig = RunConfig().withBudgets(b)

  /**
   * plan fans out to workers; while `blocking` is set, a worker whose item is in `blockOn` waits
   * for every other item to finish, then blocks until interrupted, recording that it saw the
   * interrupt. Every worker counts its runs and its concurrency.
   */
  final private class FanOut(blockOn: Set[String], items: Vector[String], work: () => Unit = () => ()) {
    val blocking    = new AtomicBoolean(true)
    val runs        = new ConcurrentHashMap[String, AtomicInteger]()
    val interrupted = ConcurrentHashMap.newKeySet[String]()
    val finished    = ConcurrentHashMap.newKeySet[String]()
    val running     = new AtomicInteger()
    val maxRunning  = new AtomicInteger()
    val results     = StateKey.appending[String]("results")

    def ran(item: String): Int = Option(runs.get(item)).fold(0)(_.get)

    val graph: CompiledGraph[Vector[String], Vector[String]] = {
      val b = GraphBuilder("fan-out", "v1")
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
        runs.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        maxRunning.accumulateAndGet(running.incrementAndGet(), math.max)
        if blocking.get && blockOn(item) then
          while items.filterNot(blockOn).exists(i => !finished.contains(i)) do Thread.onSpinWait()
          CancelledError.catchInterrupt(Thread.sleep(60_000)).left.foreach { e =>
            interrupted.add(item)
            running.decrementAndGet()
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

  /** A one-node graph whose node counts its runs, then blocks until interrupted while `blocking` is set. */
  private def blockingGraph(runs: AtomicInteger, blocking: AtomicBoolean): CompiledGraph[String, String] = {
    val out = StateKey.appending[String]("out")
    val b   = GraphBuilder("one-node", "v1")
    val node = b.node[String]("n", writes = Set(out)) { (input, _, _) =>
      runs.incrementAndGet()
      if blocking.get then Thread.sleep(60_000) // an interrupt throws out of the node
      continue(Command.empty.update(out, input))
    }
    b.compile(node)(_.get(out).map(_.mkString)).value
  }

  "A timeout" should "stop every sibling with DeadlineExceeded and RunTimedOut, leaving the run recoverable" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val f       = FanOut(blockOn = Set("b", "c"), items = Vector("a", "b", "c"))
    val handle  = runtime.start(thread, f.graph, Vector("a", "b", "c"), budgets(RunBudgets(timeout = Some(200.millis))))

    val result = await(handle.value)
    val latest = store.latest(thread).value.get
    result.failed._2 shouldBe GraphError.DeadlineExceeded("t", Some(latest.checkpoint.id))
    LLMError.isRecoverable(result.failed._2) shouldBe true
    f.interrupted.toArray.toSet shouldBe Set("b", "c")
    latest.checkpoint.status shouldBe CheckpointStatus.Running
    events(store) should contain(RunEvent.RunTimedOut)
    events(store) should not contain RunEvent.RunCancelled

    f.blocking.set(false)
    await(runtime.recover(f.graph, thread).value).completed._2.toSet shouldBe Set("A", "B", "C")
    f.ran("a") shouldBe 1 // its pending write survived the deadline
    f.ran("b") shouldBe 2
  }

  it should "report DeadlineExceeded before any superstep when it has already passed" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val runs    = new AtomicInteger()
    val g       = blockingGraph(runs, new AtomicBoolean(false))
    val result  = await(runtime.start(thread, g, "x", budgets(RunBudgets(timeout = Some(1.nanosecond)))).value)
    result.failed._2 shouldBe a[GraphError.DeadlineExceeded]
    runs.get shouldBe 0
    events(store) should contain(RunEvent.RunTimedOut)
    await(runtime.recover(g, thread).value).completed._2 shouldBe "x"
    runs.get shouldBe 1
  }

  it should "report exactly one cause when cancel races expiry" in {
    (1 to 100).foreach { i =>
      val store   = InMemoryCheckpointer()
      val runtime = GraphRuntime(store)
      val g       = blockingGraph(new AtomicInteger(), new AtomicBoolean(true))
      val handle  = runtime.start(thread, g, "x", budgets(RunBudgets(timeout = Some(50.millis)))).value
      val canceller = Thread.ofVirtual().start { () =>
        Thread.sleep(50)
        handle.cancel()
      }
      val result = await(handle)
      canceller.join(5_000)
      val stops = events(store).filter(e => e == RunEvent.RunCancelled || e == RunEvent.RunTimedOut)
      result.failed._2 match {
        case _: GraphError.Cancelled        => stops shouldBe Vector(RunEvent.RunCancelled)
        case _: GraphError.DeadlineExceeded => stops shouldBe Vector(RunEvent.RunTimedOut)
        case other                          => fail(s"iteration $i: $other")
      }
    }
  }

  "maxConcurrency" should "bound the tasks of a superstep that run at once" in {
    val items = Vector("a", "b", "c", "d", "e", "f")
    def maxInFlight(n: Int): Int = {
      val f = FanOut(blockOn = Set.empty, items = items, work = () => Thread.sleep(50))
      runInMemory(f.graph, items, budgets(RunBudgets(maxConcurrency = n))).completed
      f.maxRunning.get
    }
    maxInFlight(2) shouldBe 2
    maxInFlight(6) should be > 2
  }

  "maxSupersteps" should "limit each run, so a recover gets a fresh allowance" in {
    val b     = GraphBuilder("forever", "v1")
    val count = StateKey.replace[Int]("count", 0)
    val tick  = b.declare[Unit]("tick")
    b.implement(tick, writes = Set(count)) { (_, state, _) =>
      NodeResult.fromResult(state.get(count).map(n => Command.empty.update(count, n + 1).goto(tick)))
    }
    val g       = b.compile(tick)(_.get(count)).value
    val runtime = GraphRuntime.inMemory()
    val limit   = budgets(RunBudgets(maxSupersteps = 3))

    val (first, error) = await(runtime.start(thread, g, (), limit).value).failed
    error shouldBe GraphError.SuperstepLimitExceeded(3)
    first.get(count).value shouldBe 3
    val (second, again) = await(runtime.recover(g, thread, limit).value).failed
    again shouldBe GraphError.SuperstepLimitExceeded(3)
    second.get(count).value shouldBe 6
  }
}
