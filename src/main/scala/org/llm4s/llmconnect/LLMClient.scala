package org.llm4s.llmconnect

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

trait LLMClient {

  /** Complete a conversation and get a response */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion]

  /** Stream a completion with callback for chunks */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion]

  /** Validate client configuration */
  def validate(): Result[Unit] = Right(())

  /** Close client and cleanup resources */
  def close(): Unit = ()
}
