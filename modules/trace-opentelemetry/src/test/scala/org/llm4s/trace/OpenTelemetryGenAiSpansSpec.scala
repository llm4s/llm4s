package org.llm4s.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Model calls become spans that follow the OpenTelemetry GenAI semantic conventions (#2275): `chat <model>` and
 * `embeddings <model>` `CLIENT` spans, with `gen_ai.*` attributes, `error.type` on failure, and message content only
 * with `captureContent`, redacted.
 */
class OpenTelemetryGenAiSpansSpec extends AnyWordSpec with Matchers:

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

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
    val tracing  = OpenTelemetryTracing.exportingTo("genai-spec", exporter, captureContent)
    try body(tracing, () => exporter.spans.asScala.toVector)
    finally tracing.shutdown()

  private val Prompt = "What is the capital of France? My key is sk-abcdefghijklmnopqrstuvwxyz0123456789ABCD"
  private val Answer = "Paris is the capital of France."
  private val Secret = "sk-abcdefghijklmnopqrstuvwxyz0123456789ABCD"

  private val conversation = Conversation(Seq(SystemMessage("Be brief."), UserMessage(Prompt)))
  private val options      = CompletionOptions().withTemperature(0.2).withMaxTokens(256)

  // any ProviderConfig: the fixture provider's stands in for a vendor's
  private val fixtureConfig = FixtureChatConfig("key", "gpt-4o", "https://api.example.com/v1")

  /** A client of `provider` answering every call with [[Answer]], or failing with `failure`. */
  private class Stub(provider: String, failure: Option[RateLimitError] = None) extends LLMClient:
    private def answer: Result[Completion] = failure.toLeft(
      Completion(
        id = "chatcmpl-1",
        created = Instant.EPOCH,
        content = Answer,
        model = "gpt-4o-2024-08-06",
        message = AssistantMessage(Answer),
        usage = Some(TokenUsage(12, 7, 19))
      )
    )
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = answer
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      onChunk(StreamedChunk("chatcmpl-1", Some(Answer)))
      onChunk(StreamedChunk("chatcmpl-1", None, finishReason = Some("stop")))
      answer
    def getContextWindow(): Int                 = 128000
    def getReserveCompletion(): Int             = 4096
    override def providerId: Option[ProviderId] = Some(ProviderId(provider))

  private def str(span: SpanData, key: String): String = span.getAttributes.get(AttributeKey.stringKey(key))
  private def long(span: SpanData, key: String): Long  = span.getAttributes.get(AttributeKey.longKey(key)).longValue

  /** Every attribute value and event of every span, as text. */
  private def recorded(spans: Vector[SpanData]): Vector[String] =
    spans.flatMap { s =>
      s.getAttributes.asMap.asScala.values.map(String.valueOf).toVector ++
        s.getEvents.asScala.flatMap(e => e.getName +: e.getAttributes.asMap.asScala.values.map(String.valueOf).toSeq)
    }

  "A completion through TracingLLMClient" should {

    "be one CLIENT span `chat <model>` with the GenAI conventions' attributes" in withTracing() { (tracing, spans) =>
      val client = TracingLLMClient(new Stub("openai"), tracing)
      client.complete(conversation, options).isRight shouldBe true

      val span = spans().loneElement
      span.getName shouldBe "chat gpt-4o-2024-08-06"
      span.getKind shouldBe SpanKind.CLIENT
      str(span, "gen_ai.operation.name") shouldBe "chat"
      str(span, "gen_ai.provider.name") shouldBe "openai"
      str(span, "gen_ai.request.model") shouldBe "gpt-4o-2024-08-06"
      str(span, "gen_ai.response.model") shouldBe "gpt-4o-2024-08-06"
      str(span, "gen_ai.response.id") shouldBe "chatcmpl-1"
      long(span, "gen_ai.usage.input_tokens") shouldBe 12L
      long(span, "gen_ai.usage.output_tokens") shouldBe 7L
      span.getAttributes.get(AttributeKey.doubleKey("gen_ai.request.temperature")) shouldBe 0.2
      long(span, "gen_ai.request.max_tokens") shouldBe 256L
      span.getEndEpochNanos should be >= span.getStartEpochNanos
      span.getStatus.getStatusCode should not be StatusCode.ERROR
      span.getInstrumentationScopeInfo.getSchemaUrl shouldBe OpenTelemetryTracing.SemconvSchemaUrl
    }

    "name the configured request model and its endpoint's host" in withTracing() { (tracing, spans) =>
      TracingLLMClient(new Stub("openai"), tracing, fixtureConfig).complete(conversation, options)

      val span = spans().loneElement
      span.getName shouldBe "chat gpt-4o"
      str(span, "gen_ai.request.model") shouldBe "gpt-4o"
      str(span, "gen_ai.response.model") shouldBe "gpt-4o-2024-08-06"
      str(span, "server.address") shouldBe "api.example.com"
    }

    "record no prompt or completion text by default" in withTracing() { (tracing, spans) =>
      TracingLLMClient(new Stub("openai"), tracing).complete(conversation, options)
      TracingLLMClient(new Stub("openai"), tracing).streamComplete(conversation, options, _ => ())
      tracing.traceEvent(TraceEvent.AgentInitialized(Prompt, Vector("search")))
      tracing.traceEvent(
        TraceEvent.ToolExecuted("search", Prompt, Answer, scala.concurrent.duration.Duration.Zero, true)
      )

      val texts = recorded(spans())
      texts.filter(t => t.contains("capital of France") || t.contains(Secret)) shouldBe empty
      spans().flatMap(s => Option(str(s, "gen_ai.input.messages"))) shouldBe empty
      spans().flatMap(s => Option(str(s, "gen_ai.output.messages"))) shouldBe empty
    }
  }

  "A streamed completion through TracingLLMClient" should {

    "be one CLIENT span `chat <model>` with the stream's finish reason" in withTracing() { (tracing, spans) =>
      val chunks = Vector.newBuilder[StreamedChunk]
      TracingLLMClient(new Stub("anthropic"), tracing).streamComplete(conversation, options, chunks += _)

      chunks.result() should have size 2
      val span = spans().loneElement
      span.getName shouldBe "chat gpt-4o-2024-08-06"
      span.getKind shouldBe SpanKind.CLIENT
      str(span, "gen_ai.operation.name") shouldBe "chat"
      str(span, "gen_ai.provider.name") shouldBe "anthropic"
      span.getAttributes.get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")).asScala shouldBe Seq(
        "stop"
      )
      span.getAttributes.get(AttributeKey.booleanKey("llm4s.model_call.streamed")) shouldBe true
    }
  }

  "A failed call through TracingLLMClient" should {

    "be the call's span with error.type and an ERROR status" in withTracing() { (tracing, spans) =>
      TracingLLMClient(new Stub("gemini", Some(RateLimitError("gemini"))), tracing, fixtureConfig)
        .complete(conversation, options)
        .isLeft shouldBe true

      val span = spans().loneElement
      span.getName shouldBe "chat gpt-4o"
      span.getKind shouldBe SpanKind.CLIENT
      str(span, "gen_ai.operation.name") shouldBe "chat"
      str(span, "gen_ai.provider.name") shouldBe "gcp.gemini"
      str(span, "gen_ai.request.model") shouldBe "gpt-4o"
      str(span, "error.type") shouldBe "RateLimitError"
      span.getStatus.getStatusCode shouldBe StatusCode.ERROR
    }
  }

  "An embedding call through EmbeddingClient" should {

    val model = EmbeddingModelConfig("text-embedding-3-small", 1536)

    def provider(result: Result[EmbeddingResponse]): EmbeddingProvider = new EmbeddingProvider:
      def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = result

    "be one CLIENT span `embeddings <model>`" in withTracing() { (tracing, spans) =>
      val response = EmbeddingResponse(Seq(Seq(0.1, 0.2)), usage = Some(EmbeddingUsage(5, 5)))
      new EmbeddingClient(provider(Right(response)), providerId = Some(ProviderId("openai")))
        .withTracing(tracing)
        .embed(EmbeddingRequest(input = Seq(Prompt), model = model))

      val span = spans().filter(_.getName.startsWith("embeddings")).loneElement
      span.getName shouldBe "embeddings text-embedding-3-small"
      span.getKind shouldBe SpanKind.CLIENT
      str(span, "gen_ai.operation.name") shouldBe "embeddings"
      str(span, "gen_ai.provider.name") shouldBe "openai"
      str(span, "gen_ai.request.model") shouldBe "text-embedding-3-small"
      long(span, "gen_ai.usage.input_tokens") shouldBe 5L
      recorded(spans()).filter(_.contains("capital of France")) shouldBe empty
    }

    "be that span with error.type when it fails" in withTracing() { (tracing, spans) =>
      new EmbeddingClient(provider(Left(RateLimitError("voyage"))), providerId = Some(ProviderId("voyage")))
        .withTracing(tracing)
        .embed(EmbeddingRequest(input = Seq(Prompt), model = model))

      val span = spans().loneElement
      span.getName shouldBe "embeddings text-embedding-3-small"
      span.getKind shouldBe SpanKind.CLIENT
      str(span, "gen_ai.provider.name") shouldBe "voyage"
      str(span, "error.type") shouldBe "RateLimitError"
      span.getStatus.getStatusCode shouldBe StatusCode.ERROR
    }
  }

  "With captureContent" should {

    "record the messages as gen_ai.input.messages and gen_ai.output.messages, secrets redacted" in withTracing(
      captureContent = true
    ) { (tracing, spans) =>
      TracingLLMClient(new Stub("openai"), tracing).streamComplete(conversation, options, _ => ())

      val span   = spans().loneElement
      val input  = ujson.read(str(span, "gen_ai.input.messages"))
      val output = ujson.read(str(span, "gen_ai.output.messages"))
      input.arr.map(_("role").str) shouldBe Seq("system", "user")
      input(1)("parts")(0)("type").str shouldBe "text"
      input(1)("parts")(0)("content").str should include("capital of France")
      output(0)("role").str shouldBe "assistant"
      output(0)("parts")(0)("content").str shouldBe Answer
      output(0)("finish_reason").str shouldBe "stop"
      recorded(spans()).filter(_.contains(Secret)) shouldBe empty
    }

    "record tool calls and tool results as their own message parts" in withTracing(captureContent = true) {
      (tracing, spans) =>
        val call = ToolCall("call-1", "search", ujson.Obj("q" -> "france"))
        val history = Conversation(
          Seq(UserMessage("find it"), AssistantMessage(None, Seq(call)), ToolMessage("Paris", "call-1"))
        )
        TracingLLMClient(new Stub("openai"), tracing).complete(history, options)

        val input = ujson.read(str(spans().loneElement, "gen_ai.input.messages"))
        input.arr.map(_("role").str) shouldBe Seq("user", "assistant", "tool")
        input(1)("parts").arr.loneElement("type").str shouldBe "tool_call"
        input(1)("parts")(0)("name").str shouldBe "search"
        input(1)("parts")(0)("arguments").str should include("france")
        input(2)("parts")(0)("type").str shouldBe "tool_call_response"
        input(2)("parts")(0)("id").str shouldBe "call-1"
        input(2)("parts")(0)("response").str shouldBe "Paris"
    }

    "record an agent's query and tool input and output, redacted" in withTracing(captureContent = true) {
      (tracing, spans) =>
        tracing.traceEvent(TraceEvent.AgentInitialized(Prompt, Vector.empty))
        tracing.traceEvent(TraceEvent.ToolExecuted("t", Prompt, Answer, scala.concurrent.duration.Duration.Zero, true))
        val texts = recorded(spans())
        texts.exists(_.contains("capital of France")) shouldBe true
        texts.filter(_.contains(Secret)) shouldBe empty
    }
  }

  "Agent spans" should {

    val t0 = Instant.parse("2026-10-21T10:00:00Z")

    "keep their names and carry gen_ai.provider.name" in withTracing() { (tracing, spans) =>
      val run = TraceContext.rootFor("run-1")
      tracing.traceEvent(
        TraceEvent.CustomEvent(
          TraceAttributes.AgentModelCallCompleted,
          ujson.Obj(
            "threadId" -> "thread-1",
            "payload"  -> ujson.Obj("agent" -> "assistant", "model" -> "claude-sonnet-4-5", "provider" -> "anthropic")
          ),
          t0.plusSeconds(1),
          Some(TraceSpan(run.spanFor("call"), Some(run), t0))
        )
      )
      tracing.traceEvent(
        TraceEvent.AgentRunEnded(
          "thread-1",
          "run-1",
          "assistant",
          "completed",
          Seq.empty,
          UsageSummary(),
          t0.plusSeconds(2),
          span = Some(TraceSpan(run, None, t0)),
          provider = Some("bedrock")
        )
      )

      val byName = spans().map(s => s.getName -> s).toMap
      str(byName("chat claude-sonnet-4-5"), "gen_ai.provider.name") shouldBe "anthropic"
      str(byName("invoke_agent assistant"), "gen_ai.provider.name") shouldBe "aws.bedrock"
    }
  }

  "gen_ai.provider.name" should {
    "be the conventions' well-known value where there is one, else the provider id" in {
      TraceAttributes.providerName("openai") shouldBe "openai"
      TraceAttributes.providerName("azure") shouldBe "azure.ai.openai"
      TraceAttributes.providerName("vertexai") shouldBe "gcp.vertex_ai"
      TraceAttributes.providerName("ollama") shouldBe "ollama"
    }
  }

  extension [A](as: collection.Seq[A])
    private def loneElement: A =
      as should have size 1
      as.head
