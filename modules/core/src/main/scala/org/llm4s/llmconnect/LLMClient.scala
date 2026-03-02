package org.llm4s.llmconnect

import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TokenBudget, HeadroomPercent }

/**
 * Core interface for interacting with Large Language Model providers.
 *
 * Abstracts communication with various LLM APIs (OpenAI, Azure OpenAI, Anthropic, etc.),
 * providing a unified interface for completion requests, streaming responses, and token management.
 * Implementations handle provider-specific authentication, message formatting, and tool calling.
 */
trait LLMClient extends AutoCloseable {

  /**
   * Executes a blocking completion request and returns the full response.
   *
   * Sends the conversation to the LLM and waits for the complete response. Use when you need
   * the entire response at once or when streaming is not required.
   *
   * @param conversation conversation history including system, user, assistant, and tool messages
   * @param options configuration including temperature, max tokens, tools, etc. (default: CompletionOptions())
   * @return Right(Completion) with the model's response, or Left(LLMError) on failure
   */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion]

  /**
   * Executes a streaming completion request, invoking a callback for each chunk as it arrives.
   *
   * Streams the response incrementally, calling `onChunk` for each token/chunk received. Enables
   * real-time display of responses. Returns the final accumulated completion on success.
   *
   * @param conversation conversation history including system, user, assistant, and tool messages
   * @param options configuration including temperature, max tokens, tools, etc. (default: CompletionOptions())
   * @param onChunk callback invoked for each chunk; called synchronously, avoid blocking operations
   * @return Right(Completion) with the complete accumulated response, or Left(LLMError) on failure
   */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion]

  /**
   * Returns the maximum context window size supported by this model in tokens.
   *
   * The context window is the total tokens (prompt + completion) the model can process in a
   * single request, including all conversation messages and the generated response.
   *
   * @return total context window size in tokens (e.g., 4096, 8192, 128000)
   */
  def getContextWindow(): Int

  /**
   * Returns the number of tokens reserved for the model's completion response.
   *
   * This value is subtracted from the context window when calculating available tokens for prompts.
   * Corresponds to the max_tokens or completion token limit configured for the model.
   *
   * @return number of tokens reserved for completion
   */
  def getReserveCompletion(): Int

  /**
   * Calculates available token budget for prompts after accounting for completion reserve and headroom.
   *
   * Formula: `(contextWindow - reserveCompletion) * (1 - headroom)`
   *
   * Headroom provides a safety margin for tokenization variations and message formatting overhead.
   *
   * @param headroom safety margin as percentage of prompt budget (default: HeadroomPercent.Standard ~10%)
   * @return maximum tokens available for prompt content
   */
  def getContextBudget(headroom: HeadroomPercent = HeadroomPercent.Standard): TokenBudget = {
    val promptBudget = getContextWindow() - getReserveCompletion()
    (promptBudget * (1.0 - headroom.asRatio)).toInt
  }

  /**
   * Validates client configuration and connectivity to the LLM provider.
   *
   * May perform checks such as verifying API credentials, testing connectivity, and validating
   * configuration. Default implementation returns success; override for provider-specific validation.
   *
   * @return Right(()) if validation succeeds, Left(LLMError) with details on failure
   */
  def validate(): Result[Unit] = Right(())

  /**
   * Releases resources and closes connections to the LLM provider.
   *
   * Call when the client is no longer needed. After calling close(), the client should not be used.
   * Default implementation is a no-op; override if managing resources like connections or thread pools.
   */
  def close(): Unit = ()
}
