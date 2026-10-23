package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.{ SchemaVersion, StateKey }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, ContentPart, Message, ToolMessage, UserMessage }
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

  /**
   * The conversation, as a typed state key. Every update is checked against the current history.
   *
   * Version 2 (#1913): a user or tool message may carry content parts. Version-1 JSON is valid
   * version 2 - core's `Message` reader takes the old string shape as one text part - so the step is
   * the identity; the version is what makes an older runtime refuse a value that may hold parts.
   */
  val key: StateKey[Vector[StoredMessage], MessageUpdate] =
    StateKey[Vector[StoredMessage], MessageUpdate]("messages", Vector.empty)(apply)
      .withStateVersion(SchemaVersion(2)(1 -> Right.apply))
      .withUpdateVersion(SchemaVersion(2)(1 -> Right.apply))

  /**
   * The most inline media bytes a thread's messages hold, in total: 20 MiB, four inline parts at
   * `ContentPart.MaxInlineBytes`. Each part and each tool result is bounded on its own, but a thread
   * gains messages turn after turn and every checkpoint copies them all, so the history is bounded
   * too. A message appended (or replaced) past the bound is kept with its own inline media parts
   * replaced, first to last, by a text part naming what was removed, until the history fits: a total
   * update, so no run fails for it, whatever wrote the media - a tool, a caller's imported history, a
   * batch of results that each fit on their own. Only the incoming message is changed, never the
   * history before it, so an output Block's `RemoveTurn` retracts a turn without having to restore
   * anything it evicted.
   */
  val MaxInlineBytes: Long = 4L * ContentPart.MaxInlineBytes

  /** `incoming`, with its inline media evicted as far as `history` plus it would be over [[MaxInlineBytes]]. */
  private[toolloop] def admitted(history: Vector[StoredMessage], incoming: StoredMessage): StoredMessage =
    val excess = (history :+ incoming).map(m => inlineBytes(m.message)).sum - MaxInlineBytes
    if excess <= 0 then incoming
    else
      val (parts, freed) = InlineMediaEviction.evict(partsOf(incoming.message), excess, MaxInlineBytes)
      if freed == 0 then incoming else incoming.copy(message = withParts(incoming.message, parts))

  private def partsOf(message: Message): Vector[ContentPart] = message match
    case u: UserMessage => u.parts
    case t: ToolMessage => t.parts
    case _              => Vector.empty

  private def withParts(message: Message, parts: Vector[ContentPart]): Message = message match
    case u: UserMessage => u.withParts(parts)
    case t: ToolMessage => t.withParts(parts)
    case other          => other

  private def inlineBytes(message: Message): Long = ContentPart.inlineBytes(partsOf(message))

  private def apply(history: Vector[StoredMessage], update: MessageUpdate): Result[Vector[StoredMessage]] =
    def indexOf(id: String) =
      Some(history.indexWhere(_.id == id)).filter(_ >= 0).toRight(ValidationError("messages", s"no message '$id'"))
    update match
      case MessageUpdate.Append(message) =>
        if history.exists(_.id == message.id) then
          Left(ValidationError("messages", s"message '${message.id}' already exists"))
        else Right(history :+ admitted(history, message))
      case MessageUpdate.Replace(id, message) =>
        indexOf(id).map(i => history.updated(i, admitted(history.patch(i, Nil, 1), message)))
      case MessageUpdate.RemoveThrough(id) =>
        indexOf(id).map(i => history.drop(i + 1))
      case MessageUpdate.RemoveTurn(id) =>
        indexOf(id).map(i => history.take(i))
      case MessageUpdate.EditToolCall(messageId, toolCallId, arguments) =>
        indexOf(messageId).flatMap { i =>
          history(i).message match
            case assistant: AssistantMessage if assistant.toolCalls.exists(_.id == toolCallId) =>
              val edited = assistant.toolCalls.map(c => if c.id == toolCallId then c.withArguments(arguments) else c)
              Right(history.updated(i, history(i).copy(message = assistant.withToolCalls(edited))))
            case _ => Left(ValidationError("messages", s"message '$messageId' has no tool call '$toolCallId'"))
        }
