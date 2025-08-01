package org.llm4s.llmconnect

import org.llm4s.Result
import org.llm4s.error.ErrorBridge
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

/**
 * Adapter that wraps existing LLMClient with enhanced error handling.
 *
 * This allows users to get enhanced error handling immediately without
 * waiting for provider implementations to be updated.
 */
class LLMClientAdapter(underlying: LLMClient) extends LLMClientV2 {

  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] =
    underlying.complete(conversation, options).left.map(ErrorBridge.toCore)

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    underlying.streamComplete(conversation, options, onChunk).left.map(ErrorBridge.toCore)

  def validate(): Result[Unit] =
    // Basic validation - can be enhanced later
    Result.success(())

  def close(): Unit =
    // Delegate to underlying client if it supports closing
    underlying match {
      case _ =>
      // No-op if underlying client does not support closing
    }
}
