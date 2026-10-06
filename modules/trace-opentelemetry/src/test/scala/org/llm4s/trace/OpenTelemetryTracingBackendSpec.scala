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

    "map an ended agent run to an Agent Run span" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val usage   = UsageSummary().add("m", TokenUsage(10, 5, 15), None)
      val event = TraceEvent.AgentRunEnded("thread-1", "run-1", "assistant", "completed", Seq(UserMessage("hi")), usage)

      tracing.getSpanKind(event) shouldBe io.opentelemetry.api.trace.SpanKind.INTERNAL
      val (name, attributes) = tracing.mapEventToAttributes(event)
      name shouldBe "Agent Run"
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
  }
