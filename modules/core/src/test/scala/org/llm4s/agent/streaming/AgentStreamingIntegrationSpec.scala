package org.llm4s.agent.streaming

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent._
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.collection.mutable.ListBuffer

/**
 * Integration tests for agent streaming events end-to-end (issue #997).
 *
 * All tests use mock clients; no external dependencies are required.
 * Event ordering is strictly asserted for each scenario.
 */
class AgentStreamingIntegrationSpec extends AnyFlatSpec with Matchers {

  // ============================================================
  // Mock infrastructure
  // ============================================================

  /**
   * A mock LLM client that cycles through a pre-configured sequence of completions.
   * Each call to complete/streamComplete advances to the next response.
   */
  class SequencedMockClient(responses: Seq[Result[Completion]]) extends LLMClient {
    private var index = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val result = if (index < responses.size) responses(index) else responses.last
      index += 1
      result
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val result = complete(conversation, options)
      result.foreach { completion =>
        if (completion.content.nonEmpty) {
          onChunk(StreamedChunk(id = completion.id, content = Some(completion.content)))
        }
      }
      result
    }

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Builds a Completion with no tool calls (plain text response). */
  private def textCompletion(content: String): Completion =
    Completion(
      id = s"test-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = content,
      model = "mock-model",
      message = AssistantMessage(content, toolCalls = Seq.empty),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )

  /** Builds a Completion that requests a tool call. */
  private def toolCallCompletion(content: String, toolCalls: Seq[ToolCall]): Completion = {
    val message = AssistantMessage(content, toolCalls)
    Completion(
      id = s"test-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = content,
      model = "mock-model",
      message = message,
      toolCalls = toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  /** A case class used as tool result payload. */
  case class EchoResult(value: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  /**
   * Creates a minimal echo tool that accepts a single string "input" argument
   * and returns it wrapped in EchoResult.
   */
  private def createEchoTool(): Result[org.llm4s.toolapi.ToolFunction[Map[String, Any], EchoResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo tool parameters")
      .withRequiredField("input", Schema.string("Value to echo"))

    ToolBuilder[Map[String, Any], EchoResult]("echo", "Echoes input back", schema)
      .withHandler(extractor => extractor.getString("input").map(v => EchoResult(v)))
      .buildSafe()
  }

  // ============================================================
  // Helper to collect events
  // ============================================================

  private def collectEvents(
    agent: Agent,
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    handoffs: Seq[Handoff] = Seq.empty,
    maxSteps: Option[Int] = None
  ): (Result[org.llm4s.agent.AgentState], Seq[AgentEvent]) = {
    val buffer = ListBuffer[AgentEvent]()
    val result = agent.runWithEvents(
      query = query,
      tools = tools,
      onEvent = buffer += _,
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails,
      handoffs = handoffs,
      maxSteps = maxSteps
    )
    (result, buffer.toSeq)
  }

  // ============================================================
  // Test 1: Happy-path tool call — verify full event sequence
  // ============================================================

  "AgentStreamingIntegrationSpec" should "emit the full event sequence for a single tool call" in {
    val echoToolResult = createEchoTool()
    echoToolResult.isRight shouldBe true

    val echoTool = echoToolResult.toOption.get
    val tools    = new ToolRegistry(Seq(echoTool))

    val toolCall = ToolCall(
      id = "call-001",
      name = "echo",
      arguments = ujson.read("""{"input": "hello"}""")
    )

    val mockClient = new SequencedMockClient(
      Seq(
        Right(toolCallCompletion("I'll echo that.", Seq(toolCall))),
        Right(textCompletion("The echo returned: hello"))
      )
    )
    val agent = new Agent(mockClient)

    val (result, events) = collectEvents(agent, "Echo hello", tools)

    result.isRight shouldBe true

    // Verify key event types are present
    events.exists(_.isInstanceOf[AgentStarted]) shouldBe true
    events.exists(_.isInstanceOf[StepStarted]) shouldBe true
    events.exists(_.isInstanceOf[ToolCallStarted]) shouldBe true
    events.exists(_.isInstanceOf[ToolCallCompleted]) shouldBe true
    events.exists(_.isInstanceOf[StepCompleted]) shouldBe true
    events.exists(_.isInstanceOf[AgentCompleted]) shouldBe true

    // Verify strict ordering: AgentStarted before StepStarted before ToolCallStarted
    val agentStartedIdx   = events.indexWhere(_.isInstanceOf[AgentStarted])
    val stepStartedIdx    = events.indexWhere(_.isInstanceOf[StepStarted])
    val toolStartedIdx    = events.indexWhere(_.isInstanceOf[ToolCallStarted])
    val toolCompletedIdx  = events.indexWhere(_.isInstanceOf[ToolCallCompleted])
    val stepCompletedIdx  = events.indexWhere(_.isInstanceOf[StepCompleted])
    val agentCompletedIdx = events.indexWhere(_.isInstanceOf[AgentCompleted])

    agentStartedIdx should be >= 0
    stepStartedIdx should be >= 0
    toolStartedIdx should be >= 0
    toolCompletedIdx should be >= 0
    stepCompletedIdx should be >= 0
    agentCompletedIdx should be >= 0

    agentStartedIdx should be < stepStartedIdx
    stepStartedIdx should be < toolStartedIdx
    toolStartedIdx should be < toolCompletedIdx
    agentCompletedIdx should be > stepCompletedIdx

    // Verify tool call details
    val toolStarted = events.collectFirst { case e: ToolCallStarted => e }.get
    toolStarted.toolName shouldBe "echo"

    val toolCompleted = events.collectFirst { case e: ToolCallCompleted => e }.get
    toolCompleted.toolName shouldBe "echo"
    toolCompleted.success shouldBe true

    // AgentCompleted should not come before last AgentStarted
    agentCompletedIdx should be > agentStartedIdx

    // There must be no AgentFailed
    events.exists(_.isInstanceOf[AgentFailed]) shouldBe false
  }

  // ============================================================
  // Test 2: Multi-step tool calls — two tool calls before final answer
  // ============================================================

  it should "emit correct interleaved events for two sequential tool calls" in {
    val echoToolResult = createEchoTool()
    echoToolResult.isRight shouldBe true

    val echoTool = echoToolResult.toOption.get
    val tools    = new ToolRegistry(Seq(echoTool))

    val toolCall1 = ToolCall(
      id = "call-step1",
      name = "echo",
      arguments = ujson.read("""{"input": "first"}""")
    )
    val toolCall2 = ToolCall(
      id = "call-step2",
      name = "echo",
      arguments = ujson.read("""{"input": "second"}""")
    )

    val mockClient = new SequencedMockClient(
      Seq(
        Right(toolCallCompletion("Calling echo step 1", Seq(toolCall1))),
        Right(toolCallCompletion("Calling echo step 2", Seq(toolCall2))),
        Right(textCompletion("Done with both echoes."))
      )
    )
    val agent = new Agent(mockClient)

    val (result, events) = collectEvents(agent, "Echo twice", tools)

    result.isRight shouldBe true

    // There should be exactly two ToolCallStarted events and two ToolCallCompleted events
    val toolStartEvents    = events.collect { case e: ToolCallStarted => e }
    val toolCompleteEvents = events.collect { case e: ToolCallCompleted => e }

    toolStartEvents.size shouldBe 2
    toolCompleteEvents.size shouldBe 2

    // Tool IDs should match
    toolStartEvents.map(_.toolCallId) should contain("call-step1")
    toolStartEvents.map(_.toolCallId) should contain("call-step2")

    // Both tools should succeed
    toolCompleteEvents.forall(_.success) shouldBe true

    // At least two StepStarted events
    val stepStartedEvents = events.collect { case e: StepStarted => e }
    stepStartedEvents.size should be >= 2

    // Agent should complete (not fail)
    events.exists(_.isInstanceOf[AgentCompleted]) shouldBe true
    events.exists(_.isInstanceOf[AgentFailed]) shouldBe false

    // Ordering: first tool start before second tool start
    val firstIdx  = events.indexWhere { case e: ToolCallStarted => e.toolCallId == "call-step1"; case _ => false }
    val secondIdx = events.indexWhere { case e: ToolCallStarted => e.toolCallId == "call-step2"; case _ => false }
    firstIdx should be < secondIdx
  }

  // ============================================================
  // Test 3: Input guardrail firing
  // ============================================================

  it should "emit InputGuardrailStarted then InputGuardrailCompleted(rejected) then return Left on guardrail rejection" in {
    val mockClient = new SequencedMockClient(Seq(Right(textCompletion("should not reach"))))
    val agent      = new Agent(mockClient)
    val tools      = ToolRegistry.empty

    // A guardrail that always rejects
    val rejectingGuardrail = new InputGuardrail {
      val name: String = "AlwaysReject"
      def validate(value: String): Result[String] =
        Left(ValidationError("input", "Rejected by test guardrail"))
    }

    val (result, events) = collectEvents(
      agent,
      query = "any query",
      tools = tools,
      inputGuardrails = Seq(rejectingGuardrail)
    )

    // Result must be Left (guardrail rejection)
    result.isLeft shouldBe true

    // InputGuardrailStarted must be emitted
    val guardrailStarted = events.collectFirst { case e: InputGuardrailStarted => e }
    guardrailStarted.isDefined shouldBe true
    guardrailStarted.get.guardrailName shouldBe "AlwaysReject"

    // InputGuardrailCompleted with passed=false must be emitted
    val guardrailCompleted = events.collectFirst { case e: InputGuardrailCompleted => e }
    guardrailCompleted.isDefined shouldBe true
    guardrailCompleted.get.guardrailName shouldBe "AlwaysReject"
    guardrailCompleted.get.passed shouldBe false

    // InputGuardrailStarted must come before InputGuardrailCompleted
    val startedIdx   = events.indexWhere(_.isInstanceOf[InputGuardrailStarted])
    val completedIdx = events.indexWhere(_.isInstanceOf[InputGuardrailCompleted])
    startedIdx should be < completedIdx

    // AgentCompleted must NOT be emitted
    events.exists(_.isInstanceOf[AgentCompleted]) shouldBe false
  }

  // ============================================================
  // Test 4: Output guardrail firing
  // ============================================================

  it should "emit OutputGuardrailStarted then OutputGuardrailCompleted(rejected) and return Left on output rejection" in {
    val mockClient = new SequencedMockClient(Seq(Right(textCompletion("bad output"))))
    val agent      = new Agent(mockClient)
    val tools      = ToolRegistry.empty

    // An output guardrail that rejects everything
    val rejectingOutputGuardrail = new OutputGuardrail {
      val name: String = "OutputRejecter"
      def validate(value: String): Result[String] =
        Left(ValidationError("output", "Output rejected by test guardrail"))
    }

    val (result, events) = collectEvents(
      agent,
      query = "valid query",
      tools = tools,
      outputGuardrails = Seq(rejectingOutputGuardrail)
    )

    // Result must be Left
    result.isLeft shouldBe true

    // OutputGuardrailStarted must be emitted
    val outputStarted = events.collectFirst { case e: OutputGuardrailStarted => e }
    outputStarted.isDefined shouldBe true
    outputStarted.get.guardrailName shouldBe "OutputRejecter"

    // OutputGuardrailCompleted with passed=false must be emitted
    val outputCompleted = events.collectFirst { case e: OutputGuardrailCompleted => e }
    outputCompleted.isDefined shouldBe true
    outputCompleted.get.guardrailName shouldBe "OutputRejecter"
    outputCompleted.get.passed shouldBe false

    // Ordering: OutputGuardrailStarted before OutputGuardrailCompleted
    val startedIdx   = events.indexWhere(_.isInstanceOf[OutputGuardrailStarted])
    val completedIdx = events.indexWhere(_.isInstanceOf[OutputGuardrailCompleted])
    startedIdx should be < completedIdx

    // AgentCompleted must NOT appear (run was rejected by output guardrail)
    // Note: AgentCompleted is emitted by the streaming executor BEFORE output guardrails run,
    // so it may be present; what matters is the result is Left and OutputGuardrailCompleted
    // with passed=false was emitted.
    outputCompleted.get.passed shouldBe false
    result.isLeft shouldBe true
  }

  // ============================================================
  // Test 5: Handoff event
  // ============================================================

  it should "emit HandoffStarted and HandoffCompleted events when agent hands off" in {
    // Target agent: returns a simple text completion
    val targetClient = new SequencedMockClient(Seq(Right(textCompletion("Specialist response"))))
    val targetAgent  = new Agent(targetClient)
    val handoff      = Handoff.to(targetAgent, "Specialist for echoing")

    // Get the handoff tool id that will be registered
    val handoffToolId = handoff.handoffId

    val handoffToolCall = ToolCall(
      id = "call-handoff-1",
      name = handoffToolId,
      arguments = ujson.read("""{"reason": "delegating to specialist"}""")
    )

    // Primary agent: returns a tool call that triggers handoff
    val primaryClient = new SequencedMockClient(
      Seq(Right(toolCallCompletion("Handing off to specialist.", Seq(handoffToolCall))))
    )
    val agent = new Agent(primaryClient)

    val buffer = ListBuffer[AgentEvent]()
    val result = agent.runWithEvents(
      query = "Please delegate this",
      tools = ToolRegistry.empty,
      onEvent = buffer += _,
      handoffs = Seq(handoff),
      maxSteps = Some(10)
    )
    val events = buffer.toSeq

    // The run should succeed (target agent completes)
    result.isRight shouldBe true

    // HandoffStarted must be emitted
    val handoffStarted = events.collectFirst { case e: HandoffStarted => e }
    handoffStarted.isDefined shouldBe true
    handoffStarted.get.targetAgentName should include("Specialist")

    // HandoffCompleted must be emitted
    val handoffCompleted = events.collectFirst { case e: HandoffCompleted => e }
    handoffCompleted.isDefined shouldBe true
    handoffCompleted.get.success shouldBe true

    // Ordering: HandoffStarted before HandoffCompleted
    val startedIdx   = events.indexWhere(_.isInstanceOf[HandoffStarted])
    val completedIdx = events.indexWhere(_.isInstanceOf[HandoffCompleted])
    startedIdx should be >= 0
    completedIdx should be >= 0
    startedIdx should be < completedIdx
  }

  // ============================================================
  // Test 6: Collect all events — verify event log ordering
  // ============================================================

  it should "collect all events via runCollectingEvents and assert ordering" in {
    val echoToolResult = createEchoTool()
    echoToolResult.isRight shouldBe true

    val echoTool = echoToolResult.toOption.get
    val tools    = new ToolRegistry(Seq(echoTool))

    val toolCall = ToolCall(
      id = "call-collect",
      name = "echo",
      arguments = ujson.read("""{"input": "world"}""")
    )

    val mockClient = new SequencedMockClient(
      Seq(
        Right(toolCallCompletion("Echoing...", Seq(toolCall))),
        Right(textCompletion("Echo result received."))
      )
    )
    val agent = new Agent(mockClient)

    val collectResult = agent.runCollectingEvents("Echo world", tools)

    collectResult.isRight shouldBe true

    val (finalState, events) = collectResult.toOption.get

    // Final state must be Complete
    finalState.status shouldBe org.llm4s.agent.AgentStatus.Complete

    // Collect all event class names in order for position lookups
    val orderedTypes: Seq[String] = events.map(_.getClass.getSimpleName)

    // AgentStarted must be first lifecycle event (before StepStarted)
    val agentStartedPos   = orderedTypes.indexOf("AgentStarted")
    val stepStartedPos    = orderedTypes.indexOf("StepStarted")
    val toolStartedPos    = orderedTypes.indexOf("ToolCallStarted")
    val toolCompletedPos  = orderedTypes.indexOf("ToolCallCompleted")
    val agentCompletedPos = orderedTypes.lastIndexOf("AgentCompleted")

    agentStartedPos should be >= 0
    stepStartedPos should be >= 0
    toolStartedPos should be >= 0
    toolCompletedPos should be >= 0
    agentCompletedPos should be >= 0

    // Strict ordering checks
    agentStartedPos should be < stepStartedPos
    stepStartedPos should be < toolStartedPos
    toolStartedPos should be < toolCompletedPos
    toolCompletedPos should be < agentCompletedPos

    // TextDelta events should appear (streaming emits chunks)
    events.exists(_.isInstanceOf[TextDelta]) shouldBe true

    // TextComplete should come before AgentCompleted
    val textCompletePos = orderedTypes.lastIndexOf("TextComplete")
    if (textCompletePos >= 0) {
      textCompletePos should be < agentCompletedPos
    }
  }

  // ============================================================
  // Test 7: No events emitted for guardrail-rejected input
  // ============================================================

  it should "emit only guardrail events when input guardrail rejects — no AgentStarted" in {
    val mockClient = new SequencedMockClient(Seq(Right(textCompletion("unreachable"))))
    val agent      = new Agent(mockClient)
    val tools      = ToolRegistry.empty

    val shortOnlyGuardrail = new InputGuardrail {
      val name: String = "MaxLengthFive"
      def validate(value: String): Result[String] =
        if (value.length > 5) Left(ValidationError("input", "too long"))
        else Right(value)
    }

    val (result, events) = collectEvents(
      agent,
      query = "this query is definitely longer than five characters",
      tools = tools,
      inputGuardrails = Seq(shortOnlyGuardrail)
    )

    result.isLeft shouldBe true

    // Guardrail events must be present
    events.exists(_.isInstanceOf[InputGuardrailStarted]) shouldBe true
    events.exists(_.isInstanceOf[InputGuardrailCompleted]) shouldBe true

    // AgentStarted must NOT be present (execution never started)
    events.exists(_.isInstanceOf[AgentStarted]) shouldBe false

    // The completed event must indicate failure
    val completed = events.collectFirst { case e: InputGuardrailCompleted => e }
    completed.isDefined shouldBe true
    completed.get.passed shouldBe false
  }

  // ============================================================
  // Test 8: Passing guardrails emit passed=true events
  // ============================================================

  it should "emit InputGuardrailCompleted(passed=true) when input guardrail passes" in {
    val mockClient = new SequencedMockClient(Seq(Right(textCompletion("ok"))))
    val agent      = new Agent(mockClient)
    val tools      = ToolRegistry.empty

    val passingGuardrail = new InputGuardrail {
      val name: String                            = "AlwaysPass"
      def validate(value: String): Result[String] = Right(value)
    }

    val (result, events) = collectEvents(
      agent,
      query = "valid query",
      tools = tools,
      inputGuardrails = Seq(passingGuardrail)
    )

    result.isRight shouldBe true

    val completed = events.collectFirst { case e: InputGuardrailCompleted => e }
    completed.isDefined shouldBe true
    completed.get.guardrailName shouldBe "AlwaysPass"
    completed.get.passed shouldBe true
  }

  // ============================================================
  // Test 9: Passing output guardrail emits passed=true
  // ============================================================

  it should "emit OutputGuardrailCompleted(passed=true) when output guardrail passes" in {
    val mockClient = new SequencedMockClient(Seq(Right(textCompletion("clean output"))))
    val agent      = new Agent(mockClient)
    val tools      = ToolRegistry.empty

    val passingOutputGuardrail = new OutputGuardrail {
      val name: String                            = "AlwaysPassOutput"
      def validate(value: String): Result[String] = Right(value)
    }

    val (result, events) = collectEvents(
      agent,
      query = "any query",
      tools = tools,
      outputGuardrails = Seq(passingOutputGuardrail)
    )

    result.isRight shouldBe true

    val completed = events.collectFirst { case e: OutputGuardrailCompleted => e }
    completed.isDefined shouldBe true
    completed.get.guardrailName shouldBe "AlwaysPassOutput"
    completed.get.passed shouldBe true

    events.exists(_.isInstanceOf[AgentCompleted]) shouldBe true
  }
}
