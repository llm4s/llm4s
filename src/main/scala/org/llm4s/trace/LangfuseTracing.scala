package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ AssistantMessage, SystemMessage, ToolMessage, UserMessage }
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.{ Failure, Success, Try }

class LangfuseTracing(
  langfuseUrl: String = sys.env.getOrElse("LANGFUSE_URL", "https://cloud.langfuse.com/api/public/ingestion"),
  publicKey: String = sys.env.getOrElse("LANGFUSE_PUBLIC_KEY", ""),
  secretKey: String = sys.env.getOrElse("LANGFUSE_SECRET_KEY", ""),
  environment: String = sys.env.getOrElse("LANGFUSE_ENV", "production"),
  release: String = sys.env.getOrElse("LANGFUSE_RELEASE", "1.0.0"),
  version: String = sys.env.getOrElse("LANGFUSE_VERSION", "1.0.0")
) extends Tracing {
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
          val generationEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "generation-create",
            "body" -> ujson.Obj(
              "id"              -> s"${traceId}-gen-$idx",
              "traceId"         -> traceId,
              "name"            -> s"Assistant Response $idx",
              "startTime"       -> now,
              "endTime"         -> now,
              "input"           -> ujson.Obj("content" -> (if (am.content.nonEmpty) am.content else "No content")),
              "output"          -> ujson.Obj("toolCalls" -> am.toolCalls.length),
              "model"           -> modelName,
              "modelParameters" -> ujson.Obj(),
              "metadata" -> ujson.Obj(
                "role"          -> am.role,
                "toolCallCount" -> am.toolCalls.length
              )
            )
          )
          batchEvents += generationEvent
        case tm: ToolMessage =>
          val spanEvent = ujson.Obj(
            "id"        -> uuid,
            "timestamp" -> now,
            "type"      -> "span-create",
            "body" -> ujson.Obj(
              "id"        -> s"${traceId}-span-$idx",
              "traceId"   -> traceId,
              "name"      -> s"Tool Execution: ${tm.toolCallId}",
              "startTime" -> now,
              "endTime"   -> now,
              "input"     -> ujson.Obj("toolCallId" -> tm.toolCallId),
              "output"    -> ujson.Obj("content" -> (if (tm.content.nonEmpty) tm.content.take(500) else "No content")),
              "metadata" -> ujson.Obj(
                "role"       -> tm.role,
                "toolCallId" -> tm.toolCallId
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
                "role" -> userMsg.role
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
                "role" -> sysMsg.role
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
                "role" -> msg.role
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
        "input"     -> event,
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
        "input"     -> input,
        "output"    -> output,
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
        "id"        -> uuid,
        "timestamp" -> nowIso,
        "name"      -> "Error",
        "input"     -> error.getMessage,
        "metadata"  -> ujson.Obj("stackTrace" -> error.getStackTrace.mkString("\n"))
      )
    )
    sendBatch(Seq(eventObj))
  }

  override def traceCompletion(completion: org.llm4s.llmconnect.model.Completion, model: String): Unit = {
    logger.info(s"[LangfuseTracing] Completion: model=$model, id=${completion.id}")

    val now = nowIso
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
        "input"     -> ujson.Obj("messageCount" -> 1), // This could be enhanced with actual input
        "output"    -> ujson.Obj("content" -> completion.message.content),
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
          "toolCallCount" -> completion.message.toolCalls.length
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
