package org.llm4s.reliability

import org.llm4s.annotation.Experimental
import org.llm4s.metrics.{ ErrorKind, Metric, MetricsCollector }

/**
 * The metrics [[ReliableClient]] records, declared as [[org.llm4s.metrics.Metric Metric]]s and recorded through
 * [[org.llm4s.metrics.MetricsCollector.increment MetricsCollector.increment]], so every backend that exports generic
 * metrics exports them, with no method of its own to implement (#2801). They were three methods of `MetricsCollector`
 * with no-op defaults - `recordRetryAttempt`, `recordCircuitBreakerTransition` and `recordError` - which a backend
 * that did not override them dropped silently, as the Prometheus one did.
 *
 * A Prometheus backend exposes them as `llm4s_retries_total`, `llm4s_circuit_breaker_transitions_total` and
 * `llm4s_reliability_errors_total`; a Micrometer one under the names below.
 */
@Experimental
object ReliabilityMetrics {

  /** Retries: one for each attempt after a call's first. Label: `provider`. */
  val Retries: Metric.Counter =
    Metric.counter("llm4s.retries", "Total number of retry attempts", "provider")

  /** Circuit-breaker state transitions. Labels: `provider`, `state` (`open`, `closed` or `half-open`). */
  val CircuitBreakerTransitions: Metric.Counter =
    Metric.counter(
      "llm4s.circuit.breaker.transitions",
      "Total number of circuit breaker state transitions",
      "provider",
      "state"
    )

  /**
   * Errors `ReliableClient` reports outside a timed request: a call rejected by its local rate limit or its open
   * circuit, a deadline exceeded, and the final failure once retries are exhausted. Kept apart from a backend's count
   * of failed requests, which already holds each failed attempt the provider client observed, so neither counts a
   * failure twice. Labels: `provider`, `error_type` (an [[org.llm4s.metrics.ErrorKind ErrorKind]], in snake case).
   */
  val Errors: Metric.Counter =
    Metric.counter(
      "llm4s.reliability.errors",
      "Total number of errors reported outside a timed request (by ReliableClient: local throttle, open circuit, " +
        "deadline, final failure)",
      "provider",
      "error_type"
    )

  /** The `state` label of [[CircuitBreakerTransitions]] for `state`. */
  def stateLabel(state: CircuitState): String = state match {
    case CircuitState.Closed   => "closed"
    case CircuitState.Open     => "open"
    case CircuitState.HalfOpen => "half-open"
  }

  /** The `error_type` label of [[Errors]] for `kind`. */
  def errorTypeLabel(kind: ErrorKind): String = kind match {
    case ErrorKind.RateLimit      => "rate_limit"
    case ErrorKind.Timeout        => "timeout"
    case ErrorKind.Authentication => "authentication"
    case ErrorKind.Network        => "network"
    case ErrorKind.Validation     => "validation"
    case ErrorKind.ServiceError   => "service_error"
    case ErrorKind.ExecutionError => "execution_error"
    case ErrorKind.Cancelled      => "cancelled"
    case ErrorKind.Unknown        => "unknown"
  }

  private[reliability] def retried(collector: MetricsCollector, provider: String): Unit =
    collector.increment(Retries, Seq(provider))

  private[reliability] def transitioned(collector: MetricsCollector, provider: String, state: CircuitState): Unit =
    collector.increment(CircuitBreakerTransitions, Seq(provider, stateLabel(state)))

  private[reliability] def failed(collector: MetricsCollector, provider: String, kind: ErrorKind): Unit =
    collector.increment(Errors, Seq(provider, errorTypeLabel(kind)))
}
