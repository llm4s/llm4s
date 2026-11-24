package org.llm4s.samples.basic

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.trace.{ NoOpTracing, Tracing, TracingComposer, TraceEvent, TracingMode }
import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.llm4s.llmconnect.config.TracingSettings
import scala.concurrent.duration.*
import java.time.Instant

class TracingExampleTest extends AnyFunSuite with Matchers {

  private val baseSettings = TracingSettings(TracingMode.Console)

  test("should create basic enhanced tracing with console mode") {
    val basicTracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
    basicTracer shouldBe a[Tracing]

    val agentEvent = TraceEvent.CustomEvent("query_received", ujson.Obj("query" -> "What's the weather like?"))

    val result = basicTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should compose multiple tracers") {
    val consoleTracer  = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
    val noOpTracer     = Tracing.fromSettings(baseSettings.withMode(TracingMode.NoOp)).getOrElse(new NoOpTracing())
    val composedTracer = TracingComposer.combine(consoleTracer, noOpTracer)

    composedTracer shouldBe a[Tracing]

    val agentEvent = TraceEvent.CustomEvent("query_received", ujson.Obj("query" -> "Test query"))

    val result = composedTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should filter tracing to only error events") {
    val consoleTracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
    val errorOnlyTracer = TracingComposer.filter(consoleTracer) { event =>
      event.isInstanceOf[TraceEvent.ErrorOccurred]
    }

    val agentEvent = TraceEvent.CustomEvent("query_received", ujson.Obj("query" -> "This won't be traced"))

    val errorEvent = TraceEvent.ErrorOccurred.of(new RuntimeException("Test error"), "Test context")

    // Non-error event should not be traced (filtered out)
    val nonErrorResult = errorOnlyTracer.traceEvent(agentEvent)
    nonErrorResult shouldBe Right(())

    // Error event should be traced
    val errorResult = errorOnlyTracer.traceEvent(errorEvent)
    errorResult shouldBe a[Right[_, Unit]]
  }

  test("should transform tracing events with metadata") {
    val consoleTracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
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
      val tracer = Tracing.fromSettings(baseSettings.withMode(mode)).getOrElse(new NoOpTracing())
      tracer shouldBe a[Tracing]
    }
  }

  test("should trace completion events") {
    val tracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())

    val completion = org.llm4s.llmconnect.model.Completion(
      id = "test-id",
      created = Instant.now(),
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

  test("should trace agent run ended") {
    val tracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())

    val messages = Seq(
      org.llm4s.llmconnect.model.SystemMessage("You are a helpful assistant"),
      org.llm4s.llmconnect.model.UserMessage("Hello")
    )
    val event = TraceEvent.AgentRunEnded(
      threadId = "t",
      runId = "r",
      agent = "a",
      status = TraceEvent.RunStatus.Completed,
      messages = messages,
      usage = UsageSummary()
    )

    val result = tracer.traceEvent(event)
    result shouldBe a[Right[_, Unit]]
  }

  test("should handle complex composition of tracers") {
    val consoleTracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
    val noOpTracer    = Tracing.fromSettings(baseSettings.withMode(TracingMode.NoOp)).getOrElse(new NoOpTracing())

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
    val tracer = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())

    // Test all event types
    val events = Seq(
      TraceEvent.CompletionReceived("id", "model", 0, "content"),
      TraceEvent.ToolExecuted("tool", "input", "output", 100.millis, true),
      TraceEvent.ErrorOccurred.of(new RuntimeException("error"), "context"),
      TraceEvent.TokenUsageRecorded(TokenUsage(1, 2, 3), "model", "op"),
      TraceEvent.AgentRunEnded("t", "r", "a", TraceEvent.RunStatus.Completed, Seq.empty, UsageSummary()),
      TraceEvent.CustomEvent("name", ujson.Obj("key" -> "value"))
    )

    events.foreach { event =>
      val result = tracer.traceEvent(event)
      result shouldBe a[Right[_, Unit]]
    }
  }

  test("should test tracing mode factory methods") {
    // Test typed factory via settings
    val consoleTracer1 = Tracing.fromSettings(baseSettings.withMode(TracingMode.Console)).getOrElse(new NoOpTracing())
    val noOpTracer1    = Tracing.fromSettings(baseSettings.withMode(TracingMode.NoOp)).getOrElse(new NoOpTracing())
    val langfuseTracer1 =
      Tracing.fromSettings(baseSettings.withMode(TracingMode.Named("langfuse"))).getOrElse(new NoOpTracing())
    val defaultTracer = Tracing.fromSettings(baseSettings).getOrElse(new NoOpTracing()) // Console

    Seq(consoleTracer1, noOpTracer1, langfuseTracer1, defaultTracer).foreach(tracer => tracer shouldBe a[Tracing])
  }
}
