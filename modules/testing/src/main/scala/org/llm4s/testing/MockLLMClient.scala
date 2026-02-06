package org.llm4s.testing

import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.collection.mutable

/**
 * A mock LLMClient that allows programmatic definition of expected behaviors.
 *
 * Supports multiple matching strategies:
 * - Exact match: `whenExactly(conversation, options)(response)`
 * - Content contains: `whenContains("hello").thenReturn(response)`
 * - Custom predicate: `when((conv, opts) => Some(response))`
 *
 * @example
 * {{{
 * val mock = new MockLLMClient()
 *
 * // Exact match
 * mock.whenExactly(conversation, options)(response)
 *
 * // Partial match - returns response when prompt contains "hello"
 * mock.whenContains("hello").thenReturn(response)
 *
 * // Simulate error
 * mock.whenContains("error").thenFail(ValidationError("test", "Simulated error"))
 * }}}
 */
class MockLLMClient extends LLMClient {

  private val expectations = mutable.Buffer[(Conversation, CompletionOptions) => Option[Either[LLMError, Completion]]]()

  /**
   * Registers a function to handle requests. Functions are checked in order.
   * The first function that returns Some(...) handles the request.
   */
  def when(handler: (Conversation, CompletionOptions) => Option[Completion]): Unit =
    expectations += { (c, o) => handler(c, o).map(Right(_)) }

  /**
   * Register handler that can return either success or failure.
   */
  def whenWithResult(handler: (Conversation, CompletionOptions) => Option[Either[LLMError, Completion]]): Unit =
    expectations += handler

  /**
   * Exact match expectation.
   */
  def whenExactly(conversation: Conversation, options: CompletionOptions)(response: Completion): Unit =
    when((c, o) => if (c == conversation && o == options) Some(response) else None)

  /**
   * Start a fluent builder for content-based matching.
   *
   * @param text Text that must appear in any message content
   */
  def whenContains(text: String): MockExpectationBuilder = new MockExpectationBuilder(text, this)

  /**
   * Always return the same response regardless of input.
   * Useful for simple test setups.
   */
  def alwaysReturn(response: Completion): Unit =
    when((_, _) => Some(response))

  /**
   * Always return an error regardless of input.
   */
  def alwaysFail(error: LLMError): Unit =
    expectations += { (_, _) => Some(Left(error)) }

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    expectations.view.flatMap(_(conversation, options)).headOption match {
      case Some(result) => result
      case None         => Left(ValidationError("conversation", "No mock expectation matched this request."))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options).map { completion =>
      onChunk(StreamedChunk(completion.id, Some(completion.content)))
      completion
    }

  /**
   * Clear all registered expectations.
   */
  def reset(): Unit = expectations.clear()

  override def getContextWindow(): Int     = 4096
  override def getReserveCompletion(): Int = 1024
}

/**
 * Fluent builder for mock expectations.
 */
class MockExpectationBuilder(text: String, client: MockLLMClient) {

  private def matchesContent(conversation: Conversation): Boolean =
    conversation.messages.exists(msg => msg.content.contains(text))

  /**
   * Return the specified response when content matches.
   */
  def thenReturn(response: Completion): Unit =
    client.when((conv, _) => if (matchesContent(conv)) Some(response) else None)

  /**
   * Return an error when content matches.
   */
  def thenFail(error: LLMError): Unit =
    client.whenWithResult((conv, _) => if (matchesContent(conv)) Some(Left(error)) else None)
}
