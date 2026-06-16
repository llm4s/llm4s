package org.llm4s.spring

import org.llm4s.java.{ JLlmClient, LlmResult }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }

import java.util.concurrent.CompletableFuture

final class LLM4STemplate(private val client: JLlmClient) {

  def complete(query: String): String =
    client.complete(query).get()

  def complete(conversation: Conversation): String =
    client.complete(conversation).get()

  def complete(conversation: Conversation, options: CompletionOptions): String =
    client.complete(conversation, options).get()

  def tryComplete(query: String): LlmResult[String] =
    client.complete(query)

  def tryComplete(conversation: Conversation): LlmResult[String] =
    client.complete(conversation)

  def completeAsync(query: String): CompletableFuture[String] =
    client.complete(query).toCompletableFuture

  def completeAsync(conversation: Conversation): CompletableFuture[String] =
    client.complete(conversation).toCompletableFuture
}
