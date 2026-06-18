package org.llm4s.java

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, Message, SystemMessage, UserMessage }

/**
 * Builder for constructing a [[Conversation]] without using Scala case-class
 * syntax or sequence literals.
 *
 * {{{
 * Conversation conv = ConversationBuilder.create()
 *     .system("You are a helpful assistant.")
 *     .user("What is the capital of France?")
 *     .build();
 * }}}
 */
final class ConversationBuilder private (private val messages: Seq[Message]) {

  def system(content: String): ConversationBuilder =
    new ConversationBuilder(messages :+ SystemMessage(content))

  def user(content: String): ConversationBuilder =
    new ConversationBuilder(messages :+ UserMessage(content))

  def assistant(content: String): ConversationBuilder =
    new ConversationBuilder(messages :+ AssistantMessage(content))

  def build(): Conversation = Conversation(messages)
}

object ConversationBuilder {

  /** Returns a new empty builder. */
  def create(): ConversationBuilder = new ConversationBuilder(Seq.empty)
}
