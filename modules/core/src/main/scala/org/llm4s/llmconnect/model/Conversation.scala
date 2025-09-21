package org.llm4s.llmconnect.model

import org.llm4s.types.Result
import upickle.default.{ macroRW, ReadWriter => RW }

/**
 * Represents the message stream in a conversation.
 *  Typically this will be a sequence of system prompt, then a series of user message and assistant responses.
 *  After the system message we have a user message.  The next message is the assistant response.
 *  If the conversation is ongoing, the next message will be a user message, or if the previous AssistantMessage requested
 *  one or more tool calls it will be followed by ToolMessages in response to each requested tool.
 *
 * @param messages Sequence of messages in the conversation.
 */
case class Conversation(messages: Seq[Message]) {
  // Add a message and return a new Conversation
  def addMessage(message: Message): Conversation =
    Conversation(messages :+ message)

  // Add multiple messages and return a new Conversation
  def addMessages(newMessages: Seq[Message]): Conversation =
    Conversation(messages ++ newMessages)
}

object Conversation {

  implicit val rw: RW[Conversation] = macroRW

  /**
   * Create conversation from single message - FIXED MISSING METHOD
   */
  def create(message: Message): Result[Conversation] =
    Message.validateConversation(List(message)).map(_ => Conversation(messages = List(message)))

  /**
   * Create conversation from multiple messages - FIXED MISSING METHOD
   */
  def create(messages: Message*): Result[Conversation] = create(messages.toList)

  def create(messages: List[Message]): Result[Conversation] =
    Message.validateConversation(messages).map(_ => Conversation(messages = messages))

  /**
   * Create empty conversation
   */
  def empty(): Conversation = Conversation(messages = List.empty)
}
