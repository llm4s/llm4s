package org.llm4s.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class CostTrackerSpec extends AnyFlatSpec with Matchers {

  private def mkTracker(): CostTracker = CostTracker.create()

  "CostTracker" should "return zeros when empty" in {
    val tracker = mkTracker()
    tracker.totalCost shouldBe BigDecimal(0)
    tracker.totalTokens shouldBe 0L
    tracker.totalInputTokens shouldBe 0L
    tracker.totalOutputTokens shouldBe 0L
    tracker.totalRequests shouldBe 0L
    tracker.byModel shouldBe empty
  }

  it should "accumulate a single request" in {
    val tracker = mkTracker()
    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.005)

    tracker.totalRequests shouldBe 1L
    tracker.totalInputTokens shouldBe 100L
    tracker.totalOutputTokens shouldBe 50L
    tracker.totalTokens shouldBe 150L
    tracker.totalCost shouldBe BigDecimal.decimal(0.005)
  }

  it should "track multiple models separately" in {
    val tracker = mkTracker()

    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.01)

    tracker.observeRequest("anthropic", "claude-sonnet", Outcome.Success, 2.seconds)
    tracker.addTokens("anthropic", "claude-sonnet", 200L, 80L)
    tracker.recordCost("anthropic", "claude-sonnet", 0.02)

    tracker.totalRequests shouldBe 2L
    tracker.totalInputTokens shouldBe 300L
    tracker.totalOutputTokens shouldBe 130L
    tracker.totalCost shouldBe BigDecimal.decimal(0.03)

    val models = tracker.byModel
    models should have size 2
    models("gpt-4o").requestCount shouldBe 1L
    models("gpt-4o").inputTokens shouldBe 100L
    models("claude-sonnet").requestCount shouldBe 1L
    models("claude-sonnet").inputTokens shouldBe 200L
  }

  it should "maintain BigDecimal precision over many small additions" in {
    val tracker = mkTracker()
    (1 to 1000).foreach(_ => tracker.recordCost("openai", "gpt-4o", 0.001))
    tracker.totalCost shouldBe BigDecimal(1).setScale(3)
  }

  it should "handle concurrent updates correctly" in {
    val tracker   = mkTracker()
    val threads   = 10
    val perThread = 100

    val workers = (1 to threads).map { _ =>
      new Thread(() =>
        (1 to perThread).foreach { _ =>
          tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
          tracker.addTokens("openai", "gpt-4o", 10L, 5L)
          tracker.recordCost("openai", "gpt-4o", 0.001)
        }
      )
    }

    workers.foreach(_.start())
    workers.foreach(_.join())

    val total = threads * perThread
    tracker.totalRequests shouldBe total.toLong
    tracker.totalInputTokens shouldBe (total * 10L)
    tracker.totalOutputTokens shouldBe (total * 5L)
    tracker.totalCost shouldBe BigDecimal(total) * BigDecimal.decimal(0.001)
  }

  it should "reset to empty state" in {
    val tracker = mkTracker()
    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.01)

    tracker.reset()

    tracker.totalRequests shouldBe 0L
    tracker.totalTokens shouldBe 0L
    tracker.totalCost shouldBe BigDecimal(0)
    tracker.byModel shouldBe empty
  }

  it should "produce a summary string with key fields" in {
    val tracker = mkTracker()
    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.005)

    val s = tracker.summary
    s should include("1 requests")
    s should include("150 tokens")
    s should include("gpt-4o")
  }

  it should "return a valid UsageSummary from snapshot" in {
    val tracker = mkTracker()
    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.01)

    val snap = tracker.snapshot
    snap.requestCount shouldBe 1L
    snap.inputTokens shouldBe 100L
    snap.outputTokens shouldBe 50L
    snap.totalCost shouldBe BigDecimal.decimal(0.01)
    (snap.byModel should contain).key("gpt-4o")
  }

  it should "produce a snapshot where totals match byModel breakdown" in {
    val tracker = mkTracker()

    tracker.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    tracker.addTokens("openai", "gpt-4o", 100L, 50L)
    tracker.recordCost("openai", "gpt-4o", 0.01)

    tracker.observeRequest("anthropic", "claude-sonnet", Outcome.Success, 2.seconds)
    tracker.addTokens("anthropic", "claude-sonnet", 200L, 80L)
    tracker.recordCost("anthropic", "claude-sonnet", 0.02)

    val snap = tracker.snapshot

    snap.requestCount shouldBe snap.byModel.values.map(_.requestCount).sum
    snap.inputTokens shouldBe snap.byModel.values.map(_.inputTokens).sum
    snap.outputTokens shouldBe snap.byModel.values.map(_.outputTokens).sum
    snap.totalCost shouldBe snap.byModel.values.map(_.totalCost).sum
  }

  "MetricsCollector.compose" should "fan out to all collectors" in {
    val tracker1 = mkTracker()
    val tracker2 = mkTracker()
    val composed = MetricsCollector.compose(tracker1, tracker2)

    composed.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    composed.addTokens("openai", "gpt-4o", 100L, 50L)
    composed.recordCost("openai", "gpt-4o", 0.01)

    tracker1.totalRequests shouldBe 1L
    tracker2.totalRequests shouldBe 1L
    tracker1.totalTokens shouldBe 150L
    tracker2.totalCost shouldBe BigDecimal.decimal(0.01)
  }

  it should "continue to remaining collectors when one throws" in {
    val failing = new MetricsCollector {
      override def observeRequest(p: String, m: String, o: Outcome, d: FiniteDuration): Unit =
        throw new RuntimeException("boom")
      override def addTokens(p: String, m: String, i: Long, o: Long): Unit =
        throw new RuntimeException("boom")
      override def recordCost(p: String, m: String, c: Double): Unit =
        throw new RuntimeException("boom")
    }
    val tracker  = mkTracker()
    val composed = MetricsCollector.compose(failing, tracker)

    noException should be thrownBy {
      composed.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
      composed.addTokens("openai", "gpt-4o", 100L, 50L)
      composed.recordCost("openai", "gpt-4o", 0.01)
    }

    tracker.totalRequests shouldBe 1L
    tracker.totalTokens shouldBe 150L
    tracker.totalCost shouldBe BigDecimal.decimal(0.01)
  }
}
