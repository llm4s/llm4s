package org.llm4s.trace

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{ EmbeddingUsage, Message, TokenUsage, UsageSummary }

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

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
 */
@Stable
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
    duration: FiniteDuration,
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
      "duration_ms" -> duration.toMillis,
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

  /**
   * An agent run ended. Emitted once per run, from the run's own final state, by the agent's
   * tracing; it carries the turn's conversation for backends that record it (Langfuse turns it into
   * a trace with one span per message). A blocked turn has been removed from the thread by then, so
   * a blocked run carries no messages.
   *
   * @param threadId the conversation's thread; backends use it as the session
   * @param runId    this run
   * @param agent    the agent active when the run ended
   * @param status   `completed`, `suspended`, `step_limit_reached`, `blocked:<guardrail>`,
   *                 `cancelled`, `timed_out` or `failed`
   * @param messages this turn's messages, from its user message on; empty when blocked, cancelled,
   *                 timed out or failed. Not included in [[toJson]], which stays a flat summary.
   * @param usage    this run's own token usage and cost, per model: the model calls the run made,
   *                 never the thread's earlier runs, so a backend can report it per run (as
   *                 OpenTelemetry's per-operation `gen_ai.usage.*`) without double counting
   */
  case class AgentRunEnded(
    threadId: String,
    runId: String,
    agent: String,
    status: String,
    messages: Seq[Message],
    usage: UsageSummary,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "agent_run_ended"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "thread_id"     -> threadId,
      "run_id"        -> runId,
      "agent"         -> agent,
      "status"        -> status,
      "message_count" -> messages.size,
      "input_tokens"  -> usage.inputTokens.toDouble,
      "output_tokens" -> usage.outputTokens.toDouble,
      "total_cost"    -> usage.totalCost.toDouble
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
   * @param duration Wall-clock duration
   * @param embeddingTokens Optional token count for embedding operations
   * @param llmPromptTokens Optional prompt tokens for LLM operations
   * @param llmCompletionTokens Optional completion tokens for LLM operations
   * @param totalCostUsd Optional accumulated cost in USD
   */
  case class RAGOperationCompleted(
    operation: String,
    duration: FiniteDuration,
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
        "duration_ms" -> duration.toMillis
      )
      embeddingTokens.foreach(t => base("embedding_tokens") = t)
      llmPromptTokens.foreach(t => base("llm_prompt_tokens") = t)
      llmCompletionTokens.foreach(t => base("llm_completion_tokens") = t)
      totalCostUsd.foreach(c => base("total_cost_usd") = c)
      base
    }
  }

  /**
   * Tracks completion of an image generation operation.
   *
   * @param model Image generation model name (e.g., "gpt-image-1", "dall-e-3")
   * @param provider Provider name (e.g., "openai", "stability-ai")
   * @param operation Type of operation: "generate", "edit"
   * @param imageCount Number of images generated
   * @param size Image size descriptor (e.g., "1024x1024")
   * @param quality Quality level (e.g., "standard", "hd")
   * @param duration Wall-clock duration
   * @param costUsd Estimated cost in USD, if available
   * @param success Whether the operation succeeded
   * @param errorMessage Error message if the operation failed
   */
  case class ImageGenerationCompleted(
    model: String,
    provider: String,
    operation: String,
    imageCount: Int,
    size: String,
    quality: String,
    duration: FiniteDuration,
    costUsd: Option[Double] = None,
    success: Boolean = true,
    errorMessage: Option[String] = None,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "image_generation_completed"
    def toJson: ujson.Value = {
      val base = ujson.Obj(
        "event_type"  -> eventType,
        "timestamp"   -> timestamp.toString,
        "model"       -> model,
        "provider"    -> provider,
        "operation"   -> operation,
        "image_count" -> imageCount,
        "size"        -> size,
        "quality"     -> quality,
        "duration_ms" -> duration.toMillis.toDouble,
        "success"     -> success
      )
      costUsd.foreach(c => base("cost_usd") = c)
      errorMessage.foreach(m => base("error_message") = m)
      base
    }
  }

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
