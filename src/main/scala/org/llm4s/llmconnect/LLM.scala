package org.llm4s.llmconnect

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.types.Result

object LLM {

  def client(
    provider: LLMProvider,
    config: ProviderConfig
  ): LLMClient = LLMConnect.getClient(provider, config)

  def complete(
    messages: Seq[Message],
    provider: LLMProvider,
    config: ProviderConfig,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val conversation = Conversation(messages)
    client(provider, config).complete(conversation, options)
  }

  def client(reader: ConfigReader): LLMClient = LLMConnect.getClient(reader)

}
