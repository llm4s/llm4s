package org.llm4s.agent.guardrails

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.time.Instant

/**
 * A judge model for guardrail specs: every call answers `reply`, and the last request is kept in
 * [[lastConversation]] so a spec can read the prompt the guardrail sent.
 */
final class ScoringJudgeClient(reply: String) extends LLMClient {
  var lastConversation: Option[Conversation] = None

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    lastConversation = Some(conversation)
    Right(
      Completion(
        id = "test-id",
        created = Instant.EPOCH,
        model = ModelName("test-model"),
        message = AssistantMessage(reply),
        usage = Some(TokenUsage(promptTokens = 10, completionTokens = 1, totalTokens = 11))
      )
    )
  }

  override def streamComplete(conversation: Conversation, options: CompletionOptions)(
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = complete(conversation, options)

  override def getContextWindow(): Int     = 4096
  override def getReserveCompletion(): Int = 1024
}
