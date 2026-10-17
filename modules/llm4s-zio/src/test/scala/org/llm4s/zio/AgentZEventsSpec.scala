package org.llm4s.zio

import org.llm4s.agent.AgentRun
import org.llm4s.agent.graph.{ EventHandlers, ThreadId }
import org.llm4s.agent.graph.tool.{ ToolContent, ToolContext, ToolOutcome }
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.agent.testkit.Fixtures.Pings
import org.llm4s.agent.testkit.Fixtures.Pings.Ping
import org.llm4s.error.LLMError
import zio.{ Chunk, ZIO }
import zio.test.*

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

object AgentZEventsSpec extends ZIOSpecDefault {

  final private case class Started(z: AgentZ, run: AgentRun)

  private def started(body: ToolContext => ToolOutcome, thread: String): ZIO[Any, LLMError, Started] = {
    val agent = Pings.agent(body)
    ZIO.fromEither(agent.start(ThreadId(thread), "go")).map(run => Started(AgentZ(agent), run))
  }

  private def awaited(run: AgentRun): ZIO[Any, LLMError, Option[String]] =
    ZIO.attemptBlocking(run.await()).orDie.flatMap(ZIO.fromEither(_)).map(_.answer)

  enum Seen {
    case Durable(n: Int)
    case Live(n: Int)
    case Other
  }

  def spec: Spec[Any, Any] = suite("AgentZ.events")(
    test("streams the run's events of one type, typed, and ends with the run") {
      for {
        s      <- started(Pings.pinging, "ze1")
        pings  <- s.z.events(s.run, Pings.Pinged).runCollect
        answer <- awaited(s.run)
      } yield assertTrue(pings == Chunk(Ping(1)), answer == Some("done"))
    },
    test("streams several types into one ADT through handlers, live events included") {
      val subscribed = new CountDownLatch(1)
      val handlers = EventHandlers
        .on(Pings.Pinged)(p => Seen.Durable(p.n))
        .on(Pings.Progressed)(p => Seen.Live(p.n))
        .otherwise { _ =>
          subscribed.countDown()
          Seen.Other
        }
      for {
        s <- started(
          context => {
            subscribed.await(5, TimeUnit.SECONDS)
            Pings.pinging(context)
          },
          "ze2"
        )
        seen   <- s.z.events(s.run, handlers).filter(_ != Seen.Other).runCollect
        answer <- awaited(s.run)
      } yield assertTrue(seen == Chunk(Seen.Live(2), Seen.Durable(1)), answer == Some("done"))
    },
    test("ends without the run's error for a run that fails") {
      val agent = Fixtures.agentOf(new Fixtures.Scripted(onComplete = () => Fixtures.boom))()
      for {
        run     <- ZIO.fromEither(agent.start(ThreadId("ze3"), "go"))
        pings   <- AgentZ(agent).events(run, Pings.Pinged).runCollect
        outcome <- ZIO.attemptBlocking(run.await()).orDie
      } yield assertTrue(pings.isEmpty, outcome.isLeft)
    },
    test("leaves the run running when the stream stops early") {
      val release = new CountDownLatch(1)
      for {
        s <- started(
          context => {
            Pings.Pinged.emit(context.run, Ping(1))
            release.await(5, TimeUnit.SECONDS)
            ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
          },
          "ze4"
        )
        first  <- s.z.events(s.run, EventHandlers.on(Pings.Pinged)(identity).otherwise(_ => Ping(0))).take(1).runCollect
        _      <- ZIO.succeed(release.countDown())
        answer <- awaited(s.run)
      } yield assertTrue(first == Chunk(Ping(0)), answer == Some("done"))
    },
    test("fails once a stalled consumer falls behind, naming the last seq it received, leaving the run alone") {
      val agent    = Pings.flooding()
      val received = new ConcurrentLinkedQueue[Long]()
      val stalling = new AtomicBoolean(true)
      for {
        run <- ZIO.fromEither(agent.start(ThreadId("ze5"), "go"))
        // stalled on its first event until the run has ended and handed the stream every event: the
        // stream's buffer overflows (or the subscription lags), whatever the timing
        error <- AgentZ(agent)
          .events(run, Pings.seqs)
          .collectSome
          .mapZIO(seq =>
            ZIO.attemptBlocking {
              if (stalling.getAndSet(false)) run.await(): Unit
              received.add(seq): Unit
            }.orDie
          )
          .runDrain
          .flip
        answer <- awaited(run)
      } yield assertTrue(
        !received.isEmpty,
        error.message.endsWith(s"after seq ${received.asScala.last}"),
        answer == Some("done")
      )
    },
    test("fails with what a handler throws, as a defect, leaving the run alone") {
      for {
        s <- started(Pings.pinging, "ze6")
        exit <- s.z
          .events(s.run, EventHandlers.on(Pings.Pinged)(_ => throw new IllegalStateException("handler")))
          .runDrain
          .exit
        answer <- awaited(s.run)
      } yield assertTrue(exit.isFailure, answer == Some("done"))
    }
  )
}
