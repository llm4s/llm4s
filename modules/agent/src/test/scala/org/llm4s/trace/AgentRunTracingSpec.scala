package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{
  Checkpointer,
  Commit,
  EventRecord,
  GraphRuntime,
  InMemoryCheckpointer,
  RunEvent,
  StoredCheckpoint,
  ThreadId
}
import org.llm4s.error.{ NetworkError, ProcessingError, UnknownError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters._

/**
 * `withTracing` traces each run's durable events as `graph.*`/`agent.*` custom events, its model
 * calls' usage as `TokenUsageRecorded`, and ends each run with one `AgentRunEnded`.
 */
class AgentRunTracingSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  /** Records every event; with `failing`, reports each as a failure, which must not fail the run. */
  final private class Recording(failing: Boolean = false) extends Tracing {
    private val events = new ConcurrentLinkedQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = {
      events.add(event)
      if (failing) Left(UnknownError("tracing down", new RuntimeException("x"))) else Right(())
    }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())
    def traceError(error: Throwable, context: String): Result[Unit] = {
      events.add(TraceEvent.ErrorOccurred(error, context))
      Right(())
    }
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())

    def all: Vector[TraceEvent]                = events.asScala.toVector
    def custom: Vector[TraceEvent.CustomEvent] = all.collect { case c: TraceEvent.CustomEvent => c }
    def names: Vector[String]                  = custom.map(_.name)
  }

  /** Answers call N with `responses(N)`. */
  final private class Scripted(responses: Result[Completion]*) extends LLMClient {
    private val sent = new CopyOnWriteArrayList[Conversation]()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val index = sent.size
      sent.add(conversation)
      responses(index)
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def answer(text: String): Completion =
    Completion("answer", 0L, text, "test-model", AssistantMessage(text), usage = usage)

  private val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
  private def toolCallCompletion: Completion =
    Completion("turn-1", 0L, "", "test-model", AssistantMessage(None, Seq(toolCall)), List(toolCall), usage)

  private def usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe().fold(e => fail(e.formatted), identity)

  private def traced(client: LLMClient, tracing: Tracing): Agent =
    Agent
      .builder("assistant", client)
      .withTools(new ToolRegistry(Seq(echoTool)))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)

  private def ok(result: Result[AgentResult]): AgentResult = result.fold(e => fail(e.message), identity)

  "An agent with tracing" should "trace the whole run as graph events, complete when the run returns" in {
    val tracing = new Recording()
    val result  = ok(traced(new Scripted(Right(answer("hello"))), tracing).run("hi"))

    val names = tracing.names
    names.head shouldBe "graph.run_started"
    names.last shouldBe "graph.run_completed"
    names should contain("graph.task_completed")
    names should contain("graph.checkpoint_committed")
    tracing.custom.map(_.data("runId").str).toSet shouldBe Set(result.runId.value)
    tracing.custom.map(_.data("threadId").str).toSet shouldBe Set(result.threadId.value)
    tracing.custom.map(_.data("seq").num) shouldBe sorted
  }

  it should "trace a tool call's task between the model calls around it" in {
    val tracing = new Recording()
    ok(traced(new Scripted(Right(toolCallCompletion), Right(answer("Echoed."))), tracing).run("Echo hello"))

    val completedNodes = tracing.custom
      .filter(_.name == "graph.task_completed")
      .map(_.data("nodeId").str)
    completedNodes shouldBe Vector(
      "input",
      "assistant/model",
      "assistant/call-tool",
      "assistant/collect",
      "assistant/model",
      "assistant/finish"
    )
  }

  it should "trace each run on a thread once: a later run's events are its own" in {
    val tracing = new Recording()
    val agent   = traced(new Scripted(Right(answer("one")), Right(answer("two"))), tracing)
    val thread  = ThreadId("traced-thread")

    val first  = ok(agent.run(thread, "first"))
    val before = tracing.custom.size
    val second = ok(agent.run(thread, "second"))

    val later = tracing.custom.drop(before)
    later.map(_.data("runId").str).toSet shouldBe Set(second.runId.value)
    later.head.name shouldBe "graph.run_started"
    later.last.name shouldBe "graph.run_completed"
    tracing.custom.count(_.data("runId").str == first.runId.value) shouldBe before
  }

  it should "trace a failed run's failure" in {
    val tracing = new Recording()
    val result  = traced(new Scripted(Left(NetworkError("down", None, "mock://llm"))), tracing).run("hi")

    result.isLeft shouldBe true
    tracing.names should contain("graph.task_failed")
    tracing.names.last shouldBe "graph.run_failed"
  }

  it should "complete the run when the tracer itself fails" in {
    val tracing = new Recording(failing = true)
    val result  = ok(traced(new Scripted(Right(answer("fine"))), tracing).run("hi"))

    result.answer shouldBe Some("fine")
    tracing.names.last shouldBe "graph.run_completed"
  }

  it should "trace nothing but graph and agent custom events, usage and the run's end" in {
    val tracing = new Recording()
    ok(traced(new Scripted(Right(answer("hello"))), tracing).run("hi"))

    tracing.custom.size should be > 0
    tracing.all.filter {
      case _: TraceEvent.CustomEvent | _: TraceEvent.TokenUsageRecorded | _: TraceEvent.AgentRunEnded => false
      case _                                                                                          => true
    } shouldBe empty
    tracing.names.foreach(n => (n.startsWith("graph.") || n.startsWith("agent.")) shouldBe true)
  }

  private def endedOf(tracing: Recording): Vector[TraceEvent.AgentRunEnded] =
    tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }

  /** A store that refuses every commit carrying a `RunCompleted`: the run ends without a terminal event. */
  final private class NoTerminal extends Checkpointer {
    private val underlying = new InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (!commit.events.exists(_.event == RunEvent.RunCompleted)) underlying.commit(threadId, commit)
      else Left(ProcessingError("store", "store down"))
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]           = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)
  }

  "A traced run" should "end with one AgentRunEnded carrying the turn's messages" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(toolCallCompletion), Right(answer("done"))), tracing)
    val first   = agent.run(ThreadId("t1"), "first").fold(e => fail(e.message), identity)
    val ended   = endedOf(tracing)
    ended.size shouldBe 1
    ended.head.threadId shouldBe "t1"
    ended.head.runId shouldBe first.runId.value
    ended.head.agent shouldBe "assistant"
    ended.head.status shouldBe "completed"
    ended.head.messages.head shouldBe UserMessage("first")
    ended.head.messages.last shouldBe AssistantMessage("done")
    ended.head.usage.inputTokens shouldBe 40 // two calls of 20
  }

  it should "send only the turn's messages on the second turn" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(answer("answer one")), Right(answer("answer two"))), tracing)
    ok(agent.run(ThreadId("t2"), "one"))
    val second = ok(agent.run(ThreadId("t2"), "two"))
    val ended  = endedOf(tracing)
    ended.size shouldBe 2
    ended.last.runId shouldBe second.runId.value
    ended.last.messages shouldBe Seq(UserMessage("two"), AssistantMessage("answer two"))
  }

  it should "trace usage as TokenUsageRecorded per model call" in {
    val tracing = Recording()
    ok(traced(Scripted(Right(toolCallCompletion), Right(answer("done"))), tracing).run(ThreadId("t3"), "go"))
    val recorded = tracing.all.collect { case e: TraceEvent.TokenUsageRecorded => e }
    recorded.size shouldBe 2
    recorded.foreach { e =>
      e.usage.promptTokens shouldBe 20
      e.usage.completionTokens shouldBe 10
      e.usage.totalTokens shouldBe 30
      e.model shouldBe "test-model"
      e.operation shouldBe "agent_completion"
    }
  }

  it should "trace agent events as agent.* custom events" in {
    val tracing = Recording()
    ok(traced(Scripted(Right(toolCallCompletion), Right(answer("done"))), tracing).run(ThreadId("t4"), "go"))
    tracing.names should contain("agent.model_call_completed")
    tracing.names should contain("agent.tool_executed")
  }

  it should "complete without anyone calling await" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(answer("done"))), tracing)
    agent.start(ThreadId("t5"), "go").fold(e => fail(e.message), identity) // never awaited
    eventually(endedOf(tracing).size shouldBe 1)
  }

  it should "trace a blocked turn with no messages and status blocked:<guardrail>" in {
    val marker  = "SECRET-42"
    val tracing = Recording()
    val guard   = new LengthCheck(1, 3)
    val agent = Agent
      .builder("assistant", Scripted(Right(answer(s"leaking $marker"))))
      .withMiddleware(new GuardrailMiddleware(input = Nil, output = Seq(guard)))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(ThreadId("t6"), "tell me")).status should matchPattern { case AgentStatus.Blocked(_, _) => }
    val ended = endedOf(tracing)
    ended.size shouldBe 1
    ended.head.status shouldBe s"blocked:${guard.name}"
    ended.head.messages shouldBe empty
    tracing.all.foreach(e => (e.toJson.render() should not).include(marker))
  }

  it should "trace a failed run as failed, with ErrorOccurred" in {
    val tracing = Recording()
    traced(Scripted(Left(NetworkError("down", None, "mock://llm"))), tracing)
      .run(ThreadId("t7"), "hi")
      .isLeft shouldBe true
    val ended = endedOf(tracing)
    ended.size shouldBe 1
    ended.head.status shouldBe "failed"
    ended.head.messages shouldBe empty
    tracing.all.collect { case e: TraceEvent.ErrorOccurred => e }.size shouldBe 1
  }

  it should "trace a cancelled run as cancelled" in {
    val tracing = Recording()
    val entered = new CountDownLatch(1)
    val blocking = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        entered.countDown()
        new CountDownLatch(1).await() // until interrupted
        Right(answer("never"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }
    val run = traced(blocking, tracing).start(ThreadId("t8"), "go").fold(e => fail(e.message), identity)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    run.await()
    eventually(endedOf(tracing).map(_.status) shouldBe Vector("cancelled"))
  }

  it should "trace a run that ends without a terminal event as ErrorOccurred, with no AgentRunEnded" in {
    val tracing = Recording()
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("done"))))
      .withRuntime(GraphRuntime(new NoTerminal()))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)
    val run = agent.start(ThreadId("t10"), "go").fold(e => fail(e.message), identity)
    // await detaches tracing once the scope has ended at the run's barrier: the trace is complete
    run.await().isLeft shouldBe true
    tracing.names should contain("graph.run_started")
    tracing.names should not contain "graph.run_completed"
    endedOf(tracing) shouldBe empty
    tracing.all.collect { case e: TraceEvent.ErrorOccurred => e }.size shouldBe 1
  }

  it should "keep two runs on one thread apart" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(answer("first answer")), Right(answer("second answer"))), tracing)
    val one     = agent.run(ThreadId("t9"), "one").fold(e => fail(e.message), identity)
    val two     = agent.run(ThreadId("t9"), "two").fold(e => fail(e.message), identity)
    eventually(endedOf(tracing).size shouldBe 2)
    val ended = endedOf(tracing)
    ended.map(_.runId) shouldBe Vector(one.runId.value, two.runId.value)
    ended.map(_.messages.head) shouldBe Vector(UserMessage("one"), UserMessage("two"))
    ended.map(_.messages.last) shouldBe Vector(AssistantMessage("first answer"), AssistantMessage("second answer"))
  }

  it should "report each run's own usage on its AgentRunEnded, not the thread's" in {
    val tracing = Recording()
    val costed  = (text: String) => answer(text).withEstimatedCost(Some(0.25))
    val agent   = traced(Scripted(Right(costed("first answer")), Right(costed("second answer"))), tracing)
    ok(agent.run(ThreadId("t11"), "one"))
    val two = ok(agent.run(ThreadId("t11"), "two"))
    two.usage.requestCount shouldBe 2 // the thread's, as await reports it
    val ended = endedOf(tracing)
    ended.size shouldBe 2
    ended.foreach { e =>
      e.usage.requestCount shouldBe 1
      e.usage.inputTokens shouldBe 20
      e.usage.outputTokens shouldBe 10
      e.usage.totalCost shouldBe BigDecimal("0.25")
      e.usage.byModel.keySet shouldBe Set("test-model")
    }
  }

  it should "keep two runs on one thread apart while run 1's tracer is still blocked on its terminal event" in {
    val recording = Recording()
    val held      = new CountDownLatch(1) // run 1's tracer is inside its terminal event
    val release   = new CountDownLatch(1)
    val first     = new java.util.concurrent.atomic.AtomicBoolean(true)
    // blocks the first run_completed - run 1's - until released
    val gated = new Tracing {
      def traceEvent(event: TraceEvent): Result[Unit] = {
        event match {
          case c: TraceEvent.CustomEvent if c.name == "graph.run_completed" && first.compareAndSet(true, false) =>
            held.countDown()
            release.await(10, TimeUnit.SECONDS)
          case _ => ()
        }
        recording.traceEvent(event)
      }
      def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())
      def traceError(error: Throwable, context: String): Result[Unit]          = recording.traceError(error, context)
      def traceCompletion(completion: Completion, model: String): Result[Unit] = Right(())
      def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    }
    val agent = traced(Scripted(Right(answer("first answer")), Right(answer("second answer"))), gated)
    val one   = agent.start(ThreadId("t12"), "one").fold(e => fail(e.message), identity)
    held.await(5, TimeUnit.SECONDS) shouldBe true
    eventually(one.status should not be org.llm4s.agent.graph.RunStatus.Running)
    // run 2 starts and ends while run 1's tracer is still blocked
    val two = ok(agent.run(ThreadId("t12"), "two"))
    endedOf(recording).map(_.runId) shouldBe Vector(two.runId.value)
    release.countDown()
    ok(one.await())
    eventually(endedOf(recording).size shouldBe 2)
    val ended = endedOf(recording).sortBy(_.messages.head.content)
    ended.map(_.runId) shouldBe Vector(one.runId.value, two.runId.value)
    ended.map(_.messages) shouldBe Vector(
      Seq(UserMessage("one"), AssistantMessage("first answer")),
      Seq(UserMessage("two"), AssistantMessage("second answer"))
    )
    ended.map(_.status) shouldBe Vector("completed", "completed")
    ended.map(_.usage.requestCount) shouldBe Vector(1L, 1L)
  }
}
