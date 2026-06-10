package org.llm4s.llmconnect.model
import cats.implicits._

/** Utility helpers for building Langfuse-compatible trace event JSON objects. */
object TraceHelper {

  /**
   * Builds a generic trace-event JSON wrapper object, optionally including an output field.
   *
   * @param uuid      unique identifier for the envelope event
   * @param now       ISO-8601 timestamp string representing the event time
   * @param eventType Langfuse event type string (e.g. `"event-create"`, `"span-create"`)
   * @param traceId   identifier of the parent trace this event belongs to
   * @param idx       sequential index of the event within the trace
   * @param input     JSON object describing the event input payload
   * @param meta      JSON object carrying metadata for the event body
   * @param output    optional JSON object describing the event output; omitted from body when `None`
   * @return a `ujson.Obj` representing the complete trace event envelope
   */
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

  /**
   * Creates a Langfuse-compatible trace event JSON object for a single conversation message.
   *
   * @param message         the conversation message to record (user, system, assistant, or tool)
   * @param uuid            unique identifier for the event envelope
   * @param traceId         identifier of the parent trace this event belongs to
   * @param idx             sequential index of the event within the trace
   * @param now             ISO-8601 timestamp string representing the event time
   * @param modelName       name of the LLM model used for generation events
   * @param contextMessages preceding messages in the conversation, used to resolve tool-call names
   * @return a `ujson.Obj` representing the typed trace event for the given message
   */
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
