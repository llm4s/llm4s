package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, RunEvent, StreamEvent, ThreadId }
import org.llm4s.error.{ CancelledError, LLMError, NetworkError, ValidationError }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, ToolCall }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.jdk.CollectionConverters.*

import StreamFixtures.*

final private case class EchoResult(echo: String)
private object EchoResult {
  implicit val rw: ReadWriter[EchoResult] = macroRW
}

class JAgentStreamSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private def causeOf(e: LLMError): LLMError = e match {
    case GraphError.NodeFailed(_, _, cause) => cause
    case other                              => other
  }

  private def calling: Completion = {
    val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
    Completion("turn-1", 0L, "", "test-model", AssistantMessage(None, Seq(toolCall)), List(toolCall))
  }

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe().fold(e => fail(e.formatted), identity)

  "JAgent.stream" should "deliver the run's events, then its result, on one thread that is not the caller's" in {
    val recorder = Recorder()
    val stream   = jAgentOf(answering("hello"))().stream(ThreadId("j1"), "hi", recorder).get()
    val result   = stream.await().get()
    result.answer shouldBe Some("hello")
    recorder.completed.get shouldBe result
    recorder.failed.get shouldBe null
    recorder.terminals.get shouldBe 1
    recorder.durable.head shouldBe RunEvent.RunStarted(None, None)
    recorder.durable.last shouldBe RunEvent.RunCompleted
    recorder.threads.asScala.toSet should have size 1
    recorder.threads.get(0) should not be Thread.currentThread()
  }

  it should "deliver the run's error to onError and to await" in {
    val down     = NetworkError("down", None, "http://x")
    val client   = new Scripted(_ => Left(down), () => Left(down))
    val recorder = Recorder()
    val stream   = jAgentOf(client)().stream(ThreadId("j2"), "hi", recorder).get()
    val outcome  = stream.await()
    outcome.isFailure shouldBe true
    causeOf(outcome.getError().error) shouldBe a[NetworkError]
    causeOf(recorder.failed.get.error) shouldBe a[NetworkError]
    recorder.completed.get shouldBe null
    recorder.terminals.get shouldBe 1
  }

  it should "need only onEvent: the terminal callbacks default to doing nothing" in {
    val down     = NetworkError("down", None, "http://x")
    val failing  = jAgentOf(new Scripted(_ => Left(down), () => Left(down)))()
    val listener = new AgentStreamListener { def onEvent(event: StreamEvent): Unit = () }
    failing.stream(ThreadId("j15"), "hi", listener).get().await().isFailure shouldBe true
    jAgentOf(answering("ok"))().stream(ThreadId("j16"), "hi", listener).get().await().isSuccess shouldBe true
  }

  it should "refuse a start at once, calling no listener method" in {
    val recorder = Recorder()
    val started  = jAgentOf(answering("x"))().stream(ThreadId("j3"), "  ", recorder)
    started.isFailure shouldBe true
    started.getError().error shouldBe a[ValidationError]
    recorder.events shouldBe empty
    recorder.terminals.get shouldBe 0
  }

  it should "refuse null arguments" in {
    val agent = jAgentOf(answering("x"))()
    agent.stream(null.asInstanceOf[ThreadId], "q", Recorder()).isFailure shouldBe true
    agent.stream(ThreadId("j"), null, Recorder()).isFailure shouldBe true
    agent.stream(ThreadId("j"), "q", null).isFailure shouldBe true
    agent.streamResume(null.asInstanceOf[ThreadId], java.util.List.of(), Recorder()).isFailure shouldBe true
    agent.streamResume(ThreadId("j"), null, Recorder()).isFailure shouldBe true
    agent.streamResume(ThreadId("j"), java.util.List.of(), null).isFailure shouldBe true
    agent.streamRecover(null.asInstanceOf[ThreadId], Recorder()).isFailure shouldBe true
    agent.streamRecover(ThreadId("j"), null).isFailure shouldBe true
  }

  it should "fail every stream of an agent that did not build" in {
    val broken = new JAgent(Left(ValidationError("agent", "broken")))
    broken.stream(ThreadId("j"), "q", Recorder()).getError().getMessage should include("broken")
  }

  it should "end, rather than hang, when the run ends without a terminal event" in {
    val agent    = jAgentOf(answering("hello"))(_.withRuntime(GraphRuntime(NoTerminal())))
    val recorder = Recorder()
    val outcome  = agent.stream(ThreadId("j4"), "hi", recorder).get().await()
    outcome.getError().getMessage should include("store down")
    recorder.failed.get.getMessage should include("store down")
    recorder.terminals.get shouldBe 1
  }

  it should "give a slow listener a LiveGap for the deltas it missed, not cancel the run" in {
    // far more live text than the stream's buffer and the subscription's queue hold together
    val client = new Scripted(
      onChunk => {
        (0 until 3000).foreach(i => onChunk(chunk(i)))
        Right(completion("done"))
      },
      () => Right(completion("done"))
    )
    val store = SignalsCompletion()
    val agent = jAgentOf(client)(_.withRuntime(GraphRuntime(store)).withStreaming())
    val first = new AtomicInteger(0)
    // the listener takes nothing more until the run has completed
    val recorder =
      Recorder(_ => if (first.getAndIncrement() == 0) store.completed.await(DeadlineSeconds, TimeUnit.SECONDS): Unit)
    val result = agent.stream(ThreadId("j7"), "hi", recorder).get().await().get()
    result.answer shouldBe Some("done")
    recorder.gaps should be > 0
    recorder.durable.last shouldBe RunEvent.RunCompleted
    recorder.completed.get shouldBe result
  }

  it should "cancel the run when cancelled while the listener waits for the next event" in {
    val calls    = new AtomicInteger(0)
    val parked   = new CountDownLatch(1)
    val unparked = new CountDownLatch(1)
    // the first call sends one delta, then parks mid-call until interrupted; later calls answer
    val client = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          onChunk(chunk(0))
          parked.countDown()
          if (parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("j8")
    val recorder = Recorder()
    val stream   = agent.stream(threadId, "hi", recorder).get()
    parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    awaitCondition(recorder.events.nonEmpty) shouldBe true
    stream.cancel()
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true // the model call was interrupted
    stream.await().isFailure shouldBe true
    recorder.terminals.get shouldBe 1
    recorder.failed.get should not be null
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer shouldBe Some("recovered")
  }

  it should "deliver no event once cancelled, even to a listener busy with an earlier one" in {
    val calls    = new AtomicInteger(0)
    val sent     = new CountDownLatch(1)
    val unparked = new CountDownLatch(1)
    // the first call floods the stream with more live text than the buffer holds, then parks until
    // interrupted; the listener holds on to the first event until the stream is cancelled
    val client = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          (0 until 3000).foreach(i => onChunk(chunk(i)))
          sent.countDown()
          if (parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime   = GraphRuntime.inMemory()
    val agent     = jAgentOf(client)(_.withRuntime(runtime).withStreaming())
    val threadId  = ThreadId("j5")
    val holding   = new CountDownLatch(1)
    val cancelled = new CountDownLatch(1)
    val recorder = Recorder { _ =>
      if (holding.getCount > 0) {
        holding.countDown()
        cancelled.await(DeadlineSeconds, TimeUnit.SECONDS): Unit
      }
    }
    val stream = agent.stream(threadId, "hi", recorder).get()
    holding.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    sent.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    stream.cancel()
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    cancelled.countDown()
    stream.await().isFailure shouldBe true
    recorder.events should have size 1
    recorder.terminals.get shouldBe 1
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer shouldBe Some("recovered")
  }

  it should "cancel the run when the listener throws, reporting what it threw" in {
    val calls    = new AtomicInteger(0)
    val unparked = new CountDownLatch(1)
    val client = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          onChunk(chunk(0))
          if (parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("j9")
    // throws on the text delta, once the model call is under way
    val recorder =
      Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) throw new IllegalStateException("listener broke"))
    val outcome = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().getMessage should include("listener broke")
    recorder.failed.get.getMessage should include("listener broke")
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer shouldBe Some("recovered")
  }

  it should "cancel the run when the listener interrupts its own thread" in {
    val calls    = new AtomicInteger(0)
    val unparked = new CountDownLatch(1)
    val client = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          onChunk(chunk(0))
          if (parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("j14")
    // interrupts itself on the text delta, once the model call is under way
    val recorder = Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) Thread.currentThread().interrupt())
    val outcome  = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().error shouldBe a[CancelledError]
    recorder.failed.get.error shouldBe a[CancelledError]
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer shouldBe Some("recovered")
  }

  it should "survive a terminal callback that throws" in {
    val listener = new AgentStreamListener {
      def onEvent(event: StreamEvent): Unit                        = ()
      override def onComplete(result: org.llm4s.agent.AgentResult) = throw new IllegalStateException("late")
    }
    jAgentOf(answering("ok"))().stream(ThreadId("j10"), "hi", listener).get().await().get().answer shouldBe Some("ok")
  }

  it should "refuse an await from the listener instead of waiting on itself" in {
    val inner  = new AtomicReference[LlmResult[org.llm4s.agent.AgentResult]](null)
    val handle = new AtomicReference[AgentStream](null)
    val ready  = new CountDownLatch(1)
    val recorder = Recorder { _ =>
      ready.await(DeadlineSeconds, TimeUnit.SECONDS)
      if (inner.get == null) inner.set(handle.get.await())
    }
    val stream = jAgentOf(answering("ok"))().stream(ThreadId("j11"), "hi", recorder).get()
    handle.set(stream)
    ready.countDown()
    stream.await().get().answer shouldBe Some("ok")
    inner.get.getError().error shouldBe a[ValidationError]
  }

  it should "return CancelledError to an interrupted await, leaving the run going" in {
    val release = new CountDownLatch(1)
    val client = new Scripted(
      _ => Right(completion("x")),
      () => {
        release.await(DeadlineSeconds, TimeUnit.SECONDS)
        Right(completion("late"))
      }
    )
    val stream = jAgentOf(client)().stream(ThreadId("j12"), "hi", Recorder()).get()
    Thread.currentThread().interrupt()
    val interrupted = stream.await()
    Thread.interrupted() shouldBe true // the flag is still set, and cleared here
    interrupted.getError().error shouldBe a[CancelledError]
    release.countDown()
    stream.await().get().answer shouldBe Some("late")
  }

  "JAgent.streamResume" should "stream the resumed run to its result" in {
    val calls = new AtomicInteger(0)
    val client = new Scripted(
      _ => if (calls.getAndIncrement() == 0) Right(calling) else Right(completion("fine")),
      () => if (calls.getAndIncrement() == 0) Right(calling) else Right(completion("fine"))
    )
    val agent = jAgentOf(client)(
      _.withTools(new ToolRegistry(Seq(echoTool))).withMiddleware(ApprovalMiddleware.unlessReadOnly)
    )
    val threadId = ThreadId("j13")
    val first    = agent.stream(threadId, "go", Recorder()).get().await().get()
    val id = first.status match {
      case AgentStatus.Suspended(approvals, _) => approvals.head._1
      case other                               => fail(s"expected a suspension, got $other")
    }
    val recorder = Recorder()
    val resumed  = agent.streamResume(threadId, java.util.List.of(first.approve(id)), recorder).get().await().get()
    resumed.answer shouldBe Some("fine")
    recorder.durable.head should matchPattern { case RunEvent.RunResumed(_, _, _) => }
    recorder.durable.last shouldBe RunEvent.RunCompleted
  }

  "JAgent.streamRecover" should "stream a recovered run to its result" in {
    val calls = new AtomicInteger(0)
    val down  = NetworkError("down", None, "x")
    val client = new Scripted(
      _ => if (calls.getAndIncrement() == 0) Left(down) else Right(completion("back")),
      () => if (calls.getAndIncrement() == 0) Left(down) else Right(completion("back"))
    )
    val agent    = jAgentOf(client)()
    val threadId = ThreadId("j6")
    agent.stream(threadId, "hi", Recorder()).get().await().isFailure shouldBe true
    val recorder = Recorder()
    agent.streamRecover(threadId, recorder).get().await().get().answer shouldBe Some("back")
    recorder.durable.last shouldBe RunEvent.RunCompleted
  }
}
