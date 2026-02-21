package org.llm4s.trace

import cats.Id
import org.llm4s.trace.model.{ SpanKind, SpanStatus }
import org.llm4s.trace.store.InMemoryTraceStore
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TraceCollectorTracingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var store: InMemoryTraceStore            = _
  private var collector: TraceCollectorTracing[Id] = _

  override def beforeEach(): Unit = {
    store = InMemoryTraceStore()
    collector = TraceCollectorTracing(store).getOrElse(fail("could not create TraceCollectorTracing"))
  }

  "TraceCollectorTracing" should "convert ToolExecuted event to ToolCall span" in {
    val event = TraceEvent.ToolExecuted("test-tool", "input", "output", 100L, true)
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.ToolCall
    span.attributes("tool_name").asString shouldBe Some("test-tool")
    span.attributes("input").asString shouldBe Some("input")
    span.attributes("output").asString shouldBe Some("output")
    span.attributes("duration_ms").asLong shouldBe Some(100L)
    span.attributes("success").asBoolean shouldBe Some(true)
    span.status shouldBe SpanStatus.Ok
  }

  it should "convert ErrorOccurred event to Error status span" in {
    val error = new RuntimeException("test error")
    val event = TraceEvent.ErrorOccurred(error, "test-context")
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.Internal
    span.status shouldBe a[SpanStatus.Error]
    span.attributes("error_type").asString shouldBe Some("RuntimeException")
    span.attributes("error_message").asString shouldBe Some("test error")
    span.attributes("context").asString shouldBe Some("test-context")
  }

  it should "convert TokenUsageRecorded event to span with token attributes" in {
    import org.llm4s.llmconnect.model.TokenUsage
    val usage = TokenUsage(10, 20, 30)
    val event = TraceEvent.TokenUsageRecorded(usage, "gpt-4", "completion")
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.attributes("model").asString shouldBe Some("gpt-4")
    span.attributes("operation").asString shouldBe Some("completion")
    span.attributes("prompt_tokens").asLong shouldBe Some(10L)
    span.attributes("completion_tokens").asLong shouldBe Some(20L)
    span.attributes("total_tokens").asLong shouldBe Some(30L)
  }

  it should "convert EmbeddingUsageRecorded to Embedding span" in {
    import org.llm4s.llmconnect.model.EmbeddingUsage
    val usage = EmbeddingUsage(100, 100)
    val event = TraceEvent.EmbeddingUsageRecorded(usage, "text-embedding-3-small", "indexing", 5)
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.Embedding
    span.attributes("model").asString shouldBe Some("text-embedding-3-small")
    span.attributes("operation").asString shouldBe Some("indexing")
    span.attributes("input_count").asLong shouldBe Some(5L)
  }

  it should "convert RAGOperationCompleted to Rag span" in {
    val event = TraceEvent.RAGOperationCompleted("search", 500L, Some(100), Some(50), Some(20), Some(0.01))
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.Rag
    span.attributes("operation").asString shouldBe Some("search")
    span.attributes("duration_ms").asLong shouldBe Some(500L)
    span.attributes("embedding_tokens").asLong shouldBe Some(100L)
    span.attributes("llm_prompt_tokens").asLong shouldBe Some(50L)
    span.attributes("llm_completion_tokens").asLong shouldBe Some(20L)
    span.attributes("total_cost_usd").asDouble shouldBe Some(0.01)
  }

  it should "convert CacheHit to Cache span" in {
    val event = TraceEvent.CacheHit(0.95, 0.8)
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.Cache
    span.attributes("similarity").asDouble shouldBe Some(0.95)
    span.attributes("threshold").asDouble shouldBe Some(0.8)
    span.attributes("hit").asBoolean shouldBe Some(true)
  }

  it should "convert CacheMiss to Cache span" in {
    val event = TraceEvent.CacheMiss(TraceEvent.CacheMissReason.LowSimilarity)
    collector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    val span = spans.head
    span.kind shouldBe SpanKind.Cache
    span.attributes("reason").asString shouldBe Some("low_similarity")
    span.attributes("hit").asBoolean shouldBe Some(false)
  }

  it should "convert AgentInitialized and AgentStateUpdated to AgentCall spans" in {
    val initEvent   = TraceEvent.AgentInitialized("test query", Vector("tool1", "tool2"))
    val updateEvent = TraceEvent.AgentStateUpdated("running", 5, 10)
    collector.traceEvent(initEvent) shouldBe Right(())
    collector.traceEvent(updateEvent) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 2
    spans.foreach(_.kind shouldBe SpanKind.AgentCall)
  }

  it should "convert CustomEvent and CostRecorded to Internal spans" in {
    val customEvent = TraceEvent.CustomEvent("my-event", ujson.Obj("key" -> "value"))
    val costEvent   = TraceEvent.CostRecorded(0.05, "gpt-4", "completion", 1000, "total")
    collector.traceEvent(customEvent) shouldBe Right(())
    collector.traceEvent(costEvent) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 2
    spans.foreach(_.kind shouldBe SpanKind.Internal)
  }

  it should "not throw when a CustomEvent array contains non-string elements" in {
    val event = TraceEvent.CustomEvent(
      "mixed-array",
      ujson.Obj("values" -> ujson.Arr(ujson.Str("ok"), ujson.Num(1.0), ujson.Bool(true)))
    )
    collector.traceEvent(event) shouldBe Right(())
    val span = store.getSpans(collector.traceId).head
    span.attributes("values").asStringList shouldBe Some(List("ok"))
  }

  it should "be composable with ConsoleTracing via TracingComposer.combine()" in {
    val console  = new ConsoleTracing()
    val combined = TracingComposer.combine(collector, console)

    val event = TraceEvent.ToolExecuted("composite-tool", "in", "out", 50L, true)
    combined.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    spans.head.attributes("tool_name").asString shouldBe Some("composite-tool")
  }

  it should "be composable with filtered tracing" in {
    val filteredCollector = TracingComposer.filter(collector)(_.eventType == "tool_executed")

    val toolEvent  = TraceEvent.ToolExecuted("tool", "in", "out", 10L, true)
    val otherEvent = TraceEvent.AgentInitialized("query", Vector.empty)

    filteredCollector.traceEvent(toolEvent) shouldBe Right(())
    filteredCollector.traceEvent(otherEvent) shouldBe Right(())

    val spans = store.getSpans(collector.traceId)
    spans should have size 1
    spans.head.attributes("tool_name").asString shouldBe Some("tool")
  }

  it should "use custom traceId when provided" in {
    val customTraceId = org.llm4s.types.TraceId("custom-trace-123")
    val customCollector =
      TraceCollectorTracing(store, customTraceId).getOrElse(fail("could not create custom collector"))

    val event = TraceEvent.ToolExecuted("tool", "in", "out", 10L, true)
    customCollector.traceEvent(event) shouldBe Right(())

    val spans = store.getSpans(customTraceId)
    spans should have size 1
  }
}
