package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.types.Result
import org.slf4j.{ Logger, LoggerFactory }

/**
 * Middleware that logs requests and responses.
 *
 * @param logger SLF4J logger instance
 * @param redactor Optional redactor to mask sensitive data in logs
 * @param includeMessages Whether to log full message content (careful with PII!)
 */
class LoggingMiddleware(
  logger: Logger = LoggerFactory.getLogger(classOf[LoggingMiddleware]),
  redactor: Option[ContentRedactor] = Some(ContentRedactor.default),
  includeMessages: Boolean = false
) extends LLMMiddleware {

  override def name: String = "logging"

  override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      logRequest(conversation, options)
      val start      = System.nanoTime()
      val result     = next.complete(conversation, options)
      val durationMs = (System.nanoTime() - start) / 1_000_000
      logResult(result, durationMs)
      result
    }

    // Note: Logging streaming is verbose; we only log the start and final summary here.
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): Result[Completion] = {
      logRequest(conversation, options, streaming = true)
      val start      = System.nanoTime()
      val result     = next.streamComplete(conversation, options, onChunk)
      val durationMs = (System.nanoTime() - start) / 1_000_000
      logResult(result, durationMs, streaming = true)
      result
    }

    private def logRequest(conversation: Conversation, options: CompletionOptions, streaming: Boolean = false): Unit =
      if (logger.isDebugEnabled) {
        val streamTag = if (streaming) "[STREAM] " else ""
        val summary   = s"${streamTag}Request: ${conversation.messages.size} messages. Options: $options"

        if (includeMessages) {
          val content  = conversation.messages.map(m => s"${m.role}: ${m.content}").mkString("\n")
          val redacted = redactor.map(_.redact(content)).getOrElse(content)
          logger.debug(s"$summary\nContent:\n$redacted")
        } else {
          logger.debug(summary)
        }
      }

    private def logResult(result: Result[Completion], durationMs: Long, streaming: Boolean = false): Unit = {
      val streamTag = if (streaming) "[STREAM] " else ""
      result match {
        case Right(completion) =>
          val tokens = completion.usage.map(u => s"${u.totalTokens} tokens").getOrElse("usage unknown")
          val cost   = completion.estimatedCost.map(c => f"$$$c%.5f").getOrElse("cost unknown")
          logger.debug(s"${streamTag}Success (${durationMs}ms): $tokens, $cost")

          if (includeMessages && logger.isTraceEnabled) {
            val content  = completion.message.content
            val redacted = redactor.map(_.redact(content)).getOrElse(content)
            logger.trace(s"${streamTag}Response Content:\n$redacted")
          }

        case Left(error) =>
          logger.warn(s"${streamTag}Failed (${durationMs}ms): ${error.message}")
      }
    }
  }
}
