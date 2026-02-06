package org.llm4s.llmconnect

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, DurationInt }

/**
 * Stateless helper functions for retrying LLM completion and streaming calls.
 *
 * Retries only on recoverable errors (e.g. rate limit, timeout). Fails immediately on non-recoverable errors.
 * Uses exponential backoff (baseDelay * 2^attempt) capped at 30 seconds.
 */
object LLMClientRetry {

  private val maxBackoffMs = 30000L

  /**
   * Calls `client.complete` with retries on recoverable errors.
   *
   * @param client       LLM client
   * @param conversation conversation to complete
   * @param options      completion options (default: CompletionOptions())
   * @param maxAttempts  maximum attempts including the first (default: 3)
   * @param baseDelay    base delay for backoff (default: 1 second)
   * @return Right(Completion) on success, Left(last error) when retries exhausted or non-recoverable error
   */
  def completeWithRetry(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second
  ): Result[Completion] = {
    @tailrec
    def attempt(attemptNumber: Int): Result[Completion] =
      client.complete(conversation, options) match {
        case Right(c) => Right(c)
        case Left(e) =>
          if (attemptNumber >= maxAttempts)
            Left(e)
          else
            if (LLMError.isRecoverable(e)) {
              val delayMs = backoffMs(attemptNumber, baseDelay)
              Thread.sleep(delayMs)
              attempt(attemptNumber + 1)
            } else
              Left(e)
      }
    attempt(1)
  }

  /**
   * Calls `client.streamComplete` with retries only when failure occurs before any chunk is emitted.
   * Once streaming has started (at least one chunk delivered), any error is returned immediately without retry.
   *
   * @param client       LLM client
   * @param conversation conversation to complete
   * @param options      completion options (default: CompletionOptions())
   * @param maxAttempts  maximum attempts including the first (default: 3)
   * @param baseDelay    base delay for backoff (default: 1 second)
   * @param onChunk      callback for each streamed chunk
   * @return Right(Completion) on success, Left(error) when retries exhausted or non-recoverable error
   */
  def streamCompleteWithRetry(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second
  )(onChunk: StreamedChunk => Unit): Result[Completion] = {
    var chunkEmitted = false
    val wrappedOnChunk: StreamedChunk => Unit = (c) => {
      chunkEmitted = true
      onChunk(c)
    }

    @tailrec
    def attempt(attemptNumber: Int): Result[Completion] =
      client.streamComplete(conversation, options, wrappedOnChunk) match {
        case Right(c) => Right(c)
        case Left(e) =>
          if (chunkEmitted)
            Left(e)
          else if (attemptNumber >= maxAttempts)
            Left(e)
          else
            if (LLMError.isRecoverable(e)) {
              val delayMs = backoffMs(attemptNumber, baseDelay)
              Thread.sleep(delayMs)
              attempt(attemptNumber + 1)
            } else
              Left(e)
      }
    attempt(1)
  }

  private def backoffMs(attemptNumber: Int, baseDelay: FiniteDuration): Long = {
    val d = (baseDelay.toMillis * Math.pow(2, attemptNumber - 1)).toLong
    Math.min(d, maxBackoffMs)
  }
}


