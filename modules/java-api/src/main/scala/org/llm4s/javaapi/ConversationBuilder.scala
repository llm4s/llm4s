package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, Message, SystemMessage, UserMessage }

import java.util.Objects

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
 *
 * Instances are immutable: every method returns a new builder, so a builder may be shared and
 * forked across threads. A `null` content argument throws `NullPointerException` immediately.
 */
final class ConversationBuilder private (private val messages: Seq[Message]) {

  def system(content: String): ConversationBuilder =
    new ConversationBuilder(
      messages :+ SystemMessage(Objects.requireNonNull(content, "system content must not be null"))
    )

  def user(content: String): ConversationBuilder =
    new ConversationBuilder(messages :+ UserMessage(Objects.requireNonNull(content, "user content must not be null")))

  def assistant(content: String): ConversationBuilder =
    new ConversationBuilder(
      messages :+ AssistantMessage(Objects.requireNonNull(content, "assistant content must not be null"))
    )

  def build(): Conversation = Conversation(messages)
}

object ConversationBuilder {

  /** Returns a new empty builder. */
  def create(): ConversationBuilder = new ConversationBuilder(Seq.empty)
}
