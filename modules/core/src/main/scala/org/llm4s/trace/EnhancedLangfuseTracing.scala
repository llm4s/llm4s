package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.ConfigKeys._
import org.llm4s.config.DefaultConfig._
import org.llm4s.config.ConfigReader
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.Try

/**
 * Enhanced Langfuse tracing with type-safe events
 */
class EnhancedLangfuseTracing(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String,
  environment: String,
  release: String,
  version: String
) extends EnhancedTracing {

  private val logger         = LoggerFactory.getLogger(getClass)
  private def nowIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
  private def uuid: String   = UUID.randomUUID().toString

  private def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
    if (publicKey.isEmpty || secretKey.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set in environment. Skipping export.")
      logger.warn(s"[Langfuse] Expected environment variables: LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY")
      logger.warn(s"[Langfuse] Current URL: $langfuseUrl")
      return Right(())
    }

    logger.debug(s"[Langfuse] Sending batch to URL: $langfuseUrl")
    logger.debug(s"[Langfuse] Using public key: ${publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))

    val attempt = Try {
      val response = requests.post(
        langfuseUrl,
        data = batchPayload.render(),
        headers = Map(
          "Content-Type" -> "application/json",
          "User-Agent"   -> "llm4s-scala/1.0.0"
        ),
        auth = (publicKey, secretKey),
        readTimeout = 30000,
        connectTimeout = 30000
      )
      response
    }
    attempt.toEither.left
      .map { e =>
        logger.error(s"[Langfuse] Batch export failed with exception: ${e.getMessage}", e)
        logger.error(s"[Langfuse] Request URL: $langfuseUrl")
        UnknownError(e.getMessage, e)
      }
      .flatMap { response =>
        if (response.statusCode == 207 || (response.statusCode >= 200 && response.statusCode < 300)) {
          logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
          if (response.statusCode == 207) {
            logger.info(s"[Langfuse] Partial success response: ${response.text()}")
          }
          Right(())
        } else {
          logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
          logger.error(s"[Langfuse] Response body: ${response.text()}")
          val runtimeException = new RuntimeException(s"Langfuse export failed: ${response.statusCode}")
          Left(UnknownError(runtimeException.getMessage, runtimeException))
        }
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

object EnhancedLangfuseTracing {
  def apply(reader: ConfigReader) =
    new EnhancedLangfuseTracing(
      langfuseUrl = reader.getOrElse(LANGFUSE_URL, DEFAULT_LANGFUSE_URL),
      publicKey = reader.getOrElse(LANGFUSE_PUBLIC_KEY, ""),
      secretKey = reader.getOrElse(LANGFUSE_SECRET_KEY, ""),
      environment = reader.getOrElse(LANGFUSE_ENV, DEFAULT_LANGFUSE_ENV),
      release = reader.getOrElse(LANGFUSE_RELEASE, DEFAULT_LANGFUSE_RELEASE),
      version = reader.getOrElse(LANGFUSE_VERSION, DEFAULT_LANGFUSE_VERSION)
    )
}
