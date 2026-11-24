package org.llm4s.trace

import org.llm4s.security.Secret
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.llmconnect.model._
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import java.time.Instant

/** The span, and its Langfuse attributes, each kind of event becomes (#2883). */
class LangfuseTracingEdgeCasesSpec extends AnyFlatSpec with Matchers with LoneElement {

  private def ended(status: TraceEvent.RunStatus, messages: Seq[Message]): TraceEvent.AgentRunEnded =
    TraceEvent.AgentRunEnded("thread-1", "run-1", "assistant", status, messages, UsageSummary())

  private def makeTracing(mockClient: MockHttpClient) = new LangfuseTracing(
    langfuseUrl = "https://cloud.langfuse.com",
    publicKey = Secret("pk-test"),
    secretKey = Secret("sk-test"),
    environment = "test",
    release = "v1.0.0",
    version = "1.0.0",
    httpClient = mockClient,
    restoreInterrupt = () => ()
  )

  /** The spans `event` becomes. */
  private def spansOf(event: TraceEvent): Seq[SentSpan] = {
    val mock = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mock).traceEvent(event) shouldBe Right(())
    mock.postCallCount shouldBe 1
    SentSpan.all(mock.lastBody.get)
  }

  private def spanOf(event: TraceEvent): SentSpan = spansOf(event).loneElement

  "LangfuseTracing" should "send CompletionReceived as a generation with its model" in {
    val span = spanOf(TraceEvent.CompletionReceived("comp-1", "gpt-4", 2, "Hello world"))
    span.kind shouldBe "generation"
    span.name shouldBe "LLM Completion"
    span.model shouldBe Some("gpt-4")
    span.output("content").str shouldBe "Hello world"
    span.meta("completion_id").str shouldBe "comp-1"
    span.meta("tool_calls").num shouldBe 2
    span.hasMeta("service_tier") shouldBe false
    span.has("langfuse.observation.usage_details") shouldBe false
  }

  it should "carry a completion's usage, output as billed, and span its duration" in {
    val at = Instant.parse("2026-11-01T00:00:10Z")
    val span = spanOf(
      TraceEvent.CompletionReceived(
        "c",
        "m",
        0,
        "x",
        timestamp = at,
        duration = Some(2.seconds),
        usage = Some(TokenUsage(10, 4, 14, thinkingTokens = Some(3)))
      )
    )
    span.usage shouldBe ujson.Obj("input" -> 10, "output" -> 7, "total" -> 14)
    span.startTime shouldBe at.minusSeconds(2)
    span.endTime shouldBe at
  }

  it should "carry the served tier in the generation's metadata (#2750)" in {
    def tierOf(trace: LangfuseTracing => Unit): String = {
      val mock = new MockHttpClient(HttpResponse(200, ""))
      trace(makeTracing(mock))
      SentSpan.all(mock.lastBody.get).loneElement.meta("service_tier").str
    }
    tierOf(
      _.traceEvent(TraceEvent.CompletionReceived("c", "m", 0, "x", servedTier = Some(ServiceTier.Priority)))
    ) shouldBe "priority"
    val completion =
      Completion("c", Instant.EPOCH, "m", AssistantMessage("x")).withServedTier(ServiceTier.Other("scale"))
    tierOf(_.traceCompletion(completion, "m")) shouldBe "scale"
  }

  it should "send ErrorOccurred as an ERROR event" in {
    val span = spanOf(TraceEvent.ErrorOccurred.of(new RuntimeException("test error"), "test-context"))
    span.kind shouldBe "event"
    span.name shouldBe "Error"
    span.level shouldBe Some("ERROR")
    span.statusMessage.getOrElse("") should include("test error")
    span.input("error").str should include("test error")
    span.meta("context").str shouldBe "test-context"
    span.meta("error_type").str shouldBe "RuntimeException"
    span.hasMeta("usage") shouldBe false
  }

  it should "carry a failed call's billed usage in the ErrorOccurred event's metadata (#2676)" in {
    val span =
      spanOf(TraceEvent.ErrorOccurred("filtered", "ContentFilteredError", "chat", usage = Some(TokenUsage(7, 4, 11))))
    // the error's own type and message, not those of an exception wrapped around it (#2800)
    span.meta("error_type").str shouldBe "ContentFilteredError"
    span.statusMessage shouldBe Some("filtered")
    span.meta("stack_trace").str shouldBe ""
    // an object in metadata is its JSON text
    val usage = ujson.read(span.meta("usage").str)
    usage("prompt_tokens").num shouldBe 7
    usage("completion_tokens").num shouldBe 4
    usage("total_tokens").num shouldBe 11
  }

  it should "send TokenUsageRecorded as an event with the counts" in {
    val span = spanOf(TraceEvent.TokenUsageRecorded(TokenUsage(100, 50, 150), "gpt-4", "completion"))
    span.kind shouldBe "event"
    span.name should include("Token Usage")
    span.meta("model").str shouldBe "gpt-4"
    span.meta("operation").str shouldBe "completion"
    // The counts themselves, as Langfuse shows them (from #1035 by @kannupriyakalra)
    span.output("prompt_tokens").num shouldBe 100
    span.output("completion_tokens").num shouldBe 50
    span.output("total_tokens").num shouldBe 150
  }

  it should "send an ended run as its trace's root span, with the run's status" in {
    val run = spansOf(ended(TraceEvent.RunStatus.Failed, Seq.empty)).loneElement
    run.setsTrace shouldBe true
    run.meta("status").str shouldBe "failed"
    run.traceMeta("status").str shouldBe "failed"
    run.str("langfuse.environment") shouldBe "test"
    run.str("langfuse.release") shouldBe "v1.0.0"
    run.str("langfuse.version") shouldBe "1.0.0"
  }

  it should "carry the run's usage in the trace's metadata" in {
    val usage = UsageSummary().add("m", TokenUsage(10, 5, 15), Some(0.5)).add("m", TokenUsage(2, 1, 3), None)
    val event = TraceEvent.AgentRunEnded(
      "thread-1",
      "run-1",
      "assistant",
      TraceEvent.RunStatus.Completed,
      Seq(UserMessage("hi")),
      usage
    )
    val trace = spansOf(event).head
    trace.traceMeta("input_tokens").num shouldBe 12
    trace.traceMeta("output_tokens").num shouldBe 6
    trace.traceMeta("total_cost").num shouldBe 0.5
  }

  it should "send a CustomEvent as an event whose input is its data" in {
    val span = spanOf(TraceEvent.CustomEvent("my-event", ujson.Obj("key" -> "value")))
    span.kind shouldBe "event"
    span.name shouldBe "my-event"
    span.input("key").str shouldBe "value"
    span.meta("source").str shouldBe "custom_event"
  }

  it should "send EmbeddingUsageRecorded as an event" in {
    val span = spanOf(
      TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(500, 500), "text-embedding-3-small", "indexing", 10)
    )
    span.kind shouldBe "event"
    span.name should include("Embedding Usage")
    span.meta("model").str shouldBe "text-embedding-3-small"
    span.meta("operation").str shouldBe "indexing"
    span.meta("input_count").num shouldBe 10
    span.output("total_tokens").num shouldBe 500
  }

  it should "send CostRecorded as an event" in {
    val span = spanOf(TraceEvent.CostRecorded(0.005, "gpt-4", "completion", 1000, "total"))
    span.kind shouldBe "event"
    span.name should include("Cost")
    span.level shouldBe Some("DEFAULT")
    span.meta("cost_usd").num shouldBe 0.005
    span.meta("cost_type").str shouldBe "total"
    span.output("cost_usd").num shouldBe 0.005
  }

  it should "send CacheHit and CacheMiss as spans" in {
    val hit = spanOf(TraceEvent.CacheHit(0.95, 0.85))
    hit.kind shouldBe "span"
    hit.name shouldBe "Cache Hit"
    hit.input("similarity").num shouldBe 0.95
    hit.input("threshold").num shouldBe 0.85
    hit.output("result").str shouldBe "hit"

    val miss = spanOf(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired))
    miss.name shouldBe "Cache Miss"
    miss.input("reason").str shouldBe "ttl_expired"
    miss.meta("reason").str shouldBe "ttl_expired"
  }

  it should "send ImageGenerationCompleted as a span, ERROR when it failed" in {
    val ok = spanOf(
      TraceEvent.ImageGenerationCompleted(
        "dall-e-3",
        "generate",
        1,
        "1024x1024",
        "hd",
        3.seconds,
        Some(0.04),
        true,
        None
      )
    )
    ok.kind shouldBe "span"
    ok.name shouldBe "Image Generation"
    ok.level shouldBe Some("DEFAULT")
    ok.output("cost_usd").num shouldBe 0.04
    ok.meta("image_count").num shouldBe 1
    java.time.Duration.between(ok.startTime, ok.endTime).toSeconds shouldBe 3

    val failed = spanOf(
      TraceEvent.ImageGenerationCompleted("m", "generate", 0, "s", "q", 1.second, None, false, Some("quota"))
    )
    failed.level shouldBe Some("ERROR")
    failed.statusMessage shouldBe Some("quota")
    failed.output("error_message").str shouldBe "quota"
  }

  it should "send RAGOperationCompleted with all optional fields" in {
    val span = spanOf(
      TraceEvent
        .RAGOperationCompleted("search", 200.millis, Some(100), Some(200), Some(50), Some(0.003))
        .withStageDurations(Map("search" -> 40.millis))
    )
    span.kind shouldBe "span"
    span.name shouldBe "RAG search"
    span.output("embedding_tokens").num.toInt shouldBe 100
    span.output("llm_prompt_tokens").num.toInt shouldBe 200
    span.output("llm_completion_tokens").num.toInt shouldBe 50
    span.output("total_cost_usd").num shouldBe 0.003
    span.output("stage_durations_ms")("search").num shouldBe 40
    span.meta("total_cost_usd").num shouldBe 0.003
    span.hasMeta("stage_durations_ms") shouldBe false
  }

  it should "send RAGOperationCompleted without optional fields" in {
    val output = spanOf(TraceEvent.RAGOperationCompleted("index", 500.millis)).output
    output.obj.contains("embedding_tokens") shouldBe false
    output.obj.contains("total_cost_usd") shouldBe false
  }

  // =========================================================================
  // An ended run carrying the conversation: its trace, session = thread, one span per message.
  // =========================================================================

  it should "send a run's trace with one span per message under it" in {
    val spans = spansOf(
      ended(
        TraceEvent.RunStatus.Completed,
        Seq(SystemMessage("You are helpful"), UserMessage("Hello"), AssistantMessage(Some("Hi there!"), Seq.empty))
      )
    )
    // 1 root span + 3 message spans, in one export
    spans should have size 4

    val root = spans.head
    root.name shouldBe "LLM4S Agent Run"
    root.str("langfuse.trace.name") shouldBe "LLM4S Agent Run"
    root.input.str shouldBe "Hello"
    root.output.str shouldBe "Hi there!"
    root.traceId shouldBe TraceContext.rootFor("run-1").traceId
    root.str("session.id") shouldBe "thread-1"
    root.traceMeta("status").str shouldBe "completed"
    root.traceMeta("agent").str shouldBe "assistant"
    root.traceMeta("message_count").num shouldBe 3
    root.attr("langfuse.trace.public") shouldBe ujson.Bool(false)

    spans.tail.map(_.name) shouldBe Seq("System Message 0", "User Input 1", "LLM Generation 2")
    spans.tail.foreach(_.kind shouldBe "span")
    spans(2).input("role").str shouldBe "user"
    spans(2).hasOutput shouldBe false
    spans(3).output("content").str shouldBe "Hi there!"
  }

  it should "attach every message span to the conversation's trace, tool calls included" in {
    // From #1035 by @kannupriyakalra: the spans are only one trace in Langfuse if they share its id.
    val call = ToolCall(ToolCallId("call-1"), "calculator", ujson.Obj("a" -> 1, "b" -> 2))
    val spans = spansOf(
      ended(
        TraceEvent.RunStatus.Completed,
        Seq(
          UserMessage("1 + 2?"),
          AssistantMessage(None, Seq(call)),
          ToolMessage("3", ToolCallId("call-1")),
          AssistantMessage("3")
        )
      )
    )
    val root     = spans.head
    val messages = spans.tail
    messages should have size 4
    messages.foreach { span =>
      span.traceId shouldBe root.traceId
      span.parentSpanId shouldBe Some(root.spanId)
      span.meta("parent_trace").str shouldBe root.traceId
    }
    messages.map(_.meta("message_type").str) shouldBe
      Seq("UserMessage", "AssistantMessage", "ToolMessage", "AssistantMessage")
    // Every span is its own observation
    spans.map(_.spanId).distinct should have size 5
  }

  it should "send the trace alone when the run carries no messages" in {
    val root = spansOf(ended(TraceEvent.RunStatus.Blocked, Seq.empty).withBlockedBy("profanity")).loneElement
    root.traceMeta("message_count").num shouldBe 0
    root.traceMeta("blocked_by").str shouldBe "profanity"
    root.input.str shouldBe ""
    root.output.str shouldBe ""
    // blocked is not an error
    root.level shouldBe None
  }

  it should "report a failed export of the conversation trace" in {
    val mock = new MockHttpClient(HttpResponse(500, "boom"))
    makeTracing(mock).traceEvent(ended(TraceEvent.RunStatus.Completed, Seq(UserMessage("Hello")))).isLeft shouldBe true
  }

  it should "send traceCompletion as a generation" in {
    val mock = new MockHttpClient(HttpResponse(200, ""))
    val completion = Completion(
      id = "comp-tools",
      created = Instant.EPOCH,
      model = "gpt-4",
      message =
        AssistantMessage(Some("Let me calculate"), Seq(ToolCall(ToolCallId("call-1"), "calculator", ujson.Obj())))
    )
    makeTracing(mock).traceCompletion(completion, "gpt-4") shouldBe Right(())
    val span = SentSpan.all(mock.lastBody.get).loneElement
    span.kind shouldBe "generation"
    span.model shouldBe Some("gpt-4")
    span.output("content").str shouldBe "Let me calculate"
    span.meta("tool_calls").num.toInt shouldBe 1
  }

  it should "give each event that is not placed a trace of its own" in {
    val a = spanOf(TraceEvent.CustomEvent("a", ujson.Obj()))
    val b = spanOf(TraceEvent.CustomEvent("b", ujson.Obj()))
    a.traceId should not be b.traceId
    a.parentSpanId shouldBe None
  }

  "LangfuseTracing.from" should "create instance from LangfuseConfig" in {
    val config = LangfuseConfig(
      url = "https://custom.langfuse.com",
      publicKey = Some(Secret("pk-test")),
      secretKey = Some(Secret("sk-test")),
      env = "production",
      release = "2.0.0",
      version = "2.0.0"
    )
    val mock = new MockHttpClient(HttpResponse(200, ""))
    LangfuseTracing.from(config, mock).traceEvent(TraceEvent.CustomEvent("x", ujson.Obj())) shouldBe Right(())
    mock.lastUrl shouldBe Some("https://custom.langfuse.com/api/public/otel/v1/traces")
    SentSpan.all(mock.lastBody.get).loneElement.str("langfuse.environment") shouldBe "production"
  }

  it should "skip exports when the config has no keys" in {
    LangfuseTracing.from(LangfuseConfig()).traceEvent(TraceEvent.CustomEvent("test", ujson.Obj())) shouldBe Right(())
  }
}
