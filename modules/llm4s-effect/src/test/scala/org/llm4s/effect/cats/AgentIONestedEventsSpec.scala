package org.llm4s.effect.cats

import org.llm4s.testutil.TestTimeLimit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph.{ EventHandlers, ThreadId }
import org.llm4s.agent.testkit.NestedFixtures
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CountDownLatch

/** `AgentIO.nestedEvents`: a delegating run's children's events, through `EventHandlers.onChild` (design 4.47). */
class AgentIONestedEventsSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  "AgentIO.nestedEvents" should "stream a child's events, matched by onChild, before the parent's TaskEnded" in {
    val subscribed = new CountDownLatch(1)
    val agent      = NestedFixtures.delegating(subscribed)
    val run        = ok(agent.start(ThreadId("n1"), "go"))
    val handlers = EventHandlers
      .onChild(AgentEvents.TextDelta)((child, d) => s"${child.agent.value}:${d.text}")
      .on(SubagentEvents.TaskEnded)(e => s"ended:${e.status}")
      .otherwise { _ =>
        subscribed.countDown()
        "other"
      }
    val seen = AgentIO[IO](agent).nestedEvents(run, handlers).filter(_ != "other").compile.toList.unsafeRunSync()

    seen.last shouldBe "ended:completed"
    seen.init.map(_.stripPrefix("general:")).mkString shouldBe "hello"
    ok(run.await()).answer shouldBe Some("composed")
  }

  "AgentIO.events" should "not carry a child's events" in {
    val subscribed = new CountDownLatch(1)
    val agent      = NestedFixtures.delegating(subscribed)
    val run        = ok(agent.start(ThreadId("n2"), "go"))
    val handlers = EventHandlers
      .onChild(AgentEvents.TextDelta)((_, d) => d.text)
      .otherwise { _ =>
        subscribed.countDown()
        "other"
      }
    AgentIO[IO](agent).events(run, handlers).filter(_ != "other").compile.toList.unsafeRunSync() shouldBe Nil
    ok(run.await()).answer shouldBe Some("composed")
  }
}
