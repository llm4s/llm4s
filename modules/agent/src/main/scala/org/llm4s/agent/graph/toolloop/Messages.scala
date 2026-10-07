package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.StateKey
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Message }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * A conversation message with a stable, graph-owned id. Core [[org.llm4s.llmconnect.model.Message]] stays id-free; ids exist
 * so operations can name the message they change, and are derived from the task that wrote the
 * message so a re-run produces the same ids.
 */
final case class StoredMessage(id: String, message: Message) derives ReadWriter

/** An operation on the message history, applied to the committed value in commit order. */
enum MessageUpdate derives ReadWriter:
  case Append(message: StoredMessage)
  case Replace(id: String, message: StoredMessage)
  case RemoveThrough(id: String)

  /**
   * Removes the message `id` and every message after it: a turn's writes - the user input, the assistant messages, the
   * tool calls and results, and the answer all come after the turn's user message. An output guardrail's
   * Block commits it for the blocked turn, so no blocked content is kept and the next turn continues from the
   * history before it. `RemoveThrough` is the opposite cut, dropping history *up to* a message.
   */
  case RemoveTurn(id: String)

  /**
   * Replaces one tool call's arguments in an assistant message - an edited approval. An operation
   * rather than a whole-message `Replace`, so two edits to calls of one message in the same
   * superstep both apply instead of the later overwriting the earlier.
   */
  case EditToolCall(messageId: String, toolCallId: String, arguments: ujson.Value)

object Messages:

  /** The conversation, as a typed state key. Every update is checked against the current history. */
  val key: StateKey[Vector[StoredMessage], MessageUpdate] =
    StateKey[Vector[StoredMessage], MessageUpdate]("messages", Vector.empty)(apply)

  private def apply(history: Vector[StoredMessage], update: MessageUpdate): Result[Vector[StoredMessage]] =
    def indexOf(id: String) =
      Some(history.indexWhere(_.id == id)).filter(_ >= 0).toRight(ValidationError("messages", s"no message '$id'"))
    update match
      case MessageUpdate.Append(message) =>
        if history.exists(_.id == message.id) then
          Left(ValidationError("messages", s"message '${message.id}' already exists"))
        else Right(history :+ message)
      case MessageUpdate.Replace(id, message) =>
        indexOf(id).map(i => history.updated(i, message))
      case MessageUpdate.RemoveThrough(id) =>
        indexOf(id).map(i => history.drop(i + 1))
      case MessageUpdate.RemoveTurn(id) =>
        indexOf(id).map(i => history.take(i))
      case MessageUpdate.EditToolCall(messageId, toolCallId, arguments) =>
        indexOf(messageId).flatMap { i =>
          history(i).message match
            case assistant: AssistantMessage if assistant.toolCalls.exists(_.id == toolCallId) =>
              val edited = assistant.toolCalls.map(c => if c.id == toolCallId then c.copy(arguments = arguments) else c)
              Right(history.updated(i, history(i).copy(message = assistant.withToolCalls(edited))))
            case _ => Left(ValidationError("messages", s"message '$messageId' has no tool call '$toolCallId'"))
        }
