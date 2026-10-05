package org.llm4s.zio

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import org.llm4s.agent.{ Agent, ThreadStatus }
import org.llm4s.error.CancelledError
import org.llm4s.toolapi.ToolRegistry
import zio.{ ZIO, durationInt }
import zio.test.*

/**
 * Core's contract is that cancellation is by interrupt: providers preserve the interrupt and return
 * `Left(CancelledError)`. The non-streaming bridges must therefore run the provider call on an
 * interruptible thread, otherwise interrupting the fiber (or `ZIO.timeout`) waits for the whole call.
 */
object CancellationSpec extends ZIOSpecDefault {
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

  private def awaitStarted(p: Parked): ZIO[Any, Nothing, Boolean] =
    ZIO.attemptBlocking(p.started.await(DeadlineSeconds, TimeUnit.SECONDS)).orDie

  // The interrupt must return promptly; a non-interruptible call would hold it for the park deadline.
  private def interruptPromptly[E, A](fiber: zio.Fiber[E, A]): ZIO[Any, Nothing, Boolean] =
    fiber.interrupt.timeout(zio.Duration.fromSeconds(PromptSeconds)).map(_.isDefined).withClock(zio.Clock.ClockLive)

  val spec =
    suite("cancellation by interrupt")(
      test("LLMClientZ.complete interrupts the blocking provider call when the fiber is interrupted") {
        val parked = new Parked
        for {
          fiber    <- LLMClientZ(parked.client).complete(conversation).fork
          started  <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(started) && assertTrue(promptly) && assertTrue(parked.sawInterrupt.get())
      },
      test("LLMClientZ.complete releases the provider thread of every interrupted call") {
        ZIO
          .foreach(0 until 5) { _ =>
            val parked = new Parked
            for {
              fiber <- LLMClientZ(parked.client).complete(conversation).fork
              _     <- awaitStarted(parked)
              _     <- interruptPromptly(fiber)
              freed <- ZIO.attemptBlocking(awaitCondition(parked.live.get() == 0)).orDie
            } yield freed && parked.sawInterrupt.get()
          }
          .map(rs => assertTrue(rs.forall(identity)))
      },
      test("AgentZ.run interrupts the provider call when the fiber is interrupted") {
        val parked = new Parked
        for {
          fiber    <- AgentZ(new Agent(parked.client)).run("q", ToolRegistry.empty).fork
          _        <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(promptly) && assertTrue(parked.sawInterrupt.get())
      },
      test("AgentZ.continueConversation interrupts the provider call when the fiber is interrupted") {
        val parked = new Parked
        for {
          first    <- AgentZ(new Agent(new Scripted())).run("q1", ToolRegistry.empty)
          fiber    <- AgentZ(new Agent(parked.client)).continueConversation(first, "q2", ToolRegistry.empty).fork
          _        <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(first.status == ThreadStatus.Completed) &&
          assertTrue(promptly) &&
          assertTrue(parked.sawInterrupt.get())
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
}
