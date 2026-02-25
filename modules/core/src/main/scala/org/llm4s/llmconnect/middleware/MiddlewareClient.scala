package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TokenBudget, HeadroomPercent }

/**
 * Base class for middleware implementations that wrap an underlying client.
 * Provides default delegation for all LLMClient methods.
 *
 * Most middleware only needs to override `complete` and `streamComplete`.
 * Other methods (validate, close, configuration getters) are delegated automatically.
 */
abstract class MiddlewareClient(protected val next: LLMClient) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] =
    next.complete(conversation, options)

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    next.streamComplete(conversation, options, onChunk)

  override def getContextWindow(): Int = next.getContextWindow()

  override def getReserveCompletion(): Int = next.getReserveCompletion()

  override def getContextBudget(headroom: HeadroomPercent = HeadroomPercent.Standard): TokenBudget =
    next.getContextBudget(headroom)

  override def validate(): Result[Unit] = next.validate()

  override def close(): Unit = next.close()
}
