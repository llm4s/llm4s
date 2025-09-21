package org.llm4s.llmconnect

import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TokenBudget, HeadroomPercent }

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

  /** Get the model's context window size */
  def getContextWindow(): Int

  /** Get the tokens reserved for completion */
  def getReserveCompletion(): Int

  /** Get the context budget for prompts using the improved formula: (contextWindow - reserveCompletion) * (1 - headroom) */
  def getContextBudget(headroom: HeadroomPercent = HeadroomPercent.Standard): TokenBudget = {
    val promptBudget = getContextWindow() - getReserveCompletion()
    (promptBudget * (1.0 - headroom.asRatio)).toInt
  }

  /** Validate client configuration */
  def validate(): Result[Unit] = Right(())

  /** Close client and cleanup resources */
  def close(): Unit = ()
}
