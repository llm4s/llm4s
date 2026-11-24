package org.llm4s.metrics

import org.llm4s.reliability.ReliabilityMetrics
import io.micrometer.prometheusmetrics.{ PrometheusConfig, PrometheusMeterRegistry }
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * Dashboards built on `llm4s-observability-prometheus` should keep working when the same application exports a
 * Micrometer registry as Prometheus. This feeds identical calls to `PrometheusMetrics` and to `MicrometerMetrics`
 * behind a real `PrometheusMeterRegistry`, then compares the series each one exposes: names, label names, bucket
 * boundaries and values.
 */
class MicrometerPrometheusSeriesSpec extends AnyFlatSpec with Matchers {

  /** One exposed sample: its metric name, its labels (including `le` for a bucket) and its value. */
  final private case class Sample(name: String, labels: Map[String, String], value: Double)

  private val Line = """^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})? (\S+)$""".r
  private val Pair = """([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""".r

  private def parse(text: String): Seq[Sample] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .flatMap {
        case Line(name, labels, value) =>
          val pairs = Option(labels).toSeq.flatMap(l => Pair.findAllMatchIn(l).map(m => m.group(1) -> m.group(2)))
          Some(Sample(name, pairs.toMap, value.toDouble))
        case other => fail(s"cannot parse the exposition line: $other")
      }
      .toSeq

  // `_max` is a Micrometer gauge next to every timer and `_created` a client-library timestamp; neither is a
  // series a dashboard of the Prometheus module relies on.
  private def comparable(samples: Seq[Sample]): Seq[Sample] =
    samples.filterNot(s => s.name.endsWith("_max") || s.name.endsWith("_created"))

  private def key(s: Sample): (String, Map[String, String]) = (s.name, s.labels)

  /** The calls both collectors implement, with durations that are exact in binary so sums compare equal. */
  private def exercise(metrics: MetricsCollector): Unit = {
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 250.millis)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 2.seconds)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 40.seconds)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 500.millis)
    metrics.observeRequest("anthropic", "claude", Outcome.Error(ErrorKind.ServiceError), 2.seconds)
    metrics.addTokens("openai", "gpt-4o", 100L, 40L)
    metrics.addTokens("anthropic", "claude", 7L, 3L)
    metrics.recordCost("openai", "gpt-4o", 0.5)
    metrics.recordCost("openai", "gpt-4o", 0.25)
    // a failed operation a module above core adds to llm4s_errors_total, as llm4s-image does. The image series
    // themselves are compared, for both collectors, by llm4s-image's ImageMetricsSeriesSpec (#2801)
    metrics.increment(MetricsCollector.Errors, Seq("openai", "network"))
    // what ReliableClient records: generic metrics core declares, which both collectors export (#2801)
    metrics.increment(ReliabilityMetrics.Retries, Seq("openai"))
    metrics.increment(ReliabilityMetrics.CircuitBreakerTransitions, Seq("openai", "open"))
    metrics.increment(ReliabilityMetrics.Errors, Seq("openai", ReliabilityMetrics.errorTypeLabel(ErrorKind.Timeout)))
    // generic metrics, as a module above core declares them (design 4.59)
    val things = Metric.counter("llm4s.test.things", "Things counted", "kind")
    metrics.increment(things, Seq("a"))
    metrics.increment(things, Seq("b"), 2.0)
    val waits = Metric.timer("llm4s.test.wait", "Waits", Seq(1.second, 10.seconds), "kind")
    metrics.observe(waits, Seq("a"), 500.millis)
    metrics.observe(waits, Seq("a"), 4.seconds)
    val depth = Metric.gauge("llm4s.test.depth", "Depth", "kind")
    metrics.set(depth, Seq("a"), 3.0)
    metrics.set(depth, Seq("a"), 5.0)
    metrics.set(depth, Seq("b"), 1.0)
  }

  private def micrometerSamples(): Seq[Sample] = {
    val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics  = new MicrometerMetrics(registry)
    exercise(metrics)
    comparable(parse(registry.scrape()))
  }

  private def prometheusSamples(): Seq[Sample] = {
    val registry = new PrometheusRegistry()
    exercise(new PrometheusMetrics(registry))
    val out = new ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, registry.scrape())
    comparable(parse(new String(out.toByteArray, StandardCharsets.UTF_8)))
  }

  /** `ReliabilityMetrics`, as Prometheus names them: once Micrometer-only, now in both (#2801). */
  private val Extras =
    Set("llm4s_retries_total", "llm4s_circuit_breaker_transitions_total", "llm4s_reliability_errors_total")

  "The Micrometer collector, exported as Prometheus," should "expose the series of the Prometheus module and nothing else" in {
    val micrometer = micrometerSamples()
    val prometheus = prometheusSamples()

    prometheus should not be empty
    micrometer.map(key).toSet shouldBe prometheus.map(key).toSet
    prometheus.map(_.name).toSet should contain allElementsOf Extras
  }

  it should "expose the same values for the same calls" in {
    val expected = prometheusSamples().map(s => key(s) -> s.value).toMap
    val actual   = micrometerSamples().map(s => key(s) -> s.value).toMap

    actual.keySet shouldBe expected.keySet
    expected.foreach { case (k, v) =>
      withClue(s"series ${k._1}${k._2}: ")(actual(k) shouldBe v +- 1e-9)
    }
  }

  it should "use the Prometheus module's bucket boundaries for the request duration and for a generic timer" in {
    def bounds(samples: Seq[Sample], name: String): Set[Double] =
      samples.filter(_.name == name).flatMap(_.labels.get("le")).map(_.replace("+Inf", "Infinity").toDouble).toSet

    val micrometer = micrometerSamples()
    val prometheus = prometheusSamples()
    Seq("llm4s_request_duration_seconds_bucket", "llm4s_test_wait_seconds_bucket").foreach { name =>
      withClue(s"$name: ")(bounds(micrometer, name) shouldBe bounds(prometheus, name))
    }
    (bounds(micrometer, "llm4s_request_duration_seconds_bucket") should contain).allOf(
      0.1,
      0.5,
      120.0,
      Double.PositiveInfinity
    )
  }

  it should "name ReliableClient's metrics and give them only the documented labels" in {
    val extras = micrometerSamples().filter(s => Extras(s.name))
    extras.find(_.name == "llm4s_retries_total").map(_.labels.keySet) shouldBe Some(Set("provider"))
    extras.find(_.name == "llm4s_circuit_breaker_transitions_total").map(_.labels.keySet) shouldBe Some(
      Set("provider", "state")
    )
    extras.find(_.name == "llm4s_reliability_errors_total").map(_.labels.keySet) shouldBe Some(
      Set("provider", "error_type")
    )
  }
}
