package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.ConfigKeys._
import org.llm4s.config.ConfigReader
import org.llm4s.config.DefaultConfig._
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, TraceHelper }
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class LangfuseTracing(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String,
  environment: String,
  release: String,
  version: String,
  batchSender: LangfuseBatchSender = new DefaultLangfuseBatchSender()
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
  private val config         = LangfuseHttpApiCaller(langfuseUrl, publicKey, secretKey)

  protected[trace] def sendBatch(events: Seq[ujson.Obj]): Unit =
    batchSender.sendBatch(events, config)

  private def determineModelName(seqOfMessages: Seq[Message]): String =
    seqOfMessages
      .collectFirst {
        case am: AssistantMessage if am.toolCalls.nonEmpty => am
      }
      .flatMap(_.toolCalls.headOption.map(_.name))
      .getOrElse("unknown-model")

  override def traceAgentState(state: AgentState): Unit = {
    logger.info("[LangfuseTracing] Exporting agent state to Langfuse.")
    val traceId = uuid
    val now     = nowIso

    val seqOfMessages = state.conversation.messages
    // Trace-create event
    val modelName   = determineModelName(seqOfMessages)
    val traceInput  = if (state.userQuery.nonEmpty) state.userQuery else "No user query"
    val traceOutput = seqOfMessages.lastOption.map(_.content).filter(_.nonEmpty).getOrElse("No output")
    val traceEvent = TraceEvent.createTraceEvent(
      traceId = traceId,
      now = now,
      environment = environment,
      release = release,
      version = version,
      traceInput = traceInput,
      traceOutput = traceOutput,
      modelName = modelName,
      messageCount = seqOfMessages.length
    )
    val batchEvents = List(traceEvent)

    // Observation events for each message
    val allBatchEvents = seqOfMessages.zipWithIndex.foldLeft(batchEvents :+ traceEvent) { case (acc, (msg, idx)) =>
      val event = createEvent(msg, seqOfMessages, idx, now, traceId, modelName)
      event :: acc
    }
    sendBatch(allBatchEvents.reverse)
  }

  // Get conversation context leading up to this generation
  private[trace] def createEvent(
    msg: Message,
    seqOfMessages: Seq[Message],
    idx: Int,
    now: String,
    traceId: String,
    modelName: String
  ): ujson.Obj =
    TraceHelper.createEvent(
      msg,
      uuid = uuid,
      traceId = traceId,
      idx = idx,
      now = now,
      modelName = modelName,
      contextMessages = seqOfMessages.take(idx)
    )

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
