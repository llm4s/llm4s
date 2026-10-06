package org.llm4s.agent.graph

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.time.Instant
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*

class EventTypeSpec extends AnyFlatSpec with Matchers:

  final case class Ping(n: Int) derives ReadWriter
  private val ping = EventType[Ping]("test.ping", 1)

  private def durable(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Durable(
      EventRecord("t", 1L, "r", None, None, None, Instant.EPOCH, RunEvent.Custom(name, version, payload))
    )
  private def live(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Live("t", "r", "task", "node", name, version, payload)

  "EventType" should "match a durable custom event of its name and version" in {
    ping.unapply(durable("test.ping", 1, ujson.Obj("n" -> 3))) shouldBe Some(Ping(3))
  }

  it should "match a live event of its name and version" in {
    ping.unapply(live("test.ping", 1, ujson.Obj("n" -> 4))) shouldBe Some(Ping(4))
  }

  it should "not match another name or version" in {
    ping.unapply(durable("test.pong", 1, ujson.Obj("n" -> 3))) shouldBe None
    ping.unapply(live("test.ping", 2, ujson.Obj("n" -> 3))) shouldBe None
  }

  it should "return None for a payload that does not decode" in {
    ping.unapply(live("test.ping", 1, ujson.Str("nope"))) shouldBe None
  }

  it should "not match kernel events or markers" in {
    ping.unapply(StreamEvent.LiveGap(2)) shouldBe None
    ping.unapply(
      StreamEvent.Durable(EventRecord("t", 1L, "r", None, None, None, Instant.EPOCH, RunEvent.RunCompleted))
    ) shouldBe None
  }

  it should "refuse an invalid name or version" in {
    an[IllegalArgumentException] should be thrownBy EventType[Ping]("Bad Name", 1)
    an[IllegalArgumentException] should be thrownBy EventType[Ping]("ok.name", 0)
  }

  it should "deliver emit as durable and progress as live through a run" in {
    val runtime = GraphRuntime.inMemory()
    val b       = GraphBuilder("event-type", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      ping.emit(ctx, Ping(1))
      ping.progress(ctx, Ping(2))
      NodeResult.Continue(Command.empty)
    }
    val graph = b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)
    val seen  = new CopyOnWriteArrayList[Ping]()
    val done  = new CountDownLatch(1)
    val listener: StreamEvent => Unit = {
      case ping(p)                                                    => seen.add(p): Unit
      case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => done.countDown()
      case _                                                          => ()
    }
    val run = runtime
      .start(ThreadId("t1"), graph, (), observer = Some(Observer(1024, listener)))
      .fold(e => fail(e.message), identity)
    run.await().isRight shouldBe true
    done.await(5, TimeUnit.SECONDS) shouldBe true
    seen.asScala.toSet shouldBe Set(Ping(1), Ping(2))
    run.observation.foreach(_.cancel())
  }
