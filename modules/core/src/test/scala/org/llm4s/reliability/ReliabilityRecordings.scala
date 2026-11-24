package org.llm4s.reliability

import org.llm4s.metrics.{ ErrorKind, Metric, MetricsCollector }

/**
 * Test support: a [[MetricsCollector]] that hands what `ReliableClient` records as [[ReliabilityMetrics]] to three
 * hooks, so a spec can count retries, transitions and errors without reading label values itself.
 */
trait ReliabilityRecordings extends MetricsCollector {

  def onRetry(provider: String): Unit                        = ()
  def onTransition(provider: String, newState: String): Unit = ()
  def onError(errorKind: ErrorKind, provider: String): Unit  = ()

  override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
    (metric, labelValues) match {
      case (ReliabilityMetrics.Retries, Seq(provider))                          => onRetry(provider)
      case (ReliabilityMetrics.CircuitBreakerTransitions, Seq(provider, state)) => onTransition(provider, state)
      case (ReliabilityMetrics.Errors, Seq(provider, errorType)) =>
        ReliabilityRecordings.kinds
          .find(ReliabilityMetrics.errorTypeLabel(_) == errorType)
          .foreach(onError(_, provider))
      case _ => ()
    }
}

object ReliabilityRecordings {
  val kinds: Seq[ErrorKind] = Seq(
    ErrorKind.RateLimit,
    ErrorKind.Timeout,
    ErrorKind.Authentication,
    ErrorKind.Network,
    ErrorKind.Validation,
    ErrorKind.ServiceError,
    ErrorKind.ExecutionError,
    ErrorKind.Cancelled,
    ErrorKind.Unknown
  )
}
