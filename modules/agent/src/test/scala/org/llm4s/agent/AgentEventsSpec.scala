package org.llm4s.agent

import org.llm4s.agent.events.{ AgentEvents, GuardrailBlock, GuardrailPhase, ToolExecutionOutcome }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, ApprovalMiddleware, GuardrailMiddleware, MiddlewareId }
import org.llm4s.agent.graph.middleware.ToolCallRequest
import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch }
import scala.jdk.CollectionConverters.*

/** The tool and guardrail events of an agent run, and what the durable ones may carry. */
class AgentEventsSpec extends AnyFlatSpec with Matchers:

  /** Answers call N with `responses(N)`. */
  final private class Scripted(responses: Result[Completion]*) extends LLMClient:
    private val sent = new CopyOnWriteArrayList[Conversation]()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
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

  private def usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))

  private def answer(text: String): Completion =
    Completion("answer", 0L, text, "test-model", AssistantMessage(text), usage = usage)

  private def calling(call: ToolCall): Completion =
    Completion("turn-1", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage)

  private val toolCall                       = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
  private def toolCallCompletion: Completion = calling(toolCall)

  private case class EchoResult(echo: String)
  private object EchoResult:
    given ReadWriter[EchoResult] = macroRW

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe().fold(e => fail(e.formatted), identity)

  private def agentWith(client: LLMClient, tools: org.llm4s.toolapi.ToolFunction[?, ?]*): AgentBuilder =
    Agent.builder("assistant", client).withTools(new ToolRegistry(tools))

  final private class Gathered(runs: Int):
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val ended  = new CountDownLatch(runs)
    val listener: StreamEvent => Unit = e =>
      events.add(e)
      e match
        case StreamEvent.Durable(record) =>
          record.event match
            case RunEvent.RunCompleted | RunEvent.RunFailed(_) | RunEvent.RunSuspended(_) | RunEvent.RunCancelled |
                RunEvent.RunTimedOut =>
              ended.countDown()
            case _ => ()
        case _ => ()
    def all: Vector[StreamEvent]             = events.asScala.toVector
    def durable: Vector[StreamEvent.Durable] = all.collect { case d: StreamEvent.Durable => d }
    def of(runId: RunId): Vector[StreamEvent] = all.filter {
      case StreamEvent.Durable(r)                  => r.runId == runId.value
      case StreamEvent.Live(_, run, _, _, _, _, _) => run == runId.value
      case _                                       => false
    }

  /**
   * Runs `body` against `builder`'s agent on a new thread, `body` streaming each of `runs` runs to the
   * listener it is given, and collects their events until each has sent its terminal durable event.
   */
  private def collecting[A](builder: AgentBuilder, runs: Int)(
    body: (Agent, ThreadId, StreamEvent => Unit) => A
  ): (A, Gathered) =
    val agent  = builder.build().fold(e => fail(e.message), identity)
    val c      = Gathered(runs)
    val result = body(agent, ThreadId(java.util.UUID.randomUUID().toString), c.listener)
    c.ended.getCount shouldBe 0 // await drained the listener
    (result, c)

  private def collect(builder: AgentBuilder, query: String): (Result[AgentResult], Gathered) =
    collecting(builder, 1)((agent, threadId, listener) => agent.stream(threadId, query)(listener).flatMap(_.await()))

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  "A tool call" should "send ToolCallStarted and ToolCallResult live, and ToolExecuted durable" in {
    val (result, c) = collect(agentWith(Scripted(Right(toolCallCompletion), Right(answer("done"))), echoTool), "go")
    result.map(_.answer) shouldBe Right(Some("done"))
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s.tool -> s.arguments } shouldBe
      Vector("echo" -> ujson.Obj("message" -> "hello"))
    c.all.collect { case AgentEvents.ToolCallResult(r) => r.toolCallId -> r.isError } shouldBe Vector("call-1" -> false)
    val executed = c.all.collect { case AgentEvents.ToolExecuted(t) => t }
    executed.map(t => (t.agent, t.tool, t.toolCallId, t.outcome)) shouldBe
      Vector(("assistant", "echo", "call-1", ToolExecutionOutcome.Succeeded))
    executed.head.duration should be >= scala.concurrent.duration.Duration.Zero
    c.durable.collect { case AgentEvents.ToolExecuted(t) => t }.size shouldBe 1 // it is durable
    // the live events are never stored
    c.durable.collect { case AgentEvents.ToolCallStarted(s) => s } shouldBe empty
    c.durable.collect { case AgentEvents.ToolCallResult(r) => r } shouldBe empty
  }

  it should "send ToolCallStarted before ToolCallResult, and ToolCallResult with the recorded content" in {
    val (_, c) = collect(agentWith(Scripted(Right(toolCallCompletion), Right(answer("done"))), echoTool), "go")
    val kinds = c.all.collect {
      case AgentEvents.ToolCallStarted(_) => "started"
      case AgentEvents.ToolCallResult(_)  => "result"
    }
    kinds shouldBe Vector("started", "result")
    c.all.collect { case AgentEvents.ToolCallResult(r) => ujson.read(r.content) } shouldBe
      Vector(ujson.Obj("echo" -> "hello"))
  }

  it should "report an unknown tool as Errored with no ToolCallStarted" in {
    val unknown = ToolCall("call-9", "nope", ujson.Obj())
    val (result, c) =
      collect(agentWith(Scripted(Right(calling(unknown)), Right(answer("sorry"))), echoTool), "go")
    result.map(_.answer) shouldBe Right(Some("sorry"))
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s } shouldBe empty
    c.all.collect { case AgentEvents.ToolCallResult(r) => r.toolCallId -> r.isError } shouldBe Vector("call-9" -> true)
    val executed = c.durable.collect { case AgentEvents.ToolExecuted(t) => t }
    executed.map(t => (t.tool, t.toolCallId, t.outcome, t.duration)) shouldBe
      Vector(("<unknown>", "call-9", ToolExecutionOutcome.Errored, scala.concurrent.duration.Duration.Zero))
  }

  it should "keep a model-invented tool name out of the durable log" in {
    val marker   = "INVENTED-7731"
    val unknown  = ToolCall("call-9", s"tool_$marker", ujson.Obj())
    val (_, c)   = collect(agentWith(Scripted(Right(calling(unknown)), Right(answer("sorry"))), echoTool), "go")
    val executed = c.durable.collect { case e @ AgentEvents.ToolExecuted(_) => e }
    executed should have size 1
    executed.foreach { case StreamEvent.Durable(r) => (upickle.default.write(r.event) should not).include(marker) }
    // the live result may name the call: it is never stored
    c.all.collect { case AgentEvents.ToolCallResult(r) => r.content }.mkString should include(marker)
  }

  it should "report each non-handoff call of a mixed handoff batch as Errored" in {
    val target  = Agent.builder("physics", Scripted(Right(answer("E=mc^2"))))
    val handoff = ToolCall("call-h", "handoff_to_physics", ujson.Obj())
    val mixed = Completion(
      "turn-1",
      0L,
      "",
      "test-model",
      AssistantMessage(None, Seq(handoff, toolCall)),
      List(handoff, toolCall),
      usage
    )
    val builder = agentWith(Scripted(Right(mixed), Right(answer("one at a time"))), echoTool)
      .withHandoffs(Handoff.to("physics", target, "physics"))
    val (result, c) = collect(builder, "go")
    result.map(_.answer) shouldBe Right(Some("one at a time"))
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s } shouldBe empty // nothing ran
    c.all.collect { case AgentEvents.ToolCallResult(r) => r.toolCallId -> r.isError } shouldBe Vector("call-1" -> true)
    c.durable.collect { case AgentEvents.ToolExecuted(t) => (t.tool, t.toolCallId, t.outcome, t.duration) } shouldBe
      Vector(("echo", "call-1", ToolExecutionOutcome.Errored, scala.concurrent.duration.Duration.Zero))
  }

  it should "report invalid arguments as Errored with no ToolCallStarted" in {
    val invalid = ToolCall("call-2", "echo", ujson.Obj("other" -> 1))
    val (_, c)  = collect(agentWith(Scripted(Right(calling(invalid)), Right(answer("sorry"))), echoTool), "go")
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s } shouldBe empty
    c.durable.collect { case AgentEvents.ToolExecuted(t) => t.outcome } shouldBe Vector(ToolExecutionOutcome.Errored)
  }

  it should "report a middleware denial as Denied" in {
    val deny = new AgentMiddleware:
      val id = MiddlewareId("deny")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(
        next: () => ToolOutcome
      ): ToolOutcome = ToolOutcome.Error("Denied: not today")
    val builder =
      agentWith(Scripted(Right(toolCallCompletion), Right(answer("ok"))), echoTool).withMiddleware(deny)
    val (result, c) = collect(builder, "go")
    result.map(_.answer) shouldBe Right(Some("ok"))
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s.toolCallId } shouldBe Vector("call-1")
    c.all.collect { case AgentEvents.ToolCallResult(r) => r.content -> r.isError } shouldBe
      Vector("Denied: not today" -> true)
    c.durable.collect { case AgentEvents.ToolExecuted(t) => t.outcome } shouldBe Vector(ToolExecutionOutcome.Denied)
  }

  it should "report NeedsApproval, then Rejected after a reject" in {
    val builder = agentWith(Scripted(Right(toolCallCompletion), Right(answer("fine"))), echoTool)
      .withMiddleware(ApprovalMiddleware.unlessReadOnly)
    val ((first, second), c) = collecting(builder, 2) { (agent, threadId, listener) =>
      val first = ok(agent.stream(threadId, "go")(listener).flatMap(_.await()))
      val id = first.status match
        case AgentStatus.Suspended(approvals, _, _, _) => approvals.head._1
        case other                                     => fail(s"expected a suspension, got $other")
      val second = ok(agent.streamResume(threadId, Map(first.reject(id, "no thanks")))(listener).flatMap(_.await()))
      (first, second)
    }
    second.answer shouldBe Some("fine")

    val firstRun = c.of(first.runId)
    firstRun.collect { case AgentEvents.ToolExecuted(t) => t.outcome } shouldBe
      Vector(ToolExecutionOutcome.NeedsApproval)
    firstRun.collect { case e @ AgentEvents.ToolExecuted(_) => e }.forall(_.isInstanceOf[StreamEvent.Durable]) shouldBe
      true
    firstRun.collect { case AgentEvents.ToolCallResult(r) => r } shouldBe empty

    val secondRun = c.of(second.runId)
    secondRun.collect { case AgentEvents.ToolExecuted(t) => t.outcome -> t.duration } shouldBe
      Vector(ToolExecutionOutcome.Rejected -> scala.concurrent.duration.Duration.Zero)
    secondRun.collect { case AgentEvents.ToolCallResult(r) => r.content -> r.isError } shouldBe
      Vector("Rejected: no thanks" -> true)
    secondRun.collect { case AgentEvents.ToolCallStarted(s) => s } shouldBe empty
  }

  it should "report an approved call as Succeeded after NeedsApproval" in {
    val builder = agentWith(Scripted(Right(toolCallCompletion), Right(answer("fine"))), echoTool)
      .withMiddleware(ApprovalMiddleware.unlessReadOnly)
    val ((first, second), c) = collecting(builder, 2) { (agent, threadId, listener) =>
      val first = ok(agent.stream(threadId, "go")(listener).flatMap(_.await()))
      val id = first.status match
        case AgentStatus.Suspended(approvals, _, _, _) => approvals.head._1
        case other                                     => fail(s"expected a suspension, got $other")
      (first, ok(agent.streamResume(threadId, Map(first.approve(id)))(listener).flatMap(_.await())))
    }
    second.answer shouldBe Some("fine")
    c.of(first.runId).collect { case AgentEvents.ToolExecuted(t) => t.outcome } shouldBe
      Vector(ToolExecutionOutcome.NeedsApproval)
    c.of(second.runId).collect { case AgentEvents.ToolExecuted(t) => t.outcome } shouldBe
      Vector(ToolExecutionOutcome.Succeeded)
    c.of(second.runId).collect { case AgentEvents.ToolCallStarted(s) => s.toolCallId } shouldBe Vector("call-1")
  }

  "A guardrail" should "send GuardrailBlocked(Input) for an input block" in {
    val builder = Agent
      .builder("assistant", Scripted(Right(answer("never"))))
      .withMiddleware(new GuardrailMiddleware(input = Seq(new LengthCheck(1, 3)), output = Nil))
    val (result, c) = collect(builder, "far too long")
    result.map(_.status) should matchPattern { case Right(AgentStatus.Blocked("LengthCheck", _)) => }
    c.durable.collect { case AgentEvents.GuardrailBlocked(b) => b } shouldBe
      Vector(GuardrailBlock("LengthCheck", GuardrailPhase.Input))
  }

  it should "send GuardrailBlocked(Output) for an output block" in {
    val builder = Agent
      .builder("assistant", Scripted(Right(answer("much too long"))))
      .withMiddleware(new GuardrailMiddleware(input = Nil, output = Seq(new LengthCheck(1, 3))))
    val (result, c) = collect(builder, "hi")
    result.map(_.status) should matchPattern { case Right(AgentStatus.Blocked("LengthCheck", _)) => }
    c.durable.collect { case AgentEvents.GuardrailBlocked(b) => b } shouldBe
      Vector(GuardrailBlock("LengthCheck", GuardrailPhase.Output))
  }

  it should "send nothing when the turn passes" in {
    val builder = Agent
      .builder("assistant", Scripted(Right(answer("ok"))))
      .withMiddleware(
        new GuardrailMiddleware(input = Seq(new LengthCheck(1, 30)), output = Seq(new LengthCheck(1, 30)))
      )
    val (_, c) = collect(builder, "hi")
    c.all.collect { case AgentEvents.GuardrailBlocked(b) => b } shouldBe empty
  }

  /** Every durable event of `threadId`'s log, replayed from the start. */
  private def replayed(runtime: GraphRuntime, threadId: ThreadId): Vector[EventRecord] =
    val records = new CopyOnWriteArrayList[EventRecord]()
    val sub = runtime
      .subscribe(threadId, afterSeq = 0L) {
        case StreamEvent.Durable(r) => records.add(r): Unit
        case _                      => ()
      }
      .fold(e => fail(e.message), identity)
    Thread.sleep(300) // replay of an in-memory log is immediate; this only lets the dispatcher drain
    sub.cancel()
    records.asScala.toVector

  "Durable agent events" should "be stored once per committed task, and none for a failed task" in {
    val runtime = GraphRuntime.inMemory()
    val client  = Scripted(Left(NetworkError("down", None, "test")), Right(answer("done")))
    val agent   = Agent.builder("assistant", client).withRuntime(runtime).build().fold(e => fail(e.message), identity)
    agent.run(ThreadId("once"), "go").isLeft shouldBe true
    agent.recover(ThreadId("once")).map(_.answer) shouldBe Right(Some("done"))
    val completed = replayed(runtime, ThreadId("once")).collect {
      case r @ EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(AgentEvents.ModelCallCompleted.name, _, _)) => r
    }
    completed.size shouldBe 1
  }

  it should "store a tool's ToolExecuted once when recover re-runs only the failed work" in {
    val runtime = GraphRuntime.inMemory()
    // the tool call succeeds; the next model call fails, and recover re-runs only that call
    val client = Scripted(Right(toolCallCompletion), Left(NetworkError("down", None, "test")), Right(answer("done")))
    val agent = Agent
      .builder("assistant", client)
      .withTools(new ToolRegistry(Seq(echoTool)))
      .withRuntime(runtime)
      .build()
      .fold(e => fail(e.message), identity)
    agent.run(ThreadId("tool-once"), "go").isLeft shouldBe true
    agent.recover(ThreadId("tool-once")).map(_.answer) shouldBe Right(Some("done"))
    val executed = replayed(runtime, ThreadId("tool-once")).collect {
      case r @ EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(AgentEvents.ToolExecuted.name, _, _)) => r
    }
    executed.size shouldBe 1
  }

  "Agent payloads" should "carry no content" in {
    val marker  = "SECRET-42"
    val runtime = GraphRuntime.inMemory()
    val call    = ToolCall("call-1", "echo", ujson.Obj("message" -> marker))
    val client = Scripted(
      Right(Completion("c1", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage)),
      Right(answer(s"the answer is $marker"))
    )
    val agent = Agent
      .builder("assistant", client)
      .withTools(new ToolRegistry(Seq(echoTool)))
      .withRuntime(runtime)
      .build()
      .fold(e => fail(e.message), identity)
    agent.run(ThreadId("content"), s"tell me $marker").map(_.answer) shouldBe Right(Some(s"the answer is $marker"))
    val agentPayloads = replayed(runtime, ThreadId("content")).collect {
      case EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(name, _, payload)) if AgentEvents.durable(name) => payload
    }
    agentPayloads should not be empty
    agentPayloads.foreach(p => (p.render() should not).include(marker))
  }

  it should "carry no guardrail reason or blocked text" in {
    val marker  = "SECRET-42"
    val runtime = GraphRuntime.inMemory()
    val agent = Agent
      .builder("assistant", Scripted(Right(answer(s"leaking $marker"))))
      .withMiddleware(new GuardrailMiddleware(input = Nil, output = Seq(new LengthCheck(1, 3))))
      .withRuntime(runtime)
      .build()
      .fold(e => fail(e.message), identity)
    agent.run(ThreadId("blocked"), s"tell me $marker").map(_.status) should matchPattern {
      case Right(AgentStatus.Blocked(_, _)) =>
    }
    val agentPayloads = replayed(runtime, ThreadId("blocked")).collect {
      case EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(name, _, payload)) if AgentEvents.durable(name) =>
        name -> payload
    }
    agentPayloads.map(_._1) should contain(AgentEvents.GuardrailBlocked.name)
    agentPayloads.foreach((_, p) => (p.render() should not).include(marker))
  }
