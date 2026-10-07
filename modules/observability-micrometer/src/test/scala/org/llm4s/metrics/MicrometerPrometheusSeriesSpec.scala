package org.llm4s.metrics

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
    metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 4.seconds, 2)
    metrics.observeImageGeneration("openai", "dall-e-3", "edit", Outcome.Error(ErrorKind.Network), 500.millis, 1)
    metrics.recordImageGenerationCost("openai", "dall-e-3", 0.5, 2)
  }

  private def micrometerSamples(): Seq[Sample] = {
    val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics  = new MicrometerMetrics(registry)
    exercise(metrics)
    // Extras the Prometheus collector leaves as no-ops: they must appear, but only as the documented series.
    metrics.recordRetryAttempt("openai", 1)
    metrics.recordCircuitBreakerTransition("openai", "open")
    comparable(parse(registry.scrape()))
  }

  private def prometheusSamples(): Seq[Sample] = {
    val registry = new PrometheusRegistry()
    exercise(new PrometheusMetrics(registry))
    val out = new ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, registry.scrape())
    comparable(parse(new String(out.toByteArray, StandardCharsets.UTF_8)))
  }

  private val Extras = Set("llm4s_retries_total", "llm4s_circuit_breaker_transitions_total")

  "The Micrometer collector, exported as Prometheus," should "expose the series of the Prometheus module and nothing else but the documented extras" in {
    val micrometer = micrometerSamples()
    val prometheus = prometheusSamples()

    prometheus should not be empty
    micrometer.filterNot(s => Extras(s.name)).map(key).toSet shouldBe prometheus.map(key).toSet
    micrometer.filter(s => Extras(s.name)).map(_.name).toSet shouldBe Extras
  }

  it should "expose the same values for the same calls" in {
    val expected = prometheusSamples().map(s => key(s) -> s.value).toMap
    val actual   = micrometerSamples().filterNot(s => Extras(s.name)).map(s => key(s) -> s.value).toMap

    actual.keySet shouldBe expected.keySet
    expected.foreach { case (k, v) =>
      withClue(s"series ${k._1}${k._2}: ")(actual(k) shouldBe v +- 1e-9)
    }
  }

  it should "use the Prometheus module's bucket boundaries for both durations" in {
    def bounds(samples: Seq[Sample], name: String): Set[Double] =
      samples.filter(_.name == name).flatMap(_.labels.get("le")).map(_.replace("+Inf", "Infinity").toDouble).toSet

    val micrometer = micrometerSamples()
    val prometheus = prometheusSamples()
    Seq("llm4s_request_duration_seconds_bucket", "llm4s_image_generation_duration_seconds_bucket").foreach { name =>
      withClue(s"$name: ")(bounds(micrometer, name) shouldBe bounds(prometheus, name))
    }
    (bounds(micrometer, "llm4s_request_duration_seconds_bucket") should contain).allOf(
      0.1,
      0.5,
      120.0,
      Double.PositiveInfinity
    )
  }

  it should "name the extras and give them only the documented labels" in {
    val extras = micrometerSamples().filter(s => Extras(s.name))
    extras.find(_.name == "llm4s_retries_total").map(_.labels.keySet) shouldBe Some(Set("provider"))
    extras.find(_.name == "llm4s_circuit_breaker_transitions_total").map(_.labels.keySet) shouldBe Some(
      Set("provider", "state")
    )
  }
}
