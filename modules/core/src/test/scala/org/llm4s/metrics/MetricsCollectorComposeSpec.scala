package org.llm4s.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.Modifier
import scala.collection.mutable
import scala.concurrent.duration._

/**
 * `MetricsCollector.compose` fans every call out to every collector it was given (#1609).
 *
 * A composed collector that keeps a trait default silently drops that metric for every
 * child, so the last test pins the contract for the whole trait: every public method
 * `MetricsCollector` declares must be overridden by the collector `compose` returns.
 */
class MetricsCollectorComposeSpec extends AnyFlatSpec with Matchers {

  /** Records the name of every method called on it; overrides every method of the trait. */
  final private class Recording extends MetricsCollector {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty

    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      calls += "observeRequest"
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit =
      calls += "addTokens"
    override def recordCost(provider: String, model: String, costUsd: Double): Unit =
      calls += "recordCost"
    override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
      calls += "increment"
    override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit =
      calls += "observe"
    override def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit =
      calls += "set"
  }

  /** A collector that throws from every method; `compose` must not let it stop the others. */
  final private class Throwing extends MetricsCollector {
    private def boom: Nothing = throw new RuntimeException("collector failure")
    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      boom
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = boom
    override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = boom
    override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit       = boom
    override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit = boom
    override def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit                = boom
  }

  /** `name(paramType, ...)` for the public, non-static, non-synthetic methods a class declares. */
  private def publicMethodSignatures(cls: Class[?]): Set[String] =
    cls.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers) && !Modifier.isStatic(m.getModifiers))
      .filter(m => !m.isSynthetic && !m.isBridge && !m.getName.contains("$"))
      .map(m => s"${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(", ")})")
      .toSet

  "MetricsCollector.compose" should "forward a generic metric, as a module above core records one, to every collector" in {
    val a        = new Recording
    val b        = new Recording
    val composed = MetricsCollector.compose(a, b)

    composed.increment(MetricsCollector.Errors, Seq("openai", "timeout"))

    a.calls.toList shouldBe List("increment")
    b.calls.toList shouldBe List("increment")
  }

  it should "forward every method of the trait to every collector, in order" in {
    val a        = new Recording
    val b        = new Recording
    val composed = MetricsCollector.compose(a, b)

    composed.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    composed.addTokens("openai", "gpt-4o", 10L, 20L)
    composed.recordCost("openai", "gpt-4o", 0.01)
    composed.increment(Metric.counter("test.things", "things"), Seq.empty)
    composed.observe(Metric.timer("test.waits", "waits", Seq(1.second)), Seq.empty, 1.second)
    composed.set(Metric.gauge("test.level", "level"), Seq.empty, 1.0)

    val expected = List(
      "observeRequest",
      "addTokens",
      "recordCost",
      "increment",
      "observe",
      "set"
    )
    a.calls.toList shouldBe expected
    b.calls.toList shouldBe expected
    // the list above is the whole trait: a new method must be added here and to `compose`
    expected.toSet shouldBe publicMethodSignatures(classOf[MetricsCollector]).map(_.takeWhile(_ != '('))
  }

  it should "keep forwarding the generic methods when an earlier collector throws" in {
    val recording = new Recording
    val composed  = MetricsCollector.compose(new Throwing, recording)

    composed.increment(Metric.counter("test.things", "things"), Seq.empty)
    composed.observe(Metric.timer("test.waits", "waits", Seq(1.second)), Seq.empty, 1.second)
    composed.set(Metric.gauge("test.level", "level"), Seq.empty, 1.0)

    recording.calls.toList shouldBe List("increment", "observe", "set")
  }

  "MetricsCollector" should "declare no image-generation method: those metrics are llm4s-image's (#2801)" in {
    publicMethodSignatures(classOf[MetricsCollector]).filter(_.toLowerCase.contains("image")) shouldBe empty
  }

  "MetricsCollector.Errors" should "be the failed-operations counter a backend exports as llm4s_errors_total" in {
    MetricsCollector.Errors.name shouldBe "llm4s.errors"
    MetricsCollector.Errors.labelNames shouldBe Vector("provider", "error_type")
  }

  it should "override every public method MetricsCollector declares, so no trait default is left in place" in {
    val declared   = publicMethodSignatures(classOf[MetricsCollector])
    val overridden = publicMethodSignatures(MetricsCollector.compose().getClass)

    declared should not be empty
    withClue("methods of MetricsCollector that compose does not forward: ") {
      (declared -- overridden) shouldBe empty
    }
  }
}
