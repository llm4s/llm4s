package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Message,
  SystemMessage,
  ThinkingBlock,
  ToolCall,
  ToolMessage,
  UserMessage
}

import java.util.{ Objects, Optional }
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * One message of an agent thread's history, as [[JAgentResult.messages]] lists them: its [[role]] - a
 * Java enum - and its text, with the tool calls an assistant message asked for and the call a tool
 * message answers.
 *
 * {{{
 * for (JMessage m : result.messages()) {
 *     switch (m.role()) {
 *         case USER      -> System.out.println("> " + m.content());
 *         case ASSISTANT -> m.toolCalls().forEach(c -> System.out.println(c.name() + c.argumentsJson()));
 *         case TOOL      -> System.out.println(m.toolCallId().orElseThrow() + ": " + m.content());
 *         case SYSTEM    -> { }
 *     }
 * }
 * }}}
 *
 * A value: two are equal when every field is.
 *
 * @param role who wrote it
 * @param content its text; empty, never `null`, for an assistant message with only tool calls
 * @param toolCalls the tool calls an `ASSISTANT` message asked for, in order; an empty list for the
 *                  other roles. Unmodifiable.
 * @param toolCallId the id of the tool call a `TOOL` message answers; empty for the other roles
 * @param thinking the reasoning text an `ASSISTANT` message's model reported; empty when there was
 *                 none, and for the other roles
 */
final class JMessage private (
  val role: JMessageRole,
  val content: String,
  val toolCalls: java.util.List[JToolCall],
  val toolCallId: Optional[String],
  val thinking: Optional[String]
) {

  private def fields: List[Any] = List(role, content, toolCalls, toolCallId, thinking)

  override def equals(other: Any): Boolean = other match {
    case that: JMessage => fields == that.fields
    case _              => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String = s"$role: $content"
}

object JMessage {

  /** `message` as Java and Kotlin callers read it. */
  private[javaapi] def of(message: Message): JMessage = message match {
    case UserMessage(content)   => plain(JMessageRole.USER, content)
    case SystemMessage(content) => plain(JMessageRole.SYSTEM, content)
    case assistant: AssistantMessage =>
      new JMessage(
        JMessageRole.ASSISTANT,
        assistant.content,
        java.util.List.copyOf(assistant.toolCalls.map(JToolCall.of).asJava),
        Optional.empty,
        ThinkingBlock.text(assistant.thinking).toJava
      )
    case ToolMessage(content, toolCallId) =>
      new JMessage(JMessageRole.TOOL, content, java.util.List.of(), Optional.of(toolCallId), Optional.empty)
  }

  private def plain(role: JMessageRole, content: String): JMessage =
    new JMessage(role, content, java.util.List.of(), Optional.empty, Optional.empty)
}

/**
 * A tool call an assistant message asked for, as [[JMessage.toolCalls]] lists them: JSON as text.
 *
 * A value: two are equal when every field is.
 *
 * @param id the call's id, which the `TOOL` message answering it carries as `toolCallId()`
 * @param name the tool's name
 * @param argumentsJson the call's arguments, a JSON object as text
 */
final class JToolCall private (val id: String, val name: String, val argumentsJson: String) {

  private def fields: List[Any] = List(id, name, argumentsJson)

  override def equals(other: Any): Boolean = other match {
    case that: JToolCall => fields == that.fields
    case _               => false
  }

  override def hashCode: Int = Objects.hash(id, name, argumentsJson)

  override def toString: String = s"JToolCall($id, $name, $argumentsJson)"
}

object JToolCall {

  /** `call` as Java and Kotlin callers read it. */
  private[javaapi] def of(call: ToolCall): JToolCall = new JToolCall(call.id, call.name, call.arguments.render())
}
