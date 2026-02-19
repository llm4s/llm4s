package org.llm4s.llmconnect.middleware

import org.llm4s.error.{ RateLimitError, LLMError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.types.Result
import java.util.concurrent.atomic.AtomicLong

/**
 * Middleware that enforces a local rate limit using a Token Bucket algorithm.
 *
 * Prevents the application from overwhelming downstream providers or exceeding cost budgets.
 *
 * @param requestsPerMinute Maximum allowable requests per minute
 * @param burstCapacity Maximum burst size (default: same as RPM)
 */
class RateLimitingMiddleware(
  requestsPerMinute: Int,
  burstCapacity: Int,
  timeSource: () => Long = () => System.nanoTime()
) extends LLMMiddleware {

  def this(requestsPerMinute: Int) = this(requestsPerMinute, requestsPerMinute)

  override def name: String = "rate-limiting"

  private val refillRatePerNano = requestsPerMinute.toDouble / 60_000_000_000L

  private def convertError[A](error: LLMError): Result[A] = Left(error)

  override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
    // Simple thread-safe Token Bucket implementation
    // State is now LOCAL to this wrapper instance
    private val capacity            = burstCapacity.toLong
    private val tokens              = new AtomicLong(capacity)
    private val lastRefillTimestamp = new AtomicLong(timeSource())

    @scala.annotation.tailrec
    private def tryAcquire(): Boolean = {
      refill()
      val current = tokens.get()
      if (current > 0) {
        if (tokens.compareAndSet(current, current - 1)) true
        else tryAcquire()
      } else {
        false
      }
    }

    private def refill(): Unit = {
      val now  = timeSource()
      val last = lastRefillTimestamp.get()
      if (now > last) {
        val deltaNanos  = now - last
        val tokensToAdd = (deltaNanos * refillRatePerNano).toLong
        if (tokensToAdd > 0) {
          // Only update if we can advance the time
          if (lastRefillTimestamp.compareAndSet(last, now)) {
            tokens.updateAndGet(t => Math.min(capacity, t + tokensToAdd))
          }
        }
      }
    }

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      if (tryAcquire()) {
        next.complete(conversation, options)
      } else {
        convertError(RateLimitError("Local rate limit exceeded."))
      }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): Result[Completion] =
      if (tryAcquire()) {
        next.streamComplete(conversation, options, onChunk)
      } else {
        convertError(RateLimitError("Local rate limit exceeded."))
      }
  }
}
