package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.AgentRun
import org.llm4s.agent.graph.{ EventHandlers, RunEvent, StreamEvent, ThreadId }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.agent.testkit.Fixtures.Pings
import org.llm4s.agent.testkit.Fixtures.Pings.Ping
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }

class AgentIOEventsSpec extends AnyFlatSpec with Matchers {

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  private def started(
    body: org.llm4s.agent.graph.tool.ToolContext => ToolOutcome,
    thread: String
  ): (AgentIO[IO], AgentRun) = {
    val agent = Pings.agent(body)
    (AgentIO[IO](agent), ok(agent.start(ThreadId(thread), "go")))
  }

  enum Seen {
    case Durable(n: Int)
    case Live(n: Int)
    case Other
  }

  "AgentIO.events" should "stream the run's events of one type, typed, and end with the run" in {
    val (io, run) = started(Pings.pinging, "e1")
    io.events(run, Pings.Pinged).compile.toList.unsafeRunSync() shouldBe List(Ping(1))
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "stream several types into one ADT through handlers, live events included" in {
    val subscribed = new CountDownLatch(1)
    // the tool waits for the stream to have subscribed, so its live event is not sent before
    val (io, run) = started(
      context => {
        subscribed.await(5, TimeUnit.SECONDS)
        Pings.pinging(context)
      },
      "e2"
    )
    val handlers = EventHandlers
      .on(Pings.Pinged)(p => Seen.Durable(p.n))
      .on(Pings.Progressed)(p => Seen.Live(p.n))
      .otherwise { _ =>
        subscribed.countDown()
        Seen.Other
      }
    val seen = io.events(run, handlers).filter(_ != Seen.Other).compile.toList.unsafeRunSync()
    seen shouldBe List(Seen.Live(2), Seen.Durable(1))
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "end without failing, and without raising the run's error, for a run that fails" in {
    val agent = Fixtures.agentOf(new Fixtures.Scripted(onComplete = () => Fixtures.boom))()
    val run   = ok(agent.start(ThreadId("e3"), "go"))
    val io    = AgentIO[IO](agent)
    io.events(run, Pings.Pinged).compile.toList.unsafeRunSync() shouldBe Nil
    run.await().isLeft shouldBe true
  }

  it should "leave the run running when the stream stops early" in {
    val release = new CountDownLatch(1)
    val (io, run) = started(
      context => {
        Pings.Pinged.emit(context.run, Ping(1))
        release.await(5, TimeUnit.SECONDS)
        ToolOutcome.Success(ujson.Str("ok"))
      },
      "e4"
    )
    val first = io.events(run, EventHandlers.on(Pings.Pinged)(identity).otherwise(_ => Ping(0))).take(1)
    first.compile.toList.unsafeRunSync() shouldBe List(Ping(0)) // the replayed RunStarted
    release.countDown()
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail with an LLMException when its subscription disconnects as lagging, leaving the run alone" in {
    val (io, run) = started(
      context => {
        (1 to 1100).foreach(i => Pings.Pinged.emit(context.run, Ping(i)))
        ToolOutcome.Success(ujson.Str("ok"))
      },
      "e5"
    )
    // one commit of more durable events than the subscription holds disconnects it (Lagging)
    val stalled = io.events(run, Pings.Pinged).evalMap(p => IO.blocking(run.await()).as(p)).compile.drain
    val outcome = stalled.attempt.unsafeRunSync()
    outcome.left.toOption.collect { case e: LLMException => e }.isDefined shouldBe true
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail the stream with what a handler throws, leaving the run alone" in {
    val (io, run) = started(Pings.pinging, "e6")
    val handlers  = EventHandlers.on(Pings.Pinged)(_ => throw new IllegalStateException("handler"))
    val outcome   = io.events(run, handlers).compile.drain.attempt.unsafeRunSync()
    outcome.left.toOption.map(_.getMessage) shouldBe Some("handler")
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "deliver events of kernel types through otherwise" in {
    val (io, run) = started(Pings.pinging, "e7")
    val kernel = EventHandlers
      .on(Pings.Pinged)(_ => Option.empty[RunEvent])
      .otherwise {
        case StreamEvent.Durable(r) => Some(r.event)
        case _                      => None
      }
    val events = io.events(run, kernel).compile.toList.unsafeRunSync().flatten
    events.head shouldBe RunEvent.RunStarted(None, None)
    events.last shouldBe RunEvent.RunCompleted
  }
}
