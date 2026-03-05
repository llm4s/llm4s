package org.llm4s.trace

import org.llm4s.llmconnect.model.{ EmbeddingUsage, TokenUsage }

import java.time.Instant
import java.util.UUID

/**
 * Type-safe, sealed hierarchy of trace events emitted during LLM agent execution.
 *
 * Each subtype carries the data relevant to one observable moment in the
 * agent lifecycle (completion received, tool executed, token usage recorded,
 * etc.).  All subtypes are serialisable to JSON via `toJson`, which produces a
 * flat object containing at minimum `"event_type"` and `"timestamp"` fields.
 *
 * == Error truncation ==
 * `ErrorOccurred.toJson` includes only the first 5 frames of the stack trace
 * to limit payload size.
 *
 * == Timestamp default ==
 * Every case class defaults `timestamp` to `Instant.now()` at construction
 * time.  Override explicitly when replaying historical events or writing
 * deterministic tests.
 *
 * @see [[Tracing]] for the interface that consumes these events
 * @see [[TraceEvent.createTraceEvent]] for the Langfuse batch-envelope helper
 */
sealed trait TraceEvent {
  def timestamp: Instant
  def eventType: String
  def toJson: ujson.Value
}

object TraceEvent {

  case class AgentInitialized(
    query: String,
    tools: Vector[String],
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "agent_initialized"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "query"      -> query,
      "tools"      -> ujson.Arr(tools.map(ujson.Str(_)): _*)
    )
  }

  case class CompletionReceived(
    id: String,
    model: String,
    toolCalls: Int,
    content: String,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "completion_received"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "completion_id" -> id,
      "model"         -> model,
      "tool_calls"    -> toolCalls,
      "content"       -> content
    )
  }

  case class ToolExecuted(
    name: String,
    input: String,
    output: String,
    duration: Long,
    success: Boolean,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "tool_executed"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"  -> eventType,
      "timestamp"   -> timestamp.toString,
      "tool_name"   -> name,
      "input"       -> input,
      "output"      -> output,
      "duration_ms" -> duration,
      "success"     -> success
    )
  }

  case class ErrorOccurred(
    error: Throwable,
    context: String,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "error_occurred"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "error_type"    -> error.getClass.getSimpleName,
      "error_message" -> error.getMessage,
      "context"       -> context,
      "stack_trace"   -> error.getStackTrace.take(5).mkString("\n")
    )
  }

  case class TokenUsageRecorded(
    usage: TokenUsage,
    model: String,
    operation: String,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "token_usage_recorded"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"        -> eventType,
      "timestamp"         -> timestamp.toString,
      "model"             -> model,
      "operation"         -> operation,
      "prompt_tokens"     -> usage.promptTokens,
      "completion_tokens" -> usage.completionTokens,
      "total_tokens"      -> usage.totalTokens
    )
  }

  case class AgentStateUpdated(
    status: String,
    messageCount: Int,
    logCount: Int,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "agent_state_updated"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "status"        -> status,
      "message_count" -> messageCount,
      "log_count"     -> logCount
    )
  }

  case class CustomEvent(
    name: String,
    data: ujson.Value,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "custom_event"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "name"       -> name,
      "data"       -> data
    )
  }

  /**
   * Tracks embedding token usage for cost analysis.
   *
   * @param usage Token usage statistics from embedding operation
   * @param model Embedding model name (e.g., "text-embedding-3-small")
   * @param operation Type of operation: "indexing", "query", "evaluation"
   * @param inputCount Number of texts embedded in this operation
   */
  case class EmbeddingUsageRecorded(
    usage: EmbeddingUsage,
    model: String,
    operation: String,
    inputCount: Int,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "embedding_usage_recorded"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "model"         -> model,
      "operation"     -> operation,
      "input_count"   -> inputCount,
      "prompt_tokens" -> usage.promptTokens,
      "total_tokens"  -> usage.totalTokens
    )
  }

  /**
   * Tracks cost in USD for any model operation.
   *
   * @param costUsd Estimated cost in US dollars
   * @param model Model name used for pricing lookup
   * @param operation Type of operation: "embedding", "completion", "evaluation"
   * @param tokenCount Total tokens consumed
   * @param costType Category: "embedding", "completion", "total"
   */
  case class CostRecorded(
    costUsd: Double,
    model: String,
    operation: String,
    tokenCount: Int,
    costType: String,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "cost_recorded"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"  -> eventType,
      "timestamp"   -> timestamp.toString,
      "cost_usd"    -> costUsd,
      "model"       -> model,
      "operation"   -> operation,
      "token_count" -> tokenCount,
      "cost_type"   -> costType
    )
  }

  /**
   * Tracks completion of a RAG operation with full metrics.
   *
   * @param operation Type: "index", "search", "answer", "evaluate"
   * @param durationMs Wall-clock duration in milliseconds
   * @param embeddingTokens Optional token count for embedding operations
   * @param llmPromptTokens Optional prompt tokens for LLM operations
   * @param llmCompletionTokens Optional completion tokens for LLM operations
   * @param totalCostUsd Optional accumulated cost in USD
   */
  case class RAGOperationCompleted(
    operation: String,
    durationMs: Long,
    embeddingTokens: Option[Int] = None,
    llmPromptTokens: Option[Int] = None,
    llmCompletionTokens: Option[Int] = None,
    totalCostUsd: Option[Double] = None,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "rag_operation_completed"
    def toJson: ujson.Value = {
      val base = ujson.Obj(
        "event_type"  -> eventType,
        "timestamp"   -> timestamp.toString,
        "operation"   -> operation,
        "duration_ms" -> durationMs
      )
      embeddingTokens.foreach(t => base("embedding_tokens") = t)
      llmPromptTokens.foreach(t => base("llm_prompt_tokens") = t)
      llmCompletionTokens.foreach(t => base("llm_completion_tokens") = t)
      totalCostUsd.foreach(c => base("total_cost_usd") = c)
      base
    }
  }

  /**
   * Builds a Langfuse `"trace-create"` batch-envelope object.
   *
   * The resulting JSON is suitable for inclusion in the `batch` array sent to
   * the Langfuse ingest endpoint.  A new random UUID is generated for the
   * outer envelope `"id"` on every call; the trace identity is carried by
   * `traceId`.  The `"sessionId"` field is derived from `System.currentTimeMillis()`
   * and therefore reflects wall-clock time at call time, not the event timestamp.
   *
   * @param traceId      Stable identifier for the trace; correlates spans across calls
   * @param now          ISO-8601 timestamp string for both envelope and body `"timestamp"` fields
   * @param environment  Deployment environment label forwarded to Langfuse metadata
   * @param release      Application release string forwarded to Langfuse metadata
   * @param version      Framework version string forwarded to Langfuse metadata
   * @param traceInput   The user query or agent input recorded as the trace input
   * @param traceOutput  The agent's final response recorded as the trace output
   * @param modelName    Model name forwarded to the Langfuse `"model"` field
   * @param messageCount Conversation length forwarded to Langfuse metadata
   */
  def createTraceEvent(
    traceId: String,
    now: String,
    environment: String,
    release: String,
    version: String,
    traceInput: String,
    traceOutput: String,
    modelName: String,
    messageCount: Int
  ): ujson.Obj =
    ujson.Obj(
      "id"        -> UUID.randomUUID().toString,
      "timestamp" -> now,
      "type"      -> "trace-create",
      "body" -> ujson.Obj(
        "id"          -> traceId,
        "timestamp"   -> now,
        "environment" -> environment,
        "release"     -> release,
        "version"     -> version,
        "public"      -> true,
        "name"        -> "LLM4S Agent Run",
        "input"       -> traceInput,
        "output"      -> traceOutput,
        "userId"      -> "llm4s-user",
        "sessionId"   -> s"session-${System.currentTimeMillis()}",
        "model"       -> modelName,
        "metadata" -> ujson.Obj(
          "framework"    -> "llm4s",
          "messageCount" -> messageCount
        ),
        "tags" -> ujson.Arr("llm4s", "agent")
      )
    )

  /**
   * Cache hit event for semantic caching
   */
  case class CacheHit(
    similarity: Double,
    threshold: Double,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "cache_hit"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "similarity" -> similarity,
      "threshold"  -> threshold
    )
  }

  /**
   * Cache miss reason ADT
   */
  sealed trait CacheMissReason {
    def value: String
  }
  object CacheMissReason {
    case object LowSimilarity extends CacheMissReason {
      def value: String = "low_similarity"
    }
    case object TtlExpired extends CacheMissReason {
      def value: String = "ttl_expired"
    }
    case object OptionsMismatch extends CacheMissReason {
      def value: String = "options_mismatch"
    }
  }

  /**
   * Cache miss event for semantic caching
   */
  case class CacheMiss(
    reason: CacheMissReason,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "cache_miss"
    def toJson: ujson.Value = ujson.Obj(
      "event_type" -> eventType,
      "timestamp"  -> timestamp.toString,
      "reason"     -> reason.value
    )
  }

}
