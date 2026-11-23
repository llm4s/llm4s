package org.llm4s.reliability

import org.llm4s.metrics.{ ErrorKind, Metric, MetricsCollector, Outcome }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * What `ReliableClient` records reaches a collector through `increment` alone (#2801): a backend that implements the
 * generic methods needs no method of its own for retries, circuit-breaker transitions or errors.
 */
class ReliabilityMetricsSpec extends AnyFlatSpec with Matchers {

  /** A backend that knows nothing of reliability: it implements only what has no default, and `increment`. */
  final private class Generic extends MetricsCollector {
    val counted: mutable.Buffer[(String, Seq[String], Double)]                             = mutable.Buffer.empty
    override def observeRequest(p: String, m: String, o: Outcome, d: FiniteDuration): Unit = ()
    override def addTokens(p: String, m: String, in: Long, out: Long): Unit                = ()
    override def recordCost(p: String, m: String, usd: Double): Unit                       = ()
    override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
      counted += ((metric.name, labelValues, amount))
  }

  "ReliabilityMetrics" should "declare its counters with the names and labels the backends export" in {
    ReliabilityMetrics.Retries.name shouldBe "llm4s.retries"
    ReliabilityMetrics.Retries.labelNames shouldBe Vector("provider")
    ReliabilityMetrics.CircuitBreakerTransitions.name shouldBe "llm4s.circuit.breaker.transitions"
    ReliabilityMetrics.CircuitBreakerTransitions.labelNames shouldBe Vector("provider", "state")
    ReliabilityMetrics.Errors.name shouldBe "llm4s.reliability.errors"
    ReliabilityMetrics.Errors.labelNames shouldBe Vector("provider", "error_type")
  }

  it should "record each through increment, with one value per label" in {
    val backend = new Generic

    ReliabilityMetrics.retried(backend, "acme")
    ReliabilityMetrics.transitioned(backend, "acme", CircuitState.HalfOpen)
    ReliabilityMetrics.failed(backend, "acme", ErrorKind.RateLimit)

    backend.counted.toSeq shouldBe Seq(
      ("llm4s.retries", Seq("acme"), 1.0),
      ("llm4s.circuit.breaker.transitions", Seq("acme", "half-open"), 1.0),
      ("llm4s.reliability.errors", Seq("acme", "rate_limit"), 1.0)
    )
  }

  it should "label every circuit state and every error kind distinctly" in {
    val states = Seq(CircuitState.Closed, CircuitState.Open, CircuitState.HalfOpen).map(ReliabilityMetrics.stateLabel)
    states shouldBe Seq("closed", "open", "half-open")

    val kinds = ReliabilityRecordings.kinds.map(ReliabilityMetrics.errorTypeLabel)
    kinds.distinct should have size ReliabilityRecordings.kinds.size.toLong
    (all(kinds) should fullyMatch).regex("[a-z_]+")
  }

  "MetricsCollector.compose" should "forward what ReliableClient records to every collector" in {
    val a = new Generic
    val b = new Generic

    ReliabilityMetrics.retried(MetricsCollector.compose(a, b), "acme")

    a.counted.map(_._1) shouldBe Seq("llm4s.retries")
    b.counted.map(_._1) shouldBe Seq("llm4s.retries")
  }
}
