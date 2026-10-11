package org.llm4s.agent.graph.toolloop

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, ToolMessage }
import org.llm4s.types.Result

/**
 * The rule every conversation the loop sends to a model obeys (design §5.3, §9 "Dangling tool
 * calls"): each tool call of an assistant message has exactly one [[ToolMessage]] in the run of
 * tool messages straight after that message, every tool message in that run answers one of its
 * calls, and no tool message stands anywhere else.
 *
 * It is positional, as the OpenAI and Anthropic formats are: a result answers the call of the turn
 * just before it. `Message.validateConversation` pairs calls and results by id across the whole
 * conversation, so a provider that reuses ids (`call_0` in every turn) can hide a call with no
 * result behind an earlier turn's result; this rule cannot be fooled that way. Within one message,
 * call ids must be unique and not blank, or no result could be matched to exactly one call.
 */
private[llm4s] object ToolResultRule:

  /** `Right` when `messages` obeys the rule, else a `ValidationError` listing every violation. */
  def check(messages: Seq[Message]): Result[Unit] =
    violations(messages) match
      case Vector() => Right(())
      case problems => Left(ValidationError("tool results", problems.toList))

  /**
   * `Right` when every call of `message` can be given exactly one result: no blank id, and no id
   * twice. The model step refuses any other message before it is stored, as it does a blank answer,
   * so the history stays valid and `recover` asks the model again.
   */
  def callIdsUsable(message: AssistantMessage): Result[Unit] =
    idProblems(message, "the model's message") match
      case Vector() => Right(())
      case problems => Left(ValidationError("tool calls", problems.toList))

  /** Every way `messages` breaks the rule, in message order; empty when it holds. */
  def violations(messages: Seq[Message]): Vector[String] =
    val indexed = messages.toVector
    indexed.indices.toVector.flatMap { i =>
      indexed(i) match
        case assistant: AssistantMessage if assistant.toolCalls.nonEmpty =>
          val ids     = assistant.toolCalls.map(_.id).toVector
          val results = indexed.drop(i + 1).takeWhile(_.isInstanceOf[ToolMessage]).collect { case t: ToolMessage => t }
          val counted = ids.distinct.flatMap { id =>
            results.count(_.toolCallId == id) match
              case 1 => None
              case 0 => Some(s"message $i: tool call '$id' has no result straight after it")
              case n => Some(s"message $i: tool call '$id' has $n results")
          }
          val stray = results
            .map(_.toolCallId)
            .filterNot(ids.contains)
            .distinct
            .map(id => s"message $i: a tool result for '$id' answers none of its calls")
          idProblems(assistant, s"message $i") ++ counted ++ stray
        case _: ToolMessage =>
          // a tool message in the run after an assistant message with calls is checked there
          indexed.take(i).reverseIterator.dropWhile(_.isInstanceOf[ToolMessage]).nextOption() match
            case Some(a: AssistantMessage) if a.toolCalls.nonEmpty => Vector.empty
            case _ => Vector(s"message $i: a tool result follows no assistant message with tool calls")
        case _ => Vector.empty
    }

  private def idProblems(message: AssistantMessage, where: String): Vector[String] =
    val ids = message.toolCalls.map(_.id).toVector
    val blank =
      Option.when(ids.exists(_.trim.isEmpty))(s"$where: a tool call has a blank id").toVector
    val repeated =
      ids.filter(_.trim.nonEmpty).groupBy(identity).collect { case (id, n) if n.size > 1 => id }.toVector.sorted
    blank ++ repeated.map(id => s"$where: tool call id '$id' is used more than once")
