package org.llm4s.javaapi

import java.util.Optional
import org.llm4s.agent.AgentResult
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, InterruptId, RunEvent, StreamEvent, ThreadId }
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
import scala.jdk.OptionConverters.*

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
    val stream   = jAgentOf(answering("hello"))().stream("j1", "hi", recorder).get()
    val result   = stream.await().get()
    result.answer() shouldBe Optional.of("hello")
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
    val stream   = jAgentOf(client)().stream("j2", "hi", recorder).get()
    val outcome  = stream.await()
    outcome.isFailure shouldBe true
    causeOf(outcome.getError().error) shouldBe a[NetworkError]
    causeOf(recorder.failed.get.error) shouldBe a[NetworkError]
    recorder.completed.get shouldBe null
    recorder.terminals.get shouldBe 1
  }

  it should "carry text deltas from an agent created with streaming, and none without" in {
    val client = new Scripted(
      onChunk => {
        onChunk(org.llm4s.llmconnect.model.StreamedChunk(id = "c", content = Some("he")))
        Right(completion("hello"))
      },
      () => Right(completion("hello"))
    )
    def deltas(agent: JAgent, threadId: String): Vector[String] = {
      val recorder = Recorder()
      agent.stream(threadId, "hi", recorder).get().await().get().answer() shouldBe Optional.of("hello")
      recorder.events.flatMap(AgentEvents.TextDelta.unapply).map(_.text)
    }
    val jClient = new JLlmClient(client)
    deltas(Llm4s.createAgent(jClient, ToolRegistry.empty, true), "j21") shouldBe Vector("he")
    deltas(Llm4s.createAgent(jClient, ToolRegistry.empty, false), "j22") shouldBe empty
  }

  it should "need only onEvent: the terminal callbacks default to doing nothing" in {
    val down     = NetworkError("down", None, "http://x")
    val failing  = jAgentOf(new Scripted(_ => Left(down), () => Left(down)))()
    val listener = new AgentStreamListener { def onEvent(event: StreamEvent): Unit = () }
    failing.stream("j15", "hi", listener).get().await().isFailure shouldBe true
    jAgentOf(answering("ok"))().stream("j16", "hi", listener).get().await().isSuccess shouldBe true
  }

  it should "refuse a start at once, calling no listener method" in {
    val recorder = Recorder()
    val started  = jAgentOf(answering("x"))().stream("j3", "  ", recorder)
    started.isFailure shouldBe true
    started.getError().error shouldBe a[ValidationError]
    recorder.events shouldBe empty
    recorder.terminals.get shouldBe 0
  }

  it should "refuse null arguments" in {
    val agent = jAgentOf(answering("x"))()
    agent.stream(null, "q", Recorder()).isFailure shouldBe true
    agent.stream("j", null, Recorder()).isFailure shouldBe true
    agent.stream("j", "q", null).isFailure shouldBe true
    agent.streamResume(null, java.util.List.of(), Recorder()).isFailure shouldBe true
    agent.streamResume("j", null, Recorder()).isFailure shouldBe true
    agent.streamResume("j", java.util.List.of(), null).isFailure shouldBe true
    agent.streamRecover(null, Recorder()).isFailure shouldBe true
    agent.streamRecover("j", null).isFailure shouldBe true
  }

  it should "fail every stream of an agent that did not build" in {
    val broken = new JAgent(Left(ValidationError("agent", "broken")))
    broken.stream("j", "q", Recorder()).getError().getMessage should include("broken")
  }

  it should "end, rather than hang, when the run ends without a terminal event" in {
    val agent    = jAgentOf(answering("hello"))(_.withRuntime(GraphRuntime(NoTerminal())))
    val recorder = Recorder()
    val outcome  = agent.stream("j4", "hi", recorder).get().await()
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
    val result = agent.stream("j7", "hi", recorder).get().await().get()
    result.answer() shouldBe Optional.of("done")
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
    val threadId = "j8"
    val recorder = Recorder()
    val stream   = agent.stream(threadId, "hi", recorder).get()
    parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    awaitCondition(recorder.events.nonEmpty) shouldBe true
    stream.cancel()
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true // the model call was interrupted
    stream.await().isFailure shouldBe true
    recorder.terminals.get shouldBe 1
    recorder.failed.get should not be null
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
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
    val threadId  = "j5"
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
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
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
    val threadId = "j9"
    // throws on the text delta, once the model call is under way
    val recorder =
      Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) throw new IllegalStateException("listener broke"))
    val outcome = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().getMessage should include("listener broke")
    recorder.failed.get.getMessage should include("listener broke")
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
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
    val threadId = "j14"
    // interrupts itself on the text delta, once the model call is under way
    val recorder = Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) Thread.currentThread().interrupt())
    val outcome  = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().error shouldBe a[CancelledError]
    recorder.failed.get.error shouldBe a[CancelledError]
    unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
  }

  it should "accept a cancel from the listener itself: one onError, await returns, recover works" in {
    val model    = parksOnce()
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(model.client)(_.withRuntime(runtime).withStreaming())
    val threadId = "j19"
    val handle   = new AtomicReference[AgentStream](null)
    val ready    = new CountDownLatch(1)
    val recorder = Recorder { e =>
      if (AgentEvents.TextDelta.unapply(e).isDefined) {
        ready.await(DeadlineSeconds, TimeUnit.SECONDS)
        handle.get.cancel()
      }
    }
    val stream = agent.stream(threadId, "hi", recorder).get()
    handle.set(stream)
    ready.countDown()
    stream.await().isFailure shouldBe true
    recorder.terminals.get shouldBe 1
    recorder.failed.get should not be null
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
  }

  it should "cancel the run when the listener throws a fatal error, failing await" in {
    val model    = parksOnce()
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(model.client)(_.withRuntime(runtime).withStreaming())
    val threadId = "j20"
    val recorder = Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) throw new LinkageError("fatal"))
    val outcome  = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().getMessage should include("failed fatally")
    recorder.terminals.get shouldBe 0
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
  }

  it should "cancel the run at once when the listener interrupts itself with more events already queued" in {
    val model    = parksOnce(deltas = 50)
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(model.client)(_.withRuntime(runtime).withStreaming())
    val threadId = "j23"
    // the first event is held until the model has sent its deltas, then the listener interrupts itself
    val recorder = Recorder { _ =>
      model.parked.await(DeadlineSeconds, TimeUnit.SECONDS)
      Thread.currentThread().interrupt()
    }
    val outcome = agent.stream(threadId, "hi", recorder).get().await()
    outcome.getError().error shouldBe a[CancelledError]
    recorder.events should have size 1
    recorder.terminals.get shouldBe 1
    recorder.failed.get.error shouldBe a[CancelledError]
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
  }

  it should "call onError with the flag clear when the listener interrupted itself and then threw" in {
    val model    = parksOnce()
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(model.client)(_.withRuntime(runtime).withStreaming())
    val threadId = "j27"
    val errors   = new AtomicInteger(0)
    val flagSeen = new AtomicReference[java.lang.Boolean](null)
    val listener = new AgentStreamListener {
      def onEvent(event: StreamEvent): Unit =
        if (AgentEvents.TextDelta.unapply(event).isDefined) {
          Thread.currentThread().interrupt()
          throw new IllegalStateException("listener broke")
        }
      override def onError(error: LlmException): Unit = {
        errors.incrementAndGet()
        flagSeen.set(Thread.currentThread().isInterrupted)
      }
    }
    // with the flag set when it threw, the error is classified as a cancellation
    agent.stream(threadId, "hi", listener).get().await().isFailure shouldBe true
    errors.get shouldBe 1
    flagSeen.get shouldBe java.lang.Boolean.FALSE
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
  }

  it should "cancel the run when the listener throws InterruptedException" in {
    val model    = parksOnce()
    val runtime  = GraphRuntime.inMemory()
    val agent    = jAgentOf(model.client)(_.withRuntime(runtime).withStreaming())
    val threadId = "j24"
    val recorder =
      Recorder(e => if (AgentEvents.TextDelta.unapply(e).isDefined) throw new InterruptedException("listener"))
    agent.stream(threadId, "hi", recorder).get().await().getError().error shouldBe a[CancelledError]
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    eventually(runtime.liveSubscriptions(ThreadId(threadId)) shouldBe 0)
  }

  "AgentStream.cancel" should "wait for the run's end on an interrupted thread, and keep the flag" in {
    val model    = parksOnce()
    val agent    = jAgentOf(model.client)(_.withStreaming())
    val threadId = "j25"
    val stream   = agent.stream(threadId, "hi", Recorder()).get()
    model.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    Thread.currentThread().interrupt()
    stream.cancel()
    Thread.interrupted() shouldBe true // still set, and cleared here
    // the run has ended: its thread is free at once
    agent.streamRecover(threadId, Recorder()).get().await().get().answer() shouldBe Optional.of("recovered")
  }

  it should "keep waiting for the run's end when interrupted while it waits" in {
    val linger   = new CountDownLatch(1)
    val model    = parksOnce(linger = linger)
    val agent    = jAgentOf(model.client)(_.withStreaming())
    val threadId = "j26"
    val stream   = agent.stream(threadId, "hi", Recorder()).get()
    model.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    val flagKept  = new AtomicReference[java.lang.Boolean](null)
    val recovered = new AtomicReference[Option[String]](None)
    val canceller = new Thread(() => {
      stream.cancel()
      flagKept.set(Thread.interrupted())
      recovered.set(agent.streamRecover(threadId, Recorder()).get().await().toOptional.flatMap(_.answer()).toScala)
    })
    canceller.start()
    // the model has been interrupted, and lingers; the canceller waits for the run's end
    model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    awaitCondition(canceller.getState == Thread.State.WAITING || canceller.getState == Thread.State.TIMED_WAITING)
    canceller.interrupt()
    linger.countDown()
    canceller.join(DeadlineSeconds * 1000)
    flagKept.get shouldBe java.lang.Boolean.TRUE
    recovered.get shouldBe Some("recovered")
  }

  it should "survive a terminal callback that throws" in {
    val listener = new AgentStreamListener {
      def onEvent(event: StreamEvent): Unit         = ()
      override def onComplete(result: JAgentResult) = throw new IllegalStateException("late")
    }
    jAgentOf(answering("ok"))().stream("j10", "hi", listener).get().await().get().answer() shouldBe Optional.of("ok")
  }

  it should "refuse an await from the listener instead of waiting on itself" in {
    val inner  = new AtomicReference[LlmResult[JAgentResult]](null)
    val handle = new AtomicReference[AgentStream](null)
    val ready  = new CountDownLatch(1)
    val recorder = Recorder { _ =>
      ready.await(DeadlineSeconds, TimeUnit.SECONDS)
      if (inner.get == null) inner.set(handle.get.await())
    }
    val stream = jAgentOf(answering("ok"))().stream("j11", "hi", recorder).get()
    handle.set(stream)
    ready.countDown()
    stream.await().get().answer() shouldBe Optional.of("ok")
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
    val stream = jAgentOf(client)().stream("j12", "hi", Recorder()).get()
    Thread.currentThread().interrupt()
    val interrupted = stream.await()
    Thread.interrupted() shouldBe true // the flag is still set, and cleared here
    interrupted.getError().error shouldBe a[CancelledError]
    release.countDown()
    stream.await().get().answer() shouldBe Optional.of("late")
  }

  /** An agent whose first turn on `threadId` suspends on an approval: the agent and the approval's id. */
  private def suspended(threadId: String): (JAgent, InterruptId) = {
    val calls = new AtomicInteger(0)
    val client = new Scripted(
      _ => if (calls.getAndIncrement() == 0) Right(calling) else Right(completion("fine")),
      () => if (calls.getAndIncrement() == 0) Right(calling) else Right(completion("fine"))
    )
    val agent = jAgentOf(client)(
      _.withTools(new ToolRegistry(Seq(echoTool))).withMiddleware(ApprovalMiddleware.unlessReadOnly)
    )
    val first = agent.stream(threadId, "go", Recorder()).get().await().get()
    first.status.kind shouldBe AgentStatusKind.SUSPENDED
    (agent, InterruptId(first.status.pending.get(0).id))
  }

  /** A Scala `AgentResult`, whose `approve` / `reject` / `edit` are how the agent itself encodes an answer. */
  private lazy val scalaResult: AgentResult =
    agentOf(answering("x"))().run("x").fold(e => fail(e.message), identity)

  "JAgent.streamResume" should "stream the resumed run to its result" in {
    val threadId    = "j13"
    val (agent, id) = suspended(threadId)
    Answer.approve(id.value).underlying shouldBe Right(scalaResult.approve(id))
    val recorder = Recorder()
    val resumed =
      agent.streamResume(threadId, java.util.List.of(Answer.approve(id.value)), recorder).get().await().get()
    resumed.answer() shouldBe Optional.of("fine")
    recorder.durable.head should matchPattern { case RunEvent.RunResumed(_, _, _) => }
    recorder.durable.last shouldBe RunEvent.RunCompleted
  }

  it should "take a rejection and an edit, encoded as the agent's own answers are" in {
    val threadId    = "j17"
    val (agent, id) = suspended(threadId)
    Answer.reject(id.value, "no").underlying shouldBe Right(scalaResult.reject(id, "no"))
    Answer.edit(id.value, """{"message":"edited"}""").underlying shouldBe
      Right(scalaResult.edit(id, ujson.Obj("message" -> "edited")))
    // the last answer to an id counts
    val answers = java.util.List.of(Answer.approve(id.value), Answer.reject(id.value, "no"))
    agent.streamResume(threadId, answers, Recorder()).get().await().get().answer() shouldBe Optional.of("fine")
  }

  it should "encode a reply as its JSON" in {
    Answer.reply("q1", """{"a":1}""").underlying shouldBe Right(InterruptId("q1") -> ujson.Obj("a" -> 1))
    Answer.reply("q1", "{}").toString shouldBe "Answer(q1)"
    Answer.reply("q1", "{}").interruptId shouldBe "q1"
  }

  it should "refuse a null or malformed answer before starting anything" in {
    val agent    = jAgentOf(answering("x"))()
    val recorder = Recorder()
    def refused(answer: Answer): LlmException =
      agent.streamResume("j18", java.util.Arrays.asList(answer), recorder).getError()
    refused(null).error shouldBe a[ValidationError]
    refused(Answer.approve(null)).error shouldBe a[ValidationError]
    refused(Answer.reject("i", null)).error shouldBe a[ValidationError]
    refused(Answer.edit("i", null)).error shouldBe a[ValidationError]
    refused(Answer.reply("i", "{not json")).getMessage should include("not valid JSON")
    recorder.terminals.get shouldBe 0
  }

  "JAgent.streamRecover" should "stream a recovered run to its result" in {
    val calls = new AtomicInteger(0)
    val down  = NetworkError("down", None, "x")
    val client = new Scripted(
      _ => if (calls.getAndIncrement() == 0) Left(down) else Right(completion("back")),
      () => if (calls.getAndIncrement() == 0) Left(down) else Right(completion("back"))
    )
    val agent    = jAgentOf(client)()
    val threadId = "j6"
    agent.stream(threadId, "hi", Recorder()).get().await().isFailure shouldBe true
    val recorder = Recorder()
    agent.streamRecover(threadId, recorder).get().await().get().answer() shouldBe Optional.of("back")
    recorder.durable.last shouldBe RunEvent.RunCompleted
  }
}
