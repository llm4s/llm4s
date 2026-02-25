package org.llm4s.llmconnect.model

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.{ macroRW, read, readwriter, write, ReadWriter => RW }

/**
 * A single turn in an LLM conversation.
 *
 * Conversations are sequences of `Message` values passed to [[org.llm4s.llmconnect.LLMClient]].
 * Each concrete subtype corresponds to one participant role:
 * [[UserMessage]], [[SystemMessage]], [[AssistantMessage]], [[ToolMessage]].
 *
 * `content` is always a non-null, non-empty string for well-formed messages —
 * use [[validate]] or the smart constructors on the [[Message]] companion to
 * ensure this invariant. `AssistantMessage.content` returns `""` rather than
 * `null` when the LLM response contains only tool calls and no text.
 */
sealed trait Message {
  def role: MessageRole
  def content: String

  override def toString: String = s"$role: $content"

  /**
   * Validates that this message satisfies its role-specific content constraints.
   *
   * Returns `Left(ValidationError)` when `content` is blank; `AssistantMessage`
   * additionally requires at least one of `content` or `toolCalls` to be present.
   */
  def validate: Result[Message] =
    if (content.trim.isEmpty) {
      Left(ValidationError(s"$role message content cannot be empty", "content"))
    } else {
      Right(this)
    }
}

/**
 * Companion object providing smart constructors and conversation-level validation.
 *
 * The smart constructors (`system`, `user`, `assistant`, `tool`) return
 * `Left(ValidationError)` on blank content so callers get typed errors instead
 * of runtime exceptions. Prefer these over the case-class constructors in
 * application code; use case-class constructors directly only in tests or when
 * content is guaranteed non-blank.
 */
object Message {

  implicit val rw: RW[Message] = readwriter[ujson.Value].bimap[Message](
    {
      case um: UserMessage      => ujson.Obj("type" -> ujson.Str("user"), "content" -> ujson.Str(um.content))
      case sm: SystemMessage    => ujson.Obj("type" -> ujson.Str("system"), "content" -> ujson.Str(sm.content))
      case am: AssistantMessage => ujson.Obj("type" -> ujson.Str("assistant"), "data" -> ujson.read(write(am)))
      case tm: ToolMessage =>
        ujson.Obj(
          "type"       -> ujson.Str("tool"),
          "toolCallId" -> ujson.Str(tm.toolCallId),
          "content"    -> ujson.Str(tm.content)
        )
    },
    json => {
      val obj = json.obj
      obj("type").str match {
        case "user"      => UserMessage(obj("content").str)
        case "system"    => SystemMessage(obj("content").str)
        case "assistant" => read[AssistantMessage](obj("data"))
        case "tool"      => ToolMessage(obj("content").str, obj("toolCallId").str)
      }
    }
  )

  /**
   * Validates structural consistency of a full conversation.
   *
   * Three checks are applied in order and all failures are collected before
   * returning, so callers see every problem in a single pass:
   *  1. Each message satisfies its individual [[Message.validate]] constraint.
   *  2. Conversation-flow rules: system messages only at the start; tool messages
   *     must be preceded by an assistant message that requested the matching tool call.
   *  3. Tool-call/response pairing: every tool call in an assistant message must
   *     have exactly one [[ToolMessage]] response with a matching `toolCallId`, and
   *     vice-versa.
   *
   * An empty `messages` list is considered valid.
   *
   * @param messages Messages in conversation order (oldest first).
   * @return `Right(())` when all checks pass; `Left(ValidationError)` with all
   *         violations listed in `error.violations` when any check fails.
   */
  def validateConversation(messages: List[Message]): Result[Unit] = {
    if (messages.isEmpty) {
      return Right(())
    }

    val validationErrors = scala.collection.mutable.ListBuffer[String]()

    // Validate individual messages first
    messages.zipWithIndex.foreach { case (message, index) =>
      message.validate match {
        case Left(error) => validationErrors += s"Message $index: ${error.formatted}"
        case Right(_)    => // OK
      }
    }

    // Validate conversation flow rules
    validationErrors ++= validateConversationFlow(messages)

    // Validate tool call consistency
    validationErrors ++= validateToolCallConsistency(messages)

    if (validationErrors.nonEmpty) {
      Left(
        ValidationError(
          s"Conversation validation failed: ${validationErrors.mkString("; ")}",
          "conversation"
        ).withViolations(validationErrors.toList)
      )
    } else {
      Right(())
    }
  }

  /**
   * Validates conversation flow rules
   */
  private def validateConversationFlow(messages: List[Message]): List[String] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    messages.zipWithIndex.foreach { case (message, index) =>
      message match {
        case _: SystemMessage =>
          // System messages should be at the beginning
          if (index > 0 && messages.take(index).exists(_.isInstanceOf[SystemMessage])) {
            errors += s"Multiple system messages found - system message at index $index should be first"
          }

        case toolMsg: ToolMessage =>
          // Tool messages must follow assistant messages with tool calls
          // Multiple consecutive tool messages are allowed if they belong to the same batch of tool calls
          if (index == 0) {
            errors += "Tool messages cannot be the first message in a conversation"
          } else {
            // Find the most recent AssistantMessage with tool calls before this ToolMessage
            val previousMessages = messages.take(index).reverse
            val recentAssistantWithToolCalls = previousMessages.collectFirst {
              case am: AssistantMessage if am.toolCalls.nonEmpty => am
            }

            recentAssistantWithToolCalls match {
              case Some(assistantMsg) =>
                // Check if this tool message's ID matches one of the tool calls
                if (!assistantMsg.toolCalls.exists(_.id == toolMsg.toolCallId)) {
                  errors += s"Tool message at index $index with call ID '${toolMsg.toolCallId}' does not match any tool calls from recent assistant message"
                }
              case None =>
                errors += s"Tool message at index $index must have an assistant message with tool calls before it"
            }
          }

        case _: UserMessage =>
        // User messages are generally always valid in any position

        case _: AssistantMessage =>
        // Assistant messages are generally always valid in any position
      }
    }

    errors.toList
  }

  /**
   * Validates tool call consistency
   */
  private def validateToolCallConsistency(messages: List[Message]): List[String] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    // Collect all tool calls and their responses
    val toolCalls     = scala.collection.mutable.Map[String, (AssistantMessage, Int)]()
    val toolResponses = scala.collection.mutable.Map[String, (ToolMessage, Int)]()

    messages.zipWithIndex.foreach { case (message, index) =>
      message match {
        case assistantMsg: AssistantMessage =>
          assistantMsg.toolCalls.foreach(toolCall => toolCalls.put(toolCall.id, (assistantMsg, index)))

        case toolMsg: ToolMessage =>
          toolResponses.put(toolMsg.toolCallId, (toolMsg, index))

        case _ => // Other message types don't affect tool call consistency
      }
    }

    // Check for tool calls without responses
    toolCalls.foreach { case (toolCallId, (assistantMsg, assistantIndex)) =>
      if (!toolResponses.contains(toolCallId)) {
        errors += s"Tool call '$toolCallId' at $assistantIndex has no corresponding tool response; Message: $assistantMsg"
      }
    }

    // Check for tool responses without calls
    toolResponses.foreach { case (toolCallId, (toolMsg, toolIndex)) =>
      if (!toolCalls.contains(toolCallId)) {
        errors += s"Tool response at message $toolIndex references unknown tool call '$toolCallId'; Message: $toolMsg"
      }
    }

    errors.toList
  }

  // Individual message constructors with validation
  def system(content: String): Result[SystemMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("System message content cannot be empty", "content"))
    } else {
      Right(SystemMessage(content = content))
    }

  def user(content: String): Result[UserMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("User message content cannot be empty", "content"))
    } else {
      Right(UserMessage(content = content))
    }

  def assistant(content: String, toolCalls: List[ToolCall] = List.empty): Result[AssistantMessage] =
    if (content.trim.isEmpty && toolCalls.isEmpty) {
      Left(
        ValidationError(
          "Assistant message must have either content or tool calls",
          "content"
        )
      )
    } else {
      Right(AssistantMessage(content = content, toolCalls = toolCalls))
    }

  def tool(content: String, toolCallId: String): Result[ToolMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("Tool message content cannot be empty", "content"))
    } else if (toolCallId.trim.isEmpty) {
      Left(ValidationError("Tool call ID cannot be empty", "toolCallId"))
    } else {
      Right(ToolMessage(content = content, toolCallId = toolCallId))
    }
}

/**
 * Identifies the participant that authored a [[Message]].
 *
 * Maps directly to the `role` field in provider API payloads.
 * The string representation returned by `toString` is the lowercase name
 * forwarded verbatim to the provider (e.g. `"user"`, `"assistant"`).
 */
sealed trait MessageRole {
  def name: String
  override def toString: String = name
}

object MessageRole {
  case object System    extends MessageRole { val name = "system"    }
  case object User      extends MessageRole { val name = "user"      }
  case object Assistant extends MessageRole { val name = "assistant" }
  case object Tool      extends MessageRole { val name = "tool"      }
}

/**
 * Represents a user message in the conversation.
 *
 * @param content Content of the user message.
 */
final case class UserMessage(content: String) extends Message {
  val role: MessageRole = MessageRole.User
}

object UserMessage {
  implicit val rw: RW[UserMessage] = macroRW
}

/**
 * Represents a system message, which is typically used to set context or instructions for the LLM.
 *
 * A system prompt provides the foundational instructions and behavioral guidelines that shape how the
 * LLM should respond to a user request, including its personality, capabilities, constraints, and communication style.
 * It acts as the model's "operating manual," establishing context about what it should and shouldn't do,
 * how to handle various scenarios, and what information it has access to.
 *
 * @param content Content of the system message.
 */
final case class SystemMessage(content: String) extends Message {
  val role: MessageRole = MessageRole.System
}

object SystemMessage {
  implicit val rw: RW[SystemMessage] = macroRW
}

/**
 * A response from the LLM, optionally containing text, tool-call requests, or both.
 *
 * `content` always returns a non-null `String`; it returns `""` when the LLM
 * response contains only tool calls and no accompanying text (`contentOpt` is
 * `None`). Code that displays assistant output should check for an empty string
 * rather than null-guarding.
 *
 * A well-formed `AssistantMessage` must satisfy at least one of:
 *  - `contentOpt.exists(_.trim.nonEmpty)` — the LLM produced text.
 *  - `toolCalls.nonEmpty` — the LLM requested one or more tool invocations.
 *
 * @param contentOpt Text portion of the response; `None` when the model produced
 *                   only tool calls.
 * @param toolCalls  Tool invocations requested by the model; each carries an `id`
 *                   that must be matched by a subsequent [[ToolMessage]].
 */
case class AssistantMessage(
  contentOpt: Option[String] = None,
  toolCalls: Seq[ToolCall] = Seq.empty
) extends Message {
  val role: MessageRole = MessageRole.Assistant

  def content: String = contentOpt.getOrElse("")

  override def toString: String = {
    val toolCallsStr = if (toolCalls.nonEmpty) {
      s"\nTool Calls: ${toolCalls.map(tc => s"[${tc.id}: ${tc.name}(${tc.arguments})]").mkString(", ")}"
    } else " - no tool calls"

    s"$role: $content$toolCallsStr"
  }

  def hasToolCalls: Boolean = toolCalls.nonEmpty

  override def validate: Result[Message] =
    if (content.trim.isEmpty && toolCalls.isEmpty) {
      Left(
        ValidationError(
          "Assistant message must have either content or tool calls",
          "content"
        )
      )
    } else {
      Right(this)
    }
}

object AssistantMessage {
  // Manual ReadWriter for AssistantMessage due to macro issues with default parameters
  implicit val rw: RW[AssistantMessage] = readwriter[ujson.Value].bimap[AssistantMessage](
    msg =>
      ujson.Obj(
        "contentOpt" -> (msg.contentOpt match {
          case None          => ujson.Null
          case Some(content) => ujson.Str(content)
        }),
        "toolCalls" -> ujson.read(write(msg.toolCalls))
      ),
    json => {
      val obj = json.obj
      val contentOpt = obj.get("contentOpt") match {
        case Some(ujson.Null)         => None
        case Some(ujson.Str(content)) => Some(content)
        case _                        => None
      }
      val toolCalls = obj.get("toolCalls") match {
        case Some(toolCallsJson) => read[Seq[ToolCall]](toolCallsJson)
        case _                   => Seq.empty
      }
      AssistantMessage(contentOpt, toolCalls)
    }
  )

  def apply(content: String): AssistantMessage =
    AssistantMessage(Some(content), Seq.empty)
  def apply(content: String, toolCalls: Seq[ToolCall]): AssistantMessage =
    AssistantMessage(Some(content), toolCalls)
}

/**
 * Represents a message from a tool, typically containing the result of a tool call.
 *
 * @param content    Content of the tool message, usually the result of the tool execution, e.g. a json response.
 * @param toolCallId Unique identifier for the tool call (as provided by the ToolCall).
 */
final case class ToolMessage(
  content: String,
  toolCallId: String
) extends Message {
  val role: MessageRole = MessageRole.Tool

  /**
   * Resolves the tool name for this response by scanning `contextMessages` for
   * the [[ToolCall]] whose `id` matches [[toolCallId]].
   *
   * Returns `"unknown-tool"` when no matching tool call is found, which can
   * happen if `contextMessages` does not include the assistant message that
   * issued the request (e.g. after context pruning).
   *
   * @param contextMessages The conversation history to search; all
   *                        [[AssistantMessage]] entries are scanned.
   */
  def findToolCallName(contextMessages: Seq[Message]): String =
    contextMessages
      .collect { case am: AssistantMessage => am.toolCalls }
      .flatten
      .find(_.id == toolCallId)
      .map(_.name)
      .getOrElse("unknown-tool")

  override def validate: Result[Message] = {
    val validations = List(
      if (content.trim.isEmpty) Some("Tool message content cannot be empty") else None,
      if (toolCallId.trim.isEmpty) Some("Tool call ID cannot be empty") else None
    ).flatten

    if (validations.nonEmpty) {
      Left(
        ValidationError(
          validations.mkString("; "),
          "toolMessage"
        ).withViolations(validations)
      )
    } else {
      Right(this)
    }
  }
}

object ToolMessage {
  implicit val rw: RW[ToolMessage] = macroRW
}

/**
 * A single tool invocation requested by the LLM.
 *
 * The LLM generates `id` to correlate this request with its [[ToolMessage]] response;
 * the agent framework forwards `id` unchanged when constructing [[ToolMessage]] values,
 * so do not modify it.
 *
 * `arguments` is a parsed `ujson.Value` (typically a JSON object), not a raw string.
 * Use `arguments.obj` to access fields or pass it directly to
 * [[org.llm4s.toolapi.ToolRegistry.execute]] via a [[org.llm4s.toolapi.ToolCallRequest]].
 *
 * @param id        Provider-generated identifier; matched by the corresponding [[ToolMessage.toolCallId]].
 * @param name      Name of the tool to invoke; must match a registered [[org.llm4s.toolapi.ToolFunction]].
 * @param arguments Parsed JSON arguments; the schema is defined by the tool's [[org.llm4s.toolapi.Schema]].
 */
case class ToolCall(
  id: String,
  name: String,
  arguments: ujson.Value
) {
  override def toString: String = s"ToolCall($id, $name, $arguments)"
}

object ToolCall {
  implicit val rw: RW[ToolCall] = macroRW
}
