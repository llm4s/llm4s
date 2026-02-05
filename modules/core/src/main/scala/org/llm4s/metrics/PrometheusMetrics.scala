package org.llm4s.metrics

import io.prometheus.metrics.core.metrics.{ Counter, Histogram }
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Prometheus implementation of MetricsCollector.
 *
 * Tracks request volumes, token usage, errors, and latency across
 * different providers and models using Prometheus metrics.
 *
 * All operations are wrapped in try-catch to ensure metric failures
 * never propagate to callers. This implementation is thread-safe.
 *
 * Example usage:
 * {{{
 * val registry = new PrometheusRegistry()
 * val metrics = new PrometheusMetrics(registry)
 *
 * // Use with endpoint
 * PrometheusEndpoint.start(9090, registry).foreach { endpoint =>
 *   // ... use metrics ...
 *   endpoint.stop()
 * }
 * }}}
 *
 * @param registry Prometheus collector registry
 */
final class PrometheusMetrics(
  private[llm4s] val registry: PrometheusRegistry
) extends MetricsCollector {

  private val logger = LoggerFactory.getLogger(getClass)

  // Request counter with labels
  private val requestsTotal = Counter
    .builder()
    .name("llm4s_requests_total")
    .help("Total number of LLM requests")
    .labelNames("provider", "model", "status")
    .register(registry)

  // Token counter
  private val tokensTotal = Counter
    .builder()
    .name("llm4s_tokens_total")
    .help("Total tokens consumed")
    .labelNames("provider", "model", "type")
    .register(registry)

  // Cost counter
  private val costUsdTotal = Counter
    .builder()
    .name("llm4s_cost_usd_total")
    .help("Total estimated cost in USD")
    .labelNames("provider", "model")
    .register(registry)

  // Error counter
  private val errorsTotal = Counter
    .builder()
    .name("llm4s_errors_total")
    .help("Total number of errors")
    .labelNames("provider", "error_type")
    .register(registry)

  // Request duration histogram
  private val requestDuration = Histogram
    .builder()
    .name("llm4s_request_duration_seconds")
    .help("Request duration in seconds")
    .labelNames("provider", "model")
    .classicUpperBounds(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0)
    .register(registry)

  /**
   * Record an LLM request with its outcome and duration.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    Try {
      val status = outcome match {
        case Outcome.Success          => "success"
        case Outcome.Error(errorKind) =>
          // Convert PascalCase to snake_case: RateLimit -> rate_limit
          val kindStr   = errorKind.toString
          val snakeCase = kindStr.replaceAll("([A-Z])", "_$1").toLowerCase.drop(1)
          s"error_$snakeCase"
      }

      requestsTotal.labelValues(provider, model, status).inc()
      requestDuration.labelValues(provider, model).observe(duration.toMillis / 1000.0)

      outcome match {
        case Outcome.Error(errorKind) =>
          val errorLabel = errorKind.toString.toLowerCase
          errorsTotal.labelValues(provider, errorLabel).inc()
        case _ => // No additional action for success
      }
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record request metrics: ${e.getMessage}")
    }

  /**
   * Record token usage.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit =
    Try {
      tokensTotal.labelValues(provider, model, "input").inc(inputTokens.toDouble)
      tokensTotal.labelValues(provider, model, "output").inc(outputTokens.toDouble)
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record token metrics: ${e.getMessage}")
    }

  /**
   * Record estimated cost in USD.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit =
    Try {
      costUsdTotal.labelValues(provider, model).inc(costUsd)
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record cost metrics: ${e.getMessage}")
    }
}

object PrometheusMetrics {

  /**
   * Create a new PrometheusMetrics instance with a fresh registry.
   *
   * Use this when you want an isolated metrics collector, e.g., for testing
   * or when you'll expose metrics through your own HTTP endpoint.
   *
   * @return New PrometheusMetrics instance
   */
  def create(): PrometheusMetrics = {
    val registry = new PrometheusRegistry()
    new PrometheusMetrics(registry)
  }
}
