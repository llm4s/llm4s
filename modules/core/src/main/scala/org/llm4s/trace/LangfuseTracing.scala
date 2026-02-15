package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.{ Base64, UUID }
import scala.util.control.NonFatal

/**
 * Langfuse [[Tracing]] implementation for production observability.
 *
 * Sends trace events to Langfuse for centralized observability,
 * debugging, and LLM application monitoring. Supports all trace
 * event types with type-safe `Result[Unit]` return values.
 *
 * == Features ==
 *
 *  - Real-time event streaming to Langfuse
 *  - Hierarchical trace structure (traces with child spans)
 *  - Token usage and cost tracking
 *  - Error logging with stack traces
 *  - Agent state snapshots with conversation history
 *
 * == Configuration ==
 *
 * Configure via environment variables or direct instantiation:
 *
 * {{{
 * // Environment variables
 * LANGFUSE_PUBLIC_KEY=pk-lf-...
 * LANGFUSE_SECRET_KEY=sk-lf-...
 * LANGFUSE_URL=https://cloud.langfuse.com (optional)
 *
 * // Or programmatic configuration
 * val tracing = new LangfuseTracing(
 *   langfuseUrl = "https://cloud.langfuse.com",
 *   publicKey = "pk-lf-...",
 *   secretKey = "sk-lf-...",
 *   environment = "production",
 *   release = "1.0.0",
 *   version = "1.0.0"
 * )
 * }}}
 *
 * == Usage ==
 *
 * {{{
 * val tracing: Tracing = LangfuseTracing.from(config)
 *
 * for {
 *   _ <- tracing.traceEvent(TraceEvent.AgentInitialized("query", tools))
 *   _ <- tracing.traceCompletion(completion, "gpt-4")
 *   _ <- tracing.traceTokenUsage(usage, "gpt-4", "completion")
 * } yield ()
 * }}}
 *
 * @param langfuseUrl Langfuse API URL (default: https://cloud.langfuse.com)
 * @param publicKey Langfuse public key for authentication
 * @param secretKey Langfuse secret key for authentication
 * @param environment Environment name (e.g., "production", "staging")
 * @param release Release/version identifier
 * @param version API version
 *
 * @see [[ConsoleTracing]] for local development
 * @see [[NoOpTracing]] for disabled tracing
 * @see [[TracingMode]] for configuration modes
 */
class LangfuseTracing(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String,
  environment: String,
  release: String,
  version: String,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
  restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
) extends Tracing {

  private val logger         = LoggerFactory.getLogger(getClass)
  private def nowIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
  private def uuid: String   = UUID.randomUUID().toString

  protected def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
    if (publicKey.isEmpty || secretKey.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set in environment. Skipping export.")
      logger.warn(s"[Langfuse] Expected environment variables: LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY")
      logger.warn(s"[Langfuse] Current URL: $langfuseUrl")
      return Right(())
    }

    // Ensure URL includes the API ingestion endpoint
    val apiUrl = if (langfuseUrl.endsWith("/api/public/ingestion")) {
      langfuseUrl
    } else {
      langfuseUrl.stripSuffix("/") + "/api/public/ingestion"
    }

    logger.debug(s"[Langfuse] Sending batch to URL: $apiUrl")
    logger.debug(s"[Langfuse] Using public key: ${publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))
    val credentials  = Base64.getEncoder.encodeToString(s"$publicKey:$secretKey".getBytes(StandardCharsets.UTF_8))

    // Catch only non-fatal exceptions. Fatal errors (OOM, StackOverflow, etc.) will crash fast.
    // InterruptedException is handled explicitly to restore the interrupt flag.
    try {
      val response = httpClient.post(
        url = apiUrl,
        headers = Map(
          "Content-Type"  -> "application/json",
          "User-Agent"    -> "llm4s-scala/1.0.0",
          "Authorization" -> s"Basic $credentials"
        ),
        body = batchPayload.render(),
        timeout = 30000
      )

      if (response.statusCode == 207 || (response.statusCode >= 200 && response.statusCode < 300)) {
        logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
        if (response.statusCode == 207) {
          logger.info(
            s"[Langfuse] Partial success response: ${org.llm4s.util.Redaction.truncateForLog(response.body)}"
          )
        }
        Right(())
      } else {
        logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
        logger.error(s"[Langfuse] Response body: ${org.llm4s.util.Redaction.truncateForLog(response.body)}")
        val runtimeException = new RuntimeException(s"Langfuse export failed: ${response.statusCode}")
        Left(UnknownError(runtimeException.getMessage, runtimeException))
      }
    } catch {
      case _: InterruptedException =>
        // Restore interrupt flag for proper thread shutdown and timeout semantics
        restoreInterrupt()
        logger.warn("[Langfuse] Batch export was interrupted.")
        Left(UnknownError("Batch export was interrupted", new InterruptedException()))
      case NonFatal(e) =>
        // Catch all non-fatal exceptions (network errors, etc.)
        // Fatal errors (OutOfMemoryError, StackOverflowError, etc.) will propagate
        logger.error(s"[Langfuse] Batch export failed with exception: ${e.getMessage}", e)
        logger.error(s"[Langfuse] Request URL: $langfuseUrl")
        Left(UnknownError(e.getMessage, e))
    }
  }

  def traceEvent(event: TraceEvent): Result[Unit] = {
    val traceId     = uuid
    val now         = nowIso
    val batchEvents = scala.collection.mutable.ArrayBuffer[ujson.Obj]()

    // Convert event to Langfuse format
    val langfuseEvent = event match {
      case e: TraceEvent.AgentInitialized =>
        ujson.Obj(
          "id"        -> uuid,
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
            "input"       -> e.query,
            "output"      -> s"Tools: ${e.tools.mkString(", ")}",
            "userId"      -> "llm4s-user",
            "sessionId"   -> s"session-${System.currentTimeMillis()}",
            "metadata" -> ujson.Obj(
              "framework" -> "llm4s",
              "tools"     -> e.tools
            ),
            "tags" -> ujson.Arr("llm4s", "agent", "initialized")
          )
        )

      case e: TraceEvent.CompletionReceived =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "generation-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> "LLM Completion",
            "model"     -> e.model,
            "input" -> ujson.Arr(
              ujson.Obj("role" -> "system", "content" -> "LLM4S completion request")
            ),
            "output" -> ujson.Obj(
              "role"    -> "assistant",
              "content" -> e.content
            ),
            "metadata" -> ujson.Obj(
              "completion_id" -> e.id,
              "tool_calls"    -> e.toolCalls
            )
          )
        )

      case e: TraceEvent.ToolExecuted =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "span-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> s"Tool Execution: ${e.name}",
            "input"     -> ujson.Obj("arguments" -> e.input),
            "output"    -> ujson.Obj("result" -> e.output),
            "metadata" -> ujson.Obj(
              "tool_name" -> e.name,
              "duration"  -> e.duration,
              "success"   -> e.success
            )
          )
        )

      case e: TraceEvent.ErrorOccurred =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "event-create",
          "body" -> ujson.Obj(
            "id"            -> uuid,
            "timestamp"     -> now,
            "name"          -> "Error",
            "level"         -> "ERROR",
            "statusMessage" -> e.error.getMessage,
            "input"         -> ujson.Obj("error" -> e.error.getMessage),
            "metadata" -> ujson.Obj(
              "error_type"  -> e.error.getClass.getSimpleName,
              "context"     -> e.context,
              "stack_trace" -> e.error.getStackTrace.take(5).mkString("\n")
            )
          )
        )

      case e: TraceEvent.TokenUsageRecorded =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "event-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> s"Token Usage - ${e.operation}",
            "input" -> ujson.Obj(
              "model"     -> e.model,
              "operation" -> e.operation
            ),
            "output" -> ujson.Obj(
              "prompt_tokens"     -> e.usage.promptTokens,
              "completion_tokens" -> e.usage.completionTokens,
              "total_tokens"      -> e.usage.totalTokens
            ),
            "metadata" -> ujson.Obj(
              "model"      -> e.model,
              "operation"  -> e.operation,
              "token_type" -> "usage"
            )
          )
        )

      case e: TraceEvent.AgentStateUpdated =>
        ujson.Obj(
          "id"        -> uuid,
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
            "input" -> ujson.Obj(
              "status"        -> e.status,
              "message_count" -> e.messageCount,
              "log_count"     -> e.logCount
            ),
            "output" -> ujson.Obj(
              "status"   -> e.status,
              "messages" -> e.messageCount,
              "logs"     -> e.logCount
            ),
            "userId"    -> "llm4s-user",
            "sessionId" -> s"session-${System.currentTimeMillis()}",
            "metadata" -> ujson.Obj(
              "framework"     -> "llm4s",
              "status"        -> e.status,
              "message_count" -> e.messageCount,
              "log_count"     -> e.logCount
            ),
            "tags" -> ujson.Arr("llm4s", "agent", "state-update")
          )
        )

      case e: TraceEvent.CustomEvent =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "event-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> e.name,
            "input"     -> e.data,
            "metadata" -> ujson.Obj(
              "source" -> "custom_event"
            )
          )
        )

      case e: TraceEvent.EmbeddingUsageRecorded =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "event-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> s"Embedding Usage - ${e.operation}",
            "input" -> ujson.Obj(
              "model"       -> e.model,
              "operation"   -> e.operation,
              "input_count" -> e.inputCount
            ),
            "output" -> ujson.Obj(
              "prompt_tokens" -> e.usage.promptTokens,
              "total_tokens"  -> e.usage.totalTokens
            ),
            "metadata" -> ujson.Obj(
              "model"       -> e.model,
              "operation"   -> e.operation,
              "input_count" -> e.inputCount,
              "token_type"  -> "embedding_usage"
            )
          )
        )

      case e: TraceEvent.CostRecorded =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "event-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> s"Cost - ${e.operation}",
            "level"     -> "DEFAULT",
            "input" -> ujson.Obj(
              "model"       -> e.model,
              "operation"   -> e.operation,
              "token_count" -> e.tokenCount,
              "cost_type"   -> e.costType
            ),
            "output" -> ujson.Obj(
              "cost_usd" -> e.costUsd
            ),
            "metadata" -> ujson.Obj(
              "model"       -> e.model,
              "operation"   -> e.operation,
              "token_count" -> e.tokenCount,
              "cost_type"   -> e.costType,
              "cost_usd"    -> e.costUsd
            )
          )
        )

      case e: TraceEvent.CacheHit =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "span-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> "Cache Hit",
            "level"     -> "DEFAULT",
            "input" -> ujson.Obj(
              "similarity" -> e.similarity,
              "threshold"  -> e.threshold
            ),
            "output" -> ujson.Obj(
              "result" -> "hit"
            ),
            "metadata" -> ujson.Obj(
              "similarity" -> e.similarity,
              "threshold"  -> e.threshold
            )
          )
        )

      case e: TraceEvent.CacheMiss =>
        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "span-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> "Cache Miss",
            "level"     -> "DEFAULT",
            "input" -> ujson.Obj(
              "reason" -> e.reason.value
            ),
            "output" -> ujson.Obj(
              "result" -> "miss"
            ),
            "metadata" -> ujson.Obj(
              "reason" -> e.reason.value
            )
          )
        )

      case e: TraceEvent.RAGOperationCompleted =>
        val outputObj = ujson.Obj(
          "operation"   -> e.operation,
          "duration_ms" -> e.durationMs
        )
        e.embeddingTokens.foreach(t => outputObj("embedding_tokens") = t)
        e.llmPromptTokens.foreach(t => outputObj("llm_prompt_tokens") = t)
        e.llmCompletionTokens.foreach(t => outputObj("llm_completion_tokens") = t)
        e.totalCostUsd.foreach(c => outputObj("total_cost_usd") = c)

        val metadataObj = ujson.Obj(
          "operation"   -> e.operation,
          "duration_ms" -> e.durationMs
        )
        e.embeddingTokens.foreach(t => metadataObj("embedding_tokens") = t)
        e.llmPromptTokens.foreach(t => metadataObj("llm_prompt_tokens") = t)
        e.llmCompletionTokens.foreach(t => metadataObj("llm_completion_tokens") = t)
        e.totalCostUsd.foreach(c => metadataObj("total_cost_usd") = c)

        ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> now,
          "type"      -> "span-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "name"      -> s"RAG ${e.operation}",
            "input" -> ujson.Obj(
              "operation" -> e.operation
            ),
            "output"   -> outputObj,
            "metadata" -> metadataObj
          )
        )
    }

    batchEvents += langfuseEvent
    sendBatch(batchEvents.toSeq)
  }

  def traceAgentState(state: AgentState): Result[Unit] = {
    // Send hierarchical structure: one main trace with child spans for each message
    if (state.conversation.messages.nonEmpty) {
      val batchEvents = scala.collection.mutable.ArrayBuffer[ujson.Obj]()
      val traceId     = uuid
      val sessionId   = s"session-${System.currentTimeMillis()}"

      // Get the first user message and last assistant message for main trace input/output
      val firstUserMessage = state.conversation.messages.find(_.isInstanceOf[org.llm4s.llmconnect.model.UserMessage])
      val lastAssistantMessage =
        state.conversation.messages.findLast(_.isInstanceOf[org.llm4s.llmconnect.model.AssistantMessage])

      // 1. Create the main trace with exact input/output like old system
      val mainTrace = ujson.Obj(
        "id"        -> traceId,
        "timestamp" -> nowIso,
        "type"      -> "trace-create",
        "body" -> ujson.Obj(
          "id"          -> traceId,
          "timestamp"   -> nowIso,
          "environment" -> environment,
          "release"     -> release,
          "version"     -> version,
          "public"      -> true,
          "name"        -> "LLM4S Agent Run",
          "input"       -> ujson.Str(firstUserMessage.map(_.content).getOrElse("No user input")),
          "output"      -> ujson.Str(lastAssistantMessage.map(_.content).getOrElse("No response")),
          "userId"      -> "llm4s-user",
          "sessionId"   -> sessionId,
          "metadata" -> ujson.Obj(
            "framework"     -> "llm4s",
            "status"        -> state.status.toString,
            "message_count" -> state.conversation.messages.length,
            "log_count"     -> state.logs.length
          ),
          "tags" -> ujson.Arr("llm4s", "agent", "conversation")
        )
      )
      batchEvents += mainTrace

      // 2. Create child spans for each message
      state.conversation.messages.zipWithIndex.foreach { case (message, index) =>
        val messageName = message match {
          case _: org.llm4s.llmconnect.model.SystemMessage    => "System Message"
          case _: org.llm4s.llmconnect.model.UserMessage      => "User Input"
          case _: org.llm4s.llmconnect.model.AssistantMessage => "LLM Generation"
          case _                                              => message.getClass.getSimpleName
        }

        val childSpan = ujson.Obj(
          "id"        -> uuid,
          "timestamp" -> nowIso,
          "type"      -> "span-create",
          "body" -> ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> nowIso,
            "traceId"   -> traceId,
            "name"      -> s"$messageName $index",
            "input" -> ujson.Obj(
              "content" -> message.content,
              "role"    -> message.getClass.getSimpleName.replace("Message", "").toLowerCase
            ),
            "output" -> (message match {
              case _: org.llm4s.llmconnect.model.AssistantMessage =>
                ujson.Obj(
                  "content" -> message.content,
                  "role"    -> "assistant"
                )
              case _ =>
                ujson.Null
            }),
            "metadata" -> ujson.Obj(
              "framework"     -> "llm4s",
              "message_index" -> index,
              "message_type"  -> message.getClass.getSimpleName,
              "parent_trace"  -> traceId
            ),
            "tags" -> ujson.Arr("llm4s", "agent", "conversation", "message")
          )
        )
        batchEvents += childSpan
      }

      sendBatch(batchEvents.toSeq)
    }

    Right(())
  }

  def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
    val event = TraceEvent.ToolExecuted(toolName, input, output, 0, true)
    traceEvent(event)
  }

  def traceError(error: Throwable, context: String): Result[Unit] = {
    val event = TraceEvent.ErrorOccurred(error, context)
    traceEvent(event)
  }

  def traceCompletion(completion: Completion, model: String): Result[Unit] = {
    val event = TraceEvent.CompletionReceived(
      id = completion.id,
      model = model,
      toolCalls = completion.message.toolCalls.size,
      content = completion.message.content
    )
    traceEvent(event)
  }

  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
    val event = TraceEvent.TokenUsageRecorded(usage, model, operation)
    traceEvent(event)
  }
}

/**
 * Factory methods for [[LangfuseTracing]].
 */
object LangfuseTracing {

  /**
   * Create a LangfuseTracing instance from configuration.
   *
   * @param cfg Langfuse configuration with URL, keys, and metadata
   * @return Configured LangfuseTracing instance
   */
  def from(cfg: org.llm4s.llmconnect.config.LangfuseConfig): LangfuseTracing =
    new LangfuseTracing(
      langfuseUrl = cfg.url,
      publicKey = cfg.publicKey.getOrElse(""),
      secretKey = cfg.secretKey.getOrElse(""),
      environment = cfg.env,
      release = cfg.release,
      version = cfg.version
    )
}
