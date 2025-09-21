package org.llm4s.types.typeclass

import org.llm4s.Result
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Conversation, UserMessage }
import org.llm4s.types._

/**
 * Type class for types that can interact with LLMs.
 *
 * Provides a unified interface for different types to be processed by LLMs,
 * enabling generic algorithms that work across various input types.
 */
trait LLMCapable[A] {

  /** Convert value to conversation format */
  def toConversation(value: A): Result[Conversation]

  /** Extract structured data from completion */
  def fromCompletion(completion: Completion): Result[A]

  /** Validate that the type can be processed */
  def validate(value: A): Result[Unit]
}

object LLMCapable {
  def apply[A](implicit instance: LLMCapable[A]): LLMCapable[A] = instance

  /** Syntax extension for any type with LLMCapable instance */
  implicit class LLMCapableOps[A](private val value: A) extends AnyVal {
    def toLLMConversation(implicit capable: LLMCapable[A]): Result[Conversation] =
      capable.toConversation(value)

    def validateForLLM(implicit capable: LLMCapable[A]): Result[Unit] =
      capable.validate(value)
  }

  // Standard instances
  implicit val stringLLMCapable: LLMCapable[String] = new LLMCapable[String] {
    def toConversation(value: String): Result[Conversation] =
      Conversation.create(UserMessage(value))

    def fromCompletion(completion: Completion): Result[String] =
      Result.success(completion.content)

    def validate(value: String): Result[Unit] =
      Result.fromBoolean(value.trim.nonEmpty, ValidationError("content", "cannot be empty"))
  }

  implicit val conversationLLMCapable: LLMCapable[Conversation] = new LLMCapable[Conversation] {
    def toConversation(value: Conversation): Result[Conversation] = Result.success(value)
    def fromCompletion(completion: Completion): Result[Conversation] =
      Conversation.create(AssistantMessage(completion.asText))
    def validate(value: Conversation): Result[Unit] =
      Result.fromBoolean(value.messages.nonEmpty, ValidationError("conversation", "must have messages"))
  }
}
