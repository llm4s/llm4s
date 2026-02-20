package org.llm4s.agent

import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.trace.Tracing
import org.llm4s.trace.TraceEvent
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Focused tests that verify Agent integrates with the Tracing API
 * without changing core control flow.
 */
class AgentTracingSpec extends AnyFlatSpec with Matchers {

  // Simple recording tracer for assertions
  private class RecordingTracing extends Tracing {
    var completions: Vector[Completion]                   = Vector.empty
    var tokenUsages: Vector[(TokenUsage, String, String)] = Vector.empty
    var toolCalls: Vector[(String, String, String)]       = Vector.empty
    var states: Vector[AgentState]                        = Vector.empty
    var errors: Vector[(Throwable, String)]               = Vector.empty
    var events: Vector[TraceEvent]                        = Vector.empty

    override def traceEvent(event: TraceEvent): Result[Unit] = {
      events = events :+ event
      Right(())
    }

    override def traceAgentState(state: AgentState): Result[Unit] = {
      states = states :+ state
      Right(())
    }

    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
      toolCalls = toolCalls :+ ((toolName, input, output))
      Right(())
    }

    override def traceError(error: Throwable, context: String): Result[Unit] = {
      errors = errors :+ ((error, context))
      Right(())
    }

    override def traceCompletion(completion: Completion, model: String): Result[Unit] = {
      completions = completions :+ completion
      Right(())
    }

    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
      tokenUsages = tokenUsages :+ ((usage, model, operation))
      Right(())
    }
  }

  /** Tracer that returns Left from all methods; used to verify safeTrace swallows failures. */
  private class FailingTracing extends Tracing {
    override def traceEvent(event: TraceEvent): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))

    override def traceAgentState(state: AgentState): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))

    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))

    override def traceError(error: Throwable, context: String): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))

    override def traceCompletion(completion: Completion, model: String): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))

    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] =
      Left(ValidationError.invalid("tracing", "test failure"))
  }

  /** Tracer where only completion tracing fails with a non-Throwable error. */
  private class CompletionErrorOnlyTracing extends Tracing {
    override def traceEvent(event: TraceEvent): Result[Unit] = Right(())

    override def traceAgentState(state: AgentState): Result[Unit] = Right(())

    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())

    override def traceError(error: Throwable, context: String): Result[Unit] = Right(())

    override def traceCompletion(completion: Completion, model: String): Result[Unit] =
      Left(ValidationError.invalid("tracing", "boom"))

    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  /** Tracer where completion tracing fails with an UnknownError wrapping a Throwable. */
  private class ThrowableCompletionTracing extends Tracing {
    override def traceEvent(event: TraceEvent): Result[Unit] = Right(())

    override def traceAgentState(state: AgentState): Result[Unit] = Right(())

    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())

    override def traceError(error: Throwable, context: String): Result[Unit] = Right(())

    override def traceCompletion(completion: Completion, model: String): Result[Unit] =
      Left(org.llm4s.error.UnknownError("boom", new RuntimeException("boom")))

    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  // Minimal LLM client that returns a single configured completion
  private class StubLLMClient(response: Result[Completion]) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      response

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      response

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // Multi-response LLM client for tests requiring different responses per call
  private class MultiResponseStubLLMClient(responses: Seq[Result[Completion]]) extends LLMClient {
    private var callIndex = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val idx = callIndex.min(responses.size - 1)
      callIndex += 1
      responses(idx)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val result = complete(conversation, options)
      result.foreach(c => if (c.content.nonEmpty) onChunk(StreamedChunk(id = c.id, content = Some(c.content))))
      result
    }

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private case class CalculatorResult(result: Double)
  private object CalculatorResult {
    implicit val rw: ReadWriter[CalculatorResult] = macroRW
  }

  private def createCalculatorTool(): Result[ToolFunction[Map[String, Any], CalculatorResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Calculator parameters")
      .withRequiredField("a", Schema.number("First number"))
      .withRequiredField("b", Schema.number("Second number"))

    ToolBuilder[Map[String, Any], CalculatorResult](
      "calculator",
      "Performs basic arithmetic",
      schema
    ).withHandler { extractor =>
      for {
        a <- extractor.getDouble("a")
        b <- extractor.getDouble("b")
      } yield CalculatorResult(a + b)
    }.buildSafe()
  }

  private def createCompletion(
    content: String,
    toolCalls: Seq[ToolCall] = Seq.empty
  ): Completion = {
    val message = AssistantMessage(content, toolCalls)
    Completion(
      id = "test-completion",
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = message,
      toolCalls = toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  private def createToolCall(name: String, arguments: String, id: String = "call_123"): ToolCall =
    ToolCall(id = id, name = name, arguments = ujson.read(arguments))

  "Agent.run" should "trace completion, token usage and agent state" in {
    val completion = createCompletion("Hello, world!")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)

    val tools   = new ToolRegistry(Seq.empty)
    val tracing = new RecordingTracing()
    val result  = agent.run("test query", tools, context = AgentContext(tracing = Some(tracing), debug = true))

    result.isRight shouldBe true
    tracing.completions should have size 1
    tracing.tokenUsages should have size 1
    tracing.states.nonEmpty shouldBe true
  }

  it should "trace tool executions when tools are called" in {
    val toolCall = createToolCall("calculator", """{"a": 1, "b": 2}""")
    val completionWithTool = createCompletion(
      content = "",
      toolCalls = Seq(toolCall)
    )

    val client  = new StubLLMClient(Right(completionWithTool))
    val agent   = new Agent(client)
    val tracing = new RecordingTracing()

    val result = for {
      tool <- createCalculatorTool()
      tools = new ToolRegistry(Seq(tool))
      state <- agent.run("use calculator", tools, context = AgentContext(tracing = Some(tracing), debug = true))
    } yield state

    result.fold(
      e => fail(s"Test failed: ${e.formatted}"),
      _ => tracing.toolCalls.nonEmpty shouldBe true
    )
  }

  it should "trace error when LLM completion fails" in {
    val client  = new StubLLMClient(Left(ValidationError.invalid("api", "test failure")))
    val agent   = new Agent(client)
    val tracing = new RecordingTracing()

    val result = agent.run("test", ToolRegistry.empty, context = AgentContext(tracing = Some(tracing), debug = true))

    result.isLeft shouldBe true
    tracing.errors.nonEmpty shouldBe true
    tracing.errors.head._2 shouldBe "agent_completion"
  }

  it should "swallow non-throwable tracing errors in safeTrace" in {
    val completion = createCompletion("Hello, world!")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)
    val tracing    = new CompletionErrorOnlyTracing()

    val result = agent.run("test query", ToolRegistry.empty, context = AgentContext(tracing = Some(tracing)))

    result.isRight shouldBe true
  }

  it should "swallow throwable-wrapped tracing errors in safeTrace" in {
    val completion = createCompletion("Hello, world!")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)
    val tracing    = new ThrowableCompletionTracing()

    val result = agent.run("test query", ToolRegistry.empty, context = AgentContext(tracing = Some(tracing)))

    result.isRight shouldBe true
  }

  it should "succeed when tracer fails (safeTrace swallows errors)" in {
    val completion = createCompletion("Hello")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)
    val failing    = new FailingTracing()

    val result = agent.run("test", ToolRegistry.empty, context = AgentContext(tracing = Some(failing)))

    result.isRight shouldBe true
  }

  it should "trace completion, token usage and state on streaming path" in {
    val completion = createCompletion("Streamed response")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)
    val tracing    = new RecordingTracing()

    val result = agent.runWithEvents(
      query = "test",
      tools = ToolRegistry.empty,
      onEvent = _ => (),
      context = AgentContext(tracing = Some(tracing))
    )

    result.isRight shouldBe true
    tracing.completions should have size 1
    tracing.tokenUsages should have size 1
    tracing.states.nonEmpty shouldBe true
  }

  it should "trace streaming error path when LLM streaming fails" in {
    val client  = new StubLLMClient(Left(ValidationError.invalid("api", "stream failure")))
    val agent   = new Agent(client)
    val tracing = new RecordingTracing()

    val result = agent.runWithEvents(
      query = "test",
      tools = ToolRegistry.empty,
      onEvent = _ => (),
      context = AgentContext(tracing = Some(tracing))
    )

    result.isLeft shouldBe true
    tracing.errors.nonEmpty shouldBe true
    tracing.errors.head._2 shouldBe "agent_stream_completion"
  }

  it should "trace tool call when tool returns error" in {
    val failingToolResult = ToolBuilder[Map[String, Any], Unit](
      "failing_tool",
      "Tool that returns error",
      Schema.`object`[Map[String, Any]]("args")
    ).withHandler(_ => Left("intentional"))
      .buildSafe()

    val toolCall           = createToolCall("failing_tool", "{}")
    val completionWithTool = createCompletion(content = "", toolCalls = Seq(toolCall))
    val completionText     = createCompletion("Done")
    val client             = new MultiResponseStubLLMClient(Seq(Right(completionWithTool), Right(completionText)))
    val agent              = new Agent(client)
    val tracing            = new RecordingTracing()

    val result = for {
      failingTool <- failingToolResult
      tools = new ToolRegistry(Seq(failingTool))
      state <- agent.run("use failing tool", tools, context = AgentContext(tracing = Some(tracing), debug = true))
    } yield state

    result.fold(
      e => fail(s"Test failed: ${e.formatted}"),
      _ => {
        tracing.toolCalls should have size 1
        tracing.toolCalls.head._3 should include("intentional")
      }
    )
  }

  it should "trace error when tool throws" in {
    val throwingToolResult = ToolBuilder[Map[String, Any], Unit](
      "throwing_tool",
      "Tool that throws",
      Schema.`object`[Map[String, Any]]("args")
    ).withHandler(_ => throw new RuntimeException("intentional throw"))
      .buildSafe()

    val toolCall           = createToolCall("throwing_tool", "{}")
    val completionWithTool = createCompletion(content = "", toolCalls = Seq(toolCall))
    val completionText     = createCompletion("Done")
    val client             = new MultiResponseStubLLMClient(Seq(Right(completionWithTool), Right(completionText)))
    val agent              = new Agent(client)
    val tracing            = new RecordingTracing()

    val result = for {
      throwingTool <- throwingToolResult
      tools = new ToolRegistry(Seq(throwingTool))
      state <- agent.run("use throwing tool", tools, context = AgentContext(tracing = Some(tracing), debug = true))
    } yield state

    result.fold(
      e => fail(s"Test failed: ${e.formatted}"),
      _ => tracing.toolCalls.nonEmpty shouldBe true
    )
  }

  it should "trace unexpected tool processing failures when processToolCalls throws" in {
    // Tool registry that throws from execute, causing processToolCalls to throw inside runStep
    val throwingRegistry = new ToolRegistry(Seq.empty) {
      override def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
        throw new RuntimeException("registry failure")
    }

    val toolCall         = createToolCall("throwing_tool", "{}")
    val assistantMessage = AssistantMessage("", Seq(toolCall))
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("user"), assistantMessage)),
      tools = throwingRegistry,
      status = AgentStatus.WaitingForTools
    )

    val client  = new StubLLMClient(Right(createCompletion("ignored")))
    val agent   = new Agent(client)
    val tracing = new RecordingTracing()

    val result = agent.runStep(state, context = AgentContext(tracing = Some(tracing), debug = true))

    result.isRight shouldBe true
    tracing.errors.nonEmpty shouldBe true
    tracing.errors.head._2 shouldBe "agent_tool_execution"
  }

  it should "propagate tracing across handoff with debug enabled" in {
    val clientB     = new StubLLMClient(Right(createCompletion("Specialist response")))
    val agentB      = new Agent(clientB)
    val handoff     = Handoff(agentB, Some("delegate"))
    val toolCall    = createToolCall(handoff.handoffId, """{"reason":"delegate"}""")
    val completionA = createCompletion(content = "", toolCalls = Seq(toolCall))

    val clientA = new StubLLMClient(Right(completionA))
    val agentA  = new Agent(clientA)
    val tracing = new RecordingTracing()

    val result = agentA.run(
      query = "hand off",
      tools = ToolRegistry.empty,
      handoffs = Seq(handoff),
      context = AgentContext(tracing = Some(tracing), debug = true)
    )

    result.isRight shouldBe true
    tracing.completions should have size 2
    tracing.states.nonEmpty shouldBe true
  }

  it should "emit HandoffStarted and HandoffCompleted events" in {
    val clientB     = new StubLLMClient(Right(createCompletion("Specialist response")))
    val agentB      = new Agent(clientB)
    val handoff     = Handoff(agentB, Some("delegate"))
    val toolCall    = createToolCall(handoff.handoffId, """{"reason":"delegate"}""")
    val completionA = createCompletion(content = "", toolCalls = Seq(toolCall))

    val clientA = new StubLLMClient(Right(completionA))
    val agentA  = new Agent(clientA)
    val tracing = new RecordingTracing()
    var events  = List.empty[AgentEvent]

    val result = agentA.runWithEvents(
      query = "hand off",
      tools = ToolRegistry.empty,
      onEvent = e => events = events :+ e,
      handoffs = Seq(handoff),
      context = AgentContext(tracing = Some(tracing), debug = true)
    )

    result.isRight shouldBe true
    events.exists(_.isInstanceOf[AgentEvent.HandoffStarted]) shouldBe true
    events.exists(_.isInstanceOf[AgentEvent.HandoffCompleted]) shouldBe true
  }

  "Agent.runWithStrategy" should "execute runWithStrategyInternal recursion path" in {
    val completion = createCompletion("strategy response")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)
    val tools      = ToolRegistry.empty
    val tracing    = new RecordingTracing()

    val result = agent.runWithStrategy(
      query = "test strategy",
      tools = tools,
      toolExecutionStrategy = ToolExecutionStrategy.Sequential,
      context = AgentContext(tracing = Some(tracing), debug = true)
    )

    result.isRight shouldBe true
    tracing.states.nonEmpty shouldBe true
  }

  it should "handle LLM failure in runWithStrategyInternal" in {
    val client  = new StubLLMClient(Left(ValidationError.invalid("api", "boom")))
    val agent   = new Agent(client)
    val tools   = ToolRegistry.empty
    val tracing = new RecordingTracing()

    val result = agent.runWithStrategy(
      query = "test strategy",
      tools = tools,
      toolExecutionStrategy = ToolExecutionStrategy.Sequential,
      context = AgentContext(tracing = Some(tracing), debug = true)
    )

    result.isLeft shouldBe true
    tracing.errors.nonEmpty shouldBe true
    tracing.errors.head._2 shouldBe "agent_completion"
  }
}
