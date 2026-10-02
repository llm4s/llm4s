package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.evaluation.EvalSummary
import scala.concurrent.duration.*

class BenchmarkResultSpec extends AnyFlatSpec with Matchers {

  "TimingInfo" should "format milliseconds correctly" in {
    TimingInfo("test", 500.millis).formatted shouldBe "500ms"
  }

  it should "format seconds correctly" in {
    val info = TimingInfo("test", 2500.millis)
    info.duration shouldBe 2500.millis
    info.formatted should include("2.50s")
  }

  it should "calculate average per item" in {
    val info = TimingInfo("test", 1000.millis, 10)
    info.avgPerItem shouldBe Some(100.millis)
    info.formatted should include("100.0ms/item")
  }

  it should "return None for avgPerItem when itemCount is 0" in {
    TimingInfo("test", 1000.millis, 0).avgPerItem shouldBe None
  }

  it should "measure execution time" in {
    val (result, timing) = TimingInfo.measure("computation") {
      Thread.sleep(50)
      "done"
    }
    result shouldBe "done"
    timing.phase shouldBe "computation"
    timing.duration should be >= 50.millis
  }

  "ExperimentResult" should "create failed result" in {
    val config = RAGExperimentConfig(name = "failed-test")
    val result = ExperimentResult.failed(config, "Something went wrong")

    result.success shouldBe false
    result.error shouldBe Some("Something went wrong")
    result.ragasScore shouldBe 0.0
  }

  it should "create successful result" in {
    val config = RAGExperimentConfig(name = "success-test")
    val evalSummary = EvalSummary(
      results = Seq.empty,
      averages = Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.85),
      overallRagasScore = 0.875,
      sampleCount = 10
    )

    val result = ExperimentResult.success(
      config = config,
      evalSummary = evalSummary,
      documentCount = 5,
      chunkCount = 25,
      queryCount = 10
    )

    result.success shouldBe true
    result.error shouldBe None
    result.ragasScore shouldBe 0.875
    result.faithfulness shouldBe Some(0.9)
    result.answerRelevancy shouldBe Some(0.85)
    result.documentCount shouldBe 5
    result.chunkCount shouldBe 25
  }

  it should "calculate total time from timings" in {
    val config = RAGExperimentConfig(name = "timing-test")
    val result = ExperimentResult(
      config = config,
      evalSummary = None,
      timings = Seq(
        TimingInfo("indexing", 1000.millis),
        TimingInfo("search", 500.millis),
        TimingInfo("evaluation", 2000.millis)
      )
    )

    result.totalTime shouldBe 3500.millis
    result.indexingTime.map(_.duration) shouldBe Some(1000.millis)
    result.searchTime.map(_.duration) shouldBe Some(500.millis)
    result.evaluationTime.map(_.duration) shouldBe Some(2000.millis)
  }

  "BenchmarkResults" should "calculate rankings" in {
    val suite = BenchmarkSuite.quickSuite("test.json")

    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResult("low", 0.6),
        createResult("high", 0.9),
        createResult("medium", 0.75)
      )
    )

    val rankings = results.rankings
    rankings.map(_.config.name) shouldBe Seq("high", "medium", "low")
    results.winner.map(_.config.name) shouldBe Some("high")
  }

  it should "track success and failure counts" in {
    val suite = BenchmarkSuite.quickSuite("test.json")

    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResult("success1", 0.8),
        ExperimentResult.failed(RAGExperimentConfig("failed1"), "error"),
        createResult("success2", 0.7)
      )
    )

    results.successCount shouldBe 2
    results.failureCount shouldBe 1
    results.successfulResults should have size 2
    results.failedResults should have size 1
  }

  it should "compare two experiments" in {
    val suite = BenchmarkSuite.quickSuite("test.json")

    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResult("baseline", 0.7),
        createResult("improved", 0.85)
      )
    )

    val comparison = results.compare("baseline", "improved")
    comparison shouldBe defined
    comparison.get._1 shouldBe -0.15 +- 0.001
    comparison.get._2 should include("improved is better")
  }

  it should "return None for comparison with missing experiment" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(createResult("only-one", 0.8))
    )

    results.compare("only-one", "nonexistent") shouldBe None
  }

  it should "calculate average scores" in {
    val suite = BenchmarkSuite.quickSuite("test.json")

    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResultWithMetrics("exp1", 0.8, Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.7)),
        createResultWithMetrics("exp2", 0.7, Map("faithfulness" -> 0.7, "answer_relevancy" -> 0.7))
      )
    )

    val avgScores = results.averageScores
    avgScores("faithfulness") shouldBe 0.8 +- 0.001
    avgScores("answer_relevancy") shouldBe 0.7 +- 0.001
  }

  "ExperimentComparison" should "calculate improvement metrics" in {
    val baseline   = createResult("baseline", 0.7)
    val comparison = createResult("improved", 0.77)

    val comp = ExperimentComparison(baseline, comparison)

    comp.ragasDiff shouldBe 0.07 +- 0.001
    comp.relativeImprovement shouldBe 10.0 +- 0.1
    comp.isImprovement shouldBe true
    comp.summary should include("improvement")
  }

  it should "detect regression" in {
    val baseline   = createResult("baseline", 0.8)
    val comparison = createResult("worse", 0.6)

    val comp = ExperimentComparison(baseline, comparison)

    comp.isImprovement shouldBe false
    comp.summary should include("regression")
  }

  private def createResult(name: String, score: Double): ExperimentResult = {
    val config = RAGExperimentConfig(name = name)
    val summary = EvalSummary(
      results = Seq.empty,
      averages = Map.empty,
      overallRagasScore = score,
      sampleCount = 10
    )
    ExperimentResult.success(config, summary)
  }

  private def createResultWithMetrics(
    name: String,
    score: Double,
    metrics: Map[String, Double]
  ): ExperimentResult = {
    val config = RAGExperimentConfig(name = name)
    val summary = EvalSummary(
      results = Seq.empty,
      averages = metrics,
      overallRagasScore = score,
      sampleCount = 10
    )
    ExperimentResult.success(config, summary)
  }
}
