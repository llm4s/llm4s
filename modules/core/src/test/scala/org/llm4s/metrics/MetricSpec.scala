package org.llm4s.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** `Metric`'s constructors refuse what a backend could not export (design 4.59). */
class MetricSpec extends AnyFlatSpec with Matchers {

  "Metric.counter" should "keep its name, help and label names in order" in {
    val m = Metric.counter("llm4s.agent.runs.started", "Runs started", "graph", "trigger")
    m.name shouldBe "llm4s.agent.runs.started"
    m.help shouldBe "Runs started"
    m.labelNames shouldBe Vector("graph", "trigger")
  }

  it should "refuse a name that is not dot-separated lower-case words" in {
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s_agent_runs", "x")
    an[IllegalArgumentException] should be thrownBy Metric.counter("Llm4s.runs", "x")
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s..runs", "x")
    an[IllegalArgumentException] should be thrownBy Metric.counter("", "x")
  }

  it should "refuse a name ending in a unit the backends add" in {
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s.runs.total", "x")
    an[IllegalArgumentException] should be thrownBy Metric.timer("llm4s.run.seconds", "x", Seq(1.second))
    an[IllegalArgumentException] should be thrownBy Metric.gauge("llm4s.runs.total", "x")
    // a gauge keeps its name, so it names its own unit
    Metric.gauge("llm4s.queue.oldest.seconds", "x").name shouldBe "llm4s.queue.oldest.seconds"
  }

  it should "refuse blank help, bad, reserved or repeated label names" in {
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s.runs", " ")
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s.runs", "x", "Status")
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s.runs", "x", "le")
    an[IllegalArgumentException] should be thrownBy Metric.counter("llm4s.runs", "x", "status", "status")
  }

  "Metric.timer" should "need positive, ascending buckets" in {
    Metric.timer("llm4s.run.duration", "x", Seq(1.second, 2.seconds)).buckets shouldBe Vector(1.second, 2.seconds)
    an[IllegalArgumentException] should be thrownBy Metric.timer("llm4s.run.duration", "x", Seq.empty)
    an[IllegalArgumentException] should be thrownBy Metric.timer("llm4s.run.duration", "x", Seq(Duration.Zero))
    an[IllegalArgumentException] should be thrownBy Metric.timer("llm4s.run.duration", "x", Seq(2.seconds, 1.second))
  }

  "MetricLabels" should "refuse a recording whose values do not match the labels" in {
    val labels = new MetricLabels()
    val m      = Metric.counter("llm4s.runs", "x", "status")
    labels(m, Seq("a", "b")) shouldBe None
    labels(m, Seq.empty) shouldBe None
    labels(m, Seq("ok")) shouldBe Some(Vector("ok"))
  }

  it should "record a null or blank value as unknown" in {
    val labels = new MetricLabels()
    val m      = Metric.counter("llm4s.runs", "x", "status")
    labels(m, Seq(null)) shouldBe Some(Vector("unknown"))
    labels(m, Seq(" ")) shouldBe Some(Vector("unknown"))
  }

  it should "record values past the cap as other, per label of each metric, keeping those admitted" in {
    val labels = new MetricLabels(limit = 2)
    val m      = Metric.counter("llm4s.runs", "x", "a", "b")
    labels(m, Seq("1", "x")) shouldBe Some(Vector("1", "x"))
    labels(m, Seq("2", "x")) shouldBe Some(Vector("2", "x"))
    labels(m, Seq("3", "y")) shouldBe Some(Vector("other", "y"))
    labels(m, Seq("1", "z")) shouldBe Some(Vector("1", "other"))
    labels(Metric.counter("llm4s.other.runs", "x", "a"), Seq("3")) shouldBe Some(Vector("3"))
  }
}
