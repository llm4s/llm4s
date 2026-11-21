package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, Message, SystemMessage, UserMessage }

import java.util.Objects

/**
 * Builder for constructing a [[org.llm4s.llmconnect.model.Conversation Conversation]] without using Scala case-class
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
final class ConversationBuilder private (token: FacadeToken, messages: Seq[Message]) {

  FacadeToken.check(token, "ConversationBuilder")

  /**
   * Returns a new builder with a system message holding `content` appended; this builder is unchanged.
   *
   * Blank content is accepted here; it is reported later, as a `ValidationError`, when the message is validated
   * (see `SystemMessage.validate`).
   *
   * @param content the system message text; must not be `null`
   * @return a new builder whose messages are this builder's followed by the new system message
   * @throws NullPointerException if `content` is `null`, with the message `"system content must not be null"`
   */
  def system(content: String): ConversationBuilder =
    new ConversationBuilder(
      FacadeToken.INSTANCE,
      messages :+ SystemMessage(Objects.requireNonNull(content, "system content must not be null"))
    )

  /**
   * Returns a new builder with a user message holding `content` appended; this builder is unchanged.
   *
   * Blank content is accepted here; it is reported later, as a `ValidationError`, when the message is validated
   * (see `UserMessage.validate`).
   *
   * @param content the user message text; must not be `null`
   * @return a new builder whose messages are this builder's followed by the new user message
   * @throws NullPointerException if `content` is `null`, with the message `"user content must not be null"`
   */
  def user(content: String): ConversationBuilder =
    new ConversationBuilder(
      FacadeToken.INSTANCE,
      messages :+ UserMessage(Objects.requireNonNull(content, "user content must not be null"))
    )

  /**
   * Returns a new builder with a assistant message holding `content` appended; this builder is unchanged.
   *
   * Blank content is accepted here; it is reported later, as a `ValidationError`, when the message is validated
   * (see `AssistantMessage.validate`).
   *
   * @param content the assistant message text; must not be `null`
   * @return a new builder whose messages are this builder's followed by the new assistant message
   * @throws NullPointerException if `content` is `null`, with the message `"assistant content must not be null"`
   */
  def assistant(content: String): ConversationBuilder =
    new ConversationBuilder(
      FacadeToken.INSTANCE,
      messages :+ AssistantMessage(Objects.requireNonNull(content, "assistant content must not be null"))
    )

  /**
   * Returns a [[org.llm4s.llmconnect.model.Conversation Conversation]] holding the messages added so far, in call
   * order. Building does not change the builder, so it can still be extended or built again afterwards.
   */
  def build(): Conversation = Conversation(messages)
}

object ConversationBuilder {

  /** Returns a new empty builder. */
  def create(): ConversationBuilder = new ConversationBuilder(FacadeToken.INSTANCE, Seq.empty)
}
