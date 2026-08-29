package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.evaluation.EvalSummary

class BenchmarkResultEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // TimingInfo edge cases
  // =========================================================================

  "TimingInfo" should "handle zero duration" in {
    val info = TimingInfo("instant", 0)
    info.formatted shouldBe "0ms"
    info.durationSeconds shouldBe 0.0
  }

  it should "format exactly 1000ms as seconds" in {
    val info = TimingInfo("boundary", 1000)
    info.formatted should include("1.00s")
  }

  it should "format 999ms as milliseconds" in {
    val info = TimingInfo("just-under", 999)
    info.formatted shouldBe "999ms"
  }

  it should "include per-item average in formatted output for seconds" in {
    val info = TimingInfo("slow", 5000, 10)
    info.formatted should include("500.0ms/item")
    info.formatted should include("5.00s")
  }

  it should "handle large durations" in {
    val info = TimingInfo("long", 3600000) // 1 hour
    info.durationSeconds shouldBe 3600.0
    info.formatted should include("3600.00s")
  }

  // =========================================================================
  // ExperimentResult edge cases
  // =========================================================================

  "ExperimentResult" should "return None for metricScore when evalSummary is None" in {
    val result = ExperimentResult.failed(RAGExperimentConfig("test"), "error")
    result.metricScore("faithfulness") shouldBe None
    result.faithfulness shouldBe None
    result.answerRelevancy shouldBe None
    result.contextPrecision shouldBe None
    result.contextRecall shouldBe None
  }

  it should "return None for metricScore when metric is missing from averages" in {
    val summary = EvalSummary(Seq.empty, Map("faithfulness" -> 0.9), 0.9, 1)
    val result  = ExperimentResult(RAGExperimentConfig("test"), Some(summary))
    result.faithfulness shouldBe Some(0.9)
    result.answerRelevancy shouldBe None
  }

  it should "return 0.0 total time when no timings" in {
    val result = ExperimentResult(RAGExperimentConfig("test"), None)
    result.totalTimeMs shouldBe 0
    result.totalTimeSeconds shouldBe 0.0
  }

  it should "return None for getTiming with unrecognized phase" in {
    val result = ExperimentResult(
      RAGExperimentConfig("test"),
      None,
      timings = Seq(TimingInfo("indexing", 100))
    )
    result.getTiming("nonexistent") shouldBe None
    result.searchTime shouldBe None
    result.evaluationTime shouldBe None
  }

  it should "not be successful if evalSummary is None even without error" in {
    val result = ExperimentResult(RAGExperimentConfig("test"), None)
    result.success shouldBe false
  }

  it should "not be successful if both error and evalSummary are present" in {
    val summary = EvalSummary(Seq.empty, Map.empty, 0.5, 1)
    val result  = ExperimentResult(RAGExperimentConfig("test"), Some(summary), error = Some("partial failure"))
    result.success shouldBe false
  }

  // =========================================================================
  // BenchmarkResults edge cases
  // =========================================================================

  "BenchmarkResults" should "handle empty results" in {
    val suite   = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults.empty(suite)

    results.successCount shouldBe 0
    results.failureCount shouldBe 0
    results.rankings shouldBe empty
    results.winner shouldBe None
    results.averageScores shouldBe empty
    results.metricTable shouldBe empty
  }

  it should "handle all-failed results" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        ExperimentResult.failed(RAGExperimentConfig("f1"), "err1"),
        ExperimentResult.failed(RAGExperimentConfig("f2"), "err2")
      )
    )

    results.successCount shouldBe 0
    results.failureCount shouldBe 2
    results.rankings shouldBe empty
    results.winner shouldBe None
  }

  it should "calculate duration correctly" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq.empty,
      startTime = 1000L,
      endTime = 6000L
    )

    results.totalDurationMs shouldBe 5000
    results.totalDurationSeconds shouldBe 5.0
  }

  it should "return None for compare when one experiment is failed" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResult("good", 0.8),
        ExperimentResult.failed(RAGExperimentConfig("bad"), "error")
      )
    )

    results.compare("good", "bad") shouldBe None
  }

  it should "return None for compare when both experiments are failed" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        ExperimentResult.failed(RAGExperimentConfig("bad1"), "error1"),
        ExperimentResult.failed(RAGExperimentConfig("bad2"), "error2")
      )
    )

    results.compare("bad1", "bad2") shouldBe None
  }

  it should "get result by experiment name" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(createResult("exp1", 0.8), createResult("exp2", 0.7))
    )

    results.getResult("exp1").map(_.config.name) shouldBe Some("exp1")
    results.getResult("nonexistent") shouldBe None
  }

  it should "add results using companion object method" in {
    val suite   = BenchmarkSuite.quickSuite("test.json")
    val empty   = BenchmarkResults.empty(suite)
    val result  = createResult("new", 0.9)
    val updated = BenchmarkResults.add(empty, result)

    updated.results should have size 1
    updated.results.head.config.name shouldBe "new"
  }

  it should "build metric table from successful results" in {
    val suite = BenchmarkSuite.quickSuite("test.json")
    val results = BenchmarkResults(
      suite = suite,
      results = Seq(
        createResultWithMetrics("exp1", 0.8, Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.7)),
        ExperimentResult.failed(RAGExperimentConfig("failed"), "error")
      )
    )

    val table = results.metricTable
    table should have size 1
    table("exp1")("faithfulness") shouldBe 0.9
    table("exp1")("answer_relevancy") shouldBe 0.7
  }

  // =========================================================================
  // ExperimentComparison edge cases
  // =========================================================================

  "ExperimentComparison" should "handle zero baseline score for relativeImprovement" in {
    val baseline   = createResult("baseline", 0.0)
    val comparison = createResult("improved", 0.5)
    val comp       = ExperimentComparison(baseline, comparison)

    comp.relativeImprovement shouldBe 0.0
  }

  it should "compute metric diffs across different metric sets" in {
    val baseline   = createResultWithMetrics("base", 0.7, Map("faithfulness" -> 0.8))
    val comparison = createResultWithMetrics("comp", 0.8, Map("answer_relevancy" -> 0.9))
    val comp       = ExperimentComparison(baseline, comparison)

    val diffs = comp.metricDiffs
    diffs("faithfulness") shouldBe -0.8 +- 0.001
    diffs("answer_relevancy") shouldBe 0.9 +- 0.001
  }

  it should "compute metric diffs with overlapping metrics" in {
    val baseline   = createResultWithMetrics("base", 0.7, Map("faithfulness" -> 0.6, "answer_relevancy" -> 0.8))
    val comparison = createResultWithMetrics("comp", 0.8, Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.7))
    val comp       = ExperimentComparison(baseline, comparison)

    comp.metricDiffs("faithfulness") shouldBe 0.3 +- 0.001
    comp.metricDiffs("answer_relevancy") shouldBe -0.1 +- 0.001
  }

  it should "handle both experiments with no evalSummary" in {
    val baseline   = ExperimentResult.failed(RAGExperimentConfig("b"), "err")
    val comparison = ExperimentResult.failed(RAGExperimentConfig("c"), "err")
    val comp       = ExperimentComparison(baseline, comparison)

    comp.ragasDiff shouldBe 0.0
    comp.metricDiffs shouldBe empty
  }

  it should "include percentage in summary" in {
    val baseline   = createResult("base", 0.5)
    val comparison = createResult("comp", 0.6)
    val comp       = ExperimentComparison(baseline, comparison)

    comp.summary should include("+20.0%")
    comp.summary should include("improvement")
  }

  private def createResult(name: String, score: Double): ExperimentResult = {
    val summary = EvalSummary(Seq.empty, Map.empty, score, 10)
    ExperimentResult.success(RAGExperimentConfig(name), summary)
  }

  private def createResultWithMetrics(
    name: String,
    score: Double,
    metrics: Map[String, Double]
  ): ExperimentResult = {
    val summary = EvalSummary(Seq.empty, metrics, score, 10)
    ExperimentResult.success(RAGExperimentConfig(name), summary)
  }
}
