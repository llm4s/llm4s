package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.types.Result

object LLM {

  /** Factory method for getting a client with the right configuration */
  def client(
    provider: LLMProvider,
    config: ProviderConfig
  ): LLMClient = LLMConnect.getClient(provider, config)

  /** Convenience method for quick completion */
  def complete(
    messages: Seq[Message],
    provider: LLMProvider,
    config: ProviderConfig,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val conversation = Conversation(messages)
    client(provider, config).complete(conversation, options)
  }

  /** Get a client based on environment variables */
  def client(): LLMClient = LLMConnect.getClient()

  /** Convenience method for quick completion using environment variables */
  def completeWithEnv(
    messages: Seq[Message],
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val conversation = Conversation(messages)
    client().complete(conversation, options)
  }
}
