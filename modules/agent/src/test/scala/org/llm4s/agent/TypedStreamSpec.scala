package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.tool.ToolContent
import org.llm4s.error.SimpleError
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import upickle.default.ReadWriter

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Typed handlers ([[EventHandlers]]) over an agent's runs: a tool's own events, a slow handler, recovery. */
class TypedStreamSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually:
  import AgentFixture.*

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  final case class Ping(n: Int) derives ReadWriter

  private val Pinged     = EventType[Ping]("test.pinged", 1)     // durable, from the tool
  private val Progressed = EventType[Ping]("test.progressed", 1) // live, from the tool

  private def agentWith(tool: (SpecTools.Text, graph.tool.ToolContext) => ToolOutcome, runtime: GraphRuntime) =
    built(
      Agent
        .builder("assistant", ScriptedLLMClient.of(SpecTools.calling(SpecTools.call("c1", "pinger")), answer))
        .withTools(SpecTools.set(SpecTools.tool("pinger")(tool)))
        .withRuntime(runtime)
    )

  private def answer = CompletionFixture.simple("done")

  private val pinging: (SpecTools.Text, graph.tool.ToolContext) => ToolOutcome = (_, ctx) =>
    Pinged.emit(ctx.run, Ping(1))
    Progressed.progress(ctx.run, Ping(2))
    ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))

  final private class Seen:
    private val all           = new CopyOnWriteArrayList[String]()
    def add(s: String): Unit  = all.add(s): Unit
    def items: Vector[String] = all.asScala.toVector

  private def handlersInto(seen: Seen): EventHandlers[Unit] =
    EventHandlers
      .on(Pinged)(p => seen.add(s"pinged ${p.n}"))
      .on(Progressed)(p => seen.add(s"progressed ${p.n}"))
      .on(AgentEvents.ToolExecuted)(t => seen.add(s"executed ${t.tool}"))

  "agent.stream" should "hand a tool's durable and live events, typed, to their handlers" in {
    val seen = Seen()
    val run  = agentWith(pinging, GraphRuntime.inMemory()).stream(ThreadId("ts1"), "go")(handlersInto(seen)).value0
    run.await().value.answer shouldBe Some("done")
    seen.items should contain theSameElementsAs Vector("pinged 1", "progressed 2", "executed pinger")
    seen.items.indexOf("progressed 2") should be < seen.items.indexOf("pinged 1") // live at once, durable on commit
  }

  "AgentRun.subscribe" should "replay a finished run's durable events to typed handlers" in {
    val agent = agentWith(pinging, GraphRuntime.inMemory())
    val run   = agent.start(ThreadId("ts2"), "go").value0
    run.await().value
    val seen = Seen()
    run.subscribe()(handlersInto(seen)).value0
    eventually(seen.items should contain theSameElementsAs Vector("pinged 1", "executed pinger"))
  }

  "a slow handler" should "lose live events as a LiveGap, which otherwise receives, and keep every durable one" in {
    val emitted = new CountDownLatch(1)
    val flood: (SpecTools.Text, graph.tool.ToolContext) => ToolOutcome = (_, ctx) =>
      (1 to 3000).foreach(i => Progressed.progress(ctx.run, Ping(i)))
      emitted.countDown()
      Pinged.emit(ctx.run, Ping(0))
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
    val progressed = new AtomicInteger(0)
    val gaps       = new AtomicInteger(0)
    val pinged     = new AtomicInteger(0)
    val handlers = EventHandlers
      .on(Progressed) { _ =>
        // blocked on the first until the tool has sent them all: the subscription's live queue overflows
        if progressed.incrementAndGet() == 1 then emitted.await(5, TimeUnit.SECONDS): Unit
      }
      .on(Pinged)(_ => pinged.incrementAndGet(): Unit)
      .otherwise {
        case StreamEvent.LiveGap(n) => gaps.addAndGet(n): Unit
        case _                      => ()
      }
    val run = agentWith(flood, GraphRuntime.inMemory()).stream(ThreadId("ts3"), "go")(handlers).value0
    run.await().value.answer shouldBe Some("done")
    gaps.get should be > 0
    progressed.get should be < 3000
    // a gap counts every live event dropped, the agent's own (`ToolCallResult`) among them
    progressed.get + gaps.get should be >= 3000
    pinged.get shouldBe 1
  }

  "a tool that recover runs again" should "commit its durable event once, and send its live event to both runs" in {
    val runtime  = GraphRuntime.inMemory()
    val attempts = new AtomicInteger(0)
    val failingFirst: (SpecTools.Text, graph.tool.ToolContext) => ToolOutcome = (_, ctx) =>
      val attempt = attempts.incrementAndGet()
      Pinged.emit(ctx.run, Ping(attempt))
      Progressed.progress(ctx.run, Ping(attempt))
      if attempt == 1 then ToolOutcome.Fatal(SimpleError("first attempt fails"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
    val agent    = agentWith(failingFirst, runtime)
    val threadId = ThreadId("ts4")
    val first    = Seen()
    agent.stream(threadId, "go")(handlersInto(first)).value0.await().isLeft shouldBe true
    // the failed attempt's durable event was discarded with its task; its live one was already sent
    first.items shouldBe Vector("progressed 1")

    val second    = Seen()
    val recovered = agent.streamRecover(threadId)(handlersInto(second)).value0
    recovered.await().value.answer shouldBe Some("done")
    second.items should contain theSameElementsAs Vector("progressed 2", "pinged 2", "executed pinger")

    val log = Seen()
    val sub = runtime.subscribe(threadId)(EventHandlers.on(Pinged)(p => log.add(s"pinged ${p.n}"))).value0
    try
      eventually(log.items shouldBe Vector("pinged 2"))
      Thread.sleep(100)
      log.items shouldBe Vector("pinged 2") // once in the thread's log
    finally sub.cancel()
  }

  extension [A](result: org.llm4s.types.Result[A]) private def value0: A = result.fold(e => fail(e.message), identity)
