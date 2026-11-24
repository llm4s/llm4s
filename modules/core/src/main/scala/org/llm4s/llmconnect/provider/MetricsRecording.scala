package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.ContentFilteredError
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
@Stable
trait MetricsRecording {

  /**
   * The [[org.llm4s.metrics.MetricsCollector]] that receives timing, token, and cost events.
   *
   * Injected by each concrete provider client. Defaults to
   * `MetricsCollector.noop` in all public constructors, so
   * callers that do not need metrics do not pay an allocation cost.
   */
  protected def metrics: MetricsCollector

  /**
   * Executes `operation` and records metrics for the call.
   *
   * Latency and outcome (success or classified error) are recorded for every
   * call regardless of result. Token counts and cost are recorded on
   * success. A `Left` result emits an `Outcome.Error`
   * ([[org.llm4s.metrics.Outcome]]) event whose kind is derived from the [[org.llm4s.error.LLMError]] subtype
   * via `ErrorKind.fromLLMError`, and records tokens only for a [[org.llm4s.error.ContentFilteredError]] carrying
   * the usage the provider billed for the filtered call.
   *
   * @param provider     Provider label forwarded to the collector (e.g. `"openai"`).
   * @param model        Model identifier forwarded to the collector.
   * @param operation    The LLM call to time and observe.
   * @param extractUsage Extracts prompt/completion token counts from a successful
   *                     result; return `None` to skip token recording.
   * @param extractCost  Extracts the pre-computed cost (USD) from a successful
   *                     result; return `None` to skip cost recording.
   * @return The result of `operation`, unchanged.
   */
  protected def withMetrics[A](
    provider: String,
    model: String,
    operation: => Result[A],
    extractUsage: A => Option[TokenUsage],
    extractCost: A => Option[Double]
  ): Result[A] = {
    val startNanos = System.nanoTime()
    val result     = operation
    val duration   = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)

    result match {
      case Right(value) =>
        metrics.observeRequest(provider, model, Outcome.Success, duration)
        extractUsage(value).foreach { usage =>
          // output tokens are what the provider billed as output: the completion plus any thinking
          metrics.addTokens(provider, model, usage.promptTokens.toLong, usage.totalOutputTokens.toLong)
        }
        // Record cost from the result itself (not computed here)
        extractCost(value).foreach(cost => metrics.recordCost(provider, model, cost))
      case Left(error) =>
        val errorKind = ErrorKind.fromLLMError(error)
        metrics.observeRequest(provider, model, Outcome.Error(errorKind), duration)
        error match {
          case filtered: ContentFilteredError =>
            // a filtered reply is billed: its tokens count, as a success's do (#2676)
            filtered.usage.foreach(usage =>
              metrics.addTokens(provider, model, usage.promptTokens.toLong, usage.totalOutputTokens.toLong)
            )
          case _ => ()
        }
    }

    result
  }
}
