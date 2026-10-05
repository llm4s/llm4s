package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, LLMError, NetworkError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A node's [[RetryPolicy]], run through the real executor. The wait between attempts goes through a
 * recording sleeper, so the backoff schedule is exact and no spec sleeps.
 */
class NodeRetrySpec extends AnyFlatSpec with Matchers with EitherValues {

  private val out    = StateKey.replace[String]("out", "")
  private val thread = ThreadId("retry")

  private def down(attempt: Int): LLMError = NetworkError(s"down on attempt $attempt", None, "https://example.test")

  /**
   * One node, `work`, that fails the first `failures` attempts with `error(attempt)` and then
   * writes `done after <attempt>`. It emits a durable event on every attempt.
   */
  final private class Flaky(policy: RetryPolicy, failures: Int, error: Int => LLMError = down) {
    val calls  = new AtomicInteger()
    val delays = new ConcurrentLinkedQueue[FiniteDuration]()

    val graph: CompiledGraph[Unit, String] = {
      val b = GraphBuilder("flaky", "v1")
      val work = b.node[Unit]("work", writes = Set(out), retry = policy) { (_, _, context) =>
        val attempt = calls.incrementAndGet()
        context.emit("attempt", 1, ujson.Num(attempt))
        if attempt <= failures then NodeResult.Fail(error(attempt))
        else continue(Command.empty.update(out, s"done after $attempt"))
      }
      b.compile(work)(_.get(out)).value.withSleeper(delay => delays.add(delay): Unit)
    }

    def waited: List[FiniteDuration] = delays.asScala.toList
  }

  "A node with a retry policy" should "run again after a recoverable failure until it succeeds" in {
    val flaky = new Flaky(RetryPolicy(maxAttempts = 3, initialBackoff = 100.millis, backoffFactor = 2.0), failures = 2)

    runInMemory(flaky.graph, ()).completed._2 shouldBe "done after 3"

    flaky.calls.get shouldBe 3
    flaky.waited shouldBe List(100.millis, 200.millis)
  }

  it should "wait the capped exponential backoff between attempts" in {
    val policy = RetryPolicy(
      maxAttempts = 5,
      initialBackoff = 100.millis,
      backoffFactor = 3.0,
      maxBackoff = 500.millis
    )
    val flaky = new Flaky(policy, failures = 4)

    runInMemory(flaky.graph, ()).completed._2 shouldBe "done after 5"

    flaky.waited shouldBe List(100.millis, 300.millis, 500.millis, 500.millis)
  }

  it should "fail with the last attempt's error once its attempts run out" in {
    val flaky = new Flaky(RetryPolicy(maxAttempts = 3), failures = 10)

    val (_, error) = runInMemory(flaky.graph, ()).failed

    error match {
      case GraphError.NodeFailed(node, _, cause) =>
        node shouldBe NodeId("work")
        cause.message should include("down on attempt 3")
      case other => fail(other.toString)
    }
    flaky.calls.get shouldBe 3
    flaky.waited should have size 2
  }

  it should "not retry a non-recoverable error" in {
    val flaky = new Flaky(RetryPolicy(maxAttempts = 3), failures = 1, error = n => ValidationError("work", s"bad $n"))

    runInMemory(flaky.graph, ()).failed._2 shouldBe a[GraphError.NodeFailed]

    flaky.calls.get shouldBe 1
    flaky.waited shouldBe empty
  }

  it should "retry whatever the policy's predicate accepts" in {
    val flaky = new Flaky(
      RetryPolicy(maxAttempts = 3, retryOn = _ => true),
      failures = 2,
      error = n => ValidationError("work", s"bad $n")
    )

    runInMemory(flaky.graph, ()).completed._2 shouldBe "done after 3"
  }

  it should "retry a node that throws, when the predicate accepts the failure" in {
    val calls = new AtomicInteger()
    val b     = GraphBuilder("throws", "v1")
    val work = b.node[Unit]("work", writes = Set(out), retry = RetryPolicy(maxAttempts = 3, retryOn = _ => true)) {
      (_, _, _) =>
        if calls.incrementAndGet() < 3 then throw new IllegalStateException("boom")
        else continue(Command.empty.update(out, "recovered"))
    }
    val graph = b.compile(work)(_.get(out)).value.withSleeper(_ => ())

    runInMemory(graph, ()).completed._2 shouldBe "recovered"
    calls.get shouldBe 3
  }

  it should "not retry a result the kernel rejects" in {
    val calls      = new AtomicInteger()
    val undeclared = StateKey.replace[String]("undeclared", "")
    val b          = GraphBuilder("rejected", "v1")
    val work = b.node[Unit]("work", retry = RetryPolicy(maxAttempts = 3, retryOn = _ => true)) { (_, _, _) =>
      calls.incrementAndGet()
      continue(Command.empty.update(undeclared, "x"))
    }
    val graph = b.compile(work)(_ => Right(())).value.withSleeper(_ => ())

    runInMemory(graph, ()).failed._2 shouldBe a[GraphError.UndeclaredWrite]

    calls.get shouldBe 1
  }

  it should "not retry a cancellation, whatever the predicate says" in {
    val calls = new AtomicInteger()
    val b     = GraphBuilder("cancelled", "v1")
    val work = b.node[Unit]("work", retry = RetryPolicy(maxAttempts = 3, retryOn = _ => true)) { (_, _, _) =>
      calls.incrementAndGet()
      NodeResult.Fail(CancelledError("work"))
    }
    val graph = b.compile(work)(_ => Right(())).value.withSleeper(_ => ())

    runInMemory(graph, ()).failed

    calls.get shouldBe 1
  }

  it should "stop retrying when the wait between attempts is interrupted" in {
    val flaky = new Flaky(RetryPolicy(maxAttempts = 5), failures = 10)
    val interrupted =
      flaky.graph.withSleeper(_ => throw new InterruptedException("cancelled during the backoff wait"))

    val (_, error) = runInMemory(interrupted, ()).failed

    flaky.calls.get shouldBe 1
    (error.isInstanceOf[CancelledError] || error.isInstanceOf[GraphError.Cancelled]) shouldBe true
  }

  "A node without a retry policy" should "fail on its first failure" in {
    val flaky = new Flaky(RetryPolicy.none, failures = 1)

    runInMemory(flaky.graph, ()).failed._2 shouldBe a[GraphError.NodeFailed]

    flaky.calls.get shouldBe 1
    flaky.waited shouldBe empty
  }

  "In a durable run" should "give recover the node's full policy again" in {
    val flaky   = new Flaky(RetryPolicy(maxAttempts = 3), failures = 4)
    val runtime = GraphRuntime.inMemory()

    runtime.start(thread, flaky.graph, (), RunConfig()).awaited.value.failed
    flaky.calls.get shouldBe 3

    runtime.recover(thread, flaky.graph, RunConfig()).awaited.value.completed._2 shouldBe "done after 5"
    flaky.calls.get shouldBe 5
  }

  it should "commit one result for a task that succeeded after retries, with no trace of the failed attempts" in {
    val store   = new InMemoryCheckpointer
    val runtime = GraphRuntime(store)
    val flaky   = new Flaky(RetryPolicy(maxAttempts = 3), failures = 2)

    runtime.start(thread, flaky.graph, (), RunConfig()).awaited.value.completed._2 shouldBe "done after 3"

    val events = store.eventsAfter(thread, 0L, 100).value.map(_.event)
    events.count(_ == RunEvent.TaskCompleted) shouldBe 1
    events.collect { case failed: RunEvent.TaskFailed => failed } shouldBe empty
    // the node emitted on every attempt; only the attempt that succeeded left its event
    events.collect { case RunEvent.Custom(name, _, payload) => name -> payload } shouldBe
      Vector("attempt" -> ujson.Num(3))
  }

  "A retry policy" should "not change the graph's structural fingerprint" in {
    def fingerprint(policy: RetryPolicy): String = {
      val b    = GraphBuilder("same", "v1")
      val work = b.node[Unit]("work", writes = Set(out), retry = policy)((_, _, _) => continue(Command.empty))
      b.compile(work)(_.get(out)).value.fingerprint
    }

    fingerprint(RetryPolicy.none) shouldBe fingerprint(RetryPolicy(maxAttempts = 9))
  }
}
