package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration._

/**
 * [[DefaultRunHandle.awaitEnd]] for a [[RunHandle]] that is not the runtime's own - a test double or an
 * adapter - which it polls; the runtime's handles are covered through `AgentRun.cancelAndAwaitEnd`.
 */
class RunHandleAwaitEndSpec extends AnyFlatSpec with Matchers {

  /** A handle whose status is whatever `current` holds. */
  final private class Foreign extends RunHandle[Unit] {
    val current                           = new AtomicReference[RunStatus](RunStatus.Running)
    def threadId: ThreadId                = ThreadId("foreign")
    def runId: RunId                      = RunId("foreign-run")
    def status: RunStatus                 = current.get
    def await(): Result[RunResult[Unit]]  = Left(ValidationError("await", "not used"))
    def cancel(): Unit                    = current.set(RunStatus.Failed)
    def observation: Option[Subscription] = None
    def subscribe(capacity: Int)(listener: StreamEvent => Unit): Result[Subscription] =
      Left(ValidationError("subscribe", "not used"))
  }

  "DefaultRunHandle.awaitEnd" should "return true at once for a foreign handle whose run has ended" in {
    val handle = new Foreign
    handle.cancel()
    DefaultRunHandle.awaitEnd(handle, 10.seconds) shouldBe true
  }

  it should "poll a foreign handle until its run ends" in {
    val handle = new Foreign
    Thread.ofVirtual().start { () =>
      Thread.sleep(100)
      handle.current.set(RunStatus.Completed)
    }
    DefaultRunHandle.awaitEnd(handle, 10.seconds) shouldBe true
  }

  it should "give up on a foreign handle at its bound, through an interrupt, and leave the flag set" in {
    val handle  = new Foreign
    val outcome = new LinkedBlockingQueue[(Boolean, Long, Boolean)]()
    val waiter = Thread.ofVirtual().start { () =>
      val began = System.nanoTime()
      val ended = DefaultRunHandle.awaitEnd(handle, 300.millis)
      outcome.offer((ended, System.nanoTime() - began, Thread.currentThread().isInterrupted)): Unit
    }
    Thread.sleep(50)
    waiter.interrupt()
    val (ended, waited, stillInterrupted) = outcome.poll(10, TimeUnit.SECONDS)

    ended shouldBe false
    waited.nanos should be >= 300.millis
    stillInterrupted shouldBe true
  }
}
