package org.llm4s.llmconnect.model

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
