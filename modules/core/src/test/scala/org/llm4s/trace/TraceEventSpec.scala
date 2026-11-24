package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.{ AssistantMessage, EmbeddingUsage, TokenUsage, UsageSummary, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/**
 * Tests for TraceEvent sealed trait and all event subtypes
 */
class TraceEventSpec extends AnyFlatSpec with Matchers {

  private val fixedTimestamp = Instant.parse("2024-01-15T10:30:00Z")

  // ============ CompletionReceived ============

  "TraceEvent.CompletionReceived" should "have correct event type" in {
    val event = TraceEvent.CompletionReceived("comp-123", ModelName("gpt-4"), 2, "Hello world", fixedTimestamp)

    event.eventType shouldBe "completion_received"
    event.id shouldBe "comp-123"
    event.model shouldBe ModelName("gpt-4")
    event.toolCalls shouldBe 2
    event.content shouldBe "Hello world"
  }

  it should "serialize to JSON correctly" in {
    val event = TraceEvent.CompletionReceived("comp-123", ModelName("gpt-4"), 2, "Hello", fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "completion_received"
    json("completion_id").str shouldBe "comp-123"
    json("model").str shouldBe "gpt-4"
    json("tool_calls").num shouldBe 2
    json("content").str shouldBe "Hello"
  }

  // ============ ToolExecuted ============

  "TraceEvent.ToolExecuted" should "have correct event type" in {
    val event = TraceEvent.ToolExecuted("calculator", "{\"a\": 1}", "3", 150.millis, true, fixedTimestamp)

    event.eventType shouldBe "tool_executed"
    event.name shouldBe "calculator"
    event.input shouldBe "{\"a\": 1}"
    event.output shouldBe "3"
    event.duration shouldBe 150.millis
    event.success shouldBe true
  }

  it should "serialize to JSON correctly" in {
    val event = TraceEvent.ToolExecuted("calc", "input", "output", 100.millis, true, fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "tool_executed"
    json("tool_name").str shouldBe "calc"
    json("input").str shouldBe "input"
    json("output").str shouldBe "output"
    json("duration_ms").value.toString shouldBe "100"
    json("success").bool shouldBe true
  }

  it should "track failed executions" in {
    val event = TraceEvent.ToolExecuted("tool", "input", "error", 50.millis, false, fixedTimestamp)

    event.success shouldBe false
    event.toJson("success").bool shouldBe false
  }

  // ============ ErrorOccurred ============

  "TraceEvent.ErrorOccurred" should "have correct event type" in {
    val error = new RuntimeException("Test error")
    val event = TraceEvent.ErrorOccurred.of(error, "test context").withTimestamp(fixedTimestamp)

    event.eventType shouldBe "error_occurred"
    event.message shouldBe "Test error"
    event.errorType shouldBe "RuntimeException"
    event.cause shouldBe Some(error)
    event.context shouldBe "test context"
  }

  it should "carry an LLMError's message and type, and no stack trace (#2800)" in {
    val error = org.llm4s.error.ProcessingError("store", "store down")
    val json  = TraceEvent.ErrorOccurred.of(error, "commit").withTimestamp(fixedTimestamp).toJson

    json("error_type").str shouldBe "ProcessingError"
    json("error_message").str shouldBe error.message
    json("context").str shouldBe "commit"
    json("stack_trace").str shouldBe ""
  }

  it should "serialize to JSON with error details" in {
    val error = new IllegalArgumentException("Bad argument")
    val event = TraceEvent.ErrorOccurred.of(error, "validation").withTimestamp(fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "error_occurred"
    json("error_type").str shouldBe "IllegalArgumentException"
    json("error_message").str shouldBe "Bad argument"
    json("context").str shouldBe "validation"
    json("stack_trace").str should not be empty
  }

  // ============ TokenUsageRecorded ============

  "TraceEvent.TokenUsageRecorded" should "have correct event type" in {
    val usage = TokenUsage(100, 50, 150)
    val event = TraceEvent.TokenUsageRecorded(usage, ModelName("gpt-4"), "completion", fixedTimestamp)

    event.eventType shouldBe "token_usage_recorded"
    event.usage shouldBe usage
    event.model shouldBe ModelName("gpt-4")
    event.operation shouldBe "completion"
  }

  it should "serialize to JSON correctly" in {
    val usage = TokenUsage(100, 50, 150)
    val event = TraceEvent.TokenUsageRecorded(usage, ModelName("gpt-4"), "completion", fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "token_usage_recorded"
    json("model").str shouldBe "gpt-4"
    json("operation").str shouldBe "completion"
    json("prompt_tokens").num shouldBe 100
    json("completion_tokens").num shouldBe 50
    json("total_tokens").num shouldBe 150
  }

  // ============ AgentRunEnded ============

  "AgentRunEnded" should "serialise a flat summary without messages" in {
    val usage = UsageSummary().add(ModelName("m"), TokenUsage(10, 5, 15), None)
    val e = TraceEvent.AgentRunEnded(
      "thread-1",
      "run-1",
      "assistant",
      TraceEvent.RunStatus.Completed,
      Seq(UserMessage("hi"), AssistantMessage("hello")),
      usage,
      Instant.EPOCH
    )
    e.eventType shouldBe "agent_run_ended"
    val json = e.toJson
    json("thread_id").str shouldBe "thread-1"
    json("run_id").str shouldBe "run-1"
    json("agent").str shouldBe "assistant"
    json("status").str shouldBe "completed"
    json("message_count").num shouldBe 2
    json("input_tokens").num shouldBe 10
    json("output_tokens").num shouldBe 5
    json.obj.contains("messages") shouldBe false
    json.obj.contains("blocked_by") shouldBe false
    json.obj.contains("active_agent") shouldBe false
  }

  it should "name a blocked run's guardrail beside its status, and the agent a handoff left active (#2829)" in {
    val json = TraceEvent
      .AgentRunEnded("thread-1", "run-1", "triage", TraceEvent.RunStatus.Blocked, Seq.empty, UsageSummary())
      .withBlockedBy("pii")
      .withActiveAgent("billing")
      .toJson
    json("status").str shouldBe "blocked"
    json("blocked_by").str shouldBe "pii"
    json("agent").str shouldBe "triage"
    json("active_agent").str shouldBe "billing"
  }

  "TraceEvent.RunStatus" should "write each status under one name, and call only failed, cancelled and timed out errors" in {
    TraceEvent.RunStatus.values.map(s => s.name -> s.isError).toSeq shouldBe Seq(
      "completed"           -> false,
      "step_limit_reached"  -> false,
      "token_limit_reached" -> false,
      "truncated"           -> false,
      "suspended"           -> false,
      "blocked"             -> false,
      "cancelled"           -> true,
      "timed_out"           -> true,
      "failed"              -> true
    )
  }

  // ============ CustomEvent ============

  "TraceEvent.CustomEvent" should "have correct event type" in {
    val data  = ujson.Obj("key" -> "value")
    val event = TraceEvent.CustomEvent("my_event", data, fixedTimestamp)

    event.eventType shouldBe "custom_event"
    event.name shouldBe "my_event"
    event.data shouldBe data
  }

  it should "serialize to JSON correctly" in {
    val data  = ujson.Obj("foo" -> "bar", "num" -> 42)
    val event = TraceEvent.CustomEvent("custom", data, fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "custom_event"
    json("name").str shouldBe "custom"
    json("data")("foo").str shouldBe "bar"
    json("data")("num").num shouldBe 42
  }

  // ============ EmbeddingUsageRecorded ============

  "TraceEvent.EmbeddingUsageRecorded" should "have correct event type" in {
    val usage = EmbeddingUsage(500, 500)
    val event =
      TraceEvent.EmbeddingUsageRecorded(usage, ModelName("text-embedding-3-small"), "indexing", 10, fixedTimestamp)

    event.eventType shouldBe "embedding_usage_recorded"
    event.usage shouldBe usage
    event.model shouldBe ModelName("text-embedding-3-small")
    event.operation shouldBe "indexing"
    event.inputCount shouldBe 10
  }

  it should "serialize to JSON correctly" in {
    val usage = EmbeddingUsage(500, 500)
    val event =
      TraceEvent.EmbeddingUsageRecorded(usage, ModelName("text-embedding-3-small"), "query", 5, fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "embedding_usage_recorded"
    json("model").str shouldBe "text-embedding-3-small"
    json("operation").str shouldBe "query"
    json("input_count").num shouldBe 5
    json("prompt_tokens").num shouldBe 500
    json("total_tokens").num shouldBe 500
  }

  // ============ CostRecorded ============

  "TraceEvent.CostRecorded" should "have correct event type" in {
    val event = TraceEvent.CostRecorded(0.0025, ModelName("gpt-4"), "completion", 1000, "total", fixedTimestamp)

    event.eventType shouldBe "cost_recorded"
    event.costUsd shouldBe 0.0025
    event.model shouldBe ModelName("gpt-4")
    event.operation shouldBe "completion"
    event.tokenCount shouldBe 1000
    event.costType shouldBe "total"
  }

  it should "serialize to JSON correctly" in {
    val event = TraceEvent.CostRecorded(0.005, ModelName("gpt-4"), "completion", 2000, "embedding", fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "cost_recorded"
    json("cost_usd").num shouldBe 0.005
    json("model").str shouldBe "gpt-4"
    json("operation").str shouldBe "completion"
    json("token_count").num shouldBe 2000
    json("cost_type").str shouldBe "embedding"
  }

  // ============ RAGOperationCompleted ============

  "TraceEvent.RAGOperationCompleted" should "have correct event type" in {
    val event = TraceEvent.RAGOperationCompleted("search", 150.millis, timestamp = fixedTimestamp)

    event.eventType shouldBe "rag_operation_completed"
    event.operation shouldBe "search"
    event.duration shouldBe 150.millis
  }

  it should "serialize to JSON with optional fields" in {
    val event = TraceEvent.RAGOperationCompleted(
      "answer",
      500.millis,
      Some(100),
      Some(200),
      Some(50),
      Some(0.003),
      fixedTimestamp
    )

    val json = event.toJson

    json("event_type").str shouldBe "rag_operation_completed"
    json("operation").str shouldBe "answer"
    json("duration_ms").value.toString shouldBe "500"
    json("embedding_tokens").num.toInt shouldBe 100
    json("llm_prompt_tokens").num.toInt shouldBe 200
    json("llm_completion_tokens").num.toInt shouldBe 50
    json("total_cost_usd").num shouldBe 0.003
  }

  it should "omit optional fields when not provided" in {
    val event = TraceEvent.RAGOperationCompleted("index", 200.millis, timestamp = fixedTimestamp)

    val json = event.toJson

    json("event_type").str shouldBe "rag_operation_completed"
    json("operation").str shouldBe "index"
    json("duration_ms").value.toString shouldBe "200"
    json.obj.contains("embedding_tokens") shouldBe false
    json.obj.contains("llm_prompt_tokens") shouldBe false
    json.obj.contains("llm_completion_tokens") shouldBe false
    json.obj.contains("total_cost_usd") shouldBe false
    json.obj.contains("stage_durations_ms") shouldBe false
  }

  it should "serialize its stage durations in milliseconds (#2734)" in {
    val event = TraceEvent
      .RAGOperationCompleted("answer", 500.millis, timestamp = fixedTimestamp)
      .withStageDurations(Map("search" -> 40.millis, "answer" -> 300.millis))

    event.stageDurations("search") shouldBe 40.millis
    val stages = event.toJson("stage_durations_ms").obj
    stages.keys.toSeq shouldBe Seq("answer", "search")
    stages("search").num shouldBe 40
  }

  // ============ TraceEvent Trait ============

  "TraceEvent" should "have timestamp on all events" in {
    val events: Seq[TraceEvent] = Seq(
      TraceEvent.CompletionReceived("id", ModelName("model"), 0, "c", fixedTimestamp),
      TraceEvent.ToolExecuted("t", "i", "o", 0.millis, true, fixedTimestamp),
      TraceEvent.ErrorOccurred.of(new Exception(), "").withTimestamp(fixedTimestamp),
      TraceEvent.TokenUsageRecorded(TokenUsage(0, 0, 0), ModelName("m"), "o", fixedTimestamp),
      TraceEvent
        .AgentRunEnded("t", "r", "a", TraceEvent.RunStatus.Completed, Seq.empty, UsageSummary(), fixedTimestamp),
      TraceEvent.CustomEvent("n", ujson.Obj(), fixedTimestamp),
      TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(0, 0), ModelName("m"), "o", 0, fixedTimestamp),
      TraceEvent.CostRecorded(0.0, ModelName("m"), "o", 0, "t", fixedTimestamp),
      TraceEvent.RAGOperationCompleted("o", 0.millis, timestamp = fixedTimestamp)
    )

    events.foreach(event => event.timestamp shouldBe fixedTimestamp)
  }

  it should "serialize all events to JSON" in {
    val events: Seq[TraceEvent] = Seq(
      TraceEvent.CompletionReceived("id", ModelName("model"), 0, "c"),
      TraceEvent.ToolExecuted("t", "i", "o", 0.millis, true),
      TraceEvent.ErrorOccurred.of(new Exception("e"), ""),
      TraceEvent.TokenUsageRecorded(TokenUsage(0, 0, 0), ModelName("m"), "o"),
      TraceEvent.AgentRunEnded("t", "r", "a", TraceEvent.RunStatus.Completed, Seq.empty, UsageSummary()),
      TraceEvent.CustomEvent("n", ujson.Obj()),
      TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(0, 0), ModelName("m"), "o", 0),
      TraceEvent.CostRecorded(0.0, ModelName("m"), "o", 0, "t"),
      TraceEvent.RAGOperationCompleted("o", 0.millis)
    )

    events.foreach { event =>
      val json = event.toJson
      json("event_type").str shouldBe event.eventType
      json("timestamp").str should not be empty
    }
  }
}
