package org.llm4s.agent.graph

import org.llm4s.agent.events.{ AgentEvents, TextDelta }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class EventHandlersSpec extends AnyFlatSpec with Matchers:

  final case class Ping(n: Int) derives ReadWriter
  final case class Pong(s: String) derives ReadWriter

  private val ping   = EventType[Ping]("test.ping", 1)
  private val pingV2 = EventType[Ping]("test.ping", 2)
  private val pong   = EventType[Pong]("test.pong", 1)

  enum Seen:
    case P(n: Int)
    case Q(s: String)
    case Other(event: StreamEvent)

  private def durable(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Durable(
      EventRecord(
        "t",
        1L,
        "r",
        FencingToken(1L),
        None,
        None,
        None,
        Instant.EPOCH,
        RunEvent.Custom(name, version, payload)
      )
    )
  private def live(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Live("t", "r", "task", "node", name, version, payload)
  private val completed =
    StreamEvent.Durable(
      EventRecord("t", 2L, "r", FencingToken(1L), None, None, None, Instant.EPOCH, RunEvent.RunCompleted)
    )

  "EventHandlers" should "dispatch a durable and a live event to the handler of its type, typed" in {
    val handlers = EventHandlers.on(ping)(p => Seen.P(p.n)).on(pong)(q => Seen.Q(q.s))
    handlers.dispatch(durable("test.ping", 1, ujson.Obj("n" -> 3))) shouldBe Some(Seen.P(3))
    handlers.dispatch(live("test.pong", 1, ujson.Obj("s" -> "x"))) shouldBe Some(Seen.Q("x"))
  }

  it should "skip an event no handler takes: another name, another version, a kernel event, a gap" in {
    val handlers = EventHandlers.on(ping)(p => Seen.P(p.n))
    handlers.dispatch(durable("test.pong", 1, ujson.Obj("s" -> "x"))) shouldBe None
    handlers.dispatch(live("test.ping", 2, ujson.Obj("n" -> 1))) shouldBe None
    handlers.dispatch(completed) shouldBe None
    handlers.dispatch(StreamEvent.LiveGap(4)) shouldBe None
  }

  it should "skip a payload that does not decode, without throwing" in {
    EventHandlers.on(ping)(p => Seen.P(p.n)).dispatch(live("test.ping", 1, ujson.Str("nope"))) shouldBe None
  }

  it should "hand every event no handler took to otherwise" in {
    val handlers = EventHandlers.on(ping)(p => Seen.P(p.n)).otherwise(Seen.Other(_))
    val gap      = StreamEvent.LiveGap(4)
    val bad      = live("test.ping", 1, ujson.Str("nope"))
    handlers.dispatch(gap) shouldBe Some(Seen.Other(gap))
    handlers.dispatch(completed) shouldBe Some(Seen.Other(completed))
    handlers.dispatch(bad) shouldBe Some(Seen.Other(bad))
    handlers.dispatch(live("test.ping", 1, ujson.Obj("n" -> 1))) shouldBe Some(Seen.P(1))
  }

  it should "decode a payload once, with the one matching type" in {
    val reads = new AtomicInteger(0)
    given ReadWriter[Ping] = upickle.default
      .readwriter[ujson.Value]
      .bimap[Ping](
        p => ujson.Obj("n" -> p.n),
        json =>
          reads.incrementAndGet()
          Ping(json("n").num.toInt)
      )
    val counted  = EventType[Ping]("test.counted", 1)
    val others   = (1 to 5).map(i => EventType[Ping](s"test.other$i", 1))
    val handlers = others.foldLeft(EventHandlers.on(counted)(_.n))((h, t) => h.on(t)(_ => -1))
    handlers.dispatch(live("test.counted", 1, ujson.Obj("n" -> 7))) shouldBe Some(7)
    reads.get shouldBe 1
  }

  it should "replace the handler of a name and version registered twice" in {
    val handlers = EventHandlers.on(ping)(_ => "first").on(ping)(_ => "second")
    handlers.dispatch(live("test.ping", 1, ujson.Obj("n" -> 1))) shouldBe Some("second")
  }

  it should "keep versions of a name apart" in {
    val handlers = EventHandlers.on(ping)(p => s"v1 ${p.n}").on(pingV2)(p => s"v2 ${p.n}")
    handlers.dispatch(live("test.ping", 1, ujson.Obj("n" -> 1))) shouldBe Some("v1 1")
    handlers.dispatch(durable("test.ping", 2, ujson.Obj("n" -> 2))) shouldBe Some("v2 2")
  }

  it should "take the built-in agent events" in {
    val handlers = EventHandlers.on(AgentEvents.TextDelta)(_.text)
    val delta    = AgentEvents.TextDelta.encode(TextDelta(1, "hi"))
    handlers.dispatch(live(AgentEvents.TextDelta.name, 1, delta)) shouldBe Some("hi")
  }

  it should "be a listener, calling its handlers and skipping what none takes" in {
    val seen                          = new CopyOnWriteArrayList[Int]()
    val listener: StreamEvent => Unit = EventHandlers.on(ping)(p => seen.add(p.n): Unit)
    listener(live("test.ping", 1, ujson.Obj("n" -> 1)))
    listener(completed)
    listener(durable("test.ping", 1, ujson.Obj("n" -> 2)))
    seen.asScala.toVector shouldBe Vector(1, 2)
  }

  it should "let a handler's throw reach the caller, as a listener's does" in {
    val handlers = EventHandlers.on(ping)(_ => throw new IllegalStateException("boom"))
    an[IllegalStateException] should be thrownBy handlers(live("test.ping", 1, ujson.Obj("n" -> 1)))
  }
