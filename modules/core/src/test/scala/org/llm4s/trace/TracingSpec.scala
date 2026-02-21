package org.llm4s.trace

import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * Tests for Tracing trait and implementations
 */
class TracingSpec extends AnyFlatSpec with Matchers {

  // ============ TracingMode ============

  "TracingMode.fromString" should "parse langfuse mode" in {
    TracingMode.fromString("langfuse") shouldBe TracingMode.Langfuse
    TracingMode.fromString("LANGFUSE") shouldBe TracingMode.Langfuse
    TracingMode.fromString("Langfuse") shouldBe TracingMode.Langfuse
  }

  it should "parse console mode" in {
    TracingMode.fromString("console") shouldBe TracingMode.Console
    TracingMode.fromString("CONSOLE") shouldBe TracingMode.Console
    TracingMode.fromString("print") shouldBe TracingMode.Console
    TracingMode.fromString("PRINT") shouldBe TracingMode.Console
  }

  it should "parse noop mode" in {
    TracingMode.fromString("noop") shouldBe TracingMode.NoOp
    TracingMode.fromString("NOOP") shouldBe TracingMode.NoOp
    TracingMode.fromString("none") shouldBe TracingMode.NoOp
    TracingMode.fromString("NONE") shouldBe TracingMode.NoOp
  }

  it should "default to NoOp for unknown modes" in {
    TracingMode.fromString("unknown") shouldBe TracingMode.NoOp
    TracingMode.fromString("") shouldBe TracingMode.NoOp
    TracingMode.fromString("invalid") shouldBe TracingMode.NoOp
  }

  // ============ NoOpTracing ============

  "NoOpTracing" should "return success for all operations" in {
    val tracing = new NoOpTracing()

    tracing.traceEvent(TraceEvent.CustomEvent("test", ujson.Obj())) shouldBe Right(())
    tracing.traceAgentState(createTestAgentState()) shouldBe Right(())
    tracing.traceToolCall("tool", "input", "output") shouldBe Right(())
    tracing.traceError(new RuntimeException("test")) shouldBe Right(())
    tracing.traceCompletion(createTestCompletion(), "gpt-4") shouldBe Right(())
    tracing.traceTokenUsage(TokenUsage(100, 50, 150), "gpt-4", "completion") shouldBe Right(())
  }

  it should "support convenience methods" in {
    val tracing = new NoOpTracing()

    tracing.traceEvent("custom event string") shouldBe Right(())
    tracing.traceEmbeddingUsage(EmbeddingUsage(500, 500), "text-embedding-3-small", "indexing", 10) shouldBe Right(())
    tracing.traceCost(0.005, "gpt-4", "completion", 1000, "total") shouldBe Right(())
    tracing.traceRAGOperation("search", 150L) shouldBe Right(())
  }

  // ============ ConsoleTracing ============

  "ConsoleTracing" should "return success for all operations" in {
    val tracing = new ConsoleTracing()

    // These will print to console but should return success
    tracing.traceEvent(TraceEvent.CustomEvent("test", ujson.Obj())).isRight shouldBe true
    tracing.traceToolCall("tool", "input", "output").isRight shouldBe true
    tracing.traceError(new RuntimeException("test"), "context").isRight shouldBe true
  }

  it should "handle agent state tracing" in {
    val tracing = new ConsoleTracing()
    val state   = createTestAgentState()

    tracing.traceAgentState(state).isRight shouldBe true
  }

  it should "handle completion tracing" in {
    val tracing    = new ConsoleTracing()
    val completion = createTestCompletion()

    tracing.traceCompletion(completion, "gpt-4").isRight shouldBe true
  }

  it should "handle token usage tracing" in {
    val tracing = new ConsoleTracing()
    val usage   = TokenUsage(100, 50, 150)

    tracing.traceTokenUsage(usage, "gpt-4", "completion").isRight shouldBe true
  }

  it should "trace all event types without error" in {
    val tracing = new ConsoleTracing()

    // Test each event type
    tracing.traceEvent(TraceEvent.AgentInitialized("query", Vector("tool1"))).isRight shouldBe true
    tracing.traceEvent(TraceEvent.CompletionReceived("id", "model", 0, "content")).isRight shouldBe true
    tracing.traceEvent(TraceEvent.ToolExecuted("tool", "in", "out", 100L, true)).isRight shouldBe true
    tracing.traceEvent(TraceEvent.ErrorOccurred(new Exception("e"), "ctx")).isRight shouldBe true
    tracing.traceEvent(TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), "m", "o")).isRight shouldBe true
    tracing.traceEvent(TraceEvent.AgentStateUpdated("running", 5, 10)).isRight shouldBe true
    tracing.traceEvent(TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(100, 100), "m", "o", 5)).isRight shouldBe true
    tracing.traceEvent(TraceEvent.CostRecorded(0.001, "m", "o", 100, "t")).isRight shouldBe true
    tracing.traceEvent(TraceEvent.RAGOperationCompleted("search", 150L)).isRight shouldBe true
  }

  // ============ Tracing.create ============

  "Tracing.create" should "create NoOp tracing for NoOp mode" in {
    val settings = TracingSettings(
      mode = TracingMode.NoOp,
      langfuse = LangfuseConfig()
    )

    val tracing = Tracing.create(settings)

    tracing shouldBe a[NoOpTracing]
  }

  it should "create Console tracing for Console mode" in {
    val settings = TracingSettings(
      mode = TracingMode.Console,
      langfuse = LangfuseConfig()
    )

    val tracing = Tracing.create(settings)

    tracing shouldBe a[ConsoleTracing]
  }

  it should "create Langfuse tracing for Langfuse mode" in {
    val settings = TracingSettings(
      mode = TracingMode.Langfuse,
      langfuse = LangfuseConfig(
        url = "https://api.langfuse.com",
        publicKey = Some("pk-test"),
        secretKey = Some("sk-test"),
        env = "test",
        release = "1.0.0",
        version = "1.0.0"
      )
    )

    val tracing = Tracing.create(settings)

    tracing shouldBe a[LangfuseTracing]
  }

  // ============ TracingComposer ============

  "TracingComposer.combine" should "combine multiple tracers" in {
    val events1 = mutable.Buffer.empty[TraceEvent]
    val events2 = mutable.Buffer.empty[TraceEvent]

    val tracer1 = new TestTracing(events1)
    val tracer2 = new TestTracing(events2)

    val combined = TracingComposer.combine(tracer1, tracer2)
    val event    = TraceEvent.CustomEvent("test", ujson.Obj())

    combined.traceEvent(event) shouldBe Right(())

    events1 should contain(event)
    events2 should contain(event)
  }

  it should "succeed if at least one tracer succeeds" in {
    val successEvents = mutable.Buffer.empty[TraceEvent]
    val successTracer = new TestTracing(successEvents)
    val failingTracer = new FailingTracing()

    val combined = TracingComposer.combine(successTracer, failingTracer)
    val event    = TraceEvent.CustomEvent("test", ujson.Obj())

    combined.traceEvent(event) shouldBe Right(())
    successEvents should contain(event)
  }

  it should "fail if all tracers fail" in {
    val failing1 = new FailingTracing()
    val failing2 = new FailingTracing()

    val combined = TracingComposer.combine(failing1, failing2)
    val event    = TraceEvent.CustomEvent("test", ujson.Obj())

    combined.traceEvent(event).isLeft shouldBe true
  }

  "TracingComposer.filter" should "only trace events matching predicate" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new TestTracing(events)
    val filtered = TracingComposer.filter(tracer)(_.eventType == "custom_event")

    val customEvent = TraceEvent.CustomEvent("test", ujson.Obj())
    val agentEvent  = TraceEvent.AgentInitialized("query", Vector.empty)

    filtered.traceEvent(customEvent) shouldBe Right(())
    filtered.traceEvent(agentEvent) shouldBe Right(())

    events should contain(customEvent)
    events should not contain agentEvent
  }

  it should "delegate other methods to underlying tracer" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new TestTracing(events)
    val filtered = TracingComposer.filter(tracer)(_ => true)

    filtered.traceToolCall("tool", "in", "out") shouldBe Right(())
    filtered.traceError(new Exception("e"), "ctx") shouldBe Right(())
  }

  "TracingComposer.transform" should "transform events before tracing" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new TestTracing(events)
    val transformed = TracingComposer.transform(tracer) {
      case e: TraceEvent.CustomEvent => e.copy(name = "transformed_" + e.name)
      case other                     => other
    }

    val event = TraceEvent.CustomEvent("original", ujson.Obj())
    transformed.traceEvent(event) shouldBe Right(())

    events.head match {
      case e: TraceEvent.CustomEvent => e.name shouldBe "transformed_original"
      case other                     => fail(s"Expected CustomEvent, got: $other")
    }
  }

  // ============ Convenience Methods ============

  "Tracing convenience methods" should "create correct events for traceEmbeddingUsage" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new TestTracing(events)
    val usage  = EmbeddingUsage(500, 500)

    tracer.traceEmbeddingUsage(usage, "text-embedding-3-small", "indexing", 10) shouldBe Right(())

    events should have size 1
    events.head match {
      case recorded: TraceEvent.EmbeddingUsageRecorded =>
        recorded.usage shouldBe usage
        recorded.model shouldBe "text-embedding-3-small"
        recorded.operation shouldBe "indexing"
        recorded.inputCount shouldBe 10
      case other => fail(s"Expected EmbeddingUsageRecorded, got: $other")
    }
  }

  it should "create correct events for traceCost" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new TestTracing(events)

    tracer.traceCost(0.005, "gpt-4", "completion", 1000, "total") shouldBe Right(())

    events should have size 1
    events.head match {
      case recorded: TraceEvent.CostRecorded =>
        recorded.costUsd shouldBe 0.005
        recorded.model shouldBe "gpt-4"
        recorded.tokenCount shouldBe 1000
      case other => fail(s"Expected CostRecorded, got: $other")
    }
  }

  it should "create correct events for traceRAGOperation" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new TestTracing(events)

    tracer.traceRAGOperation("search", 150L, Some(100), Some(200), Some(50), Some(0.003)) shouldBe Right(())

    events should have size 1
    events.head match {
      case recorded: TraceEvent.RAGOperationCompleted =>
        recorded.operation shouldBe "search"
        recorded.durationMs shouldBe 150L
        recorded.embeddingTokens shouldBe Some(100)
        recorded.llmPromptTokens shouldBe Some(200)
        recorded.llmCompletionTokens shouldBe Some(50)
        recorded.totalCostUsd shouldBe Some(0.003)
      case other => fail(s"Expected RAGOperationCompleted, got: $other")
    }
  }

  // ============ Helper Methods and Classes ============

  private def createTestAgentState(): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("Hello"))),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress,
      initialQuery = Some("test query"),
      logs = Vector("log1")
    )

  private def createTestCompletion(): Completion =
    Completion(
      id = "comp-123",
      created = 1234567890L,
      content = "Hello",
      model = "gpt-4",
      message = AssistantMessage(Some("Hello"), Seq.empty),
      usage = Some(TokenUsage(100, 50, 150))
    )

  /** Test tracer that records events */
  private class TestTracing(events: mutable.Buffer[TraceEvent]) extends Tracing {
    def traceEvent(event: TraceEvent): Result[Unit] = {
      events += event
      Right(())
    }

    def traceAgentState(state: AgentState): Result[Unit] = {
      events += TraceEvent.AgentStateUpdated(
        state.status.toString,
        state.conversation.messages.length,
        state.logs.length
      )
      Right(())
    }

    def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
      events += TraceEvent.ToolExecuted(toolName, input, output, 0L, true)
      Right(())
    }

    def traceError(error: Throwable, context: String): Result[Unit] = {
      events += TraceEvent.ErrorOccurred(error, context)
      Right(())
    }

    def traceCompletion(completion: Completion, model: String): Result[Unit] = {
      events += TraceEvent.CompletionReceived(
        completion.id,
        model,
        completion.message.toolCalls.size,
        completion.message.content
      )
      Right(())
    }

    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
      events += TraceEvent.TokenUsageRecorded(usage, model, operation)
      Right(())
    }
  }

  /** Test tracer that always fails */
  private class FailingTracing extends Tracing {
    import org.llm4s.error.SimpleError

    def traceEvent(event: TraceEvent): Result[Unit]                                  = Left(SimpleError("Always fails"))
    def traceAgentState(state: AgentState): Result[Unit]                             = Left(SimpleError("Always fails"))
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Left(SimpleError("Always fails"))
    def traceError(error: Throwable, context: String): Result[Unit]                  = Left(SimpleError("Always fails"))
    def traceCompletion(completion: Completion, model: String): Result[Unit]         = Left(SimpleError("Always fails"))
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] =
      Left(SimpleError("Always fails"))
  }
}
