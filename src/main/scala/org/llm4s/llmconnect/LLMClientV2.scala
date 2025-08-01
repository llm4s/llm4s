package org.llm4s.llmconnect

import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

/**
 * Enhanced LLM client with comprehensive error handling.
 *
 * This interface coexists with the existing LLMClient without breaking changes.
 * It provides the same functionality but with enhanced error types.
 *
 * Migration path:
 * 1. Current:  org.llm4s.llmconnect.LLMClient (legacy errors)
 * 2. Enhanced: org.llm4s.llmconnect.LLMClientV2 (comprehensive errors)
 * 3. Future:   Enhanced version becomes the main LLMClient in later releases
 */
trait LLMClientV2 {

  /**
   * Execute completion with enhanced error handling
   */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion]

  /**
   * Execute streaming completion with enhanced error handling
   */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion]

  /**
   * Validate client configuration
   */
  def validate(): Result[Unit]

  /**
   * Close client and cleanup resources
   */
  def close(): Unit
}
