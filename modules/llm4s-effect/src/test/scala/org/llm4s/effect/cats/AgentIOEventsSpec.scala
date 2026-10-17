package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.AgentRun
import org.llm4s.agent.graph.{ EventHandlers, RunEvent, StreamEvent, ThreadId }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.tool.ToolContent
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.agent.testkit.Fixtures.Pings
import org.llm4s.agent.testkit.Fixtures.Pings.Ping
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

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
        ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
      },
      "e4"
    )
    val first = io.events(run, EventHandlers.on(Pings.Pinged)(identity).otherwise(_ => Ping(0))).take(1)
    first.compile.toList.unsafeRunSync() shouldBe List(Ping(0)) // the replayed RunStarted
    release.countDown()
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail once a stalled consumer falls behind, naming the last seq it received, leaving the run alone" in {
    val agent    = Pings.flooding()
    val run      = ok(agent.start(ThreadId("e5"), "go"))
    val received = new ConcurrentLinkedQueue[Long]()
    val stalling = new AtomicBoolean(true)
    // stalled on its first event until the run has ended and handed the stream every event: the
    // stream's buffer overflows (or the subscription lags), whatever the timing
    val outcome = AgentIO[IO](agent)
      .events(run, Pings.seqs)
      .unNone
      .evalMap(seq =>
        IO.blocking {
          if (stalling.getAndSet(false)) run.await(): Unit
          received.add(seq): Unit
        }
      )
      .compile
      .drain
      .attempt
      .unsafeRunSync()
    val message = outcome.left.toOption.collect { case e: LLMException => e.error.message }
    message.getOrElse(fail(s"expected an LLMException, got $outcome")) should endWith(
      s"after seq ${received.asScala.last}"
    )
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
