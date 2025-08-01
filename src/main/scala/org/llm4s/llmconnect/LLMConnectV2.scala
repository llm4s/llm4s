package org.llm4s.llmconnect

import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.types.Result

/**
 * Enhanced LLM client factory with comprehensive error handling.
 *
 * This factory creates clients that use the enhanced error hierarchy
 * while maintaining compatibility with existing infrastructure.
 */
object LLMConnectV2 {

  /**
   * Creates enhanced client with comprehensive error handling using environment variables.
   * Uses existing infrastructure but with better error types.
   */
  def enhancedClient(): LLMClientV2 = {
    val legacyClient = org.llm4s.llmconnect.LLMConnect.getClient()
    new LLMClientAdapter(legacyClient)
  }

  /**
   * Create enhanced client with specific provider configuration.
   */
  def enhancedClient(provider: LLMProvider, config: ProviderConfig): LLMClientV2 = {
    val legacyClient = org.llm4s.llmconnect.LLMConnect.getClient(provider, config)
    new LLMClientAdapter(legacyClient)
  }

  /**
   * Convenience method for quick completion with enhanced error handling
   */
  def complete(
    messages: Seq[org.llm4s.llmconnect.model.Message],
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val client       = enhancedClient()
    val conversation = Conversation(messages)
    client.complete(conversation, options)
  }
}
