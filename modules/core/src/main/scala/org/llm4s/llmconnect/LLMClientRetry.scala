package org.llm4s.llmconnect

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError, SimpleError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, DurationInt }

/**
 * Stateless helper functions for retrying LLM completion and streaming calls.
 *
 * Retries only on recoverable errors (e.g. rate limit, timeout). Fails immediately on non-recoverable errors.
 *
 * '''Retry delay precedence''' (honors upstream backpressure):
 * - If the error provides a provider retry-delay hint (e.g. `retryDelay` on [[org.llm4s.error.RateLimitError]],
 *   [[org.llm4s.error.ServiceError]]) and it is present and positive, that value is used so we do not retry before the server is ready.
 * - Otherwise we fall back to local exponential backoff (baseDelay * 2^attempt) to avoid tight retry loops.
 * - The chosen delay is always capped at 30 seconds so waits remain bounded.
 */
object LLMClientRetry {

  /** Maximum retry delay in milliseconds; all delays (provider hint or computed) are capped at this. */
  private val maxBackoffMs = 30000L

  /**
   * Calls `client.complete` with retries on recoverable errors.
   *
   * @param client       LLM client
   * @param conversation conversation to complete
   * @param options      completion options (default: CompletionOptions())
   * @param maxAttempts  maximum attempts including the first (default: 3); must be positive
   * @param baseDelay    base delay for backoff when provider retry-delay hints are absent (default: 1 second); must be positive
   * @return Right(Completion) on success, Left(error) when retries exhausted, non-recoverable error, invalid input, or interrupted
   */
  def completeWithRetry(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second
  ): Result[Completion] =
    validateRetryParams(maxAttempts, baseDelay) match {
      case Left(err) => Left(err)
      case Right(()) =>
        @tailrec
        def attempt(attemptNumber: Int): Result[Completion] =
          client.complete(conversation, options) match {
            case Right(c) => Right(c)
            case Left(e) =>
              if (attemptNumber >= maxAttempts)
                Left(e)
              else if (!isRetryable(e))
                Left(e)
              else
                sleepForRetry(e, attemptNumber, baseDelay) match {
                  case Left(err) => Left(err)
                  case Right(()) => attempt(attemptNumber + 1)
                }
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
   * @param maxAttempts  maximum attempts including the first (default: 3); must be positive
   * @param baseDelay    base delay for backoff when provider retry-delay hints are absent (default: 1 second); must be positive
   * @param onChunk      callback for each streamed chunk
   * @return Right(Completion) on success, Left(error) when retries exhausted, non-recoverable error, invalid input, or interrupted
   */
  def streamCompleteWithRetry(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    maxAttempts: Int = 3,
    baseDelay: FiniteDuration = 1.second
  )(onChunk: StreamedChunk => Unit): Result[Completion] =
    validateRetryParams(maxAttempts, baseDelay) match {
      case Left(err) => Left(err)
      case Right(()) =>
        var chunkEmitted = false
        val wrappedOnChunk: StreamedChunk => Unit = c => {
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
              else if (!isRetryable(e))
                Left(e)
              else
                sleepForRetry(e, attemptNumber, baseDelay) match {
                  case Left(err) => Left(err)
                  case Right(()) => attempt(attemptNumber + 1)
                }
          }
        attempt(1)
    }

  /** ServiceError is retried only for 5xx, 429, or 408; other status codes are non-recoverable. */
  private def isRetryableServiceError(e: ServiceError): Boolean =
    e.httpStatus >= 500 || e.httpStatus == 429 || e.httpStatus == 408

  private def isRetryable(e: LLMError): Boolean = e match {
    case s: ServiceError => isRetryableServiceError(s)
    case other           => LLMError.isRecoverable(other)
  }

  /** Validate maxAttempts and baseDelay; return ValidationError if invalid so Result contract is preserved. */
  private def validateRetryParams(maxAttempts: Int, baseDelay: FiniteDuration): Result[Unit] =
    if (maxAttempts <= 0)
      Left(ValidationError("maxAttempts", "must be positive"))
    else if (baseDelay.toMillis <= 0)
      Left(ValidationError("baseDelay", "must be positive"))
    else
      Right(())

  /**
   * Chooses retry delay in milliseconds: provider hint when valid, else exponential backoff; always capped.
   *
   * Provider retry-delay is read only from existing error types that expose it (`retryDelay` on
   * [[org.llm4s.error.RateLimitError]], [[org.llm4s.error.ServiceError]]). Missing, zero, or negative values are treated as "not present" and we fall back
   * to computed backoff so retry semantics remain well-defined.
   *
   * Precedence: (1) use provider delay if present and > 0; (2) else use exponential backoff. Final delay is
   * capped at 30 seconds to keep waits bounded regardless of provider or attempt number.
   */
  private def delayMsForError(e: LLMError, attemptNumber: Int, baseDelay: FiniteDuration): Long = {
    val providerMs = e match {
      case r: RateLimitError => r.retryDelay
      case s: ServiceError   => s.retryDelay
      case _                 => None
    }
    // Treat missing, zero, or negative as not present → fall back to backoff
    val ms = providerMs.filter(_ > 0).getOrElse(backoffMs(attemptNumber, baseDelay))
    Math.min(ms, maxBackoffMs)
  }

  /**
   * Sleep for retry delay. Catches InterruptedException, restores interrupt flag, and returns a typed error
   * so the method never throws and the Result contract is preserved.
   */
  private def sleepForRetry(
    e: LLMError,
    attemptNumber: Int,
    baseDelay: FiniteDuration
  ): Result[Unit] = {
    val delayMs = delayMsForError(e, attemptNumber, baseDelay)
    try {
      Thread.sleep(delayMs)
      Right(())
    } catch {
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(
          SimpleError(
            s"Retry interrupted during attempt $attemptNumber after ${e.getClass.getSimpleName}: ${e.message}"
          )
        )
    }
  }

  private def backoffMs(attemptNumber: Int, baseDelay: FiniteDuration): Long = {
    val d = (baseDelay.toMillis * Math.pow(2, attemptNumber - 1)).toLong
    Math.min(d, maxBackoffMs)
  }
}
