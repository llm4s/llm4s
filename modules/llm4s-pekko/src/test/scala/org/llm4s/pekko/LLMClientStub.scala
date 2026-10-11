package org.llm4s.pekko

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

/** A client whose `streamComplete` is `onStream`; it receives the conversation and options it was called with. */
final private[pekko] class LLMClientStub(
  onStream: (Conversation, CompletionOptions, StreamedChunk => Unit) => Result[Completion]
) extends LLMClient {
  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = Right(Fixtures.completion)
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = onStream(conversation, options, onChunk)
  def getContextWindow(): Int     = 4096
  def getReserveCompletion(): Int = 256
}
