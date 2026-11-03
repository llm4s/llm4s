package org.llm4s.trace

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  EmbeddingUsage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The span name and attributes `OpenTelemetryTracing` gives each `TraceEvent` that `OpenTelemetryGenAiSpansSpec` and
 * `OpenTelemetryTracingBackendSpec` do not cover (#2226), the `ContentLimit` cut on message content, and the spans the
 * convenience methods export. Nothing is sent anywhere: spans go to an in-memory exporter.
 */
class OpenTelemetryEventAttributesSpec extends AnyWordSpec with Matchers:

  final private class Collecting extends SpanExporter:
    val spans = new ConcurrentLinkedQueue[SpanData]()
    def `export`(batch: java.util.Collection[SpanData]): CompletableResultCode =
      spans.addAll(batch)
      CompletableResultCode.ofSuccess()
    def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

  private def withTracing(captureContent: Boolean = false)(
    body: (OpenTelemetryTracing, () => Vector[SpanData]) => Unit
  ): Unit =
    val exporter = new Collecting
    val tracing  = OpenTelemetryTracing.exportingTo("event-attributes-spec", exporter, captureContent)
    try body(tracing, () => exporter.spans.asScala.toVector)
    finally tracing.shutdown()

  private def mapped(event: TraceEvent, captureContent: Boolean = false): (String, Attributes) =
    val tracing = OpenTelemetryTracing.exportingTo("event-attributes-spec", new Collecting, captureContent)
    try tracing.mapEventToAttributes(event)
    finally tracing.shutdown()

  private def kind(event: TraceEvent): SpanKind =
    val tracing = OpenTelemetryTracing.exportingTo("event-attributes-spec", new Collecting)
    try tracing.getSpanKind(event)
    finally tracing.shutdown()

  private def str(a: Attributes, key: String): String              = a.get(AttributeKey.stringKey(key))
  private def long(a: Attributes, key: String): java.lang.Long     = a.get(AttributeKey.longKey(key))
  private def double(a: Attributes, key: String): java.lang.Double = a.get(AttributeKey.doubleKey(key))
  private def bool(a: Attributes, key: String): java.lang.Boolean  = a.get(AttributeKey.booleanKey(key))

  /** The `content` of every part of every message in a `gen_ai.*.messages` attribute. */
  private def partContents(json: String): Seq[String] =
    ujson.read(json).arr.toSeq.flatMap(_("parts").arr.toSeq.flatMap(_.obj.get("content").map(_.str)))

  private val at    = Instant.parse("2026-11-03T10:00:00Z")
  private val limit = TraceAttributes.ContentLimit

  "AgentInitialized" should {

    "be the `LLM4S Agent Run` span with its tools and no input by default" in {
      val (name, attributes) = mapped(TraceEvent.AgentInitialized("what is 2+2?", Vector("search", "calculator"), at))
      name shouldBe "LLM4S Agent Run"
      str(attributes, "event.type") shouldBe "trace-create"
      str(attributes, "tools") shouldBe "search, calculator"
      str(attributes, "input") shouldBe null
    }

    "record its input with captureContent, cut to ContentLimit characters" in {
      val (_, whole) = mapped(TraceEvent.AgentInitialized("a" * limit, Vector.empty, at), captureContent = true)
      str(whole, "input") shouldBe "a" * limit

      val (_, cut) = mapped(TraceEvent.AgentInitialized("a" * (limit + 1), Vector.empty, at), captureContent = true)
      str(cut, "input") shouldBe "a" * limit
    }

    "be an INTERNAL span" in {
      kind(TraceEvent.AgentInitialized("q", Vector.empty, at)) shouldBe SpanKind.INTERNAL
    }
  }

  "CompletionReceived" should {

    "cut each message part to ContentLimit characters with captureContent" in {
      val event = TraceEvent.CompletionReceived(
        id = "chatcmpl-1",
        model = "gpt-4o",
        toolCalls = 0,
        content = "b" * (limit + 1),
        timestamp = at,
        input = Seq(UserMessage("c" * (limit + 1)), UserMessage("d" * limit))
      )
      val (_, attributes) = mapped(event, captureContent = true)
      partContents(str(attributes, "gen_ai.input.messages")) shouldBe Seq("c" * limit, "d" * limit)
      partContents(str(attributes, "gen_ai.output.messages")) shouldBe Seq("b" * limit)
    }

    "cut tool-call arguments and tool responses to ContentLimit characters with captureContent" in {
      val arguments = ujson.Obj("query" -> "e" * limit)
      val event = TraceEvent.CompletionReceived(
        id = "chatcmpl-1",
        model = "gpt-4o",
        toolCalls = 0,
        content = "done",
        timestamp = at,
        input = Seq(
          AssistantMessage(None, Seq(ToolCall("call-1", "search", arguments))),
          ToolMessage("f" * (limit + 1), "call-1")
        )
      )
      val (_, attributes) = mapped(event, captureContent = true)
      val parts           = ujson.read(str(attributes, "gen_ai.input.messages")).arr.toSeq.flatMap(_("parts").arr.toSeq)

      val call = parts.find(_("type").str == "tool_call").get
      call("arguments").str shouldBe arguments.render().take(limit)
      call("arguments").str.length shouldBe limit

      parts.find(_("type").str == "tool_call_response").get("response").str shouldBe "f" * limit
    }

    "be a CLIENT span" in {
      kind(TraceEvent.CompletionReceived("chatcmpl-1", "gpt-4o", 0, "hi", at)) shouldBe SpanKind.CLIENT
    }
  }

  "EmbeddingUsageRecorded" should {

    "carry its event type, operation, input count and total tokens" in {
      val (_, attributes) =
        mapped(TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(40, 42), "text-embedding-3-small", "index", 3, at))
      str(attributes, "event.type") shouldBe "embedding-usage"
      str(attributes, "operation") shouldBe "index"
      long(attributes, "input_count") shouldBe 3L
      long(attributes, "gen_ai.usage.total_tokens") shouldBe 42L
    }

    "be a CLIENT span" in {
      kind(TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(1, 1), "m", "index", 1, at)) shouldBe SpanKind.CLIENT
    }
  }

  "ErrorOccurred without an operation" should {

    "be the `Error` span with the error's message, simple class name and context" in {
      val (name, attributes) = mapped(TraceEvent.ErrorOccurred(new IllegalStateException("boom"), "loading config", at))
      name shouldBe "Error"
      str(attributes, "event.type") shouldBe "event-create"
      str(attributes, "error.message") shouldBe "boom"
      str(attributes, "error.type") shouldBe "IllegalStateException"
      str(attributes, "context") shouldBe "loading config"
    }
  }

  "TokenUsageRecorded" should {

    "be `Token Usage - <operation>` with the model and all three token counts" in {
      val (name, attributes) = mapped(TraceEvent.TokenUsageRecorded(TokenUsage(12, 7, 19), "gpt-4o", "summarise", at))
      name shouldBe "Token Usage - summarise"
      str(attributes, "event.type") shouldBe "event-create"
      str(attributes, "gen_ai.request.model") shouldBe "gpt-4o"
      str(attributes, "operation") shouldBe "summarise"
      long(attributes, "gen_ai.usage.input_tokens") shouldBe 12L
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 7L
      long(attributes, "gen_ai.usage.total_tokens") shouldBe 19L
    }

    "be an INTERNAL span" in {
      kind(TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), "m", "op", at)) shouldBe SpanKind.INTERNAL
    }
  }

  "CostRecorded" should {

    "be `Cost - <operation>` with the model, token count, cost and cost type" in {
      val (name, attributes) = mapped(TraceEvent.CostRecorded(0.0125, "gpt-4o", "completion", 500, "total", at))
      name shouldBe "Cost - completion"
      str(attributes, "event.type") shouldBe "cost"
      str(attributes, "gen_ai.request.model") shouldBe "gpt-4o"
      str(attributes, "operation") shouldBe "completion"
      long(attributes, "token_count") shouldBe 500L
      double(attributes, "cost.usd") shouldBe 0.0125
      str(attributes, "cost.type") shouldBe "total"
    }
  }

  "RAGOperationCompleted" should {

    "be `RAG <operation>` with its duration, token counts and cost" in {
      val event = TraceEvent.RAGOperationCompleted(
        operation = "search",
        duration = 250.millis,
        embeddingTokens = Some(30),
        llmPromptTokens = Some(120),
        llmCompletionTokens = Some(45),
        totalCostUsd = Some(0.004),
        timestamp = at
      )
      val (name, attributes) = mapped(event)
      name shouldBe "RAG search"
      str(attributes, "event.type") shouldBe "rag-operation"
      str(attributes, "operation") shouldBe "search"
      long(attributes, "duration_ms") shouldBe 250L
      long(attributes, "embedding_tokens") shouldBe 30L
      long(attributes, "llm_prompt_tokens") shouldBe 120L
      long(attributes, "llm_completion_tokens") shouldBe 45L
      double(attributes, "total_cost_usd") shouldBe 0.004
    }

    "report absent token counts and cost as 0" in {
      val (_, attributes) = mapped(TraceEvent.RAGOperationCompleted("index", 1.second, timestamp = at))
      long(attributes, "embedding_tokens") shouldBe 0L
      long(attributes, "llm_prompt_tokens") shouldBe 0L
      long(attributes, "llm_completion_tokens") shouldBe 0L
      double(attributes, "total_cost_usd") shouldBe 0.0
    }
  }

  "CacheHit" should {

    "be `Cache Hit` with its similarity, threshold and timestamp" in {
      val (name, attributes) = mapped(TraceEvent.CacheHit(0.97, 0.9, at))
      name shouldBe "Cache Hit"
      str(attributes, "event.type") shouldBe "cache-hit"
      double(attributes, "similarity") shouldBe 0.97
      double(attributes, "threshold") shouldBe 0.9
      str(attributes, "timestamp") shouldBe "2026-11-03T10:00:00Z"
    }
  }

  "CacheMiss" should {

    "be `Cache Miss` with its reason's wire value and timestamp" in {
      val (name, attributes) = mapped(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired, at))
      name shouldBe "Cache Miss"
      str(attributes, "event.type") shouldBe "cache-miss"
      str(attributes, "reason") shouldBe "ttl_expired"
      str(attributes, "timestamp") shouldBe "2026-11-03T10:00:00Z"
    }
  }

  "ImageGenerationCompleted" should {

    def image(costUsd: Option[Double] = None, errorMessage: Option[String] = None, success: Boolean = true) =
      TraceEvent.ImageGenerationCompleted(
        model = "gpt-image-1",
        provider = "openai",
        operation = "generate",
        imageCount = 2,
        size = "1024x1024",
        quality = "hd",
        duration = 3.seconds,
        costUsd = costUsd,
        success = success,
        errorMessage = errorMessage,
        timestamp = at
      )

    "be `Image Generation - <operation>` with the request's details" in {
      val (name, attributes) = mapped(image())
      name shouldBe "Image Generation - generate"
      str(attributes, "event.type") shouldBe "image-generation"
      str(attributes, "gen_ai.request.model") shouldBe "gpt-image-1"
      str(attributes, "provider") shouldBe "openai"
      str(attributes, "operation") shouldBe "generate"
      long(attributes, "image_count") shouldBe 2L
      str(attributes, "size") shouldBe "1024x1024"
      str(attributes, "quality") shouldBe "hd"
      long(attributes, "duration_ms") shouldBe 3000L
      bool(attributes, "success") shouldBe true
    }

    "carry cost.usd and error.message only when they are set" in {
      val (_, unset) = mapped(image())
      double(unset, "cost.usd") shouldBe null
      str(unset, "error.message") shouldBe null

      val (_, set) = mapped(image(costUsd = Some(0.08), errorMessage = Some("content policy"), success = false))
      double(set, "cost.usd") shouldBe 0.08
      str(set, "error.message") shouldBe "content policy"
      bool(set, "success") shouldBe false
    }

    "be a CLIENT span" in {
      kind(image()) shouldBe SpanKind.CLIENT
    }
  }

  "The convenience methods" should {

    "export one span each, with the name of the event they trace" in withTracing() { (tracing, spans) =>
      val completion = Completion(
        id = "chatcmpl-1",
        created = at,
        content = "4",
        model = "gpt-4o",
        message = AssistantMessage("4")
      )
      tracing.traceToolCall("search", "{}", "[]") shouldBe Right(())
      tracing.traceError(new IllegalStateException("boom"), "loading config") shouldBe Right(())
      tracing.traceCompletion(completion, "gpt-4o") shouldBe Right(())
      tracing.traceTokenUsage(TokenUsage(12, 7, 19), "gpt-4o", "summarise") shouldBe Right(())

      spans().map(_.getName) shouldBe Vector("execute_tool search", "Error", "chat gpt-4o", "Token Usage - summarise")
    }

    "record traceError's exception on its span" in withTracing() { (tracing, spans) =>
      tracing.traceError(new IllegalStateException("boom"), "loading config") shouldBe Right(())

      val events = spans().loneElement.getEvents.asScala.toVector
      events.map(_.getName) shouldBe Vector("exception")
      val exception = events.head.getAttributes
      str(exception, "exception.type") shouldBe "java.lang.IllegalStateException"
      str(exception, "exception.message") shouldBe "boom"
    }
  }

  extension [A](as: Vector[A])
    private def loneElement: A =
      as should have size 1
      as.head
