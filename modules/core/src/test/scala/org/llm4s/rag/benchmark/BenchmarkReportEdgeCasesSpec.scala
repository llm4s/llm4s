package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.evaluation.EvalSummary

import java.nio.file.Files

class BenchmarkReportEdgeCasesSpec extends AnyFlatSpec with Matchers {

  private val testSuite = BenchmarkSuite.quickSuite("test.json")

  // =========================================================================
  // Console report edge cases
  // =========================================================================

  "BenchmarkReport.console" should "handle all-failed results" in {
    val results = BenchmarkResults(
      suite = testSuite,
      results = Seq(
        ExperimentResult.failed(RAGExperimentConfig("f1"), "Error 1"),
        ExperimentResult.failed(RAGExperimentConfig("f2"), "Error 2")
      ),
      startTime = 1000L,
      endTime = 2000L
    )

    val report = BenchmarkReport.console(results)
    report should include("0 passed, 2 failed")
    report should include("FAILED EXPERIMENTS")
    report should include("Error 1")
    report should include("Error 2")
    (report should not).include("RANKINGS")
    (report should not).include("WINNER")
  }

  it should "handle empty results" in {
    val results = BenchmarkResults(
      suite = testSuite,
      results = Seq.empty,
      startTime = 1000L,
      endTime = 1000L
    )

    val report = BenchmarkReport.console(results)
    report should include("0 (0 passed, 0 failed)")
  }

  it should "handle experiment with missing metric scores" in {
    val summary = EvalSummary(Seq.empty, Map.empty, 0.5, 1)
    val result  = ExperimentResult(RAGExperimentConfig("no-metrics"), Some(summary))
    val results = BenchmarkResults(
      suite = testSuite,
      results = Seq(result),
      startTime = 1000L,
      endTime = 2000L
    )

    val report = BenchmarkReport.console(results)
    report should include("no-metrics")
    report should include("N/A")
  }

  // =========================================================================
  // JSON report edge cases
  // =========================================================================

  "BenchmarkReport.json" should "handle failed experiments with null metrics" in {
    val results = BenchmarkResults(
      suite = testSuite,
      results = Seq(ExperimentResult.failed(RAGExperimentConfig("failed"), "Test error")),
      startTime = 1000L,
      endTime = 2000L
    )

    val json   = BenchmarkReport.json(results)
    val parsed = ujson.read(json)

    parsed("experiments")(0)("success").bool shouldBe false
    parsed("experiments")(0)("error").str shouldBe "Test error"
    parsed("summary")("winner").isNull shouldBe true
    parsed("summary")("winnerScore").isNull shouldBe true
    parsed("summary")("failureCount").num shouldBe 1
  }

  it should "include timings in experiment details" in {
    val result = ExperimentResult(
      RAGExperimentConfig("timed"),
      Some(EvalSummary(Seq.empty, Map.empty, 0.5, 1)),
      timings = Seq(TimingInfo("indexing", 500, 10), TimingInfo("search", 200, 5))
    )
    val results = BenchmarkResults(suite = testSuite, results = Seq(result), startTime = 0L, endTime = 1000L)

    val json   = BenchmarkReport.json(results)
    val parsed = ujson.read(json)

    val timings = parsed("experiments")(0)("timings").arr
    timings should have size 2
    timings(0)("phase").str shouldBe "indexing"
    timings(0)("durationMs").str shouldBe "500"
    timings(0)("itemCount").num.toInt shouldBe 10
  }

  // =========================================================================
  // Markdown report edge cases
  // =========================================================================

  "BenchmarkReport.markdown" should "handle all-failed results without rankings" in {
    val results = BenchmarkResults(
      suite = testSuite,
      results = Seq(ExperimentResult.failed(RAGExperimentConfig("f1"), "err")),
      startTime = 1000L,
      endTime = 2000L
    )

    val md = BenchmarkReport.markdown(results)
    md should include("# Benchmark Results")
    md should include("Failed | 1")
    (md should not).include("## Rankings")
  }

  it should "show metrics as dashes when not available" in {
    val summary = EvalSummary(Seq.empty, Map("faithfulness" -> 0.9), 0.9, 1)
    val result  = ExperimentResult(RAGExperimentConfig("partial"), Some(summary))
    val results = BenchmarkResults(suite = testSuite, results = Seq(result), startTime = 0L, endTime = 1000L)

    val md = BenchmarkReport.markdown(results)
    md should include("0.900")
    md should include("-") // missing metrics show as dash
  }

  // =========================================================================
  // Report saving
  // =========================================================================

  "BenchmarkReport.save" should "save console format to file" in {
    val results  = createSimpleResults()
    val tempFile = Files.createTempFile("report-test", ".txt")
    try {
      val saveResult = BenchmarkReport.save(results, tempFile.toString, ReportFormat.Console)
      saveResult.isRight shouldBe true
      val content = new String(Files.readAllBytes(tempFile))
      content should include("BENCHMARK RESULTS")
    } finally Files.deleteIfExists(tempFile)
  }

  it should "save JSON format to file" in {
    val results  = createSimpleResults()
    val tempFile = Files.createTempFile("report-test", ".json")
    try {
      val saveResult = BenchmarkReport.save(results, tempFile.toString, ReportFormat.Json)
      saveResult.isRight shouldBe true
      val content = new String(Files.readAllBytes(tempFile))
      ujson.read(content) // should parse without error
    } finally Files.deleteIfExists(tempFile)
  }

  it should "save Markdown format to file" in {
    val results  = createSimpleResults()
    val tempFile = Files.createTempFile("report-test", ".md")
    try {
      val saveResult = BenchmarkReport.save(results, tempFile.toString, ReportFormat.Markdown)
      saveResult.isRight shouldBe true
      val content = new String(Files.readAllBytes(tempFile))
      content should include("# Benchmark Results")
    } finally Files.deleteIfExists(tempFile)
  }

  it should "create parent directories if needed" in {
    val tempDir = Files.createTempDirectory("report-test-dir")
    val path    = tempDir.resolve("sub/dir/report.json").toString
    try {
      val results    = createSimpleResults()
      val saveResult = BenchmarkReport.save(results, path, ReportFormat.Json)
      saveResult.isRight shouldBe true
    } finally {
      // cleanup
      Files.deleteIfExists(java.nio.file.Paths.get(path))
      Files.deleteIfExists(tempDir.resolve("sub/dir"))
      Files.deleteIfExists(tempDir.resolve("sub"))
      Files.deleteIfExists(tempDir)
    }
  }

  // =========================================================================
  // Comparison report edge cases
  // =========================================================================

  "BenchmarkReport.comparison" should "show regression details" in {
    val baseline   = createResult("baseline", 0.9, Map("faithfulness" -> 0.95))
    val comparison = createResult("worse", 0.7, Map("faithfulness" -> 0.65))
    val comp       = ExperimentComparison(baseline, comparison)

    val report = BenchmarkReport.comparison(comp)
    report should include("regression")
    report should include("-0.2000")
  }

  it should "show per-metric differences" in {
    val baseline   = createResult("base", 0.8, Map("faithfulness" -> 0.9, "answer_relevancy" -> 0.7))
    val comparison = createResult("comp", 0.85, Map("faithfulness" -> 0.8, "answer_relevancy" -> 0.9))
    val comp       = ExperimentComparison(baseline, comparison)

    val report = BenchmarkReport.comparison(comp)
    report should include("Per-Metric Differences")
    report should include("faithfulness")
    report should include("answer_relevancy")
  }

  private def createResult(name: String, score: Double, metrics: Map[String, Double]): ExperimentResult = {
    val summary = EvalSummary(Seq.empty, metrics, score, 10)
    ExperimentResult(RAGExperimentConfig(name), Some(summary))
  }

  private def createSimpleResults(): BenchmarkResults = {
    val summary = EvalSummary(Seq.empty, Map("faithfulness" -> 0.9), 0.9, 1)
    BenchmarkResults(
      suite = testSuite,
      results = Seq(ExperimentResult(RAGExperimentConfig("test-exp"), Some(summary))),
      startTime = 1000L,
      endTime = 2000L
    )
  }
}
