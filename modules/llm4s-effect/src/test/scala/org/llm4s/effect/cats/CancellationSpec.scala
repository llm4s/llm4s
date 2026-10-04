package org.llm4s.effect.cats

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.error.CancelledError
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * Core's contract is that cancellation is by interrupt: providers preserve the interrupt and return
 * `Left(CancelledError)`. The non-streaming bridges must therefore run the provider call on an
 * interruptible thread, otherwise cancelling the fiber (or `IO.timeout`) waits for the whole call.
 */
class CancellationSpec extends AnyFlatSpec with Matchers {
  import Fixtures.*

  final private class Parked {
    val started: CountDownLatch     = new CountDownLatch(1)
    val sawInterrupt: AtomicBoolean = new AtomicBoolean(false)
    val live: AtomicInteger         = new AtomicInteger(0)
    val client: Scripted = new Scripted(onComplete = () => {
      live.incrementAndGet()
      started.countDown()
      sawInterrupt.set(parkUntilInterrupted())
      live.decrementAndGet()
      Left(CancelledError("test"))
    })
  }

  "LLMClientIO.complete" should "interrupt the blocking provider call when the fiber is cancelled" in {
    val parked = new Parked
    val fiber  = LLMClientIO[IO](parked.client).complete(conversation).start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(5.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
    fiber.join.unsafeRunSync() shouldBe a[Outcome.Canceled[?, ?, ?]]
  }

  it should "release the provider thread of every cancelled call" in {
    (0 until 5).foreach { _ =>
      val parked = new Parked
      val fiber  = LLMClientIO[IO](parked.client).complete(conversation).start.unsafeRunSync()
      parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      fiber.cancel.timeout(5.seconds).attempt.unsafeRunSync()
      awaitCondition(parked.live.get() == 0) shouldBe true
      parked.sawInterrupt.get() shouldBe true
    }
  }

  "AgentIO.run" should "interrupt the provider call when the fiber is cancelled" in {
    val parked = new Parked
    val fiber  = AgentIO[IO](new Agent(parked.client)).run("q", ToolRegistry.empty).start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(5.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
  }

  "AgentIO.continueConversation" should "interrupt the provider call when the fiber is cancelled" in {
    val first = AgentIO[IO](new Agent(new Scripted()))
      .run("q1", ToolRegistry.empty)
      .unsafeRunSync()
    first.status shouldBe AgentStatus.Complete
    val parked = new Parked
    val fiber  = AgentIO[IO](new Agent(parked.client)).continueConversation(first, "q2").start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(5.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
  }
}
