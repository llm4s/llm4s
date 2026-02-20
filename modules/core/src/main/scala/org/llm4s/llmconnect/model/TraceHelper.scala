package org.llm4s.llmconnect.model
import cats.implicits._

object TraceHelper {
  def wrapper(
    uuid: String,
    now: String,
    eventType: String,
    traceId: String,
    idx: Int,
    input: ujson.Obj,
    meta: ujson.Obj,
    output: Option[ujson.Obj]
  ): ujson.Obj =
    if (output.isDefined) {
      ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> now,
        "type"      -> eventType,
        "body" -> ujson.Obj(
          "id"        -> s"${traceId}-event-$idx",
          "traceId"   -> traceId,
          "name"      -> s"User Input $idx",
          "startTime" -> now,
          "input"     -> input,
          "output"    -> output.getOrElse(""),
          "metadata"  -> meta
        )
      )
    } else {
      ujson.Obj(
        "id"        -> uuid,
        "timestamp" -> now,
        "type"      -> eventType,
        "body" -> ujson.Obj(
          "id"        -> s"${traceId}-event-$idx",
          "traceId"   -> traceId,
          "name"      -> s"User Input $idx",
          "startTime" -> now,
          "input"     -> input,
          "metadata"  -> meta
        )
      )
    }

  def createEvent(
    message: Message,
    uuid: String,
    traceId: String,
    idx: Int,
    now: String,
    modelName: String,
    contextMessages: Seq[Message]
  ): ujson.Obj = message match {
    case um @ UserMessage(content) =>
      wrapper(
        uuid,
        now,
        "event-create",
        traceId,
        idx,
        ujson.Obj("content" -> content),
        ujson.Obj("role"    -> um.role.name),
        None
      )

    case sys @ SystemMessage(content) =>
      wrapper(
        uuid,
        now,
        "event-create",
        traceId,
        idx,
        ujson.Obj("content" -> content),
        ujson.Obj("role"    -> sys.role.name),
        None
      )

    case am @ AssistantMessage(contentOpt, toolCalls) =>
      val content = contentOpt.getOrElse("")
      val conversationInput = contextMessages.map(msg =>
        ujson.Obj(
          "role"    -> msg.role.name,
          "content" -> msg.content
        )
      )

      val generationOutput = if (am.toolCalls.nonEmpty) {
        ujson.Obj(
          "role"    -> "assistant",
          "content" -> content,
          "tool_calls" -> ujson.Arr(
            toolCalls.map(tc =>
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
      } else {
        ujson.Obj(
          "role"    -> "assistant",
          "content" -> content
        )
      }

      ujson.Obj(
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
            "toolCallCount" -> toolCalls.length
          )
        )
      )

    case tm @ ToolMessage(content, toolCallId) =>
      val toolCallName = tm.findToolCallName(contextMessages)
      wrapper(
        uuid,
        now,
        "span-create",
        traceId,
        idx,
        ujson.Obj(
          "toolCallId" -> toolCallId,
          "toolName"   -> toolCallName
        ),
        ujson.Obj(
          "role"       -> tm.role.name,
          "toolCallId" -> toolCallId,
          "toolName"   -> toolCallName
        ),
        ujson
          .Obj(
            "result" -> content
          )
          .some
      )
  }

}
