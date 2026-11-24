package org.llm4s.trace

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.{ Agent, AgentResult, Handoff }
import org.llm4s.agent.graph.{
  CheckpointStatus,
  Checkpointer,
  Commit,
  EventRecord,
  GraphRuntime,
  InMemoryCheckpointer,
  Principal,
  RunConfig,
  RunEvent,
  RunOutcome,
  StoredCheckpoint,
  ThreadId
}
import org.llm4s.error.{ NetworkError, ProcessingError, UnknownError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.middleware.{
  AdmissionContext,
  AgentMiddleware,
  BoundaryResult,
  GuardrailMiddleware,
  MiddlewareId,
  ModelContext
}
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters._
import java.time.Instant

/**
 * `withTracing` traces each run's durable events as `graph.*`/`agent.*` custom events, its model
 * calls' usage as `TokenUsageRecorded`, and ends each run with one `AgentRunEnded`.
 */
class AgentRunTracingSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  /** Records every event; with `failing`, reports each as a failure, which must not fail the run. */
  final private class Recording(failing: Boolean = false) extends Tracing {
    private val events = new ConcurrentLinkedQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = {
      events.add(event)
      if (failing) Left(UnknownError("tracing down", new RuntimeException("x"))) else Right(())
    }

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
    Completion("answer", Instant.EPOCH, text, "test-model", AssistantMessage(text), usage = usage)

  private val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
  private def toolCallCompletion: Completion =
    Completion("turn-1", Instant.EPOCH, "", "test-model", AssistantMessage(None, Seq(toolCall)), List(toolCall), usage)

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
      .withTools(ToolRegistry.of(Seq(echoTool)).toOption.get)
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

  it should "name the run's principal as the ended run's user, and no user for a run without one (#2828)" in {
    val tracing = new Recording()
    val agent   = traced(new Scripted(Right(answer("hello")), Right(answer("again"))), tracing)
    ok(agent.run("hi", RunConfig().withPrincipal(Principal("alice"))))
    ok(agent.run("hi"))

    eventually {
      tracing.all.collect { case e: TraceEvent.AgentRunEnded => e.userId } shouldBe Vector(Some("alice"), None)
    }
  }

  it should "record the client's provider on its model calls and the run (#2275)" in {
    val tracing = new Recording()
    val client = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(answer("hello"))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int        = 4096
      override def getReserveCompletion(): Int    = 1024
      override def providerId: Option[ProviderId] = Some(ProviderId("anthropic"))
    }
    ok(traced(client, tracing).run("hi"))

    eventually {
      tracing.all.collect { case e: TraceEvent.AgentRunEnded => e.provider } shouldBe Vector(
        Some(ProviderId("anthropic"))
      )
    }
    tracing.custom
      .filter(_.name == "agent.model_call_completed")
      .map(_.data("payload")("provider").str) shouldBe Vector("anthropic")
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

  /** Runs `underlying` with stdout captured, on whichever thread traces, and keeps what it printed. */
  final private class Captured(underlying: Tracing) extends Tracing {
    private val out                                 = new java.io.ByteArrayOutputStream()
    private def capture[A](body: => A): A           = Console.withOut(out)(body)
    def traceEvent(event: TraceEvent): Result[Unit] = capture(underlying.traceEvent(event))
    def printed: String                             = out.synchronized(out.toString).replaceAll("\u001b\\[[0-9;]*m", "")
  }

  private def secret = "SECRET-4111-1111"

  /** A run whose query, tool call and answer all hold `secret`, traced to `console`. */
  private def secretRun(console: ConsoleTracing): String = {
    val tracing = new Captured(console)
    val call    = ToolCall("call-1", "echo", ujson.Obj("message" -> secret))
    val calling =
      Completion("turn-1", Instant.EPOCH, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage)
    ok(traced(new Scripted(Right(calling), Right(answer(s"Echoed $secret"))), tracing).run(s"Echo $secret"))
    eventually(tracing.printed should include("AGENT RUN ENDED"))
    tracing.printed
  }

  "Console tracing of an agent run" should "print no query, tool input/output or answer by default (#2311)" in {
    val printed = secretRun(new ConsoleTracing())

    printed should include("graph.run_started")
    printed should include("Model: test-model")
    (printed should not).include(secret)
  }

  private def endedOf(tracing: Recording): Vector[TraceEvent.AgentRunEnded] =
    tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }

  /**
   * A store that refuses the first `refusals` commits carrying a `RunCompleted` - every one, by default: the run ends
   * without a terminal event.
   */
  final private class NoTerminal(refusals: Int = Int.MaxValue) extends Checkpointer {
    private val underlying = new InMemoryCheckpointer()
    private val refused    = new java.util.concurrent.atomic.AtomicInteger(0)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (!commit.events.exists(_.event == RunEvent.RunCompleted) || refused.getAndIncrement() >= refusals)
        underlying.commit(threadId, commit)
      else Left(ProcessingError("store", "store down"))
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey)       = underlying.effect(threadId, key)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]           = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      fence: Option[(ThreadId, org.llm4s.agent.graph.FencingToken)]
    ): Result[Unit] =
      underlying.deleteThread(threadId, token, fence)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int) = underlying.children(parent, after, limit)
    def threads(query: org.llm4s.agent.graph.ThreadQuery)               = underlying.threads(query)
    def incompleteThreads(
      tenantId: Option[org.llm4s.agent.graph.TenantId],
      after: Option[org.llm4s.agent.graph.ThreadCursor],
      limit: Int
    ) = underlying.incompleteThreads(tenantId, after, limit)
    def latestOfRuns(threadId: org.llm4s.agent.graph.ThreadId, runIds: Set[org.llm4s.agent.graph.RunId]) =
      underlying.latestOfRuns(threadId, runIds)
    def sweep(
      policy: org.llm4s.agent.graph.SweepPolicy,
      after: Option[org.llm4s.agent.graph.ThreadId],
      guard: org.llm4s.agent.graph.SweepGuard
    ) =
      underlying.sweep(policy, after, guard)
    def checkpoint(threadId: ThreadId, checkpointId: String) = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: org.llm4s.agent.graph.RetentionPolicy) = underlying.prune(threadId, policy)
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
    ended.head.status shouldBe TraceEvent.RunStatus.Completed
    ended.head.messages.head shouldBe UserMessage("first")
    ended.head.messages.last shouldBe AssistantMessage("done")
    ended.head.usage.inputTokens shouldBe 40 // two calls of 20
  }

  it should "name a run that hands off after its root agent, and the agent active at its end beside it (#2829)" in {
    val tracing = Recording()
    val handoff = Completion(
      "turn-1",
      Instant.EPOCH,
      "",
      "test-model",
      AssistantMessage(None, Seq(ToolCall("call-h", "handoff_to_specialist", ujson.Obj("reason" -> "maths")))),
      usage = usage
    )
    val specialist = Agent.builder("specialist", Scripted(Right(answer("2x"))))
    val agent = Agent
      .builder("triage", Scripted(Right(handoff)))
      .withHandoffs(Handoff.to("specialist", specialist, "Maths"))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(ThreadId("t-handoff"), "derive x^2")).activeAgent.value shouldBe "specialist"
    val ended = endedOf(tracing)
    ended.map(_.agent) shouldBe Vector("triage")
    ended.map(_.activeAgent) shouldBe Vector(Some("specialist"))
  }

  it should "end truncated when the answer was cut off at the output limit (RFC 0009, open question 6)" in {
    val tracing = Recording()
    val cut     = answer("half an ans").withFinishReason(org.llm4s.llmconnect.model.FinishReason.Length)
    val agent   = traced(Scripted(Right(cut)), tracing)
    agent.run(ThreadId("t-cut"), "go").fold(e => fail(e.message), identity)
    val ended = endedOf(tracing)
    ended.map(_.status.name) shouldBe Vector("truncated")
    ended.head.messages.last shouldBe AssistantMessage("half an ans")
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

  it should "trace a blocked turn with no messages, status blocked and the guardrail that blocked it" in {
    val marker  = "SECRET-42"
    val tracing = Recording()
    val guard   = LengthCheck(1, 3)
    val agent = Agent
      .builder("assistant", Scripted(Right(answer(s"leaking $marker"))))
      .withMiddleware(new GuardrailMiddleware(input = Nil, output = Seq(guard)))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(ThreadId("t6"), "tell me")).status should matchPattern { case AgentStatus.Blocked(_, _) => }
    val ended = endedOf(tracing)
    ended.size shouldBe 1
    ended.head.status shouldBe TraceEvent.RunStatus.Blocked
    ended.head.blockedBy shouldBe Some(guard.name)
    ended.head.messages shouldBe empty
    tracing.all.foreach(e => (e.toJson.render() should not).include(marker))
  }

  /** An input guardrail whose reason quotes the text it refused, as a PII or prompt-injection match does. */
  private def quoting: InputGuardrail = new InputGuardrail {
    val name: String                            = "QuotesInput"
    def validate(value: String): Result[String] = Left(ValidationError.invalid("input", s"refused '$value'"))
  }

  /** Replaces `secret` in the turn's query as it is admitted, as a PII-masking middleware does. */
  final private class Redacting(secret: String) extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("redacting")
    override def admit(query: String, context: AdmissionContext): Result[String] =
      Right(query.replace(secret, "[REDACTED]"))
  }

  /** Replaces `secret` in `beforeAgent`: after the turn's first checkpoint is stored. */
  final private class RedactingLate(secret: String) extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("redacting-late")
    override def beforeAgent(text: String, context: ModelContext): Result[BoundaryResult] =
      Right(BoundaryResult(text.replace(secret, "[REDACTED]")))
  }

  private def stored[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  "A guardrail's reason" should "reach the caller but neither the durable event log nor the trace (#2793)" in {
    val marker  = "SECRET-42"
    val store   = new InMemoryCheckpointer()
    val tracing = Recording()
    val thread  = ThreadId("t-reason")
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("never sent"))))
      .withMiddleware(new GuardrailMiddleware(input = Seq(quoting), output = Nil))
      .withRuntime(new GraphRuntime(store))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)

    ok(agent.run(thread, s"my card is $marker")).status match {
      case AgentStatus.Blocked(guardrail, Some(reason)) =>
        guardrail shouldBe "QuotesInput"
        reason should include(marker)
      case other => fail(s"expected Blocked, got $other")
    }

    val events = stored(store.eventsAfter(thread, 0L, 100))
    events.map(_.event).collect { case RunEvent.RunFailed(details) => details.message } shouldBe
      Vector("Guardrail 'QuotesInput' blocked")
    events.map(_.event).collect { case RunEvent.TaskFailed(message) => message } shouldBe
      Vector("Guardrail 'QuotesInput' blocked")
    events.foreach(e => (e.toString should not).include(marker))

    eventually(endedOf(tracing).size shouldBe 1)
    tracing.all.foreach(e => (e.toJson.render() should not).include(marker))
  }

  it should "be in no checkpoint: the closing one names the guardrail only (#2853)" in {
    val marker = "SECRET-42"
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("t-outcome")
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("never sent"))))
      .withMiddleware(new GuardrailMiddleware(input = Seq(quoting), output = Nil))
      .withRuntime(new GraphRuntime(store))
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(thread, s"my card is $marker"))

    val history = stored(store.history(thread, None, 100))
    history.flatMap(_.outcome) shouldBe Vector(RunOutcome.Blocked("QuotesInput"))
    // nothing of the blocked turn is readable: neither the reason nor the query it quotes
    history.foreach(c => (c.toString should not).include(marker))
  }

  "A query an admit stage redacts" should "be redacted in the thread, the event log and the trace (#2793)" in {
    val marker  = "SECRET-42"
    val store   = new InMemoryCheckpointer()
    val tracing = Recording()
    val thread  = ThreadId("t-redacted")
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("done"))))
      .withMiddleware(new Redacting(marker))
      .withRuntime(new GraphRuntime(store))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)

    val result = ok(agent.run(thread, s"my card is $marker"))
    val text   = result.messages.map(_.content).mkString("\n")
    text should include("[REDACTED]")
    (text should not).include(marker)

    stored(store.latest(thread)).foreach(latest => (latest.checkpoint.toString should not).include(marker))
    stored(store.eventsAfter(thread, 0L, 100)).foreach(e => (e.toString should not).include(marker))
    eventually(endedOf(tracing).size shouldBe 1)
    tracing.all.foreach(e => (e.toJson.render() should not).include(marker))
  }

  it should "be in no checkpoint as submitted: admission runs before the turn is stored (#2854)" in {
    val marker = "SECRET-42"
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("t-first")
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("done"))))
      .withMiddleware(new Redacting(marker))
      .withRuntime(new GraphRuntime(store))
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(thread, s"my card is $marker"))

    val history = stored(store.history(thread, None, 100))
    history.filter(_.toString.contains(marker)) shouldBe empty
    history.map(_.status) should contain(CheckpointStatus.Running)
    history.exists(_.toString.contains("[REDACTED]")) shouldBe true
  }

  "A query a beforeAgent hook redacts" should "still be in the run's first checkpoint: redact in admit (#2854)" in {
    val marker = "SECRET-42"
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("t-late")
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("done"))))
      .withMiddleware(new RedactingLate(marker))
      .withRuntime(new GraphRuntime(store))
      .build()
      .fold(e => fail(e.message), identity)
    ok(agent.run(thread, s"my card is $marker")).messages.map(_.content).mkString should include("[REDACTED]")

    // beforeAgent keeps its semantics: it runs inside the checkpointed input step, on a query already stored
    val holding = stored(store.history(thread, None, 100)).filter(_.toString.contains(marker))
    holding.map(_.status) shouldBe Vector(CheckpointStatus.Running)
  }

  it should "trace a failed run as failed, with ErrorOccurred" in {
    val tracing = Recording()
    traced(Scripted(Left(NetworkError("down", None, "mock://llm"))), tracing)
      .run(ThreadId("t7"), "hi")
      .isLeft shouldBe true
    val ended = endedOf(tracing)
    ended.size shouldBe 1
    ended.head.status shouldBe TraceEvent.RunStatus.Failed
    ended.head.messages shouldBe empty
    val errors = tracing.all.collect { case e: TraceEvent.ErrorOccurred => e }
    errors.size shouldBe 1
    // the run's error as it is - an LLMError, with its type - not an exception wrapped around its message (#2800)
    errors.head.errorType should not be "RuntimeException"
    errors.head.errorType should not be empty
    errors.head.message should include("down")
    errors.head.cause shouldBe None
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
    eventually(endedOf(tracing).map(_.status.name) shouldBe Vector("cancelled"))
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

  // A known limit, pinned (#2829): a run's span is sent when it ends, so a run that never ends sends none.
  it should "leave a run that died under a span no AgentRunEnded names, and place its recovery under that span" in {
    val tracing = Recording()
    val agent = Agent
      .builder("assistant", Scripted(Right(answer("done")), Right(answer("done"))))
      .withRuntime(GraphRuntime(new NoTerminal(refusals = 1)))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)
    agent.start(ThreadId("t11"), "go").fold(e => fail(e.message), identity).await().isLeft shouldBe true
    endedOf(tracing) shouldBe empty
    // every event of the run that died is under one span: its run's, which the backend is never sent
    val unsent = tracing.custom.flatMap(_.span).flatMap(_.parent).distinct
    unsent should have size 1

    ok(agent.recover(ThreadId("t11")))
    val recovered = endedOf(tracing)
    recovered should have size 1
    val span = recovered.head.span.getOrElse(fail("the recovered run has no span"))
    // the recovery is a run of its own, in the same trace, continuing the span of the run that died
    span.context.traceId shouldBe unsent.head.traceId
    span.context.spanId should not be unsent.head.spanId
    span.parent shouldBe Some(unsent.head)
    endedOf(tracing).map(_.span.map(_.context.spanId)) should not contain Some(unsent.head.spanId)
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
    ended.map(_.status.name) shouldBe Vector("completed", "completed")
    ended.map(_.usage.requestCount) shouldBe Vector(1L, 1L)
  }
}
