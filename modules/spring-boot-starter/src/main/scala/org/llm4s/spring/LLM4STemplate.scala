package org.llm4s.spring

import org.llm4s.javaapi.{ JLlmClient, LlmResult }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }

import java.util.concurrent.{ CompletableFuture, ExecutorService }

/**
 * Spring-friendly entry point to an LLM.
 *
 * `complete` and `tryComplete` block the calling thread. `completeAsync` never does: it is
 * `JLlmClient.completeAsync` with the call on `executor` (the `llm4sTaskExecutor` bean), and the returned
 * future completes from there. Cancelling the future with `cancel(true)` interrupts the provider call,
 * which is how llm4s cancels work; a failure completes the future exceptionally with the `LlmException`,
 * and so does an executor that refuses the call (its `RejectedExecutionException` the cause).
 */
final class LLM4STemplate(private val client: JLlmClient, private val executor: ExecutorService) {

  /** The client with its async calls on `executor`: `JLlmClient.completeAsync` does the work (#2276). */
  private val async: JLlmClient = client.withAsyncExecutor(executor)

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
    async.completeAsync(query)

  def completeAsync(conversation: Conversation): CompletableFuture[String] =
    async.completeAsync(conversation)
}
