package org.llm4s.metrics

import org.llm4s.llmconnect.model.TokenUsage
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class CostTrackerSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  behavior.of("CostTracker")

  it should "accumulate a single record" in {
    val tracker = CostTracker.create()

    tracker.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    tracker.addTokens("openai", "gpt-4", inputTokens = 100, outputTokens = 50)
    tracker.recordCost("openai", "gpt-4", costUsd = 0.0123)

    tracker.totalRequests shouldBe 1
    tracker.totalTokens shouldBe 150
    tracker.totalCost shouldBe 0.0123 +- 1e-12

    val byModel = tracker.byModel
    byModel.keySet shouldBe Set("gpt-4")

    val s = byModel("gpt-4")
    s.requestCount shouldBe 1
    s.inputTokens shouldBe 100
    s.outputTokens shouldBe 50
    s.totalCostUsd shouldBe 0.0123 +- 1e-12
  }

  it should "aggregate multiple models independently" in {
    val tracker = CostTracker.create()

    tracker.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    tracker.addTokens("openai", "gpt-4", inputTokens = 100, outputTokens = 50)
    tracker.recordCost("openai", "gpt-4", costUsd = 0.01)

    tracker.observeRequest("anthropic", "claude", Outcome.Success, 20.millis)
    tracker.addTokens("anthropic", "claude", inputTokens = 200, outputTokens = 25)
    tracker.recordCost("anthropic", "claude", costUsd = 0.02)

    tracker.totalRequests shouldBe 2
    tracker.totalTokens shouldBe (150 + 225)
    tracker.totalCost shouldBe 0.03 +- 1e-12

    tracker.byModel("gpt-4").totalCostUsd shouldBe 0.01 +- 1e-12
    tracker.byModel("claude").totalCostUsd shouldBe 0.02 +- 1e-12
  }

  it should "reset all counters" in {
    val tracker = CostTracker.create()

    tracker.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    tracker.addTokens("openai", "gpt-4", inputTokens = 100, outputTokens = 50)
    tracker.recordCost("openai", "gpt-4", costUsd = 0.0123)

    tracker.reset()

    tracker.totalRequests shouldBe 0
    tracker.totalTokens shouldBe 0
    tracker.totalCost shouldBe 0.0 +- 1e-12
    tracker.byModel shouldBe empty
  }

  it should "support record with None cost (tokens and requests increment; cost does not)" in {
    val tracker = CostTracker.create()

    val usage = TokenUsage(
      promptTokens = 10,
      completionTokens = 5,
      totalTokens = 15
    )

    tracker.record(model = "gpt-4", usage = usage, cost = None)

    tracker.totalRequests shouldBe 1
    tracker.totalTokens shouldBe 15
    tracker.totalCost shouldBe 0.0 +- 1e-12

    val s = tracker.byModel("gpt-4")
    s.requestCount shouldBe 1
    s.inputTokens shouldBe 10
    s.outputTokens shouldBe 5
    s.totalCostUsd shouldBe 0.0 +- 1e-12
  }

  it should "provide a stable empty summary after reset" in {
    val tracker = CostTracker.create()

    tracker.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    tracker.addTokens("openai", "gpt-4", inputTokens = 100, outputTokens = 50)
    tracker.recordCost("openai", "gpt-4", costUsd = 0.0123)

    tracker.reset()

    tracker.summary shouldBe "totalCostUsd=0.0 totalTokens=0 totalRequests=0"
  }

  it should "include per-model data in non-empty summary" in {
    val tracker = CostTracker.create()

    val usage = TokenUsage(
      promptTokens = 2,
      completionTokens = 3,
      totalTokens = 5
    )

    tracker.record(model = "gpt-4", usage = usage, cost = Some(0.0001))

    val s = tracker.summary
    s should include("totalCostUsd=")
    s should include("totalTokens=")
    s should include("totalRequests=")
    s should include("gpt-4")
    s should include("requests=1")
    s should include("inputTokens=2")
    s should include("outputTokens=3")
    s should include("costUsd=")
  }

  it should "compose and forward calls to all collectors" in {
    val tracker = CostTracker.create()
    val mock    = new MockMetricsCollector()

    val composed = MetricsCollector.compose(tracker, mock)

    composed.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    composed.addTokens("openai", "gpt-4", inputTokens = 100, outputTokens = 50)
    composed.recordCost("openai", "gpt-4", costUsd = 0.0123)

    tracker.totalRequests shouldBe 1
    tracker.totalTokens shouldBe 150
    tracker.totalCost shouldBe 0.0123 +- 1e-12

    mock.totalRequests shouldBe 1
    mock.totalTokenCalls shouldBe 1
    mock.totalCostCalls shouldBe 1
  }

  it should "compose with empty input and return noop" in {
    val composed = MetricsCollector.compose()
    composed eq MetricsCollector.noop shouldBe true
  }

  it should "compose and forward calls to three collectors" in {
    val a = new MockMetricsCollector()
    val b = new MockMetricsCollector()
    val c = new MockMetricsCollector()

    val composed = MetricsCollector.compose(a, b, c)

    composed.observeRequest("openai", "gpt-4", Outcome.Success, 10.millis)
    composed.addTokens("openai", "gpt-4", inputTokens = 1, outputTokens = 2)
    composed.recordCost("openai", "gpt-4", costUsd = 0.01)

    a.totalRequests shouldBe 1
    b.totalRequests shouldBe 1
    c.totalRequests shouldBe 1

    a.totalTokenCalls shouldBe 1
    b.totalTokenCalls shouldBe 1
    c.totalTokenCalls shouldBe 1

    a.totalCostCalls shouldBe 1
    b.totalCostCalls shouldBe 1
    c.totalCostCalls shouldBe 1
  }

  it should "have a noop implementation that stays at zero" in {
    val tracker = CostTracker.noop

    val usage = TokenUsage(
      promptTokens = 10,
      completionTokens = 5,
      totalTokens = 15
    )

    noException should be thrownBy tracker.record("gpt-4", usage, Some(0.1))

    tracker.totalRequests shouldBe 0
    tracker.totalTokens shouldBe 0
    tracker.totalCost shouldBe 0.0 +- 1e-12
    tracker.byModel shouldBe empty
    tracker.summary shouldBe "totalCostUsd=0.0 totalTokens=0 totalRequests=0"
  }

  it should "clamp totalTokens and totalRequests to Int.MaxValue" in {
    val tracker = CostTracker.create()

    val impl = tracker match {
      case t: InMemoryCostTracker => t
      case _                      => fail("Expected InMemoryCostTracker")
    }

    def addToLongAdder(fieldName: String, value: Long): Unit = {
      val f = classOf[InMemoryCostTracker].getDeclaredField(fieldName)
      f.setAccessible(true)
      val adder = f.get(impl).asInstanceOf[java.util.concurrent.atomic.LongAdder]
      adder.add(value)
    }

    addToLongAdder("totalInputTokens", Int.MaxValue.toLong)
    addToLongAdder("totalOutputTokens", 1L)
    addToLongAdder("totalRequestCount", Int.MaxValue.toLong + 1L)

    tracker.totalTokens shouldBe Int.MaxValue
    tracker.totalRequests shouldBe Int.MaxValue
  }

  it should "handle basic concurrent updates" in {
    val tracker = CostTracker.create()

    val n = 200

    val futures = (1 to n).map { _ =>
      Future {
        tracker.observeRequest("openai", "gpt-4", Outcome.Success, 1.millis)
        tracker.addTokens("openai", "gpt-4", inputTokens = 2, outputTokens = 3)
        tracker.recordCost("openai", "gpt-4", costUsd = 0.0001)
      }
    }

    val all: Future[Seq[Unit]] = Future.sequence(futures)
    whenReady(all) { _ =>
      tracker.totalRequests shouldBe n
      tracker.totalTokens shouldBe n * 5
      tracker.totalCost shouldBe (n * 0.0001) +- 1e-8

      val model = tracker.byModel("gpt-4")
      model.requestCount shouldBe n
      model.inputTokens shouldBe n.toLong * 2
      model.outputTokens shouldBe n.toLong * 3
      model.totalCostUsd shouldBe (n * 0.0001) +- 1e-8
    }
  }
}
