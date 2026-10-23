package org.llm4s.zio

import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph.{ EventHandlers, ThreadId }
import org.llm4s.agent.testkit.NestedFixtures
import zio.ZIO
import zio.durationInt
import zio.test.*

import java.util.concurrent.CountDownLatch

/** `AgentZ.nestedEvents`: a delegating run's children's events, through `EventHandlers.onChild` (design 4.47). */
object AgentZNestedEventsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("AgentZ.nestedEvents")(
    test("streams a child's events, matched by onChild, before the parent's TaskEnded") {
      val subscribed = new CountDownLatch(1)
      val agent      = NestedFixtures.delegating(subscribed)
      val handlers = EventHandlers
        .onChild(AgentEvents.TextDelta)((child, d) => s"${child.agent.value}:${d.text}")
        .on(SubagentEvents.TaskEnded)(e => s"ended:${e.status}")
        .otherwise { _ =>
          subscribed.countDown()
          "other"
        }
      for {
        run    <- ZIO.fromEither(agent.start(ThreadId("zn1"), "go"))
        seen   <- AgentZ(agent).nestedEvents(run, handlers).filter(_ != "other").runCollect
        result <- ZIO.attemptBlocking(run.await()).orDie.flatMap(ZIO.fromEither(_))
      } yield assertTrue(
        seen.last == "ended:completed",
        seen.init.map(_.stripPrefix("general:")).mkString == "hello",
        result.answer == Some("composed")
      )
    },
    test("events carries no child's events") {
      val subscribed = new CountDownLatch(1)
      val agent      = NestedFixtures.delegating(subscribed)
      val handlers = EventHandlers
        .onChild(AgentEvents.TextDelta)((_, d) => d.text)
        .otherwise { _ =>
          subscribed.countDown()
          "other"
        }
      for {
        run  <- ZIO.fromEither(agent.start(ThreadId("zn2"), "go"))
        seen <- AgentZ(agent).events(run, handlers).filter(_ != "other").runCollect
        _    <- ZIO.attemptBlocking(run.await()).orDie
      } yield assertTrue(seen.isEmpty)
    }
  ) @@ TestAspect.timeout(120.seconds)
}
