package org.llm4s.agent.graph

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * A run whose node ignores interrupts (#3253): a cancel or a timeout records its cause, and once the loss bound - twice
 * the claim's ttl - has passed without the run thread ending, the run is settled without it, `Cancelled` or
 * `DeadlineExceeded`, and its thread's claim released, so another run may take the thread.
 */
class StopBoundSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues {

  private val claims = ClaimPolicy(ttl = 300.millis, renewEvery = 100.millis)

  /** A node that ignores every interrupt until `release` opens; `entered` opens as it starts. */
  private def stubborn(entered: CountDownLatch, release: CountDownLatch, ran: AtomicInteger) = {
    val b = GraphBuilder("stubborn", "v1")
    val node = b.node[Unit]("n") { (_, _, _) =>
      ran.incrementAndGet()
      entered.countDown()
      // an interrupt ends a wait, never the loop: the node returns only once released
      while (!org.llm4s.error.CancelledError.catchInterrupt(release.await(10, TimeUnit.MILLISECONDS)).contains(true))
        Thread.interrupted(): Unit
      continue(Command.empty)
    }
    b.compile(node)(_ => Right(())).value
  }

  "A cancel of a run whose node ignores interrupts" should "end it and free its thread within the bound (#3253)" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val store   = InMemoryCheckpointer()
    val runtime = new GraphRuntime(store, claims = claims)
    val thread  = ThreadId("stubborn-cancel")
    val handle  = runtime.start(thread, stubborn(entered, release, new AtomicInteger()), (), RunConfig()).value
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    val since = System.nanoTime()
    handle.cancel()
    handle.await().value.failed._2 shouldBe a[GraphError.Cancelled]
    (System.nanoTime() - since).nanos should be < 5.seconds
    store.claim(thread, ClaimRequest(RunId("next"), None, 1.second)).isRight shouldBe true
    release.countDown()
  }

  "A timeout of a run whose node ignores interrupts" should "end it DeadlineExceeded within the bound (#3253)" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val store   = InMemoryCheckpointer()
    val runtime = new GraphRuntime(store, claims = claims)
    val thread  = ThreadId("stubborn-timeout")
    val config  = RunConfig().withBudgets(RunBudgets.default.withTimeout(200.millis))
    val handle  = runtime.start(thread, stubborn(entered, release, new AtomicInteger()), (), config).value
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    handle.await().value.failed._2 shouldBe a[GraphError.DeadlineExceeded]
    release.countDown()
  }
}
