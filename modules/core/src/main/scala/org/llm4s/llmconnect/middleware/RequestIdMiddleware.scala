package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.types.Result
import org.slf4j.MDC
import java.util.UUID

/**
 * Middleware that generates and propagates a unique Request ID for each LLM call.
 *
 * Uses SLF4J MDC to make the request ID available to all loggers in the thread context.
 * This is critical for correlating logs in distributed systems.
 */
class RequestIdMiddleware(
  headerName: String = "X-Request-Id",
  generator: () => String = () => UUID.randomUUID().toString.take(8)
) extends LLMMiddleware {

  override def name: String = "request-id"

  override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
    private def withMDC[A](key: String, value: String)(f: => Result[A]): Result[A] = {
      val previous = Option(MDC.get(key))
      MDC.put(key, value)
      val result = f
      previous.fold(MDC.remove(key))(MDC.put(key, _))
      result
    }

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      val requestId = generator()
      withMDC(headerName, requestId) {
        next.complete(conversation, options)
      }
    }

    // Note: streamComplete is tricky with MDC because the callback might run on a different thread.
    // For now we only set it for the initial call, but propagation to callbacks depends on execution context.
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): Result[Completion] = {
      val requestId = generator()
      withMDC(headerName, requestId) {
        next.streamComplete(conversation, options, onChunk)
      }
    }
  }
}
