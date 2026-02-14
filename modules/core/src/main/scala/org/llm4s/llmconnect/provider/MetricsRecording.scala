package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.types.Result

import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

/**
 * Helper trait for recording metrics consistently across all provider clients.
 *
 * Extracts the common pattern of timing requests, observing outcomes,
 * recording tokens, and reading costs from completion results.
 */
trait MetricsRecording {
  protected def metrics: MetricsCollector

  /**
   * Execute a block of code while recording metrics for the operation.
   *
   * This version extracts cost directly from the result (preferred approach).
   * The cost should already be computed and stored in the Completion.estimatedCost field.
   *
   * @param provider Provider name (e.g., "openai", "anthropic")
   * @param model Model name
   * @param f The operation to execute
   * @param extractUsage Function to extract usage from successful result
   * @param extractCost Function to extract cost from successful result
   * @tparam A Result type
   * @return The result of the operation
   */
  protected def withMetrics[A](
    provider: String,
    model: String,
    operation: => Result[A],
    extractUsage: A => Option[TokenUsage] = (_: A) => None,
    extractCost: A => Option[Double] = (_: A) => None
  ): Result[A] = {
    val startNanos = System.nanoTime()
    val result     = operation
    val duration   = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)

    result match {
      case Right(value) =>
        metrics.observeRequest(provider, model, Outcome.Success, duration)
        extractUsage(value).foreach { usage =>
          metrics.addTokens(provider, model, usage.promptTokens.toLong, usage.completionTokens.toLong)
        }
        // Record cost from the result itself (not computed here)
        extractCost(value).foreach(cost => metrics.recordCost(provider, model, cost))
      case Left(error) =>
        val errorKind = ErrorKind.fromLLMError(error)
        metrics.observeRequest(provider, model, Outcome.Error(errorKind), duration)
    }

    result
  }
}
