package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import java.util.concurrent.{ CompletableFuture, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

/**
 * [[DefaultRunHandle.closeWithin]] (#1919), the bound on a run's closing wait while it holds its claim, driven
 * directly: `stalledSince` is the test's, and reports a moment already a whole bound in the past, so every wait the
 * watchdog makes is over at once and nothing here waits for real time to pass. The watchdog gives the wait up only
 * when the same commit has been in flight for the whole bound, never while commits keep returning.
 */
class RunHandleCloseWithinSpec extends AnyFlatSpec with Matchers {

  private val bound = 1_000_000_000L
  private val state = ThreadState.empty(Map.empty)

  /** A handle whose run thread returns `Completed` once `runGate` opens; `released` counts its releases. */
  final private class Launched {
    val runGate  = new CountDownLatch(1)
    val released = new AtomicInteger()
    val handle = new DefaultRunHandle[String](
      ThreadId("t"),
      RunId("r"),
      1L,
      StopSignal(),
      (_, _, _, _) => Left(ValidationError("subscribe", "not used")),
      None
    )
    handle.launch(
      () => {
        runGate.await(10, TimeUnit.SECONDS): Unit
        RunResult.Completed(state, "done", 1)
      },
      thrown => RunResult.Failed(state, ValidationError("crashed", thrown.toString)),
      () => released.incrementAndGet(): Unit
    )
  }

  private val givenUp = RunResult.Failed(state, GraphError.CommitTimedOut("t", 1.second))

  "A run's closing wait" should "be given up once one commit has been in flight for the whole bound" in {
    val running  = Launched()
    val stuckAt  = System.nanoTime() - 2 * bound
    val computed = new AtomicInteger()
    running.handle.closeWithin(
      new CompletableFuture[Unit](),
      () => Some(stuckAt),
      bound,
      () => { computed.incrementAndGet(); givenUp },
      () => running.released.incrementAndGet(): Unit
    )

    running.handle.awaitEnd(5.seconds) shouldBe true
    running.handle.await() shouldBe Right(givenUp)
    computed.get shouldBe 1
    running.released.get shouldBe 1
    // the run thread is still in its closing wait; when it returns it changes nothing
    running.handle.runThreadExited.getCount shouldBe 1
    running.runGate.countDown()
    running.handle.runThreadExited.await(5, TimeUnit.SECONDS) shouldBe true
    running.handle.await() shouldBe Right(givenUp)
    running.released.get shouldBe 1
  }

  it should "never be given up while commits keep returning, however long the wait lasts in all" in {
    val running = Launched()
    val closed  = new CompletableFuture[Unit]()
    val checks  = new AtomicInteger()
    // each check finds a newer commit in flight - the last returned, and another began - than the one before, though
    // every one of them is already a whole bound old: the drain has lasted many bounds, but never stalled for one
    val progressed = new AtomicLong(System.nanoTime() - 100 * bound)
    val computed   = new AtomicInteger()
    running.handle.closeWithin(
      closed,
      () => {
        if checks.incrementAndGet() == 50 then closed.complete(()): Unit
        Some(progressed.incrementAndGet())
      },
      bound,
      () => { computed.incrementAndGet(); givenUp },
      () => running.released.incrementAndGet(): Unit
    )

    closed.get(5, TimeUnit.SECONDS)
    running.runGate.countDown()
    running.handle.awaitEnd(5.seconds) shouldBe true
    running.handle.await().map(_.isInstanceOf[RunResult.Completed[?]]) shouldBe Right(true)
    computed.get shouldBe 0
    checks.get should be >= 50
  }

  it should "leave a run its thread settled first to its own outcome" in {
    val running = Launched()
    running.runGate.countDown()
    running.handle.awaitEnd(5.seconds) shouldBe true
    val computed = new AtomicInteger()
    val watched  = new CountDownLatch(1)
    running.handle.closeWithin(
      new CompletableFuture[Unit](),
      () => { watched.countDown(); Some(System.nanoTime() - 2 * bound) },
      bound,
      () => { computed.incrementAndGet(); givenUp },
      () => running.released.incrementAndGet(): Unit
    )
    watched.await(5, TimeUnit.SECONDS) shouldBe true
    running.handle.await().map(_.isInstanceOf[RunResult.Completed[?]]) shouldBe Right(true)
    running.released.get shouldBe 1
    computed.get shouldBe 0
  }
}
