package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.llmconnect.config.{ OpenTelemetryConfig, TracingSettings }
import org.llm4s.llmconnect.model.{ TokenUsage, ToolCallId }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.it.tags.Local
import scala.concurrent.duration.*

@Local
class OpenTelemetryTracingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // The span mapping needs no collector: exporting over gRPC to a port with nothing listening waited out the
  // exporter's retry backoff on close (#3216). Only the construction cases below build the OTLP exporter.
  private object Discarding extends SpanExporter {
    def `export`(batch: java.util.Collection[SpanData]): CompletableResultCode = CompletableResultCode.ofSuccess()
    def flush(): CompletableResultCode                                         = CompletableResultCode.ofSuccess()
    def shutdown(): CompletableResultCode                                      = CompletableResultCode.ofSuccess()
  }

  private val tracing = OpenTelemetryTracing.exportingTo("test-service", Discarding)

  override def afterAll(): Unit = tracing.close()

  "TracingMode" should "parse opentelemetry strings correctly" in {
    TracingMode.fromString("opentelemetry") shouldBe TracingMode.Named("opentelemetry")
    // `otel` is the backend's alias, not core's: it parses as itself and the backend answers to it
    TracingMode.fromString("otel") shouldBe TracingMode.Named("otel")
    new OpenTelemetryTracingBackend().aliases shouldBe Set("otel")
    TracingMode.fromString("OpenTelemetry") shouldBe TracingMode.Named("opentelemetry")
  }

  "OpenTelemetryTracing" should "assign correct SpanKind for events" in {
    tracing.getSpanKind(
      TraceEvent.AgentRunEnded(
        "t",
        "r",
        "a",
        TraceEvent.RunStatus.Completed,
        Seq.empty,
        org.llm4s.llmconnect.model.UsageSummary()
      )
    ) shouldBe SpanKind.INTERNAL
    tracing.getSpanKind(
      TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), ModelName("model"), "op")
    ) shouldBe SpanKind.INTERNAL

    tracing.getSpanKind(TraceEvent.CompletionReceived("id", ModelName("model"), 0, "content")) shouldBe SpanKind.CLIENT
    tracing.getSpanKind(
      TraceEvent.ToolExecuted("tool", ToolCallId("in").asString, "out", 100.millis, true)
    ) shouldBe SpanKind.INTERNAL
    tracing.getSpanKind(TraceEvent.ErrorOccurred.of(new Exception(""), "")) shouldBe SpanKind.INTERNAL
  }

  it should "map CompletionReceived parameters to OpenTelemetry Semantic Conventions" in {
    val event = TraceEvent.CompletionReceived(
      id = "comp-123",
      model = ModelName("gpt-4"),
      toolCalls = 2,
      content = "Hello world"
    )

    val (name, attributes) = tracing.mapEventToAttributes(event)

    name shouldBe "chat gpt-4"
    attributes.get(TraceAttributes.EventType) shouldBe "generation-create"
    attributes.get(AttributeKey.stringKey("gen_ai.operation.name")) shouldBe "chat"
    attributes.get(AttributeKey.stringKey("gen_ai.request.model")) shouldBe "gpt-4"
    attributes.get(AttributeKey.stringKey("gen_ai.response.id")) shouldBe "comp-123"
    attributes.get(AttributeKey.longKey("llm4s.model_call.tool_calls")) shouldBe 2L
    // content is opt-in (#2275)
    attributes.get(AttributeKey.stringKey("gen_ai.output.messages")) shouldBe null
  }

  it should "map TokenUsageRecorded parameters to OpenTelemetry Semantic Conventions" in {
    val usage = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
    val event = TraceEvent.TokenUsageRecorded(usage, ModelName("gpt-4"), "chat")

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
    val capturing   = OpenTelemetryTracing.exportingTo("test-service", Discarding, captureContent = true)
    val longContent = "a" * 2000
    val event       = TraceEvent.CompletionReceived("id", ModelName("model"), 0, longContent)

    val (_, attributes)  = capturing.mapEventToAttributes(event)
    val messages: String = attributes.get(AttributeKey.stringKey("gen_ai.output.messages"))
    val storedContent    = ujson.read(messages)(0)("parts")(0)("content").str

    storedContent shouldBe longContent.take(1000)
    capturing.close()
  }

  it should "initialize without errors" in {
    val config = OpenTelemetryConfig(
      serviceName = "test-service",
      endpoint = "http://localhost:4317",
      headers = Map.empty
    )

    val otel = OpenTelemetryTracing.from(config)
    otel should not be null
    otel.initializationFailure shouldBe None
    // No span through it: one exported to a port with nothing listening held `close` for the exporter's retry
    // backoff, nine seconds (#3216). The in-memory tracing records the same event.
    otel.close()

    tracing.traceEvent(TraceEvent.CustomEvent("TestEvent", ujson.Obj())) shouldBe Right(())
  }

  it should "be loaded correctly via Tracing.fromSettings when configured" in {
    // The llm4s.tracing.opentelemetry block, as core hands it to the backend (#1133).
    val settings = TracingSettings(
      mode = TracingMode.fromString("opentelemetry"),
      extras = Map("serviceName" -> "integration-test", "endpoint" -> "http://localhost:4317")
    )

    val tracingInstance = Tracing.fromSettings(settings).fold(e => fail(e.message), identity)
    tracingInstance shouldBe a[OpenTelemetryTracing]

    // Cleanup
    tracingInstance.close()
  }
}
