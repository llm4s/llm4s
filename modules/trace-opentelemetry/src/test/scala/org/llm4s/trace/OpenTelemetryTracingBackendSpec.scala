package org.llm4s.trace

import org.llm4s.llmconnect.config.{ OpenTelemetryConfig, TracingSettings }
import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary, UserMessage }
import org.llm4s.trace.spi.TracingBackends
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Proves this module registers itself for `TracingMode.Named("opentelemetry")` through
 * its services entry - the replacement for the `Class.forName` reflection core
 * used to load it (#1133). No collector is needed: the OTLP exporter connects
 * lazily and nothing here exports a span.
 */
class OpenTelemetryTracingBackendSpec extends AnyWordSpec with Matchers with EitherValues:

  private val settings = TracingSettings(
    mode = TracingMode.fromString("opentelemetry"),
    extras = Map("serviceName" -> "backend-spec", "endpoint" -> "http://localhost:4317")
  )

  "llm4s-observability-otel" should {

    "be discovered for the opentelemetry mode through its services entry" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.find(TracingMode.Named("opentelemetry")).map(_.getClass) shouldBe Some(
        classOf[OpenTelemetryTracingBackend]
      )
      backends.failures shouldBe empty
      new OpenTelemetryTracingBackend().mode shouldBe OpenTelemetryConfig.Mode
    }

    "be selected by the otel alias too" in {
      TracingBackends
        .discover(getClass.getClassLoader)
        .find(TracingMode.fromString("otel"))
        .map(_.getClass) shouldBe Some(classOf[OpenTelemetryTracingBackend])
    }

    "be what Tracing.fromSettings and Tracing.create build for opentelemetry" in {
      val checked = Tracing.fromSettings(settings).value
      checked shouldBe an[OpenTelemetryTracing]
      checked.shutdown()

      val created = Tracing.create(settings)
      created shouldBe an[OpenTelemetryTracing]
      created.shutdown()
    }

    "be registrable explicitly, for a shaded jar whose services files did not survive" in {
      val tracing = Tracing.fromSettings(settings, TracingBackends.of(new OpenTelemetryTracingBackend)).value
      tracing shouldBe an[OpenTelemetryTracing]
      tracing.shutdown()
    }
  }

  "OpenTelemetryTracing" should {

    "map an ended agent run to an invoke_agent span" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val usage   = UsageSummary().add("m", TokenUsage(10, 5, 15), None)
      val event = TraceEvent.AgentRunEnded("thread-1", "run-1", "assistant", "completed", Seq(UserMessage("hi")), usage)

      tracing.getSpanKind(event) shouldBe io.opentelemetry.api.trace.SpanKind.INTERNAL
      val (name, attributes) = tracing.mapEventToAttributes(event)
      name shouldBe "invoke_agent assistant"
      attributes.get(key("gen_ai.operation.name")) shouldBe "invoke_agent"
      attributes.get(key("gen_ai.agent.name")) shouldBe "assistant"
      attributes.get(key("gen_ai.conversation.id")) shouldBe "thread-1"
      attributes.get(key("llm4s.parent.run_id")) shouldBe null
      attributes.get(key("llm4s.parent.thread_id")) shouldBe null
      attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("thread_id")) shouldBe "thread-1"
      attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("run_id")) shouldBe "run-1"
      attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("agent")) shouldBe "assistant"
      attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("status")) shouldBe "completed"
      attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("message_count")) shouldBe 1L
      attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.input_tokens")) shouldBe 10L
      attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.output_tokens")) shouldBe 5L

      tracing.traceEvent(event) shouldBe Right(())
      tracing.shutdown()
    }

    "carry a child run's parent ids on its invoke_agent span" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val event = TraceEvent.AgentRunEnded(
        "parent/~task/researcher/abc",
        "child-run",
        "researcher",
        "completed",
        Seq.empty,
        UsageSummary(),
        parentThreadId = Some("parent"),
        parentRunId = Some("parent-run")
      )
      val (name, attributes) = tracing.mapEventToAttributes(event)
      name shouldBe "invoke_agent researcher"
      attributes.get(key("gen_ai.conversation.id")) shouldBe "parent/~task/researcher/abc"
      attributes.get(key("llm4s.parent.thread_id")) shouldBe "parent"
      attributes.get(key("llm4s.parent.run_id")) shouldBe "parent-run"
      tracing.shutdown()
    }

    "map a tool execution to an execute_tool span" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val (name, attributes) = tracing.mapEventToAttributes(
        TraceEvent.ToolExecuted("search", "{}", "[]", scala.concurrent.duration.Duration(3, "ms"), success = true)
      )
      name shouldBe "execute_tool search"
      attributes.get(key("gen_ai.operation.name")) shouldBe "execute_tool"
      attributes.get(key("gen_ai.tool.name")) shouldBe "search"
      tracing.shutdown()
    }

    "map an agent's tool_executed event to an execute_tool span" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val data = ujson.Obj(
        "threadId" -> "thread-1",
        "runId"    -> "run-1",
        "name"     -> "agent.tool_executed",
        "version"  -> 1,
        "payload"  -> ujson.Obj("agent" -> "assistant", "toolCallId" -> "call-1", "tool" -> "task")
      )
      val (name, attributes) = tracing.mapEventToAttributes(TraceEvent.CustomEvent("agent.tool_executed", data))
      name shouldBe "execute_tool task"
      attributes.get(key("gen_ai.operation.name")) shouldBe "execute_tool"
      attributes.get(key("gen_ai.tool.name")) shouldBe "task"
      attributes.get(key("gen_ai.tool.call.id")) shouldBe "call-1"
      attributes.get(key("gen_ai.agent.name")) shouldBe "assistant"
      attributes.get(key("gen_ai.conversation.id")) shouldBe "thread-1"
      tracing.shutdown()
    }
  }

  private def key(name: String) = io.opentelemetry.api.common.AttributeKey.stringKey(name)
