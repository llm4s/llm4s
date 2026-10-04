package org.llm4s.zio

import zio.{ Fiber, Promise, ZIO, durationInt }
import zio.test.*

/**
 * The provider thread offers each chunk to the bounded queue through a ZIO fiber and blocks on it.
 * If the consumer goes away while that offer is parked on a full queue, the thread is interrupted
 * and the offer fiber must not be left behind suspended forever.
 */
object StreamFiberLeakSpec extends ZIOSpecDefault {
  import Fixtures.*

  /** Root fibers that did not exist before the scenario and are still alive (not Done). */
  private def liveNewRoots(before: Set[zio.FiberId.Runtime]): ZIO[Any, Nothing, Seq[Fiber.Runtime[?, ?]]] =
    Fiber.roots.flatMap { roots =>
      ZIO
        .foreach(roots.filterNot(f => before.contains(f.id)))(f => f.status.map(st => (f, st)))
        .map(_.collect { case (f, st) if !st.isDone => f })
    }

  private def noLeakedRoots: ZIO[Any, Nothing, Boolean] = {
    val counting = new Counting(100000)
    for {
      before <- Fiber.roots.map(_.map(_.id).toSet)
      gate   <- Promise.make[Nothing, Unit]
      fiber <- LLMClientZ(counting.client)
        .streamComplete(conversation)
        .tap(_ => gate.await)
        .runDrain
        .fork
      _ <- ZIO.attemptBlocking(awaitBlocked(counting)).orDie
      _ <- fiber.interrupt
      _ <- ZIO.attemptBlocking(counting.exited.await()).orDie
      // Teardown of the offer fiber is asynchronous: spin until nothing new is left, or give up.
      // (No ZIO.timeout here: its own timer fiber would show up as a new root fiber.)
      deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(DeadlineSeconds)
      settled <- ZIO.iterate(false)(!_ && System.nanoTime() < deadline) { _ =>
        liveNewRoots(before).map(_.isEmpty) <* ZIO.yieldNow
      }
    } yield settled
  }

  val spec =
    suite("stream producer fibers")(
      test("interrupting a consumer while the producer is blocked on a full queue leaves no suspended fiber") {
        noLeakedRoots.map(settled => assertTrue(settled))
      } @@ TestAspect.repeats(4)
    ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
}
