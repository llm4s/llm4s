package org.llm4s.testkit

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Conversation,
  Message,
  OutputCorrection,
  ToolCallId,
  ToolMessage,
  UserMessage
}

// Extractors for the rules of `ScriptedLLMClient.respondingTo`, which match on the `Conversation` of a call (#2730).
// Each says where in a turn the call is: its first call (`EndsWithUser`), the call after tools ran
// (`AfterToolResult`, `AfterToolResults`) or the retry of a structured answer (`AfterCorrection`). `LastUser` matches
// anywhere in the turn.
//
//   import org.llm4s.testkit.*
//
//   ScriptedLLMClient.respondingTo {
//     case EndsWithUser(s"take $item")              => Reply.toolCall("add_inventory_item", s"""{"item":"$item"}""")
//     case AfterToolResult("add_inventory_item", _) => Reply.text("Taken.")
//   }

extension (conversation: Conversation) {

  /**
   * The text of the latest user message, wherever it is in the conversation; `None` when there is none. A user
   * message of media only has the text `""`.
   *
   * [[RecordedCall.lastUserText]] is this, for the conversation of a recorded call, and [[LastUser]] is its extractor.
   */
  def lastUserText: Option[String] =
    conversation.messages.reverseIterator.collectFirst { case user: UserMessage => user.content }
}

/**
 * Matches a conversation whose last message is a user message, and extracts its text: the first model call of a
 * turn. The later calls of the turn end with tool results ([[AfterToolResult]]) instead.
 *
 * {{{
 * case EndsWithUser(s"take $item") => Reply.toolCall("add_inventory_item", s"""{"item":"$item"}""")
 * }}}
 *
 * The agent's correction request is a user message too, so a rule for [[AfterCorrection]] goes before a rule for
 * `EndsWithUser` that would match its text.
 */
object EndsWithUser {

  /** The text of the last message when it is a user message. */
  def unapply(conversation: Conversation): Option[String] =
    conversation.messages.lastOption.collect { case user: UserMessage => user.content }
}

/**
 * Matches a conversation that has a user message, and extracts the text of the latest one, whatever follows it: every
 * model call of a turn, not only the first. It extracts [[lastUserText]].
 *
 * {{{
 * case LastUser(text) if text.contains("Oslo") => Reply.text("cold")
 * }}}
 */
object LastUser {

  /** The text of the latest user message, if the conversation has one. */
  def unapply(conversation: Conversation): Option[String] = conversation.lastUserText
}

/**
 * Matches a conversation whose last message is a tool result, and extracts the name of the tool and the text of its
 * result: the model call after a tool ran.
 *
 * {{{
 * case AfterToolResult("add_inventory_item", _) => Reply.text("Taken.")
 * }}}
 *
 * '''The name''' is not in a tool result, which carries only the id of its call. It is read from the call with that
 * id in the assistant message the results answer: the message right before the tool results that end the
 * conversation. When that call is not there - the conversation was cut so that the assistant message is gone, or the
 * id is not one of its calls - the extractor does not match, rather than guess a name. A call with the same id in an
 * earlier turn is never used.
 *
 * '''Several results.''' When the model called several tools at once the conversation ends with one result for each,
 * and this extracts the last of them. A rule for one of the others, or for all of them, uses [[AfterToolResults]].
 */
object AfterToolResult {

  /** The tool name and the result text of the last message, when it is a tool result whose call is found. */
  def unapply(conversation: Conversation): Option[(String, String)] =
    AfterToolResults.unapply(conversation).map(_.last)
}

/**
 * Matches a conversation that ends with tool results, and extracts all of them as `(tool name, result text)`, in the
 * order they are in the conversation: the results of every tool the model called at once. It never extracts an empty
 * sequence.
 *
 * {{{
 * case AfterToolResults(results) if results.exists(_._1 == "add_inventory_item") => Reply.text("Taken.")
 * }}}
 *
 * The names are found as [[AfterToolResult]] finds one, and the extractor does not match when any of them is not
 * found.
 */
object AfterToolResults {

  /** The tool name and result text of each tool result that ends the conversation, when every call is found. */
  def unapply(conversation: Conversation): Option[Seq[(String, String)]] = {
    val (results, before) = trailingResults(conversation.messages)
    val names = before.lastOption match {
      case Some(assistant: AssistantMessage) => assistant.toolCalls.map(call => call.id -> call.name).toMap
      case _                                 => Map.empty[ToolCallId, String]
    }
    val named = results.flatMap(result => names.get(result.toolCallId).map(_ -> result.content))
    Option.when(results.nonEmpty && named.size == results.size)(named)
  }

  /** The tool results that end `messages`, in order, and the messages before them. */
  private def trailingResults(messages: Seq[Message]): (Seq[ToolMessage], Seq[Message]) = {
    val ordered = messages.reverseIterator.map(toolResult).takeWhile(_.nonEmpty).flatten.toVector.reverse
    (ordered, messages.dropRight(ordered.size))
  }

  private def toolResult(message: Message): Option[ToolMessage] = message match {
    case result: ToolMessage => Some(result)
    case _                   => None
  }
}

/**
 * Matches a conversation whose last message is the agent's correction request: the retry of a structured answer
 * (`AgentBuilder.withOutput`) that did not match its schema. It extracts nothing, so a rule writes it with
 * parentheses - `case AfterCorrection() =>`. Without them the pattern compares the conversation with this object and
 * never matches.
 *
 * {{{
 * ScriptedLLMClient.respondingTo {
 *   case AfterCorrection()  => Reply.text("""{"customer":"Acme","total":12.5}""")
 *   case EndsWithUser(_)    => Reply.text("""{"customer":"Acme","total":"lots"}""")
 * }
 * }}}
 *
 * The request is recognised by how its text starts, with the constant the agent runtime writes it with: a user
 * message has nothing else to mark it. A user message of the test's own that starts the same way matches too.
 */
object AfterCorrection {

  /** True when the last message is the agent's correction request. */
  def unapply(conversation: Conversation): Boolean =
    conversation.messages.lastOption.exists(OutputCorrection.isCorrection)
}
