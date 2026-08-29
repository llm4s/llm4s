package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.evaluation.EvalSummary

class BenchmarkReportSpec extends AnyFlatSpec with Matchers {

  val testSuite: BenchmarkSuite = BenchmarkSuite.quickSuite("test.json")

  def createTestResults(): BenchmarkResults =
    BenchmarkResults(
      suite = testSuite,
      results = Seq(
        createResult("experiment-a", 0.85, Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.8)),
        createResult("experiment-b", 0.75, Map("faithfulness" -> 0.7, "answer_relevancy" -> 0.8))
      ),
      startTime = 1000000L,
      endTime = 1005000L
    )

  "BenchmarkReport.console" should "produce readable output" in {
    val report = BenchmarkReport.console(createTestResults())

    report should include("BENCHMARK RESULTS")
    report should include("quick-test")
    report should include("RANKINGS BY RAGAS SCORE")
    report should include("experiment-a")
    report should include("experiment-b")
    report should include("0.85")
    report should include("WINNER")
  }

  it should "show winner announcement" in {
    val report = BenchmarkReport.console(createTestResults())
    report should include("WINNER: experiment-a")
  }

  it should "handle failed experiments" in {
    val resultsWithFailure = BenchmarkResults(
      suite = testSuite,
      results = Seq(
        createResult("success", 0.8, Map.empty),
        ExperimentResult.failed(RAGExperimentConfig("failed"), "Test error message")
      )
    )

    val report = BenchmarkReport.console(resultsWithFailure)
    report should include("FAILED EXPERIMENTS")
    report should include("Test error message")
  }

  "BenchmarkReport.consoleDetailed" should "include timing information" in {
    val resultsWithTimings = BenchmarkResults(
      suite = testSuite,
      results = Seq(
        createResultWithTimings("timed", 0.8)
      )
    )

    val report = BenchmarkReport.consoleDetailed(resultsWithTimings)
    report should include("DETAILED RESULTS")
    report should include("Timing:")
    report should include("indexing")
    report should include("search")
  }

  "BenchmarkReport.json" should "produce valid JSON" in {
    val json = BenchmarkReport.json(createTestResults())

    // Should be parseable
    val parsed = ujson.read(json)

    parsed("suite")("name").str shouldBe "quick-test"
    parsed("experiments").arr should have size 2
    parsed("summary")("successCount").num shouldBe 2
    parsed("summary")("winner").str shouldBe "experiment-a"
  }

  it should "include all experiment details" in {
    val json   = BenchmarkReport.json(createTestResults())
    val parsed = ujson.read(json)

    val exp1 = parsed("experiments")(0)
    exp1("config")("name").str shouldBe "experiment-a"
    exp1("metrics")("ragasScore").num shouldBe 0.85
    exp1("metrics")("faithfulness").num shouldBe 0.9
    exp1("success").bool shouldBe true
  }

  it should "handle compact format" in {
    val compact = BenchmarkReport.json(createTestResults(), pretty = false)
    (compact should not).include("\n  ")
  }

  "BenchmarkReport.markdown" should "produce valid markdown" in {
    val md = BenchmarkReport.markdown(createTestResults())

    md should include("# Benchmark Results")
    md should include("## Summary")
    md should include("## Rankings")
    md should include("| Rank |")
    md should include("| 1 | experiment-a |")
    md should include("## Configuration Details")
  }

  it should "format winner in summary table" in {
    val md = BenchmarkReport.markdown(createTestResults())
    md should include("**Winner**")
    md should include("**experiment-a**")
  }

  "BenchmarkReport.comparison" should "show comparison details" in {
    val baseline   = createResult("baseline", 0.7, Map("faithfulness" -> 0.7))
    val comparison = createResult("improved", 0.8, Map("faithfulness" -> 0.85))
    val comp       = ExperimentComparison(baseline, comparison)

    val report = BenchmarkReport.comparison(comp)

    report should include("EXPERIMENT COMPARISON")
    report should include("baseline")
    report should include("improved")
    report should include("improvement")
    report should include("+0.10")
  }

  private def createResult(
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
    ExperimentResult(
      config = config,
      evalSummary = Some(summary),
      documentCount = 5,
      chunkCount = 25,
      queryCount = 10
    )
  }

  private def createResultWithTimings(name: String, score: Double): ExperimentResult = {
    val config = RAGExperimentConfig(name = name)
    val summary = EvalSummary(
      results = Seq.empty,
      averages = Map("faithfulness" -> 0.9),
      overallRagasScore = score,
      sampleCount = 10
    )
    ExperimentResult(
      config = config,
      evalSummary = Some(summary),
      timings = Seq(
        TimingInfo("indexing", 1000, 5),
        TimingInfo("search", 500, 10),
        TimingInfo("evaluation", 2000, 10)
      ),
      documentCount = 5,
      chunkCount = 25,
      queryCount = 10
    )
  }
}
