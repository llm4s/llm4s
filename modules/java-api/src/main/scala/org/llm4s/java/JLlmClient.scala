package org.llm4s.java

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }

/**
 * Java-friendly wrapper around [[LLMClient]].
 *
 * The underlying Scala client returns `Result[Completion]`; this wrapper
 * unwraps the content string and surfaces it as [[LlmResult]] so Java
 * callers never import any Scala types.
 *
 * Obtain instances via [[Llm4s.createDefaultClient]] or
 * [[Llm4s.createClient]].
 *
 * {{{
 * LlmResult<String> r = client.complete("What is 2+2?");
 * r.ifSuccess(System.out::println).ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JLlmClient private[java] (private[java] val underlying: LLMClient) extends AutoCloseable {

  /** Sends a single user query and returns the assistant's text response. */
  def complete(query: String): LlmResult[String] = {
    val conversation = Conversation(Seq(UserMessage(query)))
    LlmResult.from(underlying.complete(conversation).map(_.content))
  }

  /** Sends a pre-built conversation and returns the assistant's text response. */
  def complete(conversation: Conversation): LlmResult[String] =
    LlmResult.from(underlying.complete(conversation).map(_.content))

  /**
   * Full access: send a conversation with explicit [[CompletionOptions]],
   * returning the raw text content.
   */
  def complete(conversation: Conversation, options: CompletionOptions): LlmResult[String] =
    LlmResult.from(underlying.complete(conversation, options).map(_.content))

  override def close(): Unit = underlying.close()
}
