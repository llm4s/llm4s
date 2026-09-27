package org.llm4s.trace

import org.llm4s.llmconnect.config.{ LangfuseConfig, OpenTelemetryConfig, TracingSettings }
import org.llm4s.trace.spi.TracingBackends
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Proves this module registers itself for `TracingMode.OpenTelemetry` through
 * its services entry - the replacement for the `Class.forName` reflection core
 * used to load it (#1133). No collector is needed: the OTLP exporter connects
 * lazily and nothing here exports a span.
 */
class OpenTelemetryTracingBackendSpec extends AnyWordSpec with Matchers with EitherValues:

  private val settings = TracingSettings(
    mode = TracingMode.OpenTelemetry,
    langfuse = LangfuseConfig(),
    openTelemetry = OpenTelemetryConfig(serviceName = "backend-spec", endpoint = "http://localhost:4317")
  )

  "llm4s-observability-otel" should {

    "be discovered for the opentelemetry mode through its services entry" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.find(TracingMode.OpenTelemetry).map(_.getClass) shouldBe Some(classOf[OpenTelemetryTracingBackend])
      backends.failures shouldBe empty
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

    "trace an agent state snapshot as an ordinary event, since traceAgentState was removed (D5)" in {
      val tracing = new OpenTelemetryTracing("backend-spec", "http://localhost:4317", Map.empty)
      val event   = TraceEvent.AgentStateUpdated("Complete", 2, 1)

      val (name, attributes) = tracing.mapEventToAttributes(event)
      name shouldBe "Agent State Updated"
      attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("status")) shouldBe "Complete"
      attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("message_count")) shouldBe 2L

      tracing.traceEvent(event) shouldBe Right(())
      tracing.shutdown()
    }
  }
