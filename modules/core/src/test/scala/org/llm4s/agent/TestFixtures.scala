package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

final private[agent] class DeterministicFakeLLMClient(
  response: Completion
) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    Right(response)

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

final private[agent] class TwoTurnDeterministicFakeLLMClient(
  first: Completion,
  second: Completion
) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    val hasToolResult =
      conversation.messages.exists(_.role == org.llm4s.llmconnect.model.MessageRole.Tool)
    val hasAssistantMessage =
      conversation.messages.exists(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
    if (hasToolResult || hasAssistantMessage) Right(second) else Right(first)
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}
