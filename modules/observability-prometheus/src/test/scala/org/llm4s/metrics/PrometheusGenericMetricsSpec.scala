package org.llm4s.metrics

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/** `PrometheusMetrics` exports the generic [[Metric]]s other modules declare (design 4.59). */
class PrometheusGenericMetricsSpec extends AnyFlatSpec with Matchers {

  private def scrape(metrics: PrometheusMetrics): Vector[String] = {
    val out = new ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, metrics.registry.scrape())
    new String(out.toByteArray, StandardCharsets.UTF_8).linesIterator.filterNot(_.startsWith("#")).toVector
  }

  private val Runs  = Metric.counter("llm4s.test.runs.started", "Runs started", "graph", "trigger")
  private val Wait  = Metric.timer("llm4s.test.queue.wait", "Queue wait", Seq(1.second, 5.seconds), "agent")
  private val Depth = Metric.gauge("llm4s.test.queue.depth", "Queue depth", "agent", "state")

  "PrometheusMetrics" should "expose a counter as <name>_total with its labels" in {
    val metrics = PrometheusMetrics.create()
    metrics.increment(Runs, Seq("assistant", "start"))
    metrics.increment(Runs, Seq("assistant", "start"), 2.0)
    scrape(metrics) should contain("llm4s_test_runs_started_total{graph=\"assistant\",trigger=\"start\"} 3.0")
  }

  it should "expose a timer as a histogram <name>_seconds with the metric's buckets" in {
    val metrics = PrometheusMetrics.create()
    metrics.observe(Wait, Seq("assistant"), 2.seconds)
    val lines = scrape(metrics)
    lines should contain("llm4s_test_queue_wait_seconds_bucket{agent=\"assistant\",le=\"1.0\"} 0")
    lines should contain("llm4s_test_queue_wait_seconds_bucket{agent=\"assistant\",le=\"5.0\"} 1")
    lines should contain("llm4s_test_queue_wait_seconds_count{agent=\"assistant\"} 1")
    lines should contain("llm4s_test_queue_wait_seconds_sum{agent=\"assistant\"} 2.0")
  }

  it should "expose a gauge under its name at its latest value" in {
    val metrics = PrometheusMetrics.create()
    metrics.set(Depth, Seq("assistant", "waiting"), 4.0)
    metrics.set(Depth, Seq("assistant", "waiting"), 2.0)
    scrape(metrics) should contain("llm4s_test_queue_depth{agent=\"assistant\",state=\"waiting\"} 2.0")
  }

  it should "drop a recording with the wrong number of label values, or a negative or non-finite amount" in {
    val metrics = PrometheusMetrics.create()
    metrics.increment(Runs, Seq("assistant"))
    metrics.increment(Runs, Seq("assistant", "start"), -1.0)
    metrics.increment(Runs, Seq("assistant", "start"), Double.NaN)
    metrics.set(Depth, Seq("assistant", "waiting"), Double.PositiveInfinity)
    scrape(metrics).filter(_.startsWith("llm4s_test_")) shouldBe empty
  }

  it should "record a blank value as unknown and values past the cap as other" in {
    val metrics = PrometheusMetrics.create()
    metrics.increment(Runs, Seq(" ", "start"))
    (1 to MetricsCollector.MaxLabelValues + 1).foreach(i => metrics.increment(Runs, Seq(s"g$i", "start")))
    val lines = scrape(metrics)
    lines should contain("llm4s_test_runs_started_total{graph=\"unknown\",trigger=\"start\"} 1.0")
    lines should contain("llm4s_test_runs_started_total{graph=\"other\",trigger=\"start\"} 2.0")
  }

  it should "drop recordings of a second definition under a name already registered" in {
    val metrics = PrometheusMetrics.create()
    metrics.increment(Runs, Seq("assistant", "start"))
    metrics.increment(Metric.counter("llm4s.test.runs.started", "Runs started", "graph"), Seq("assistant"))
    scrape(metrics).count(_.startsWith("llm4s_test_runs_started_total")) shouldBe 1
  }

  it should "never throw, whatever it is given" in {
    val metrics = PrometheusMetrics.create()
    noException should be thrownBy metrics.increment(Runs, null)
    noException should be thrownBy metrics.observe(Wait, Seq(null), 1.second)
  }
}
