package org.llm4s.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/** `MicrometerMetrics` records the generic [[Metric]]s other modules declare (design 4.59). */
class MicrometerGenericMetricsSpec extends AnyFlatSpec with Matchers {

  private val Runs  = Metric.counter("llm4s.test.runs.started", "Runs started", "graph", "trigger")
  private val Wait  = Metric.timer("llm4s.test.queue.wait", "Queue wait", Seq(1.second, 5.seconds), "agent")
  private val Depth = Metric.gauge("llm4s.test.queue.depth", "Queue depth", "agent")

  private def fresh() = {
    val registry = new SimpleMeterRegistry()
    registry -> new MicrometerMetrics(registry)
  }

  "MicrometerMetrics" should "record a counter under the metric's name and labels" in {
    val (registry, metrics) = fresh()
    metrics.increment(Runs, Seq("assistant", "start"))
    metrics.increment(Runs, Seq("assistant", "start"), 2.0)
    registry.find(Runs.name).tags("graph", "assistant", "trigger", "start").counter().count() shouldBe 3.0
  }

  it should "record a timer" in {
    val (registry, metrics) = fresh()
    metrics.observe(Wait, Seq("assistant"), 2.seconds)
    val timer = registry.find(Wait.name).tags("agent", "assistant").timer()
    timer.count() shouldBe 1L
    timer.totalTime(TimeUnit.SECONDS) shouldBe 2.0
  }

  it should "hold a gauge at its latest value, per series" in {
    val (registry, metrics) = fresh()
    metrics.set(Depth, Seq("a"), 4.0)
    metrics.set(Depth, Seq("a"), 2.0)
    metrics.set(Depth, Seq("b"), 7.0)
    System.gc()
    registry.find(Depth.name).tags("agent", "a").gauge().value() shouldBe 2.0
    registry.find(Depth.name).tags("agent", "b").gauge().value() shouldBe 7.0
  }

  it should "drop a recording with the wrong number of label values, or a negative or non-finite amount" in {
    val (registry, metrics) = fresh()
    metrics.increment(Runs, Seq("assistant"))
    metrics.increment(Runs, Seq("assistant", "start"), -1.0)
    metrics.set(Depth, Seq("a"), Double.NaN)
    registry.getMeters.size shouldBe 0
  }

  it should "record a blank value as unknown and values past the cap as other" in {
    val (registry, metrics) = fresh()
    metrics.increment(Runs, Seq(null, "start"))
    (1 to MetricsCollector.MaxLabelValues + 1).foreach(i => metrics.increment(Runs, Seq(s"g$i", "start")))
    registry.find(Runs.name).tags("graph", "unknown").counter().count() shouldBe 1.0
    registry.find(Runs.name).tags("graph", "other").counter().count() shouldBe 2.0
  }
}
