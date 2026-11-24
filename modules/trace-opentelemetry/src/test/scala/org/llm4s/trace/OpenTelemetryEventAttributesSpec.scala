package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  EmbeddingUsage,
  FinishReason,
  ServiceTier,
  TokenUsage,
  ToolCall,
  ToolCallId,
  ToolMessage,
  UserMessage
}
import org.llm4s.types.ProviderModelTypes.ProviderId
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
    finally tracing.close()

  private def mapped(event: TraceEvent, captureContent: Boolean = false): (String, Attributes) =
    val tracing = OpenTelemetryTracing.exportingTo("event-attributes-spec", new Collecting, captureContent)
    try tracing.mapEventToAttributes(event)
    finally tracing.close()

  private def kind(event: TraceEvent): SpanKind =
    val tracing = OpenTelemetryTracing.exportingTo("event-attributes-spec", new Collecting)
    try tracing.getSpanKind(event)
    finally tracing.close()

  private def str(a: Attributes, key: String): String              = a.get(AttributeKey.stringKey(key))
  private def long(a: Attributes, key: String): java.lang.Long     = a.get(AttributeKey.longKey(key))
  private def double(a: Attributes, key: String): java.lang.Double = a.get(AttributeKey.doubleKey(key))
  private def bool(a: Attributes, key: String): java.lang.Boolean  = a.get(AttributeKey.booleanKey(key))

  /** The `content` of every part of every message in a `gen_ai.*.messages` attribute. */
  private def partContents(json: String): Seq[String] =
    ujson.read(json).arr.toSeq.flatMap(_("parts").arr.toSeq.flatMap(_.obj.get("content").map(_.str)))

  private val at    = Instant.parse("2026-11-03T10:00:00Z")
  private val limit = TraceAttributes.ContentLimit

  "CompletionReceived" should {

    "cut each message part to ContentLimit characters with captureContent" in {
      val event = TraceEvent.CompletionReceived(
        id = "chatcmpl-1",
        model = ModelName("gpt-4o"),
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
        model = ModelName("gpt-4o"),
        toolCalls = 0,
        content = "done",
        timestamp = at,
        input = Seq(
          AssistantMessage(None, Seq(ToolCall(ToolCallId("call-1"), "search", arguments))),
          ToolMessage("f" * (limit + 1), ToolCallId("call-1"))
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
      kind(TraceEvent.CompletionReceived("chatcmpl-1", ModelName("gpt-4o"), 0, "hi", at)) shouldBe SpanKind.CLIENT
    }

    "report the output tokens as billed: the completion plus the thinking it leaves out" in {
      val event = TraceEvent.CompletionReceived(
        id = "chatcmpl-1",
        model = ModelName("o3"),
        toolCalls = 0,
        content = "hi",
        timestamp = at,
        usage = Some(TokenUsage(81, 18, 145, thinkingTokens = Some(46)))
      )
      val (_, attributes) = mapped(event)
      long(attributes, "gen_ai.usage.input_tokens") shouldBe 81L
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 64L
    }

    // #2750: the conventions' openai.response.service_tier on OpenAI's spans only, llm4s.service_tier on every one
    "record the served tier, as openai.response.service_tier for OpenAI and Azure OpenAI" in {
      def tiers(provider: Option[String], tier: Option[ServiceTier]) = {
        val event = TraceEvent
          .CompletionReceived("chatcmpl-1", ModelName("gpt-4o"), 0, "hi", at)
          .withProvider(provider.map(ProviderId(_)))
          .withServedTier(tier)
        val (_, attributes) = mapped(event)
        (Option(str(attributes, "llm4s.service_tier")), Option(str(attributes, "openai.response.service_tier")))
      }
      tiers(Some("openai"), Some(ServiceTier.Flex)) shouldBe ((Some("flex"), Some("flex")))
      tiers(Some("azure"), Some(ServiceTier.Other("scale"))) shouldBe ((Some("scale"), Some("scale")))
      tiers(Some("bedrock"), Some(ServiceTier.Priority)) shouldBe ((Some("priority"), None))
      tiers(None, Some(ServiceTier.Default)) shouldBe ((Some("default"), None))
      tiers(Some("openai"), None) shouldBe ((None, None))
    }

    "record the served tier traceCompletion is given" in withTracing() { (tracing, spans) =>
      val completion = Completion("chatcmpl-1", at, ModelName("gpt-4o"), AssistantMessage("hi"))
        .withServedTier(ServiceTier.Auto)
      tracing.traceCompletion(completion, ModelName("gpt-4o")) shouldBe Right(())
      spans().loneElement.getAttributes.get(AttributeKey.stringKey("llm4s.service_tier")) shouldBe "auto"
    }
  }

  "EmbeddingUsageRecorded" should {

    "carry its event type, operation, input count and total tokens" in {
      val (_, attributes) =
        mapped(
          TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(40, 42), ModelName("text-embedding-3-small"), "index", 3, at)
        )
      str(attributes, "event.type") shouldBe "embedding-usage"
      str(attributes, "operation") shouldBe "index"
      long(attributes, "input_count") shouldBe 3L
      long(attributes, "gen_ai.usage.total_tokens") shouldBe 42L
    }

    "be a CLIENT span" in {
      kind(
        TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(1, 1), ModelName("m"), "index", 1, at)
      ) shouldBe SpanKind.CLIENT
    }
  }

  "ErrorOccurred without an operation" should {

    "be the `Error` span with the error's message, simple class name and context" in {
      val (name, attributes) =
        mapped(TraceEvent.ErrorOccurred.of(new IllegalStateException("boom"), "loading config").withTimestamp(at))
      name shouldBe "Error"
      str(attributes, "event.type") shouldBe "event-create"
      str(attributes, "error.message") shouldBe "boom"
      str(attributes, "error.type") shouldBe "IllegalStateException"
      str(attributes, "context") shouldBe "loading config"
    }
  }

  "ErrorOccurred with an operation" should {

    "be the call's span with the GenAI usage a content-filtered reply was billed (#2676)" in {
      val filtered = TraceEvent.ErrorOccurred(
        "filtered",
        "ContentFilteredError",
        "chat",
        timestamp = at,
        operation = Some("chat"),
        model = Some(ModelName("gemini-2.5-flash")),
        usage = Some(TokenUsage(7, 4, 11, thinkingTokens = Some(2)))
      )
      val (name, attributes) = mapped(filtered)
      name shouldBe "chat gemini-2.5-flash"
      str(attributes, "error.type") shouldBe "ContentFilteredError"
      long(attributes, "gen_ai.usage.input_tokens") shouldBe 7L
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 6L // thinking included, as billed
    }

    "carry no usage when the call reported none" in {
      val (_, attributes) =
        mapped(TraceEvent.ErrorOccurred("x", "RateLimitError", "chat", timestamp = at, operation = Some("chat")))
      long(attributes, "gen_ai.usage.input_tokens") shouldBe null
    }
  }

  "TokenUsageRecorded" should {

    "be `Token Usage - <operation>` with the model and all three token counts" in {
      val (name, attributes) =
        mapped(TraceEvent.TokenUsageRecorded(TokenUsage(12, 7, 19), ModelName("gpt-4o"), "summarise", at))
      name shouldBe "Token Usage - summarise"
      str(attributes, "event.type") shouldBe "event-create"
      str(attributes, "gen_ai.request.model") shouldBe "gpt-4o"
      str(attributes, "operation") shouldBe "summarise"
      long(attributes, "gen_ai.usage.input_tokens") shouldBe 12L
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 7L
      long(attributes, "gen_ai.usage.total_tokens") shouldBe 19L
    }

    "be an INTERNAL span" in {
      kind(TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), ModelName("m"), "op", at)) shouldBe SpanKind.INTERNAL
    }

    "count thinking tokens in the output tokens, since completionTokens leaves them out" in {
      val usage           = TokenUsage(81, 18, 145, thinkingTokens = Some(46))
      val (_, attributes) = mapped(TraceEvent.TokenUsageRecorded(usage, ModelName("o3"), "chat", at))
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 64L
      long(attributes, "gen_ai.usage.total_tokens") shouldBe 145L
    }
  }

  "CostRecorded" should {

    "be `Cost - <operation>` with the model, token count, cost and cost type" in {
      val (name, attributes) =
        mapped(TraceEvent.CostRecorded(0.0125, ModelName("gpt-4o"), "completion", 500, "total", at))
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
        timestamp = at,
        stageDurations = Map("search" -> 40.millis, "rerank" -> 110.millis)
      )
      val (name, attributes) = mapped(event)
      long(attributes, "stage_search_ms") shouldBe 40L
      long(attributes, "stage_rerank_ms") shouldBe 110L
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
        model = ModelName("gpt-image-1"),
        operation = "generate",
        imageCount = 2,
        size = "1024x1024",
        quality = "hd",
        duration = 3.seconds,
        costUsd = costUsd,
        success = success,
        errorMessage = errorMessage,
        timestamp = at,
        provider = Some(ProviderId("openai"))
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

  "An agent's model call" should {

    "count the call's thinking tokens in its output tokens" in {
      val payload = ujson.Obj(
        "agent" -> "a",
        "model" -> "o3",
        "usage" -> ujson.Obj(
          "promptTokens"     -> 81,
          "completionTokens" -> 18,
          "totalTokens"      -> 145,
          "thinkingTokens"   -> 46
        )
      )
      val event = TraceEvent.CustomEvent(TraceAttributes.AgentModelCallCompleted, ujson.Obj("payload" -> payload), at)
      val (_, attributes) = mapped(event)
      long(attributes, "gen_ai.usage.input_tokens") shouldBe 81L
      long(attributes, "gen_ai.usage.output_tokens") shouldBe 64L
    }

    "count none when the call reports no thinking" in {
      val payload = ujson.Obj(
        "model" -> "gpt-4o",
        "usage" -> ujson.Obj(
          "promptTokens"     -> 12,
          "completionTokens" -> 7,
          "totalTokens"      -> 19,
          "thinkingTokens"   -> ujson.Null
        )
      )
      val event = TraceEvent.CustomEvent(TraceAttributes.AgentModelCallCompleted, ujson.Obj("payload" -> payload), at)
      long(mapped(event)._2, "gen_ai.usage.output_tokens") shouldBe 7L
    }

    "carry the call's finish reason by name (RFC 0009)" in {
      def reasons(finishReason: Option[FinishReason]): Option[Seq[String]] =
        val payload = ujson.Obj("agent" -> "a", "model" -> "gpt-4o")
        finishReason.foreach(r => payload("finishReason") = upickle.default.writeJs(r))
        val event = TraceEvent.CustomEvent(TraceAttributes.AgentModelCallCompleted, ujson.Obj("payload" -> payload), at)
        Option(mapped(event)._2.get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))).map(_.asScala.toSeq)
      reasons(Some(FinishReason.Length)) shouldBe Some(Seq("length"))
      reasons(Some(FinishReason.ToolCalls)) shouldBe Some(Seq("tool_calls"))
      reasons(Some(FinishReason.Other("SAFETY"))) shouldBe Some(Seq("SAFETY"))
      reasons(Some(FinishReason.Unknown)) shouldBe None
      reasons(None) shouldBe None
    }
  }

  "The span kind" should {

    val failure = new RuntimeException("rate limited")

    "be CLIENT for an event that records a request to a remote service, or its failure" in {
      kind(TraceEvent.CompletionReceived("chatcmpl-1", ModelName("gpt-4o"), 0, "hi", at)) shouldBe SpanKind.CLIENT
      kind(
        TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(1, 1), ModelName("m"), "index", 1, at)
      ) shouldBe SpanKind.CLIENT
      kind(
        TraceEvent.ImageGenerationCompleted(ModelName("gpt-image-1"), "generate", 1, "1024x1024", "hd", 1.second)
      ) shouldBe SpanKind.CLIENT
      kind(
        TraceEvent.ErrorOccurred.of(failure, "chat").withTimestamp(at).withOperation("chat")
      ) shouldBe SpanKind.CLIENT
      kind(TraceEvent.CustomEvent(TraceAttributes.AgentModelCallCompleted, ujson.Obj(), at)) shouldBe SpanKind.CLIENT
    }

    "be INTERNAL for an event that calls no remote service (#2531)" in {
      kind(TraceEvent.ErrorOccurred.of(failure, "loading config").withTimestamp(at)) shouldBe SpanKind.INTERNAL
      kind(
        TraceEvent.CostRecorded(0.01, ModelName("gpt-4o"), "completion", 500, "total", at)
      ) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.CacheHit(0.97, 0.9, at)) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired, at)) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.RAGOperationCompleted("index", 1.second, timestamp = at)) shouldBe SpanKind.INTERNAL
      kind(
        TraceEvent.ToolExecuted("search", ToolCallId("in").asString, "out", 100.millis, true, at)
      ) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.CustomEvent(TraceAttributes.AgentToolExecuted, ujson.Obj(), at)) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.CustomEvent("x", ujson.Obj(), at)) shouldBe SpanKind.INTERNAL
      kind(
        TraceEvent.CustomEvent("x", ujson.Obj(), at, Some(TraceSpan(TraceContext.random(), None, at)))
      ) shouldBe SpanKind.INTERNAL
      kind(TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), ModelName("m"), "op", at)) shouldBe SpanKind.INTERNAL
    }

    "be exported as INTERNAL for a cache hit" in withTracing() { (tracing, spans) =>
      tracing.traceEvent(TraceEvent.CacheHit(0.97, 0.9, at)) shouldBe Right(())
      spans().map(_.getKind) shouldBe Vector(SpanKind.INTERNAL)
    }
  }

  "The convenience methods" should {

    "export one span each, with the name of the event they trace" in withTracing() { (tracing, spans) =>
      val completion = Completion(
        id = "chatcmpl-1",
        created = at,
        model = ModelName("gpt-4o"),
        message = AssistantMessage("4")
      )
      tracing.traceToolCall(
        "search",
        "{}",
        "[]",
        scala.concurrent.duration.Duration.Zero,
        success = true
      ) shouldBe Right(())
      tracing.traceError(new IllegalStateException("boom"), "loading config") shouldBe Right(())
      tracing.traceCompletion(completion, ModelName("gpt-4o")) shouldBe Right(())
      tracing.traceTokenUsage(TokenUsage(12, 7, 19), ModelName("gpt-4o"), "summarise") shouldBe Right(())

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

    "record an LLMError's type and message, and no exception: it is not one (#2800)" in withTracing() {
      (tracing, spans) =>
        val error = org.llm4s.error.ProcessingError("store", "store down")
        tracing.traceError(error, "commit") shouldBe Right(())

        val span = spans().loneElement
        span.getName shouldBe "Error"
        str(span.getAttributes, "error.type") shouldBe "ProcessingError"
        str(span.getAttributes, "error.message") shouldBe error.message
        span.getEvents.asScala shouldBe empty
    }
  }

  extension [A](as: Vector[A])
    private def loneElement: A =
      as should have size 1
      as.head
