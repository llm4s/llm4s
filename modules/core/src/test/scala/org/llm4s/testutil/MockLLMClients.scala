package org.llm4s.testutil

import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/** Shared mock LLM clients for testing. */
object MockLLMClients {

  /** Returns a fixed response for every call. */
  class SimpleMock(response: String) extends LLMClient {
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

  /** Cycles through responses in order; wraps around at the end. */
  class MultiResponseMock(responses: Seq[String]) extends LLMClient {
    private var responseIndex                  = 0
    var conversationHistory: Seq[Conversation] = Seq.empty

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      conversationHistory = conversationHistory :+ conversation
      val response = responses(responseIndex % responses.size)
      responseIndex += 1
      Right(
        Completion(
          id = s"test-id-$responseIndex",
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

  /** Always fails with a NetworkError. */
  class FailingMock(errorMessage: String = "Mock network error") extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(NetworkError(errorMessage, None, "mock://test"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }
}
