package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import upickle.default.{ macroRW, ReadWriter }
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Using
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Run-scoped event delivery: `Agent.stream*` and a late `AgentRun.subscribe`. */
class AgentRunSubscribeSpec extends AnyFlatSpec with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  /** Answers call N with `responses(N)`, after `gate` opens. */
  final private class Scripted(gate: CountDownLatch, responses: Result[Completion]*) extends LLMClient:
    private val sent = new CopyOnWriteArrayList[Conversation]()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      gate.await(5, TimeUnit.SECONDS)
      val index = sent.size
      sent.add(conversation)
      responses(index)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  private def open: CountDownLatch = new CountDownLatch(0)

  final private class Received:
    private val events        = new CopyOnWriteArrayList[StreamEvent]()
    private val terminal      = new AtomicBoolean(false)
    def terminalSeen: Boolean = terminal.get
    val listener: StreamEvent => Unit = e =>
      events.add(e)
      e match
        case StreamEvent.Durable(record) =>
          record.event match
            case RunEvent.RunCompleted | RunEvent.RunFailed(_) | RunEvent.RunSuspended(_) | RunEvent.RunCancelled |
                RunEvent.RunTimedOut =>
              terminal.set(true)
            case _ => ()
        case _ => ()
    def all: Vector[StreamEvent] = events.asScala.toVector

  private def answer(text: String): Completion = Completion("answer", 0L, text, "test-model", AssistantMessage(text))

  private val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
  private def calling: Completion =
    Completion("turn-1", 0L, "", "test-model", AssistantMessage(None, Seq(toolCall)), List(toolCall))

  private case class EchoResult(echo: String)
  private object EchoResult:
    given ReadWriter[EchoResult] = macroRW

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe().fold(e => fail(e.formatted), identity)

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  private def agentOf(client: LLMClient): Agent = ok(Agent.builder("assistant", client).build())

  private def durableRuns(c: Received): Vector[String] = c.all.collect { case StreamEvent.Durable(r) => r.runId }

  private def lastIsTerminal(c: Received, expected: RunEvent): Unit =
    c.all.last match
      case StreamEvent.Durable(r) => r.event shouldBe expected
      case other                  => fail(s"last event was $other")

  "agent.stream" should "deliver the run's events, ending with its terminal event" in {
    val agent = agentOf(Scripted(open, Right(answer("hi"))))
    val c     = Received()
    val run   = ok(agent.stream(ThreadId("s1"), "hello")(c.listener))
    run.await().isRight shouldBe true
    c.terminalSeen shouldBe true // await drained the listener
    durableRuns(c).distinct shouldBe Vector(run.runId.value)
    c.all.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe Some(RunEvent.RunStarted(None, None))
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  it should "deliver nothing of a later run on the same thread" in {
    val agent = agentOf(Scripted(open, Right(answer("one")), Right(answer("two"))))
    val c     = Received()
    val first = ok(agent.stream(ThreadId("s2"), "one")(c.listener).flatMap(_.await()))
    c.terminalSeen shouldBe true // await drained the listener
    val second = ok(agent.run(ThreadId("s2"), "two"))
    second.answer shouldBe Some("two")
    Thread.sleep(200)
    durableRuns(c).toSet shouldBe Set(first.runId.value)
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  "AgentRun.await" should "return only once a stream listener has returned from the run's terminal event" in {
    val agent = agentOf(Scripted(open, Right(answer("hi"))))
    val seen  = new AtomicBoolean(false)
    // slow on the terminal event: without the drain, await returns while the listener is still in it
    val listener: StreamEvent => Unit = {
      case StreamEvent.Durable(r) if RunScope.terminal(r.event) =>
        Thread.sleep(300)
        seen.set(true)
      case _ => ()
    }
    ok(agent.stream(ThreadId("s1d"), "hello")(listener).flatMap(_.await())).answer shouldBe Some("hi")
    seen.get shouldBe true
  }

  it should "return only once a subscribe listener has returned from the run's terminal event" in {
    val gate  = new CountDownLatch(1)
    val agent = agentOf(Scripted(gate, Right(answer("hi"))))
    val run   = ok(agent.start(ThreadId("s1e"), "hello"))
    val seen  = new AtomicBoolean(false)
    run
      .subscribe() {
        case StreamEvent.Durable(r) if RunScope.terminal(r.event) =>
          Thread.sleep(300)
          seen.set(true)
        case _ => ()
      }
      .isRight shouldBe true
    gate.countDown()
    ok(run.await())
    seen.get shouldBe true
  }

  it should "not wait for a subscribe listener whose subscription the caller cancelled before the terminal event" in {
    val logger   = LoggerFactory.getLogger(classOf[AgentRun]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    val took = Using.resource(new AutoCloseable { def close(): Unit = logger.detachAppender(appender): Unit }) { _ =>
      val gate  = new CountDownLatch(1)
      val agent = agentOf(Scripted(gate, Right(answer("hi"))))
      val run   = ok(agent.start(ThreadId("s1f"), "hello"))
      val c     = Received()
      val sub   = ok(run.subscribe()(c.listener))
      sub.cancel()
      gate.countDown()
      val started = System.nanoTime()
      ok(run.await()).answer shouldBe Some("hi")
      c.terminalSeen shouldBe false
      (System.nanoTime() - started).nanos
    }
    took should be < 1.second
    appender.list.asScala.filter(_.getLevel == Level.WARN) shouldBe empty
  }

  "AgentRun.subscribe" should "replay a late subscriber's durable events from the run's start" in {
    val gate  = new CountDownLatch(1)
    val agent = agentOf(Scripted(gate, Right(answer("hi"))))
    val run   = ok(agent.start(ThreadId("s3"), "hello"))
    val c     = Received()
    run.subscribe()(c.listener).isRight shouldBe true
    gate.countDown()
    ok(run.await())
    c.terminalSeen shouldBe true // await drained the listener
    c.all.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe Some(RunEvent.RunStarted(None, None))
    val seqs = c.all.collect { case StreamEvent.Durable(r) => r.seq }
    seqs shouldBe sorted
    seqs.distinct shouldBe seqs
    durableRuns(c).distinct shouldBe Vector(run.runId.value)
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  it should "replay the whole run to a subscriber that arrives after the run ended" in {
    val agent = agentOf(Scripted(open, Right(answer("hi")), Right(answer("later"))))
    val run   = ok(agent.start(ThreadId("s3b"), "hello"))
    ok(run.await())
    ok(agent.run(ThreadId("s3b"), "later")).answer shouldBe Some("later") // a later run on the thread
    val c = Received()
    run.subscribe()(c.listener).isRight shouldBe true
    eventually(c.terminalSeen shouldBe true)
    Thread.sleep(100)
    durableRuns(c).distinct shouldBe Vector(run.runId.value)
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  "agent.streamResume" should "deliver the resumed run's events" in {
    val agent = ok(
      Agent
        .builder("assistant", Scripted(open, Right(calling), Right(answer("fine"))))
        .withTools(new ToolRegistry(Seq(echoTool)))
        .withMiddleware(ApprovalMiddleware.unlessReadOnly)
        .build()
    )
    val first = ok(agent.run(ThreadId("s4"), "go"))
    val id = first.status match
      case AgentStatus.Suspended(approvals, _) => approvals.head._1
      case other                               => fail(s"expected a suspension, got $other")
    val c       = Received()
    val resumed = ok(agent.streamResume(ThreadId("s4"), Map(first.approve(id)))(c.listener))
    ok(resumed.await()).answer shouldBe Some("fine")
    c.terminalSeen shouldBe true // await drained the listener
    durableRuns(c).distinct shouldBe Vector(resumed.runId.value)
    c.all.collectFirst { case StreamEvent.Durable(r) => r.event } should matchPattern {
      case Some(RunEvent.RunResumed(_, _, _)) =>
    }
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  "agent.streamRecover" should "deliver the recovered run's events" in {
    val agent = agentOf(Scripted(open, Left(NetworkError("down", None, "test")), Right(answer("back"))))
    agent.run(ThreadId("s5"), "hello").isLeft shouldBe true
    val c         = Received()
    val recovered = ok(agent.streamRecover(ThreadId("s5"))(c.listener))
    ok(recovered.await()).answer shouldBe Some("back")
    c.terminalSeen shouldBe true // await drained the listener
    durableRuns(c).distinct shouldBe Vector(recovered.runId.value)
    c.all.collectFirst { case StreamEvent.Durable(r) => r.event } should matchPattern {
      case Some(RunEvent.RunRecovered(_, _, _)) =>
    }
    lastIsTerminal(c, RunEvent.RunCompleted)
  }

  "agent.stream" should "refuse a blank query without subscribing" in {
    val agent = agentOf(Scripted(open, Right(answer("hi"))))
    val c     = Received()
    agent.stream(ThreadId("s6"), "  ")(c.listener).isLeft shouldBe true
    Thread.sleep(100)
    c.all shouldBe empty
  }

  it should "refuse a busy thread without calling the listener" in {
    val gate  = new CountDownLatch(1)
    val agent = agentOf(Scripted(gate, Right(answer("hi"))))
    val run   = ok(agent.start(ThreadId("s7"), "hello"))
    val c     = Received()
    agent.stream(ThreadId("s7"), "again")(c.listener) should matchPattern { case Left(_: GraphError.ThreadBusy) => }
    gate.countDown()
    ok(run.await())
    Thread.sleep(200)
    c.all shouldBe empty
  }

  final private class CountingSubscription extends Subscription:
    val cancels                 = new java.util.concurrent.atomic.AtomicInteger(0)
    override def cancel(): Unit = cancels.incrementAndGet(): Unit

  private def record(run: String, seq: Long, event: RunEvent): StreamEvent =
    StreamEvent.Durable(EventRecord("t", seq, run, None, None, None, java.time.Instant.EPOCH, event))

  "RunScope" should "cancel its subscription on the run's terminal event, once, and then pass on nothing" in {
    val c     = Received()
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r1"), c.listener, () => ends.incrementAndGet(): Unit)
    val sub   = CountingSubscription()
    scope.attach(sub)
    scope(record("r0", 1, RunEvent.RunCompleted))
    scope(record("r1", 2, RunEvent.RunStarted(None, None)))
    scope(StreamEvent.Live("t", "r0", "x", "n", "other.run", 1, ujson.Null))
    scope(StreamEvent.Live("t", "r1", "x", "n", "this.run", 1, ujson.Null))
    scope(StreamEvent.LiveGap(2))
    sub.cancels.get shouldBe 0
    scope(record("r1", 3, RunEvent.RunCompleted))
    scope(record("r2", 4, RunEvent.RunStarted(None, None)))
    scope(StreamEvent.LiveGap(1))
    sub.cancels.get shouldBe 1
    ends.get shouldBe 1
    c.all.map {
      case StreamEvent.Durable(r)                => r.seq.toString
      case StreamEvent.Live(_, _, _, _, n, _, _) => n
      case other                                 => other.toString
    } shouldBe Vector("2", "this.run", "LiveGap(2)", "3")
  }

  it should "cancel a subscription attached after the run's terminal event" in {
    val scope = RunScope(RunId("r1"), _ => ())
    scope(record("r1", 1, RunEvent.RunCompleted))
    val sub = CountingSubscription()
    scope.attach(sub)
    sub.cancels.get shouldBe 1
  }

  it should "end once, cancelling its subscription, when the caller cancels before the terminal event" in {
    val c     = Received()
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r1"), c.listener, () => ends.incrementAndGet(): Unit)
    val sub   = CountingSubscription()
    scope.attach(sub)
    scope(record("r1", 1, RunEvent.RunStarted(None, None)))
    scope.cancel()
    scope.isEnded shouldBe true
    scope.awaitEnd(Duration.Zero) shouldBe Right(true)
    sub.cancels.get shouldBe 1
    ends.get shouldBe 1
    scope(record("r1", 2, RunEvent.RunCompleted)) // the race's loser: passed on to nobody, ends nothing
    scope.cancel()
    scope.runEnded(RunId("r1"))
    ends.get shouldBe 1
    c.all shouldBe Vector(record("r1", 1, RunEvent.RunStarted(None, None)))
  }

  it should "end once when the caller cancels after the terminal event, still cancelling the subscription" in {
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r1"), _ => (), () => ends.incrementAndGet(): Unit)
    val sub   = CountingSubscription()
    scope.attach(sub)
    scope(record("r1", 1, RunEvent.RunCompleted))
    scope.cancel()
    ends.get shouldBe 1
    sub.cancels.get shouldBe 1
  }

  it should "not block a listener that cancels while another thread's cancel waits for it" in {
    val ends         = new java.util.concurrent.atomic.AtomicInteger(0)
    val winnerIn     = new CountDownLatch(1) // the other thread's cancel is waiting for the listener
    val listenerDone = new CountDownLatch(1)
    val entered      = new CountDownLatch(1)
    val scopeRef     = new java.util.concurrent.atomic.AtomicReference[RunScope]()
    val scope = RunScope(
      RunId("r1"),
      _ =>
        entered.countDown()
        winnerIn.await(5, TimeUnit.SECONDS)
        scopeRef.get.cancel() // loses the end to the waiting cancel: must return, not wait for it
        listenerDone.countDown()
      ,
      () => ends.incrementAndGet(): Unit
    )
    scopeRef.set(scope)
    // like the kernel's: a cancel off the dispatcher waits for the listener call in progress
    scope.attach(new Subscription {
      def cancel(): Unit =
        winnerIn.countDown()
        listenerDone.await(10, TimeUnit.SECONDS): Unit
    })
    val listening =
      Thread.ofVirtual().start(() => scope(StreamEvent.Live("t", "r1", "x", "n", "this.run", 1, ujson.Null)))
    entered.await(5, TimeUnit.SECONDS) shouldBe true // the listener call is in progress
    val cancelling = Thread.ofVirtual().start(() => scope.cancel())
    cancelling.join(java.time.Duration.ofSeconds(2)) shouldBe true
    listening.join(java.time.Duration.ofSeconds(2)) shouldBe true
    ends.get shouldBe 1
    scope.awaitEnd(Duration.Zero) shouldBe Right(true)
  }

  it should "end exactly once when a caller's cancel races the terminal event" in {
    (1 to 200).foreach { i =>
      val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
      val scope = RunScope(RunId("r1"), _ => (), () => ends.incrementAndGet(): Unit)
      scope.attach(CountingSubscription())
      val go = new CountDownLatch(1)
      val terminal = Thread.ofVirtual().start { () =>
        go.await(); scope(record("r1", i.toLong, RunEvent.RunCompleted))
      }
      val cancel = Thread.ofVirtual().start { () =>
        go.await(); scope.cancel()
      }
      go.countDown()
      terminal.join()
      cancel.join()
      ends.get shouldBe 1
      scope.awaitEnd(Duration.Zero) shouldBe Right(true)
    }
  }

  it should "end once on a Disconnected, after passing it on" in {
    val c     = Received()
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r1"), c.listener, () => ends.incrementAndGet(): Unit)
    val sub   = CountingSubscription()
    scope.attach(sub)
    scope(record("r1", 1, RunEvent.RunStarted(None, None)))
    scope(StreamEvent.Disconnected(1, DisconnectReason.Lagging))
    scope(StreamEvent.Disconnected(1, DisconnectReason.Lagging))
    scope(record("r1", 2, RunEvent.RunCompleted))
    ends.get shouldBe 1
    sub.cancels.get shouldBe 1
    c.all shouldBe Vector(
      record("r1", 1, RunEvent.RunStarted(None, None)),
      StreamEvent.Disconnected(1, DisconnectReason.Lagging)
    )
  }

  it should "end even when the listener throws on the Disconnected" in {
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r1"), _ => throw new IllegalStateException("boom"), () => ends.incrementAndGet(): Unit)
    an[IllegalStateException] should be thrownBy scope(StreamEvent.Disconnected(0, DisconnectReason.Lagging))
    ends.get shouldBe 1
    scope.isEnded shouldBe true
  }

  it should "end at its run's barrier, once, after what was delivered before it, and then pass on nothing" in {
    val c     = Received()
    val order = new CopyOnWriteArrayList[String]()
    val scope = RunScope(RunId("r1"), c.listener, () => order.add("onEnd"): Unit)
    scope.attach(new Subscription { def cancel(): Unit = order.add("cancel"): Unit })
    scope(record("r1", 1, RunEvent.RunStarted(None, None)))
    scope.runEnded(RunId("r1"))
    scope.isEnded shouldBe true
    scope.awaitEnd(Duration.Zero) shouldBe Right(true)
    // ended from the listener's thread, as on a terminal event: onEnd, then the cancel
    order.asScala.toVector shouldBe Vector("onEnd", "cancel")
    scope.runEnded(RunId("r1")) // already ended: ends nothing again
    scope(record("r1", 2, RunEvent.RunCompleted))
    order.asScala.toVector shouldBe Vector("onEnd", "cancel")
    c.all shouldBe Vector(record("r1", 1, RunEvent.RunStarted(None, None)))
  }

  it should "ignore another run's barrier" in {
    val ends  = new java.util.concurrent.atomic.AtomicInteger(0)
    val scope = RunScope(RunId("r2"), _ => (), () => ends.incrementAndGet(): Unit)
    val sub   = CountingSubscription()
    scope.attach(sub)
    scope.runEnded(RunId("r1"))
    scope.isEnded shouldBe false
    ends.get shouldBe 0
    sub.cancels.get shouldBe 0
  }

  it should "let onEnd cancel the scope from the barrier's thread without waiting on itself" in {
    val scopeRef = new java.util.concurrent.atomic.AtomicReference[RunScope]()
    val scope    = RunScope(RunId("r1"), _ => (), () => scopeRef.get.cancel())
    scopeRef.set(scope)
    scope.attach(CountingSubscription())
    val ending = Thread.ofVirtual().start(() => scope.runEnded(RunId("r1")))
    ending.join(java.time.Duration.ofSeconds(2)) shouldBe true
    scope.isEnded shouldBe true
  }
