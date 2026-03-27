package org.llm4s.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import org.llm4s.llmconnect.config.{ OpenTelemetryConfig, TracingSettings }
import org.llm4s.llmconnect.model.TokenUsage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenTelemetryTracingSpec extends AnyFlatSpec with Matchers {

  val tracing = new OpenTelemetryTracing("test-service", "http://localhost:4317", Map.empty)

  "TracingMode" should "parse opentelemetry strings correctly" in {
    TracingMode.fromString("opentelemetry") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("otel") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("OpenTelemetry") shouldBe TracingMode.OpenTelemetry
  }

  "OpenTelemetryTracing" should "assign correct SpanKind for events" in {
    tracing.getSpanKind(TraceEvent.AgentInitialized("query", Vector.empty)) shouldBe SpanKind.INTERNAL
    tracing.getSpanKind(TraceEvent.AgentStateUpdated("Thinking", 1, 0)) shouldBe SpanKind.INTERNAL
    tracing.getSpanKind(TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), "model", "op")) shouldBe SpanKind.INTERNAL

    tracing.getSpanKind(TraceEvent.CompletionReceived("id", "model", 0, "content")) shouldBe SpanKind.CLIENT
    tracing.getSpanKind(TraceEvent.ToolExecuted("tool", "in", "out", 100, true)) shouldBe SpanKind.CLIENT
    tracing.getSpanKind(TraceEvent.ErrorOccurred(new Exception(""), "")) shouldBe SpanKind.CLIENT
  }

  it should "map CompletionReceived parameters to OpenTelemetry Semantic Conventions" in {
    val event = TraceEvent.CompletionReceived(
      id = "comp-123",
      model = "gpt-4",
      toolCalls = 2,
      content = "Hello world"
    )

    val (name, attributes) = tracing.mapEventToAttributes(event)

    name shouldBe "LLM Completion"
    attributes.get(TraceAttributes.EventType) shouldBe "generation-create"
    attributes.get(AttributeKey.stringKey("gen_ai.request.model")) shouldBe "gpt-4"
    attributes.get(AttributeKey.stringKey("completion_id")) shouldBe "comp-123"
    attributes.get(AttributeKey.longKey("tool_calls")) shouldBe 2L
    attributes.get(AttributeKey.stringKey("content")) shouldBe "Hello world"
  }

  it should "map TokenUsageRecorded parameters to OpenTelemetry Semantic Conventions" in {
    val usage = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
    val event = TraceEvent.TokenUsageRecorded(usage, "gpt-4", "chat")

    val (name, attributes) = tracing.mapEventToAttributes(event)

    name shouldBe "Token Usage - chat"
    attributes.get(TraceAttributes.EventType) shouldBe "event-create"
    attributes.get(AttributeKey.stringKey("gen_ai.request.model")) shouldBe "gpt-4"
    attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")) shouldBe 10L
    attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")) shouldBe 20L
    attributes.get(AttributeKey.longKey("gen_ai.usage.total_tokens")) shouldBe 30L
  }

  it should "map CacheHit parameters to OpenTelemetry attributes" in {
    val event              = TraceEvent.CacheHit(similarity = 0.95, threshold = 0.9)
    val (name, attributes) = tracing.mapEventToAttributes(event)

    name shouldBe "Cache Hit"
    attributes.get(TraceAttributes.EventType) shouldBe "cache-hit"
    attributes.get(AttributeKey.doubleKey("similarity")) shouldBe 0.95
    attributes.get(AttributeKey.doubleKey("threshold")) shouldBe 0.9
    attributes.get(AttributeKey.stringKey("timestamp")) should not be empty
  }

  it should "map CacheMiss parameters to OpenTelemetry attributes" in {
    val event              = TraceEvent.CacheMiss(TraceEvent.CacheMissReason.LowSimilarity)
    val (name, attributes) = tracing.mapEventToAttributes(event)

    name shouldBe "Cache Miss"
    attributes.get(TraceAttributes.EventType) shouldBe "cache-miss"
    attributes.get(AttributeKey.stringKey("reason")) shouldBe "low_similarity"
    attributes.get(AttributeKey.stringKey("timestamp")) should not be empty
  }

  it should "truncate excessive content in attributes" in {
    val longContent = "a" * 2000
    val event       = TraceEvent.CompletionReceived("id", "model", 0, longContent)

    val (_, attributes) = tracing.mapEventToAttributes(event)
    val storedContent   = attributes.get(AttributeKey.stringKey("content"))

    storedContent.length shouldBe 1000
    storedContent shouldBe longContent.take(1000)
  }

  it should "initialize without errors" in {
    val config = OpenTelemetryConfig(
      serviceName = "test-service",
      endpoint = "http://localhost:4317",
      headers = Map.empty
    )

    val otel = OpenTelemetryTracing.from(config)
    otel should not be null

    val result = otel.traceEvent(TraceEvent.CustomEvent("TestEvent", ujson.Obj()))
    result shouldBe Right(())

    otel.shutdown()
  }

  it should "be loaded correctly via Tracing.create when configured" in {
    import org.llm4s.llmconnect.config.LangfuseConfig

    val settings = TracingSettings(
      mode = TracingMode.OpenTelemetry,
      langfuse = LangfuseConfig(),
      openTelemetry = OpenTelemetryConfig(
        serviceName = "integration-test",
        endpoint = "http://localhost:4317",
        headers = Map.empty
      )
    )

    val tracingInstance = Tracing.create(settings)
    tracingInstance shouldBe a[OpenTelemetryTracing]

    // Cleanup
    tracingInstance.shutdown()
  }
}
