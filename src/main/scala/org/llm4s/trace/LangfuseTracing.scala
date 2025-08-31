package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.{ ConfigKeys, ConfigReader }
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, SystemMessage, ToolMessage, UserMessage }
import org.slf4j.LoggerFactory
import ConfigKeys._
import org.llm4s.config.DefaultConfig._
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.{ Failure, Success, Try }

class LangfuseTracing(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String,
  environment: String,
  release: String,
  version: String
) extends Tracing {

  // Factory to build from any ConfigReader without internal fallbacks
  def this(reader: ConfigReader) =
    this(
      langfuseUrl = reader.getOrElse(LANGFUSE_URL, DEFAULT_LANGFUSE_URL),
      publicKey = reader.getOrElse(LANGFUSE_PUBLIC_KEY, ""),
      secretKey = reader.getOrElse(LANGFUSE_SECRET_KEY, ""),
      environment = reader.getOrElse(LANGFUSE_ENV, DEFAULT_LANGFUSE_ENV),
      release = reader.getOrElse(LANGFUSE_RELEASE, DEFAULT_LANGFUSE_RELEASE),
      version = reader.getOrElse(LANGFUSE_VERSION, DEFAULT_LANGFUSE_VERSION)
    )
  private val logger         = LoggerFactory.getLogger(getClass)
  private def nowIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
  private def uuid: String   = UUID.randomUUID().toString

  private def sendBatch(events: Seq[ujson.Obj]): Unit = {
    if (publicKey.isEmpty || secretKey.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set in environment. Skipping export.")
      logger.warn(s"[Langfuse] Expected environment variables: LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY")
      logger.warn(s"[Langfuse] Current URL: $langfuseUrl")
      return
    }

    logger.debug(s"[Langfuse] Sending batch to URL: $langfuseUrl")
    logger.debug(s"[Langfuse] Using public key: ${publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))

    Try {
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

      if (response.statusCode == 207 || (response.statusCode >= 200 && response.statusCode < 300)) {
        logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
        if (response.statusCode == 207) {
          logger.info(s"[Langfuse] Partial success response: ${response.text()}")
        }
      } else {
        logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
        logger.error(s"[Langfuse] Response body: ${response.text()}")
        logger.error(s"[Langfuse] Request URL: $langfuseUrl")
        logger.error(s"[Langfuse] Request payload size: ${batchPayload.render().length} bytes")
      }
    } match {
      case Failure(e) =>
        logger.error(s"[Langfuse] Batch export failed with exception: ${e.getMessage}", e)
        logger.error(s"[Langfuse] Request URL: $langfuseUrl")
      case Success(_) =>
    }
  }

  override def traceAgentState(state: AgentState): Unit = {
    logger.info("[LangfuseTracing] Exporting agent state to Langfuse.")
    val traceId     = uuid
    val now         = nowIso
    val batchEvents = scala.collection.mutable.ArrayBuffer[ujson.Obj]()

    // Trace-create event
    val traceInput  = if (state.userQuery.nonEmpty) state.userQuery else "No user query"
    val traceOutput = state.conversation.messages.lastOption.map(_.content).filter(_.nonEmpty).getOrElse("No output")
    val modelName = state.conversation.messages
      .collectFirst {
        case am: AssistantMessage if am.toolCalls.nonEmpty => am
      }
      .flatMap(_.toolCalls.headOption.map(_.name))
      .getOrElse("unknown-model")
    val traceEvent = ujson.Obj(
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
        "input"       -> traceInput,
        "output"      -> traceOutput,
        "userId"      -> "llm4s-user",
        "sessionId"   -> s"session-${System.currentTimeMillis()}",
        "model"       -> modelName,
        "metadata" -> ujson.Obj(
          "framework"    -> "llm4s",
          "messageCount" -> state.conversation.messages.length
        ),
        "tags" -> ujson.Arr("llm4s", "agent")
      )
    )
    batchEvents += traceEvent

    // Observation events for each message
    state.conversation.messages.zipWithIndex.foreach { case (msg, idx) =>
      msg match {
        case am: AssistantMessage if am.toolCalls.nonEmpty =>
          // Get conversation context leading up to this generation
          val contextMessages = state.conversation.messages.take(idx)
          val conversationInput = contextMessages.map(msg =>
            ujson.Obj(
              "role"    -> msg.role.name,
              "content" -> msg.content
            )
          )

          // Create proper output with assistant response and tool calls
          val generationOutput = ujson.Obj(
            "role"    -> MessageRole.Assistant.name,
            "content" -> am.content,
            "tool_calls" -> ujson.Arr(
              am.toolCalls.map(tc =>
                ujson.Obj(
                  "id"   -> tc.id,
                  "type" -> "function",
                  "function" -> ujson.Obj(
                    "name"      -> tc.name,
                    "arguments" -> tc.arguments.render()
                  )
                )
              ): _*
            )
          )

          val generationEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "generation-create",
            "body" -> ujson.Obj(
              "id"              -> s"${traceId}-gen-$idx",
              "traceId"         -> traceId,
              "name"            -> s"LLM Generation $idx",
              "startTime"       -> now,
              "endTime"         -> now,
              "input"           -> ujson.Arr(conversationInput: _*),
              "output"          -> generationOutput,
              "model"           -> modelName,
              "modelParameters" -> ujson.Obj(),
              "metadata" -> ujson.Obj(
                "messageIndex"  -> idx,
                "toolCallCount" -> am.toolCalls.length
              )
            )
          )
          batchEvents += generationEvent
        case am: AssistantMessage =>
          // Handle regular assistant messages without tool calls
          val contextMessages = state.conversation.messages.take(idx)
          val conversationInput = contextMessages.map(msg =>
            ujson.Obj(
              "role"    -> msg.role.name,
              "content" -> msg.content
            )
          )

          val generationOutput = ujson.Obj(
            "role"    -> "assistant",
            "content" -> am.content
          )

          val generationEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "generation-create",
            "body" -> ujson.Obj(
              "id"              -> s"${traceId}-gen-$idx",
              "traceId"         -> traceId,
              "name"            -> s"LLM Generation $idx",
              "startTime"       -> now,
              "endTime"         -> now,
              "input"           -> ujson.Arr(conversationInput: _*),
              "output"          -> generationOutput,
              "model"           -> modelName,
              "modelParameters" -> ujson.Obj(),
              "metadata" -> ujson.Obj(
                "messageIndex"  -> idx,
                "toolCallCount" -> 0
              )
            )
          )
          batchEvents += generationEvent
        case tm: ToolMessage =>
          // Find the corresponding tool call for this tool message
          val toolCallName = state.conversation.messages
            .take(idx)
            .collect { case am: AssistantMessage => am.toolCalls }
            .flatten
            .find(_.id == tm.toolCallId)
            .map(_.name)
            .getOrElse("unknown-tool")

          val spanEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "span-create",
            "body" -> ujson.Obj(
              "id"        -> s"${traceId}-span-$idx",
              "traceId"   -> traceId,
              "name"      -> s"Tool: $toolCallName",
              "startTime" -> now,
              "endTime"   -> now,
              "input" -> ujson.Obj(
                "toolCallId" -> tm.toolCallId,
                "toolName"   -> toolCallName
              ),
              "output" -> ujson.Obj(
                "result" -> tm.content
              ),
              "metadata" -> ujson.Obj(
                "role"       -> tm.role.name,
                "toolCallId" -> tm.toolCallId,
                "toolName"   -> toolCallName,
              )
            )
          )
          batchEvents += spanEvent
        case userMsg: UserMessage =>
          val eventEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "event-create",
            "body" -> ujson.Obj(
              "id"        -> s"${traceId}-event-$idx",
              "traceId"   -> traceId,
              "name"      -> s"User Input $idx",
              "startTime" -> now,
              "input"     -> ujson.Obj("content" -> userMsg.content),
              "metadata" -> ujson.Obj(
                "role" -> userMsg.role.name
              )
            )
          )
          batchEvents += eventEvent
        case sysMsg: SystemMessage =>
          val eventEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "event-create",
            "body" -> ujson.Obj(
              "id"        -> s"${traceId}-event-$idx",
              "traceId"   -> traceId,
              "name"      -> s"System Message $idx",
              "startTime" -> now,
              "input"     -> ujson.Obj("content" -> sysMsg.content),
              "metadata" -> ujson.Obj(
                "role" -> sysMsg.role.name
              )
            )
          )
          batchEvents += eventEvent
        case _ =>
          val eventEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "event-create",
            "body" -> ujson.Obj(
              "id"        -> s"${traceId}-event-$idx",
              "traceId"   -> traceId,
              "name"      -> s"Message $idx: ${msg.role}",
              "startTime" -> now,
              "input"     -> ujson.Obj("content" -> msg.content),
              "metadata" -> ujson.Obj(
                "role" -> msg.role.name
              )
            )
          )
          batchEvents += eventEvent
      }
    }
    sendBatch(batchEvents.toSeq)
  }

  override def traceEvent(event: String): Unit = {
    logger.info(s"[LangfuseTracing] Event: $event")
    val eventObj = ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> nowIso,
      "type"      -> "event-create",
      "body" -> ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> nowIso,
        "name"      -> "Custom Event",
        "input"     -> ujson.Obj("event" -> event),
        "metadata"  -> ujson.Obj("source" -> "traceEvent")
      )
    )
    sendBatch(Seq(eventObj))
  }

  override def traceToolCall(toolName: String, input: String, output: String): Unit = {
    logger.info(s"[LangfuseTracing] Tool call: $toolName, input: $input, output: $output")
    val eventObj = ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> nowIso,
      "type"      -> "span-create",
      "body" -> ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> nowIso,
        "name"      -> s"Tool Call: $toolName",
        "input"     -> ujson.Obj("arguments" -> input),
        "output"    -> ujson.Obj("result" -> output),
        "metadata"  -> ujson.Obj("toolName" -> toolName)
      )
    )
    sendBatch(Seq(eventObj))
  }

  override def traceError(error: Throwable): Unit = {
    logger.error("[LangfuseTracing] Error occurred", error)
    val eventObj = ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> nowIso,
      "type"      -> "event-create",
      "body" -> ujson.Obj(
        "id"            -> uuid,
        "timestamp"     -> nowIso,
        "name"          -> "Error",
        "level"         -> "ERROR",
        "statusMessage" -> error.getMessage,
        "input"         -> ujson.Obj("error" -> error.getMessage),
        "metadata" -> ujson.Obj(
          "errorType"  -> error.getClass.getSimpleName,
          "stackTrace" -> error.getStackTrace.take(5).mkString("\n")
        )
      )
    )
    sendBatch(Seq(eventObj))
  }

  override def traceCompletion(completion: org.llm4s.llmconnect.model.Completion, model: String): Unit = {
    logger.info(s"[LangfuseTracing] Completion: model=$model, id=${completion.id}")

    val now = nowIso

    // Create meaningful input structure
    val completionInput = ujson.Obj(
      "model"         -> model,
      "completion_id" -> completion.id,
      "created"       -> completion.created
    )

    // Create proper output structure with complete message content
    val completionOutput = ujson.Obj(
      "role"    -> completion.message.role.name,
      "content" -> completion.message.content
    )

    // Add tool calls if present
    if (completion.message.toolCalls.nonEmpty) {
      completionOutput("tool_calls") = ujson.Arr(
        completion.message.toolCalls.map(tc =>
          ujson.Obj(
            "id"   -> tc.id,
            "type" -> "function",
            "function" -> ujson.Obj(
              "name"      -> tc.name,
              "arguments" -> tc.arguments.render()
            )
          )
        ): _*
      )
    }

    val generationEvent = ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> now,
      "type"      -> "generation-create",
      "body" -> ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> now,
        "name"      -> "LLM Completion",
        "startTime" -> now,
        "endTime"   -> now,
        "model"     -> model,
        "input"     -> completionInput,
        "output"    -> completionOutput,
        "usage" -> completion.usage
          .map { usage =>
            ujson.Obj(
              "promptTokens"     -> usage.promptTokens,
              "completionTokens" -> usage.completionTokens,
              "totalTokens"      -> usage.totalTokens
            )
          }
          .getOrElse(ujson.Null),
        "metadata" -> ujson.Obj(
          "completionId"  -> completion.id,
          "created"       -> completion.created,
          "toolCallCount" -> completion.message.toolCalls.length,
          "standalone"    -> true
        )
      )
    )
    sendBatch(Seq(generationEvent))
  }

  override def traceTokenUsage(usage: org.llm4s.llmconnect.model.TokenUsage, model: String, operation: String): Unit = {
    logger.info(s"[LangfuseTracing] Token usage: $operation with $model - ${usage.totalTokens} tokens")

    val eventObj = ujson.Obj(
      "id"        -> uuid,
      "timestamp" -> nowIso,
      "type"      -> "event-create",
      "body" -> ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> nowIso,
        "name"      -> s"Token Usage - $operation",
        "input" -> ujson.Obj(
          "model"     -> model,
          "operation" -> operation
        ),
        "output" -> ujson.Obj(
          "promptTokens"     -> usage.promptTokens,
          "completionTokens" -> usage.completionTokens,
          "totalTokens"      -> usage.totalTokens
        ),
        "metadata" -> ujson.Obj(
          "model"     -> model,
          "operation" -> operation,
          "tokenType" -> "usage"
        )
      )
    )
    sendBatch(Seq(eventObj))
  }
}
