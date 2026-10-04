package org.llm4s.agent.streaming

import org.llm4s.agent.{ Agent, AgentState, AgentStatus, CompletionFixture, Handoff, NTurnFakeLLMClient }
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.{ CancelledError, LLMError, NetworkError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

/**
 * End-to-end ordering tests for the agent streaming event protocol (issue #997).
 *
 * Complements the presence-only checks in AgentSpec / AgentTracingSpec by asserting the exact
 * sequence of event types for each scenario. Mock-backed, so these are ordinary unit tests.
 */
class AgentStreamingIntegrationSpec extends AnyFlatSpec with Matchers with OptionValues with EitherValues {

  final case class EchoResult(value: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  /** Wraps a client and emits the completion text as two streamed chunks, so TextDelta events are produced. */
  private class ChunkingClient(delegate: LLMClient) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      delegate.complete(conversation, options)

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options).map { completion =>
        val (first, second) = completion.content.splitAt(completion.content.length / 2)
        Seq(first, second)
          .filter(_.nonEmpty)
          .foreach(c => onChunk(StreamedChunk(id = completion.id, content = Some(c))))
        completion
      }

    override def getContextWindow(): Int     = delegate.getContextWindow()
    override def getReserveCompletion(): Int = delegate.getReserveCompletion()
  }

  private def echoTool: ToolFunction[Map[String, Any], EchoResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo tool parameters")
      .withRequiredField("input", Schema.string("Value to echo"))
    ToolBuilder[Map[String, Any], EchoResult]("echo", "Echoes input back", schema)
      .withHandler(extractor => extractor.getString("input").map(EchoResult(_)))
      .buildSafe()
      .fold(e => fail(s"echo tool failed to build: $e"), identity)
  }

  private def echoCall(id: String, input: String): Completion =
    CompletionFixture.withToolCall("echo", ujson.Obj("input" -> input), id)

  private def passingInput(n: String): InputGuardrail = new InputGuardrail {
    val name: String                            = n
    def validate(value: String): Result[String] = Right(value)
  }
  private def rejectingInput(n: String): InputGuardrail = new InputGuardrail {
    val name: String                            = n
    def validate(value: String): Result[String] = Left(ValidationError("input", "rejected"))
  }
  private def passingOutput(n: String): OutputGuardrail = new OutputGuardrail {
    val name: String                            = n
    def validate(value: String): Result[String] = Right(value)
  }
  private def rejectingOutput(n: String): OutputGuardrail = new OutputGuardrail {
    val name: String                            = n
    def validate(value: String): Result[String] = Left(ValidationError("output", "rejected"))
  }

  private def run(
    client: LLMClient,
    tools: ToolRegistry = ToolRegistry.empty,
    query: String = "query",
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty
  ): (Result[AgentState], Seq[AgentEvent]) = {
    val buffer = ListBuffer[AgentEvent]()
    val result = new Agent(client).runWithEvents(
      query = query,
      tools = tools,
      onEvent = buffer += _,
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails,
      handoffs = handoffs,
      maxSteps = Some(10)
    )
    (result, buffer.toSeq)
  }

  private def types(events: Seq[AgentEvent]): Seq[String] = events.map(_.getClass.getSimpleName)

  "Agent.runWithEvents" should "emit the exact event sequence for a single tool call" in {
    val client = new ChunkingClient(
      new NTurnFakeLLMClient(echoCall("call-001", "hello"), CompletionFixture.simple("The echo returned: hello"))
    )
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)), "Echo hello")

    result.isRight shouldBe true
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "TextDelta",
      "TextDelta",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted"
    )

    val started   = events.collectFirst { case e: AgentEvent.ToolCallStarted => e }.value
    val completed = events.collectFirst { case e: AgentEvent.ToolCallCompleted => e }.value
    started.toolName shouldBe "echo"
    started.toolCallId shouldBe "call-001"
    completed.toolCallId shouldBe "call-001"
    completed.success shouldBe true

    events.collect { case e: AgentEvent.TextDelta => e.delta }.mkString shouldBe "The echo returned: hello"
  }

  it should "interleave step and tool events across two sequential tool calls" in {
    val client = new NTurnFakeLLMClient(
      echoCall("call-1", "first"),
      echoCall("call-2", "second"),
      CompletionFixture.simple("done")
    )
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)))

    result.isRight shouldBe true
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted"
    )
    events.collect { case e: AgentEvent.ToolCallStarted => e.toolCallId } shouldBe Seq("call-1", "call-2")
    events.collect { case e: AgentEvent.ToolCallCompleted => e.toolCallId } shouldBe Seq("call-1", "call-2")
  }

  it should "emit guardrail events and never start the agent when an input guardrail rejects" in {
    val (result, events) = run(
      new NTurnFakeLLMClient(CompletionFixture.simple("unreachable")),
      inputGuardrails = Seq(rejectingInput("Rejecter"))
    )

    result.isLeft shouldBe true
    types(events) shouldBe Seq(
      "InputGuardrailStarted",
      "InputGuardrailCompleted"
    )
    events.collectFirst { case e: AgentEvent.InputGuardrailCompleted => e }.value.passed shouldBe false
  }

  it should "report passed=true for input and output guardrails that pass, in order around the run" in {
    val (result, events) = run(
      new NTurnFakeLLMClient(CompletionFixture.simple("ok")),
      inputGuardrails = Seq(passingInput("In")),
      outputGuardrails = Seq(passingOutput("Out"))
    )

    result.isRight shouldBe true
    types(events) shouldBe Seq(
      "InputGuardrailStarted",
      "InputGuardrailCompleted",
      "AgentStarted",
      "StepStarted",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted",
      "OutputGuardrailStarted",
      "OutputGuardrailCompleted"
    )
    events.collect { case e: AgentEvent.InputGuardrailCompleted => (e.guardrailName, e.passed) } shouldBe Seq(
      ("In", true)
    )
    events.collect { case e: AgentEvent.OutputGuardrailCompleted => (e.guardrailName, e.passed) } shouldBe Seq(
      ("Out", true)
    )
  }

  it should "emit OutputGuardrailCompleted(passed=false) and return Left when an output guardrail rejects" in {
    val (result, events) = run(
      new NTurnFakeLLMClient(CompletionFixture.simple("bad output")),
      outputGuardrails = Seq(rejectingOutput("OutRejecter"))
    )

    result.isLeft shouldBe true
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted",
      "OutputGuardrailStarted",
      "OutputGuardrailCompleted"
    )
    events.collectFirst { case e: AgentEvent.OutputGuardrailCompleted => e }.value.passed shouldBe false
  }

  it should "emit HandoffStarted then HandoffCompleted when the model requests a handoff" in {
    val target  = new Agent(new NTurnFakeLLMClient(CompletionFixture.simple("Specialist response")))
    val handoff = Handoff.to(target, "Specialist for echoing")
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "delegating"), "call-handoff")
    )
    val (result, events) = run(client, handoffs = Seq(handoff))

    result.isRight shouldBe true
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "HandoffStarted",
      "HandoffCompleted"
    )
    events.collectFirst { case e: AgentEvent.HandoffCompleted => e }.value.success shouldBe true
  }

  "Agent.runCollectingEvents" should "return the final state and the ordered events" in {
    val client = new NTurnFakeLLMClient(echoCall("call-collect", "world"), CompletionFixture.simple("received"))
    val (state, events) =
      new Agent(client).runCollectingEvents("Echo world", new ToolRegistry(Seq(echoTool))).value

    state.status shouldBe AgentStatus.Complete
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted"
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Additional coverage: protocol details, ordering against real side effects, failure paths
  // ---------------------------------------------------------------------------------------------

  /**
   * Replays `script` one completion per call, streaming each text through `chunker`, and writes the
   * client-side milestones to `log` so a test can check that events interleave with real work.
   * Once the script is exhausted it fails with `exhausted`.
   */
  private class RecordingStreamClient(
    script: Seq[Completion],
    log: ListBuffer[String] = ListBuffer.empty[String],
    chunker: String => Seq[String] = text => Seq(text),
    exhausted: Option[LLMError] = None
  ) extends LLMClient {
    private val index = new AtomicInteger(0)

    def calls: Int = index.get()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      streamComplete(conversation, options, _ => ())

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val i = index.getAndIncrement()
      log += s"llm:call-$i"
      if (i >= script.size) {
        Left(exhausted.getOrElse(ProcessingError("test-script", s"script exhausted at call $i")))
      } else {
        val completion = script(i)
        chunker(completion.content).filter(_.nonEmpty).foreach { c =>
          onChunk(StreamedChunk(id = completion.id, content = Some(c)))
        }
        log += s"llm:returned-$i"
        Right(completion)
      }
    }

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
  }

  private def event(e: AgentEvent): String = s"evt:${e.getClass.getSimpleName}"

  private def failingTool(name: String): ToolFunction[Map[String, Any], EchoResult] = {
    val schema = Schema.`object`[Map[String, Any]]("Parameters").withRequiredField("input", Schema.string("Value"))
    ToolBuilder[Map[String, Any], EchoResult](name, "Always fails", schema)
      .withHandler(_ => Left("boom: backend unavailable"))
      .buildSafe()
      .fold(e => fail(s"tool failed to build: $e"), identity)
  }

  "AgentEvent payloads" should "carry step numbers, tool-call flags, totals and the finished state" in {
    val client = new NTurnFakeLLMClient(
      echoCall("c1", "a"),
      echoCall("c2", "b"),
      CompletionFixture.simple("final")
    )
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)))
    val state            = result.value

    events.collect { case e: AgentEvent.StepStarted => e.stepNumber } shouldBe Seq(0, 1, 2)
    events.collect { case e: AgentEvent.StepCompleted => (e.stepNumber, e.hasToolCalls) } shouldBe
      Seq((0, true), (1, true), (2, false))

    val started = events.collectFirst { case e: AgentEvent.AgentStarted => e }.value
    started.query shouldBe "query"
    started.toolCount shouldBe 1

    val completed = events.collect { case e: AgentEvent.AgentCompleted => e }
    completed should have size 1
    completed.head.totalSteps shouldBe 3
    completed.head.finalState shouldBe state
    state.status shouldBe AgentStatus.Complete

    val toolStarted = events.collect { case e: AgentEvent.ToolCallStarted => e }
    toolStarted.map(e => ujson.read(e.arguments)("input").str) shouldBe Seq("a", "b")
    events.collect { case e: AgentEvent.ToolCallCompleted => ujson.read(e.result)("value").str } shouldBe Seq("a", "b")
  }

  it should "reach the caller as the work happens, not batched after the run" in {
    val log = ListBuffer[String]()
    val tool = {
      val schema = Schema.`object`[Map[String, Any]]("Parameters").withRequiredField("input", Schema.string("Value"))
      ToolBuilder[Map[String, Any], EchoResult]("echo", "Echoes", schema)
        .withHandler { ex =>
          log += "tool:run"
          ex.getString("input").map(EchoResult(_))
        }
        .buildSafe()
        .fold(e => fail(s"tool failed to build: $e"), identity)
    }
    val client = new RecordingStreamClient(
      Seq(echoCall("c1", "x"), CompletionFixture.simple("abcd")),
      log,
      chunker = t => Seq(t.take(2), t.drop(2))
    )

    val result = new Agent(client).runWithEvents(
      query = "q",
      tools = new ToolRegistry(Seq(tool)),
      onEvent = e => log += event(e)
    )

    result.isRight shouldBe true
    log.toList shouldBe List(
      "evt:AgentStarted",
      "evt:StepStarted",
      "llm:call-0",
      "llm:returned-0",
      "evt:StepCompleted",
      "evt:ToolCallStarted",
      "tool:run",
      "evt:ToolCallCompleted",
      "evt:StepStarted",
      "llm:call-1",
      "evt:TextDelta",
      "evt:TextDelta",
      "llm:returned-1",
      "evt:TextComplete",
      "evt:StepCompleted",
      "evt:AgentCompleted"
    )
  }

  it should "reassemble uneven, multi-byte chunks into exactly the final text, ignoring chunks without text" in {
    val text       = "Hello, wörld 🌍 ok!"
    val completion = CompletionFixture.simple(text)
    val parts      = Seq("Hel", "lo, ", "w", "ör", "ld ", "🌍", " ok!")
    val client = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Right(completion)
      override def streamComplete(
        c: Conversation,
        o: CompletionOptions,
        f: StreamedChunk => Unit
      ): Result[Completion] = {
        parts.foreach { p =>
          f(StreamedChunk(id = "s", content = Some(p)))
          f(StreamedChunk(id = "s", content = None)) // e.g. a finish-reason or tool-call-only chunk
        }
        Right(completion)
      }
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val (result, events) = run(client)
    val deltas           = events.collect { case e: AgentEvent.TextDelta => e.delta }

    deltas shouldBe parts
    deltas.mkString shouldBe text
    events.collect { case e: AgentEvent.TextComplete => e.fullText } shouldBe Seq(text)
    result.value.conversation.messages.last.content shouldBe text
  }

  "Agent.runWithEvents failure paths" should "emit AgentFailed (and no AgentCompleted) when the model fails on the first step" in {
    val error            = NetworkError("down", None, "mock://llm")
    val (result, events) = run(new RecordingStreamClient(Seq.empty, exhausted = Some(error)))

    result shouldBe Left(error)
    types(events) shouldBe Seq("AgentStarted", "StepStarted", "AgentFailed")
    val failed = events.collectFirst { case e: AgentEvent.AgentFailed => e }.value
    failed.error shouldBe error
    failed.stepNumber shouldBe Some(0)
  }

  it should "emit AgentFailed with the step number when the model fails after a successful tool round" in {
    val error = ProcessingError("llm", "model exploded")
    val client =
      new RecordingStreamClient(Seq(echoCall("c1", "x")), exhausted = Some(error))
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)))

    result shouldBe Left(error)
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "AgentFailed"
    )
    events.collectFirst { case e: AgentEvent.AgentFailed => e }.value.stepNumber shouldBe Some(1)
  }

  it should "pass a cancellation raised by the client through as AgentFailed(CancelledError)" in {
    val cancelled        = CancelledError("stream", None)
    val (result, events) = run(new RecordingStreamClient(Seq.empty, exhausted = Some(cancelled)))

    result shouldBe Left(cancelled)
    events.collect { case e: AgentEvent.AgentFailed => e.error } shouldBe Seq(cancelled)
  }

  it should "stop with AgentFailed(step limit) and a Failed state when tool rounds exceed maxSteps" in {
    val client = new RecordingStreamClient(Seq.fill(5)(echoCall("c", "again")))
    val buffer = ListBuffer[AgentEvent]()
    val result = new Agent(client).runWithEvents(
      query = "loop",
      tools = new ToolRegistry(Seq(echoTool)),
      onEvent = buffer += _,
      maxSteps = Some(2)
    )

    result.value.status shouldBe AgentStatus.Failed("Maximum step limit reached")
    client.calls shouldBe 2
    types(buffer.toSeq) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "AgentFailed"
    )
    val failed = buffer.collectFirst { case e: AgentEvent.AgentFailed => e }.value
    failed.stepNumber shouldBe Some(2)
    failed.error shouldBe a[ProcessingError]
    failed.error.message should include("Maximum step limit reached")
    buffer.exists(_.isInstanceOf[AgentEvent.AgentCompleted]) shouldBe false
  }

  it should "fail before calling the model at all when maxSteps is zero" in {
    val client = new RecordingStreamClient(Seq(CompletionFixture.simple("never")))
    val buffer = ListBuffer[AgentEvent]()
    val result =
      new Agent(client).runWithEvents("q", ToolRegistry.empty, onEvent = buffer += _, maxSteps = Some(0))

    result.value.status shouldBe AgentStatus.Failed("Maximum step limit reached")
    client.calls shouldBe 0
    types(buffer.toSeq) shouldBe Seq("AgentStarted", "AgentFailed")
  }

  "Tool events" should "report a failing tool as ToolCallFailed (never ToolCallCompleted) and keep running" in {
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("flaky", ujson.Obj("input" -> "x"), "call-f"),
      CompletionFixture.simple("recovered")
    )
    val (result, events) = run(client, new ToolRegistry(Seq(failingTool("flaky"))))

    result.value.status shouldBe AgentStatus.Complete
    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallFailed",
      "StepStarted",
      "TextComplete",
      "StepCompleted",
      "AgentCompleted"
    )
    val failed = events.collectFirst { case e: AgentEvent.ToolCallFailed => e }.value
    failed.toolCallId shouldBe "call-f"
    failed.toolName shouldBe "flaky"
    failed.error should include("boom: backend unavailable")
    // the model is shown the failure so it can react
    result.value.conversation.messages.collect { case m: ToolMessage => m.content }.head should include("boom")
  }

  it should "report a call to an unknown tool as ToolCallFailed" in {
    val client           = new NTurnFakeLLMClient(echoCall("c1", "x"), CompletionFixture.simple("ok"))
    val (result, events) = run(client) // empty registry: "echo" is unknown

    result.value.status shouldBe AgentStatus.Complete
    events.collect { case e: AgentEvent.ToolCallFailed => (e.toolCallId, e.toolName) } shouldBe Seq(("c1", "echo"))
    events.exists(_.isInstanceOf[AgentEvent.ToolCallCompleted]) shouldBe false
  }

  it should "emit one started/completed pair per call, in order, when a step requests two tools" in {
    val twoCalls = {
      val calls = List(
        ToolCall("t1", "echo", ujson.Obj("input" -> "one")),
        ToolCall("t2", "echo", ujson.Obj("input" -> "two"))
      )
      CompletionFixture
        .simple("")
        .withMessage(AssistantMessage("", calls))
        .withToolCalls(calls)
    }
    val client           = new NTurnFakeLLMClient(twoCalls, CompletionFixture.simple("both done"))
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)))

    result.isRight shouldBe true
    events.collect {
      case e: AgentEvent.ToolCallStarted   => s"start:${e.toolCallId}"
      case e: AgentEvent.ToolCallCompleted => s"done:${e.toolCallId}"
    } shouldBe Seq("start:t1", "done:t1", "start:t2", "done:t2")
  }

  "Guardrail events" should "never call the model, and surface the rejection reason, when the input is rejected" in {
    val client    = new RecordingStreamClient(Seq(CompletionFixture.simple("unreachable")))
    val rejection = ValidationError("input", "too short")
    val rejecting = new InputGuardrail {
      val name: String                            = "Strict"
      def validate(value: String): Result[String] = Left(rejection)
    }
    val buffer = ListBuffer[AgentEvent]()
    val result = new Agent(client).runWithEvents(
      "hi",
      ToolRegistry.empty,
      onEvent = buffer += _,
      inputGuardrails = Seq(rejecting)
    )

    result.left.value shouldBe a[ValidationError]
    result.left.value.message should include(rejection.message)
    client.calls shouldBe 0
    buffer.exists(_.isInstanceOf[AgentEvent.AgentStarted]) shouldBe false
  }

  it should "report which guardrail rejected the input when several are configured" in {
    val evaluated = ListBuffer[String]()
    def recording(n: String, ok: Boolean): InputGuardrail = new InputGuardrail {
      val name: String = n
      def validate(value: String): Result[String] = {
        evaluated += n
        if (ok) Right(value) else Left(ValidationError("input", s"$n rejected"))
      }
    }
    val (result, _) = run(
      new NTurnFakeLLMClient(CompletionFixture.simple("x")),
      inputGuardrails =
        Seq(recording("first", ok = true), recording("second", ok = false), recording("third", ok = true))
    )

    result.left.value.message should include("second rejected")
    (result.left.value.message should not).include("first rejected")
    evaluated.toList.take(2) shouldBe List("first", "second")
  }

  it should "validate only the final assistant message of a multi-step run, after AgentCompleted" in {
    val seen = ListBuffer[String]()
    val out = new OutputGuardrail {
      val name: String = "Capture"
      def validate(value: String): Result[String] = {
        seen += value
        Right(value)
      }
    }
    val client = new NTurnFakeLLMClient(echoCall("c1", "a"), echoCall("c2", "b"), CompletionFixture.simple("the end"))
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)), outputGuardrails = Seq(out))

    result.isRight shouldBe true
    seen.toList shouldBe List("the end")
    types(events).takeRight(3) shouldBe Seq("AgentCompleted", "OutputGuardrailStarted", "OutputGuardrailCompleted")
  }

  it should "surface the rejection reason after exactly one model call when the output is rejected" in {
    val client    = new RecordingStreamClient(Seq(CompletionFixture.simple("bad words")))
    val rejection = ValidationError("output", "contains forbidden content")
    val out = new OutputGuardrail {
      val name: String                            = "NoBadWords"
      def validate(value: String): Result[String] = Left(rejection)
    }
    val result = new Agent(client).runWithEvents("q", ToolRegistry.empty, _ => (), outputGuardrails = Seq(out))

    result.left.value shouldBe a[ValidationError]
    result.left.value.message should include(rejection.message)
    client.calls shouldBe 1
  }

  "Handoff events" should "name the handoff, carry the model's reason and report success=false when the target fails" in {
    val targetError = NetworkError("target down", None, "mock://target")
    val target      = new Agent(new RecordingStreamClient(Seq.empty, exhausted = Some(targetError)))
    val handoff     = Handoff.to(target, "Specialist")
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "needs expert"), "call-h")
    )
    val (result, events) = run(client, handoffs = Seq(handoff))

    result shouldBe Left(targetError)
    val started = events.collectFirst { case e: AgentEvent.HandoffStarted => e }.value
    started.targetAgentName shouldBe handoff.handoffName
    started.reason shouldBe Some("needs expert")
    started.preserveContext shouldBe handoff.preserveContext
    val done = events.collectFirst { case e: AgentEvent.HandoffCompleted => e }.value
    done.targetAgentName shouldBe handoff.handoffName
    done.success shouldBe false
    types(events).takeRight(2) shouldBe Seq("HandoffStarted", "HandoffCompleted")
  }

  it should "run a handoff after a tool round and return the specialist's answer" in {
    val target  = new Agent(new NTurnFakeLLMClient(CompletionFixture.simple("specialist says hi")))
    val handoff = Handoff.to(target, "Specialist")
    val client = new NTurnFakeLLMClient(
      echoCall("c1", "x"),
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "r"), "call-h")
    )
    val (result, events) = run(client, new ToolRegistry(Seq(echoTool)), handoffs = Seq(handoff))

    types(events) shouldBe Seq(
      "AgentStarted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "StepStarted",
      "StepCompleted",
      "ToolCallStarted",
      "ToolCallCompleted",
      "HandoffStarted",
      "HandoffCompleted"
    )
    result.value.conversation.messages.last.content shouldBe "specialist says hi"
  }

  "Agent.runCollectingEvents" should "record exactly the events the callback variant delivers for the same script" in {
    def script() = new NTurnFakeLLMClient(echoCall("c1", "w"), CompletionFixture.simple("answer"))

    val buffer   = ListBuffer[AgentEvent]()
    val callback = new Agent(script()).runWithEvents("Q", new ToolRegistry(Seq(echoTool)), onEvent = buffer += _)
    val (collectedState, collected) =
      new Agent(script()).runCollectingEvents("Q", new ToolRegistry(Seq(echoTool))).value

    types(collected) shouldBe types(buffer.toSeq)
    collected.collect { case e: AgentEvent.ToolCallStarted => (e.toolCallId, e.toolName, e.arguments) } shouldBe
      buffer.collect { case e: AgentEvent.ToolCallStarted => (e.toolCallId, e.toolName, e.arguments) }.toSeq
    collectedState.conversation.messages.map(m => (m.role, m.content)) shouldBe
      callback.value.conversation.messages.map(m => (m.role, m.content))
  }

  it should "return the error unchanged when the run fails" in {
    val error = ProcessingError("llm", "nope")
    new Agent(new RecordingStreamClient(Seq.empty, exhausted = Some(error)))
      .runCollectingEvents("q", ToolRegistry.empty) shouldBe Left(error)
  }

  "Agent.continueConversationWithEvents" should "stream a follow-up turn on top of the previous history" in {
    val first  = new Agent(new NTurnFakeLLMClient(CompletionFixture.simple("first answer")))
    val state1 = first.run("one", ToolRegistry.empty).value

    val buffer = ListBuffer[AgentEvent]()
    val state2 = new Agent(new RecordingStreamClient(Seq(CompletionFixture.simple("second answer"))))
      .continueConversationWithEvents(state1, "two", onEvent = buffer += _)
      .value

    types(buffer.toSeq) shouldBe
      Seq("AgentStarted", "StepStarted", "TextDelta", "TextComplete", "StepCompleted", "AgentCompleted")
    buffer.collectFirst { case e: AgentEvent.AgentStarted => e.query }.value shouldBe "two"
    state2.conversation.messages.map(_.content).filter(_.nonEmpty).takeRight(4) shouldBe
      Seq("one", "first answer", "two", "second answer")
  }

  it should "refuse to continue an unfinished run, returning ValidationError without emitting any event" in {
    val inProgress = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )
    val client = new RecordingStreamClient(Seq(CompletionFixture.simple("unused")))
    val buffer = ListBuffer[AgentEvent]()

    val result = new Agent(client).continueConversationWithEvents(inProgress, "more", onEvent = buffer += _)

    result.left.value shouldBe a[ValidationError]
    client.calls shouldBe 0
    buffer.toSeq shouldBe empty
  }
}
