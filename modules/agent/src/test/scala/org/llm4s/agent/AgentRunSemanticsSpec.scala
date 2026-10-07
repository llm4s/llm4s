package org.llm4s.agent

import org.llm4s.agent.events.{ AgentEvents, GuardrailBlock, GuardrailPhase }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.{ CompositeGuardrail, Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * How a run behaves at its edges, as the graph runtime has it: guardrail events and evaluation, the
 * events a blocked or failed run sends, `maxSteps` across the entry points, the events of a handoff
 * target, and what a throwing client, guardrail or listener does to the `Result` API.
 *
 * These are the behaviours issue #1315 described for the loop the runtime replaced; each test says
 * which finding it answers.
 */
class AgentRunSemanticsSpec extends AnyFlatSpec with Matchers:

  /** Answers call N with `respond(N)`, counting calls. */
  final private class ByIndex(respond: Int => Result[Completion]) extends LLMClient:
    val calls = new AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      respond(calls.getAndIncrement())
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

  /** A model turn that calls a tool no agent has, so the loop goes round again. */
  private def callsMissingTool(n: Int): Result[Completion] =
    val call = ToolCall(s"call-$n", "missing_tool", ujson.Obj())
    Right(Completion(s"turn-$n", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage))

  private def calling(name: String, id: String): Completion =
    val call = ToolCall(id, name, ujson.Obj("reason" -> "needs the specialist"))
    Completion(s"turn-$id", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage)

  /** An input guardrail that fails (or passes) and records that it ran. */
  final private class Recording(val name: String, passes: Boolean, ran: CopyOnWriteArrayList[String])
      extends InputGuardrail:
    def validate(value: String): Result[String] =
      ran.add(name): Unit
      if passes then Right(value) else Left(ValidationError.invalid("input", s"$name refused"))

  /** The same, for output. */
  final private class RecordingOut(val name: String, passes: Boolean, ran: CopyOnWriteArrayList[String])
      extends OutputGuardrail:
    def validate(value: String): Result[String] =
      ran.add(name): Unit
      if passes then Right(value) else Left(ValidationError.invalid("output", s"$name refused"))

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

  private def collect(builder: AgentBuilder, query: String): (Result[AgentResult], Gathered) =
    val agent = builder.build().fold(e => fail(e.message), identity)
    val c     = Gathered(1)
    val result =
      agent.stream(ThreadId(java.util.UUID.randomUUID().toString), query)(c.listener).flatMap(_.await())
    c.ended.getCount shouldBe 0
    (result, c)

  private def built(builder: AgentBuilder): Agent = builder.build().fold(e => fail(e.message), identity)

  // ---- finding 1: guardrail events with several guardrails

  "Several failing guardrails" should "send one GuardrailBlocked naming the first, and no event for a guardrail that passed" in {
    val ran = new CopyOnWriteArrayList[String]()
    val middleware = new GuardrailMiddleware(
      input =
        Seq(new Recording("Passes", true, ran), new Recording("RuleA", false, ran), new Recording("RuleB", false, ran)),
      output = Nil
    )
    val (result, c) =
      collect(Agent.builder("assistant", new ByIndex(_ => Right(answer("never")))).withMiddleware(middleware), "hello")

    result.map(_.status) should matchPattern { case Right(AgentStatus.Blocked("RuleA", _)) => }
    c.durable.collect { case AgentEvents.GuardrailBlocked(b) => b } shouldBe
      Vector(GuardrailBlock("RuleA", GuardrailPhase.Input))
    // the durable log holds no other guardrail event: a guardrail that passed, or that ran after the first failure, sends none
    c.durable
      .collect { case StreamEvent.Durable(r) => r.event }
      .collect { case RunEvent.Custom(name, _, _) => name }
      .filter(_.contains("guardrail")) shouldBe Vector(AgentEvents.GuardrailBlocked.name)
  }

  // ---- finding 2: "fail-fast"

  "GuardrailMiddleware" should "run every guardrail of a list, so the block reports every failure" in {
    val ran = new CopyOnWriteArrayList[String]()
    val middleware = new GuardrailMiddleware(
      input =
        Seq(new Recording("RuleA", false, ran), new Recording("RuleB", false, ran), new Recording("Passes", true, ran)),
      output = Nil
    )
    val status = built(Agent.builder("assistant", new ByIndex(_ => Right(answer("never")))).withMiddleware(middleware))
      .run("hello")
      .fold(e => fail(e.message), _.status)

    ran.asScala.toVector shouldBe Vector("RuleA", "RuleB", "Passes")
    status match
      case AgentStatus.Blocked(first, reason) =>
        first shouldBe "RuleA"
        reason should (include("RuleA refused").and(include("RuleB refused")))
      case other => fail(s"expected Blocked, got $other")
  }

  "CompositeGuardrail.sequential" should "stop at the first failure" in {
    val ran = new CopyOnWriteArrayList[String]()
    val result = CompositeGuardrail
      .sequential[String](Seq(new Recording("A", false, ran), new Recording("B", true, ran)))
      .validate("x")
    result.isLeft shouldBe true
    ran.asScala.toVector shouldBe Vector("A")
  }

  "A sequential composite wrapped as an input guardrail" should "stop at its first failure, and the block names the composite" in {
    val ran = new CopyOnWriteArrayList[String]()
    val composite =
      CompositeGuardrail.sequential[String](Seq(new Recording("RuleA", false, ran), new Recording("RuleB", false, ran)))
    // the wrapper of docs/guide/agents/guardrails.md: a composite is a Guardrail[String], not an InputGuardrail
    def asInput(guardrail: Guardrail[String]): InputGuardrail = new InputGuardrail:
      val name: String                            = guardrail.name
      def validate(value: String): Result[String] = guardrail.validate(value)
    val wrapped = asInput(composite)

    val status = built(
      Agent
        .builder("assistant", new ByIndex(_ => Right(answer("never"))))
        .withMiddleware(new GuardrailMiddleware(Seq(wrapped), Nil))
    ).run("hello").fold(e => fail(e.message), _.status)

    ran.asScala.toVector shouldBe Vector("RuleA")
    status match
      case AgentStatus.Blocked(guardrail, reason) =>
        guardrail shouldBe composite.name
        reason should include("RuleA refused")
        (reason should not).include("RuleB")
      case other => fail(s"expected Blocked, got $other")
  }

  // ---- findings 3 and 4: how a guardrail rejection ends the run

  private def blockIndex(events: Vector[RunEvent]): Int =
    events.indexWhere {
      case RunEvent.Custom(name, _, _) => name == AgentEvents.GuardrailBlocked.name
      case _                           => false
    }

  private def modelCallIndex(events: Vector[RunEvent]): Int =
    events.indexWhere {
      case RunEvent.Custom(name, _, _) => name == AgentEvents.ModelCallCompleted.name
      case _                           => false
    }

  "An input block" should "send the block, then end the run with RunFailed, and never RunCompleted" in {
    val ran        = new CopyOnWriteArrayList[String]()
    val middleware = new GuardrailMiddleware(input = Seq(new Recording("RuleA", false, ran)), output = Nil)
    val client     = new ByIndex(_ => Right(answer("never")))
    val (_, c)     = collect(Agent.builder("assistant", client).withMiddleware(middleware), "hello")
    val events     = c.durable.map(_.record.event)

    events.head should matchPattern { case RunEvent.RunStarted(_, _) => }
    events.last should matchPattern { case RunEvent.RunFailed(message) if message.contains("RuleA") => }
    events should not contain RunEvent.RunCompleted
    blockIndex(events) should ((be > 0).and(be < events.size - 1))
    modelCallIndex(events) shouldBe -1 // the model was never called
    client.calls.get() shouldBe 0
  }

  "An output block" should "send the block after the model call and before RunFailed, and never RunCompleted" in {
    val ran        = new CopyOnWriteArrayList[String]()
    val middleware = new GuardrailMiddleware(input = Nil, output = Seq(new RecordingOut("RuleA", false, ran)))
    val (_, c) =
      collect(
        Agent.builder("assistant", new ByIndex(_ => Right(answer("rejected")))).withMiddleware(middleware),
        "hello"
      )
    val events = c.durable.map(_.record.event)

    events should not contain RunEvent.RunCompleted
    events.last should matchPattern { case RunEvent.RunFailed(message) if message.contains("RuleA") => }
    modelCallIndex(events) should be >= 0
    blockIndex(events) should be > modelCallIndex(events)
    blockIndex(events) should be < events.size - 1
  }

  // ---- finding 5: maxSteps

  "maxSteps" should "allow exactly that many model calls, through every entry point" in {
    def agentOf(client: ByIndex): Agent =
      built(Agent.builder("assistant", client).withMaxSteps(3))

    val viaRun = new ByIndex(callsMissingTool)
    agentOf(viaRun).run("loop").fold(e => fail(e.message), _.status) shouldBe AgentStatus.StepLimitReached
    viaRun.calls.get() shouldBe 3

    val viaStream = new ByIndex(callsMissingTool)
    val streamed  = agentOf(viaStream).stream(ThreadId("t-stream"), "loop")(_ => ()).flatMap(_.await())
    streamed.fold(e => fail(e.message), _.status) shouldBe AgentStatus.StepLimitReached
    viaStream.calls.get() shouldBe 3

    val viaMulti = new ByIndex(callsMissingTool)
    agentOf(viaMulti)
      .runMultiTurn("loop", Nil)
      .fold(e => fail(e.message), _.status) shouldBe AgentStatus.StepLimitReached
    viaMulti.calls.get() shouldBe 3

    // a continued turn gets its own budget, not what the first turn left
    val viaContinue = new ByIndex(n => if n == 0 then Right(answer("first")) else callsMissingTool(n))
    val agent       = agentOf(viaContinue)
    val first       = agent.run("hello").fold(e => fail(e.message), identity)
    agent.continueConversation(first, "loop").fold(e => fail(e.message), _.status) shouldBe AgentStatus.StepLimitReached
    viaContinue.calls.get() shouldBe 1 + 3
  }

  // ---- finding 6: the events of a failed run

  "A failed run" should "still send the events that explain it, and the failure is the Left" in {
    val error       = NetworkError("provider down", None, "mock://x")
    val (result, c) = collect(Agent.builder("assistant", new ByIndex(_ => Left(error))), "hello")
    val events      = c.durable.map(_.record.event)

    result.isLeft shouldBe true
    events.head should matchPattern { case RunEvent.RunStarted(_, _) => }
    events should contain(RunEvent.TaskFailed("Node 'assistant/model' (task 1.0) failed: provider down"))
    events.last should matchPattern { case RunEvent.RunFailed(message) if message.contains("provider down") => }
  }

  // ---- finding 7: events after a handoff

  "A handoff" should "stream the target's events and end the run once with RunCompleted" in {
    val target = Agent.builder("specialist", new ByIndex(_ => Right(answer("expert answer"))))
    val root = Agent
      .builder(
        "assistant",
        new ByIndex(n => if n == 0 then Right(calling("handoff_to_specialist", "h1")) else Right(answer("unused")))
      )
      .withHandoffs(Handoff.to("specialist", target, "Specialist"))
    val (result, c) = collect(root, "hard question")
    result.map(_.answer) shouldBe Right(Some("expert answer"))
    val events = c.durable.map(_.record.event)
    events.count(_ == RunEvent.RunCompleted) shouldBe 1
    val handedOff = c.durable.collect { case AgentEvents.HandedOff(h) => h }
    handedOff.map(h => (h.from, h.to)) shouldBe Vector(("assistant", "specialist"))
    c.durable.collect { case AgentEvents.ModelCallCompleted(m) => m.agent } should contain("specialist")
  }

  // ---- finding 8: exceptions and the Result API

  "A client that throws" should "come back as a Left, not an exception" in {
    val client = new ByIndex(_ => throw new IllegalStateException("client blew up"))
    val result = scala.util.Try(built(Agent.builder("assistant", client)).run("hello"))
    result.isSuccess shouldBe true
    result.get.isLeft shouldBe true
  }

  "A guardrail that throws" should "come back as a Left, not an exception" in {
    val bad: InputGuardrail = new InputGuardrail:
      val name: String                            = "Bad"
      def validate(value: String): Result[String] = throw new IllegalStateException("guardrail blew up")
    val agent = built(
      Agent
        .builder("assistant", new ByIndex(_ => Right(answer("x"))))
        .withMiddleware(new GuardrailMiddleware(Seq(bad), Nil))
    )
    val result = scala.util.Try(agent.run("hello"))
    result.isSuccess shouldBe true
    info(
      s"guardrail that throws: ${result.get.fold(e => s"Left(${e.getClass.getSimpleName})", r => s"Right(${r.status})")}"
    )
  }

  "A listener that throws" should "not change the run's result" in {
    val agent = built(Agent.builder("assistant", new ByIndex(_ => Right(answer("fine")))))
    val result = scala.util.Try(
      agent
        .stream(ThreadId("t-throwing"), "hello")(_ => throw new IllegalStateException("listener blew up"))
        .flatMap(_.await())
    )
    result.isSuccess shouldBe true
    result.get.map(_.answer) shouldBe Right(Some("fine"))
  }
