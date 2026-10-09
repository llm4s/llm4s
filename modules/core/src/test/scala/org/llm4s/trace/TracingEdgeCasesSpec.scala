package org.llm4s.trace

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

class TracingEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // TracingMode - OpenTelemetry parsing
  // =========================================================================

  "TracingMode.fromString" should "parse opentelemetry as Named, since its backend is outside core" in {
    TracingMode.fromString("opentelemetry") shouldBe TracingMode.Named("opentelemetry")
    TracingMode.fromString("OPENTELEMETRY") shouldBe TracingMode.Named("opentelemetry")
  }

  it should "keep otel as an alias for opentelemetry" in {
    TracingMode.fromString("otel") shouldBe TracingMode.Named("opentelemetry")
    TracingMode.fromString("OTEL") shouldBe TracingMode.Named("opentelemetry")
  }

  // =========================================================================
  // Tracing.create - OpenTelemetry falls back to NoOp when class not found
  // =========================================================================

  "Tracing.create" should "fall back to NoOpTracing for OpenTelemetry when module not on classpath" in {
    val settings = TracingSettings(mode = TracingMode.Named("opentelemetry"))

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

    tracer.traceRAGOperation("index", 500.millis) shouldBe Right(())

    events should have size 1
    val e = events.head.asInstanceOf[TraceEvent.RAGOperationCompleted]
    e.operation shouldBe "index"
    e.duration shouldBe 500.millis
    e.embeddingTokens shouldBe None
    e.llmPromptTokens shouldBe None
    e.llmCompletionTokens shouldBe None
    e.totalCostUsd shouldBe None
  }

  // =========================================================================
  // CompositeTracing - delegating methods
  // =========================================================================

  "CompositeTracing" should "fan an agent state event out to all tracers" in {
    val events1  = mutable.Buffer.empty[TraceEvent]
    val events2  = mutable.Buffer.empty[TraceEvent]
    val combined = TracingComposer.combine(new RecordingTracing(events1), new RecordingTracing(events2))

    val event = agentStateEvent()
    combined.traceEvent(event) shouldBe Right(())

    events1 should have size 1
    events2 should have size 1
    events1.head shouldBe a[TraceEvent.AgentRunEnded]
    events1.head.asInstanceOf[TraceEvent.AgentRunEnded].messages shouldBe event.messages
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

  "FilteredTracing" should "apply its predicate to agent run events like any other" in {
    val events   = mutable.Buffer.empty[TraceEvent]
    val tracer   = new RecordingTracing(events)
    val filtered = TracingComposer.filter(tracer)(_.eventType != "agent_run_ended")

    filtered.traceEvent(agentStateEvent()) shouldBe Right(())
    events shouldBe empty
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

  "TransformedTracing" should "transform agent run events like any other" in {
    val events = mutable.Buffer.empty[TraceEvent]
    val tracer = new RecordingTracing(events)
    val transformed = TracingComposer.transform(tracer) {
      case e: TraceEvent.AgentRunEnded => e.copy(messages = Seq.empty)
      case other                       => other
    }

    transformed.traceEvent(agentStateEvent()) shouldBe Right(())
    events should have size 1
    events.head.asInstanceOf[TraceEvent.AgentRunEnded].messages shouldBe empty
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
      2500.millis,
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

  /**
   * The event an ended agent run carries, built directly: these
   * specs are about the composers, and the agent runtime is in `llm4s-agent` (#1242).
   */
  private def agentStateEvent(): TraceEvent.AgentRunEnded =
    TraceEvent.AgentRunEnded("thread-1", "run-1", "assistant", "completed", Seq(UserMessage("Hello")), UsageSummary())

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
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
      events += TraceEvent.ToolExecuted(toolName, input, output, 0.millis, true)
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
