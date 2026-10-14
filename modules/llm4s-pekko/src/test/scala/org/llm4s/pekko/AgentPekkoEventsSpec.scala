package org.llm4s.pekko

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Keep, Sink }
import org.llm4s.agent.AgentRun
import org.llm4s.agent.graph.{ EventHandlers, ThreadId }
import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome }
import org.llm4s.pekko.Fixtures.Pings
import org.llm4s.pekko.Fixtures.Pings.Ping
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ blocking, Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import scala.util.Try

class AgentPekkoEventsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val system: ActorSystem = ActorSystem("agent-pekko-events-spec")

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds): Unit
    super.afterAll()
  }

  private val Patience = 30.seconds

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  private def started(body: ToolContext => ToolOutcome, thread: String): (AgentPekko, AgentRun) = {
    val agent = Pings.agent(body)
    (AgentPekko(agent), ok(agent.start(ThreadId(thread), "go")))
  }

  private def await[A](f: Future[A]): A = Await.result(f, Patience)

  enum Seen {
    case Durable(n: Int)
    case Live(n: Int)
    case Other
  }

  "AgentPekko.events" should "stream the run's events of one type, typed, and end with the run" in {
    val (pekko, run)  = started(Pings.pinging, "pe1")
    val (done, pings) = pekko.events(run, Pings.Pinged).toMat(Sink.seq)(Keep.both).run()
    await(pings) shouldBe Seq(Ping(1))
    await(done) shouldBe Done
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "stream several types into one ADT through handlers, live events included" in {
    val subscribed = new CountDownLatch(1)
    val (pekko, run) = started(
      context => {
        subscribed.await(5, TimeUnit.SECONDS)
        Pings.pinging(context)
      },
      "pe2"
    )
    val handlers = EventHandlers
      .on(Pings.Pinged)(p => Seen.Durable(p.n))
      .on(Pings.Progressed)(p => Seen.Live(p.n))
      .otherwise { _ =>
        subscribed.countDown()
        Seen.Other
      }
    await(pekko.events(run, handlers).filter(_ != Seen.Other).runWith(Sink.seq)) shouldBe Seq(
      Seen.Live(2),
      Seen.Durable(1)
    )
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "end without the run's error for a run that fails" in {
    val agent = Fixtures.agentOf(new Fixtures.Scripted(onComplete = () => Fixtures.boom))()
    val run   = ok(agent.start(ThreadId("pe3"), "go"))
    await(AgentPekko(agent).events(run, Pings.Pinged).runWith(Sink.seq)) shouldBe empty
    run.await().isLeft shouldBe true
  }

  it should "leave the run running when the stream stops early, and complete its Future once released" in {
    val release = new CountDownLatch(1)
    val (pekko, run) = started(
      context => {
        Pings.Pinged.emit(context.run, Ping(1))
        release.await(5, TimeUnit.SECONDS)
        ToolOutcome.Success(ujson.Str("ok"))
      },
      "pe4"
    )
    val (done, first) = pekko
      .events(run, EventHandlers.on(Pings.Pinged)(identity).otherwise(_ => Ping(0)))
      .take(1)
      .toMat(Sink.seq)(Keep.both)
      .run()
    await(first) shouldBe Seq(Ping(0)) // the replayed RunStarted
    await(done) shouldBe Done
    release.countDown()
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail once a stalled consumer falls behind, naming the last seq it received, leaving the run alone" in {
    val agent              = Pings.flooding()
    val run                = ok(agent.start(ThreadId("pe5"), "go"))
    val received           = new ConcurrentLinkedQueue[Long]()
    val stalling           = new AtomicBoolean(true)
    given ExecutionContext = ExecutionContext.global
    // stalled on its first event until the run has ended and handed the stream every event: the
    // stream's buffer overflows (or the subscription lags), whatever the timing
    val failed = Try(
      await(
        AgentPekko(agent)
          .events(run, Pings.seqs)
          .collect { case Some(seq) => seq }
          .mapAsync(1)(seq =>
            Future(blocking {
              if (stalling.getAndSet(false)) run.await(): Unit
              seq
            })
          )
          .runWith(Sink.foreach(seq => received.add(seq): Unit))
      )
    )
    val message = failed.failed.toOption.collect { case e: LLMException => e.error.message }
    message.getOrElse(fail(s"expected an LLMException, got $failed")) should endWith(
      s"after seq ${received.asScala.last}"
    )
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail the stream with what a handler throws, leaving the run alone" in {
    val (pekko, run) = started(Pings.pinging, "pe6")
    val handlers     = EventHandlers.on(Pings.Pinged)(_ => throw new IllegalStateException("handler"))
    Try(await(pekko.events(run, handlers).runWith(Sink.ignore))).failed.toOption.map(_.getMessage) shouldBe Some(
      "handler"
    )
    ok(run.await()).answer shouldBe Some("done")
  }

  it should "fail at once for a buffer size below 1" in {
    val (pekko, run) = started(Pings.pinging, "pe7")
    Try(await(pekko.events(run, Pings.Pinged, bufferSize = 0).runWith(Sink.ignore))).isFailure shouldBe true
    ok(run.await()).answer shouldBe Some("done")
  }
}
