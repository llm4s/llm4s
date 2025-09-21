package org.llm4s.agent

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import upickle.default.{ ReadWriter => RW, readwriter }

/**
 * Represents the current state of an agent run
 */
case class AgentState(
  conversation: Conversation,
  tools: ToolRegistry,
  userQuery: String,
  status: AgentStatus = AgentStatus.InProgress,
  logs: Seq[String] = Seq.empty,
  systemMessage: Option[SystemMessage] = None
) {

  /**
   * Adds a message to the conversation and returns a new state
   */
  def addMessage(message: Message): AgentState =
    copy(conversation = conversation.addMessage(message))

  /**
   * Adds multiple messages to the conversation and returns a new state
   */
  def addMessages(messages: Seq[Message]): AgentState =
    copy(conversation = conversation.addMessages(messages))

  /**
   * Adds a log entry and returns a new state
   */
  def log(entry: String): AgentState =
    copy(logs = logs :+ entry)

  /**
   * Updates the status and returns a new state
   */
  def withStatus(newStatus: AgentStatus): AgentState =
    copy(status = newStatus)

  /**
   * Creates a complete conversation by injecting the system message at the beginning.
   * This is used for LLM API calls where system message should be first.
   */
  def toApiConversation: Conversation =
    Conversation(systemMessage.toSeq ++ conversation.messages)

  /**
   * Prints a detailed dump of the agent execution state for debugging
   */
  def dump(): Unit = {
    val separator = "=" * 80

    println(separator)
    println(s"AGENT STATE DUMP - Status: $status")
    println(separator)

    println(s"User Query: $userQuery")
    println(s"Available Tools: ${tools.tools.map(_.name).mkString(", ")}")
    println(separator)

    println("CONVERSATION FLOW:")
    println(separator)

    conversation.messages.zipWithIndex.foreach { case (message, index) =>
      val step = index + 1
      val roleMarker = message.role match {
        case MessageRole.User      => "👤 USER"
        case MessageRole.Assistant => "🤖 ASSISTANT"
        case MessageRole.System    => "⚙️ SYSTEM"
        case MessageRole.Tool      => "🛠️ TOOL"
      }

      println(s"STEP $step: $roleMarker")

      message match {
        case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
          println(s"Content: ${msg.content}")
          println("Tool Calls:")
          msg.toolCalls.foreach { tc =>
            println(s"  - ID: ${tc.id}")
            println(s"    Tool: ${tc.name}")
            println(s"    Args: ${tc.arguments}")
          }

        case msg: ToolMessage =>
          println(s"Tool Call ID: ${msg.toolCallId}")
          println(s"Result: ${msg.content}")

        case msg =>
          println(s"Content: ${msg.content}")
      }

      println(separator)
    }

    if (logs.nonEmpty) {
      println("EXECUTION LOGS:")
      logs.zipWithIndex.foreach { case (log, index) =>
        println(s"${index + 1}. $log")
      }
      println(separator)
    }

    println(s"END OF AGENT STATE DUMP - Status: $status")
    println(separator)
  }
}

object AgentState {
  // We can't automatically serialize AgentState because it contains ToolRegistry (with function references)
  // Serialization is handled manually in SessionManager
}

/**
 * Status of the agent run
 */
sealed trait AgentStatus

object AgentStatus {
  case object InProgress           extends AgentStatus
  case object WaitingForTools      extends AgentStatus // Waiting for tool execution
  case object Complete             extends AgentStatus
  case class Failed(error: String) extends AgentStatus

  // Custom serialization for AgentStatus since sealed trait derivation can be tricky
  implicit val rw: RW[AgentStatus] = readwriter[ujson.Value].bimap[AgentStatus](
    {
      case InProgress      => ujson.Str("InProgress")
      case WaitingForTools => ujson.Str("WaitingForTools")
      case Complete        => ujson.Str("Complete")
      case Failed(error)   => ujson.Obj("type" -> ujson.Str("Failed"), "error" -> ujson.Str(error))
    },
    {
      case ujson.Str("InProgress")      => InProgress
      case ujson.Str("WaitingForTools") => WaitingForTools
      case ujson.Str("Complete")        => Complete
      case obj: ujson.Obj =>
        obj.obj.get("type") match {
          case Some(ujson.Str("Failed")) =>
            Failed(obj.obj.get("error").map(_.str).getOrElse("Unknown error"))
          case _ => Failed("Unknown status format")
        }
      case _ => Failed("Invalid status format")
    }
  )
}
