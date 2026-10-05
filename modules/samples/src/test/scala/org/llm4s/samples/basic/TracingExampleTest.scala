package org.llm4s.samples.basic

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.trace.{ Tracing, TracingComposer, TraceEvent, TracingMode }
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.config.TracingSettings
import scala.concurrent.duration.*

class TracingExampleTest extends AnyFunSuite with Matchers {

  private val baseSettings = TracingSettings(TracingMode.Console)

  test("should create basic enhanced tracing with console mode") {
    val basicTracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    basicTracer shouldBe a[Tracing]

    val agentEvent = TraceEvent.AgentInitialized(
      query = "What's the weather like?",
      tools = Vector("weather", "calculator")
    )

    val result = basicTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should compose multiple tracers") {
    val consoleTracer  = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    val noOpTracer     = Tracing.create(baseSettings.copy(mode = TracingMode.NoOp))
    val composedTracer = TracingComposer.combine(consoleTracer, noOpTracer)

    composedTracer shouldBe a[Tracing]

    val agentEvent = TraceEvent.AgentInitialized(
      query = "Test query",
      tools = Vector("test")
    )

    val result = composedTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should filter tracing to only error events") {
    val consoleTracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    val errorOnlyTracer = TracingComposer.filter(consoleTracer) { event =>
      event.isInstanceOf[TraceEvent.ErrorOccurred]
    }

    val agentEvent = TraceEvent.AgentInitialized(
      query = "This won't be traced",
      tools = Vector("test")
    )

    val errorEvent = TraceEvent.ErrorOccurred(
      error = new RuntimeException("Test error"),
      context = "Test context"
    )

    // Non-error event should not be traced (filtered out)
    val nonErrorResult = errorOnlyTracer.traceEvent(agentEvent)
    nonErrorResult shouldBe Right(())

    // Error event should be traced
    val errorResult = errorOnlyTracer.traceEvent(errorEvent)
    errorResult shouldBe a[Right[_, Unit]]
  }

  test("should transform tracing events with metadata") {
    val consoleTracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    val transformedTracer = TracingComposer.transform(consoleTracer) { event =>
      event match {
        case e: TraceEvent.CustomEvent =>
          TraceEvent.CustomEvent(
            name = s"[ENHANCED] ${e.name}",
            data = ujson.Obj.from(e.data.obj.toSeq :+ ("enhanced" -> true))
          )
        case other => other
      }
    }

    val customEvent = TraceEvent.CustomEvent("test", ujson.Obj("value" -> 42))
    val result      = transformedTracer.traceEvent(customEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should create tracers for all tracing modes") {
    val modes = Seq(TracingMode.Console, TracingMode.NoOp, TracingMode.Named("langfuse"))
    modes.foreach { mode =>
      val tracer = Tracing.create(baseSettings.copy(mode = mode))
      tracer shouldBe a[Tracing]
    }
  }

  test("should trace completion events") {
    val tracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))

    val completion = org.llm4s.llmconnect.model.Completion(
      id = "test-id",
      created = System.currentTimeMillis(),
      content = "Test response",
      model = "tracing",
      message = org.llm4s.llmconnect.model.AssistantMessage(
        content = "Test response",
        toolCalls = Vector.empty
      ),
      usage = Some(TokenUsage(10, 20, 30))
    )

    val result = tracer.traceCompletion(completion, "test-model")
    result shouldBe a[Right[_, Unit]]
  }

  test("should trace agent state updates") {
    val tracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))

    val agentState = org.llm4s.agent.AgentThread(
      threadId = "t",
      messages = Vector(
        org.llm4s.llmconnect.model.SystemMessage("You are a helpful assistant"),
        org.llm4s.llmconnect.model.UserMessage("Hello")
      ),
      status = org.llm4s.agent.ThreadStatus.Suspended(Vector.empty)
    )

    val result = tracer.traceEvent(agentState.toTraceEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should handle complex composition of tracers") {
    val consoleTracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    val noOpTracer    = Tracing.create(baseSettings.copy(mode = TracingMode.NoOp))

    val complexTracer = TracingComposer.combine(
      consoleTracer,
      TracingComposer.filter(noOpTracer)(_.isInstanceOf[TraceEvent.CompletionReceived]),
      TracingComposer.transform(consoleTracer) {
        case e: TraceEvent.TokenUsageRecorded =>
          TraceEvent.TokenUsageRecorded(
            usage = e.usage,
            model = s"[COST] ${e.model}",
            operation = e.operation
          )
        case other => other
      }
    )

    val tokenEvent = TraceEvent.TokenUsageRecorded(
      usage = TokenUsage(10, 20, 30),
      model = "gpt-4",
      operation = "completion"
    )

    val result = complexTracer.traceEvent(tokenEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should validate that all event types are properly handled") {
    val tracer = Tracing.create(baseSettings.copy(mode = TracingMode.Console))

    // Test all event types
    val events = Seq(
      TraceEvent.AgentInitialized("test", Vector("tool")),
      TraceEvent.CompletionReceived("id", "model", 0, "content"),
      TraceEvent.ToolExecuted("tool", "input", "output", 100.millis, true),
      TraceEvent.ErrorOccurred(new RuntimeException("error"), "context"),
      TraceEvent.TokenUsageRecorded(TokenUsage(1, 2, 3), "model", "op"),
      TraceEvent.AgentStateUpdated("status", 5, 3),
      TraceEvent.CustomEvent("name", ujson.Obj("key" -> "value"))
    )

    events.foreach { event =>
      val result = tracer.traceEvent(event)
      result shouldBe a[Right[_, Unit]]
    }
  }

  test("should test tracing mode factory methods") {
    // Test typed factory via settings
    val consoleTracer1  = Tracing.create(baseSettings.copy(mode = TracingMode.Console))
    val noOpTracer1     = Tracing.create(baseSettings.copy(mode = TracingMode.NoOp))
    val langfuseTracer1 = Tracing.create(baseSettings.copy(mode = TracingMode.Named("langfuse")))
    val defaultTracer   = Tracing.create(baseSettings) // Console

    Seq(consoleTracer1, noOpTracer1, langfuseTracer1, defaultTracer).foreach(tracer => tracer shouldBe a[Tracing])
  }
}
