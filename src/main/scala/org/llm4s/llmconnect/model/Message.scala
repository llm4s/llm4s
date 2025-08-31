package org.llm4s.llmconnect.model

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.{ macroRW, read, readwriter, write, ReadWriter => RW }

/**
 * Represents a message in a conversation with an LLM (Large Language Model).
 */
sealed trait Message {
  def role: MessageRole
  def content: String

  override def toString: String = s"$role: $content"

  /**
   * Validates individual message
   */
  def validate: Result[Message] =
    if (content.trim.isEmpty) {
      Left(ValidationError(s"$role message content cannot be empty", "content"))
    } else {
      Right(this)
    }
}

/**
 * Message validation with comprehensive conversation flow rules
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
        case "tool"      => ToolMessage(obj("toolCallId").str, obj("content").str)
      }
    }
  )

  /**
   * Validates a list of messages for conversation consistency
   *
   * CRITICAL MISSING METHOD - NOW IMPLEMENTED
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

        case _: ToolMessage =>
          // Tool messages must follow assistant messages with tool calls
          if (index == 0) {
            errors += "Tool messages cannot be the first message in a conversation"
          } else {
            val previousMessage = messages(index - 1)
            previousMessage match {
              case assistantMsg: AssistantMessage =>
                if (assistantMsg.toolCalls.isEmpty) {
                  errors += s"Tool message at index $index must follow an assistant message with tool calls"
                }
              case _ =>
                errors += s"Tool message at index $index must follow an assistant message"
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
 * Represents a message from the LLM assistant, which may include text, tool calls or both.
 *
 * @param contentOpt Optional content of the message.
 * @param toolCalls  Sequence of tool calls made by the assistant.
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
 * @param toolCallId Unique identifier for the tool call (as provided by the ToolCall).
 * @param content    Content of the tool message, usually the result of the tool execution, e.g. a json response.
 */
final case class ToolMessage(
  content: String,
  toolCallId: String
) extends Message {
  val role: MessageRole = MessageRole.Tool

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
 * Represents a tool call request from the LLM.
 *
 * @param id Unique identifier for the tool call (generated byt the LLM).
 * @param name Name of the tool being called. (from the list of tools provided to the LLM).
 * @param arguments Arguments passed to the tool in JSON format.
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
