package org.llm4s.agent.streaming

import org.llm4s.agent.{ Agent, AgentState, AgentStatus, CompletionFixture, Handoff, NTurnFakeLLMClient }
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

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
}
