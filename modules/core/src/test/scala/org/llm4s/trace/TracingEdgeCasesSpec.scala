package org.llm4s.trace

import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.config.{ LangfuseConfig, OpenTelemetryConfig, TracingSettings }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class TracingEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // TracingMode - OpenTelemetry parsing
  // =========================================================================

  "TracingMode.fromString" should "parse opentelemetry mode" in {
    TracingMode.fromString("opentelemetry") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("OPENTELEMETRY") shouldBe TracingMode.OpenTelemetry
  }

  it should "parse otel alias" in {
    TracingMode.fromString("otel") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("OTEL") shouldBe TracingMode.OpenTelemetry
  }

  // =========================================================================
  // Tracing.create - OpenTelemetry falls back to NoOp when class not found
  // =========================================================================

  "Tracing.create" should "fall back to NoOpTracing for OpenTelemetry when module not on classpath" in {
    val settings = TracingSettings(
      mode = TracingMode.OpenTelemetry,
      langfuse = LangfuseConfig(),
      openTelemetry = OpenTelemetryConfig()
    )

    val tracing = Tracing.create(settings)
    // OpenTelemetryTracing class is not on the classpath in core tests, so it falls back
    tracing shouldBe a[NoOpTracing]
  }

  // =========================================================================
  // Tracing.traceEvent(String) convenience method
  // =========================================================================

  "Tracing.traceEvent(String)" should "wrap string in CustomEvent" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new RecordingTracing(events)

    tracer.traceEvent("my custom event") shouldBe Right(())

    events should have size 1
    events.head shouldBe a[TraceEvent.CustomEvent]
    events.head.asInstanceOf[TraceEvent.CustomEvent].name shouldBe "my custom event"
  }

  // =========================================================================
  // Tracing.traceRAGOperation - with optional params
  // =========================================================================

  "Tracing.traceRAGOperation" should "work with no optional parameters" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new RecordingTracing(events)

    tracer.traceRAGOperation("index", 500L) shouldBe Right(())

    events should have size 1
    val e = events.head.asInstanceOf[TraceEvent.RAGOperationCompleted]
    e.operation shouldBe "index"
    e.durationMs shouldBe 500L
    e.embeddingTokens shouldBe None
    e.llmPromptTokens shouldBe None
    e.llmCompletionTokens shouldBe None
    e.totalCostUsd shouldBe None
  }

  // =========================================================================
  // CompositeTracing - delegating methods
  // =========================================================================

  "CompositeTracing" should "delegate traceAgentState to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    val state = createAgentState()
    combined.traceAgentState(state) shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.AgentStateUpdated]
  }

  it should "delegate traceToolCall to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    combined.traceToolCall("calculator", """{"a":1}""", "42") shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.ToolExecuted]
  }

  it should "delegate traceError to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    combined.traceError(new RuntimeException("test"), "context") shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.ErrorOccurred]
  }

  it should "delegate traceCompletion to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    combined.traceCompletion(createCompletion(), "gpt-4") shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.CompletionReceived]
  }

  it should "delegate traceTokenUsage to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    combined.traceTokenUsage(TokenUsage(10, 5, 15), "gpt-4", "test") shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.TokenUsageRecorded]
  }

  it should "call shutdown on all tracers" in {
    var shut1    = false
    var shut2    = false
    val t1       = new RecordingTracing(mutable.Buffer.empty) { override def shutdown(): Unit = shut1 = true }
    val t2       = new RecordingTracing(mutable.Buffer.empty) { override def shutdown(): Unit = shut2 = true }
    val combined = TracingComposer.combine(t1, t2)

    combined.shutdown()

    shut1 shouldBe true
    shut2 shouldBe true
  }

  // =========================================================================
  // FilteredTracing - delegate methods
  // =========================================================================

  "FilteredTracing" should "delegate traceAgentState to underlying" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new RecordingTracing(events)
    val filtered = TracingComposer.filter(tracer)(_ => true)

    filtered.traceAgentState(createAgentState()) shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceCompletion to underlying" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new RecordingTracing(events)
    val filtered = TracingComposer.filter(tracer)(_ => true)

    filtered.traceCompletion(createCompletion(), "gpt-4") shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceTokenUsage to underlying" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new RecordingTracing(events)
    val filtered = TracingComposer.filter(tracer)(_ => true)

    filtered.traceTokenUsage(TokenUsage(10, 5, 15), "m", "o") shouldBe Right(())
    events should have size 1
  }

  it should "delegate shutdown to underlying" in {
    var shut     = false
    val tracer   = new RecordingTracing(mutable.Buffer.empty) { override def shutdown(): Unit = shut = true }
    val filtered = TracingComposer.filter(tracer)(_ => true)

    filtered.shutdown()
    shut shouldBe true
  }

  // =========================================================================
  // TransformedTracing - delegate methods
  // =========================================================================

  "TransformedTracing" should "delegate traceAgentState to underlying" in {
    val events      = mutable.Buffer.empty[TraceEvent]
    val tracer      = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.traceAgentState(createAgentState()) shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceToolCall to underlying" in {
    val events      = mutable.Buffer.empty[TraceEvent]
    val tracer      = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.traceToolCall("tool", "in", "out") shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceError to underlying" in {
    val events      = mutable.Buffer.empty[TraceEvent]
    val tracer      = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.traceError(new Exception("e"), "ctx") shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceCompletion to underlying" in {
    val events      = mutable.Buffer.empty[TraceEvent]
    val tracer      = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.traceCompletion(createCompletion(), "gpt-4") shouldBe Right(())
    events should have size 1
  }

  it should "delegate traceTokenUsage to underlying" in {
    val events      = mutable.Buffer.empty[TraceEvent]
    val tracer      = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.traceTokenUsage(TokenUsage(10, 5, 15), "m", "o") shouldBe Right(())
    events should have size 1
  }

  it should "delegate shutdown to underlying" in {
    var shut        = false
    val tracer      = new RecordingTracing(mutable.Buffer.empty) { override def shutdown(): Unit = shut = true }
    val transformed = TracingComposer.transform(tracer)(identity)

    transformed.shutdown()
    shut shouldBe true
  }

  // =========================================================================
  // NoOpTracing.shutdown
  // =========================================================================

  "NoOpTracing" should "support shutdown without error" in {
    val noop = new NoOpTracing()
    noException should be thrownBy noop.shutdown()
  }

  // =========================================================================
  // ConsoleTracing - RAGOperationCompleted with optional fields
  // =========================================================================

  "ConsoleTracing" should "handle RAGOperationCompleted with all optional fields" in {
    val tracing = new ConsoleTracing()
    val event = TraceEvent.RAGOperationCompleted(
      "evaluate",
      2500L,
      Some(100),
      Some(200),
      Some(50),
      Some(0.003)
    )
    tracing.traceEvent(event).isRight shouldBe true
  }

  it should "handle CacheHit event" in {
    val tracing = new ConsoleTracing()
    tracing.traceEvent(TraceEvent.CacheHit(0.95, 0.85)).isRight shouldBe true
  }

  it should "handle CacheMiss event with different reasons" in {
    val tracing = new ConsoleTracing()
    tracing.traceEvent(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.LowSimilarity)).isRight shouldBe true
    tracing.traceEvent(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired)).isRight shouldBe true
    tracing.traceEvent(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.OptionsMismatch)).isRight shouldBe true
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private def createAgentState(): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("Hello"))),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress,
      logs = Vector("log1")
    )

  private def createCompletion(): Completion =
    Completion(
      id = "comp-1",
      created = 0L,
      content = "Hello",
      model = "gpt-4",
      message = AssistantMessage(Some("Hello"), Seq.empty)
    )

  /** Recording tracer for test assertions */
  private class RecordingTracing(events: mutable.Buffer[TraceEvent]) extends Tracing {
    def traceEvent(event: TraceEvent): Result[Unit] = { events += event; Right(()) }
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
}
