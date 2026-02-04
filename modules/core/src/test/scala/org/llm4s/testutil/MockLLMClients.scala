package org.llm4s.testutil

import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/**
 * Mock LLM client for testing that returns a predefined response.
 */
class MockLLMClient(response: String) extends LLMClient {
  var lastConversation: Option[Conversation] = None

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    lastConversation = Some(conversation)
    Right(
      Completion(
        id = "test-id",
        created = System.currentTimeMillis(),
        content = response,
        model = "test-model",
        message = AssistantMessage(response),
        usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
      )
    )
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int     = 4096
  override def getReserveCompletion(): Int = 1024
}

/**
 * Mock LLM client for testing that always fails with a network error.
 */
class FailingMockLLMClient extends LLMClient {
  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Left(NetworkError("Mock network error", None, "mock://test"))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int     = 4096
  override def getReserveCompletion(): Int = 1024
}
