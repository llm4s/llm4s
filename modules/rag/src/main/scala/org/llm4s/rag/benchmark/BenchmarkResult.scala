package org.llm4s.rag.benchmark

import org.llm4s.rag.evaluation.EvalSummary

/**
 * Timing information for a benchmark phase.
 *
 * @param phase Name of the phase (e.g., "indexing", "search", "evaluation")
 * @param durationMs Duration in milliseconds
 * @param itemCount Number of items processed
 */
final case class TimingInfo(
  phase: String,
  durationMs: Long,
  itemCount: Int = 0
) {

  /** Duration in seconds with 2 decimal places */
  def durationSeconds: Double = durationMs / 1000.0

  /** Average time per item in milliseconds (if itemCount > 0) */
  def avgPerItemMs: Option[Double] =
    if (itemCount > 0) Some(durationMs.toDouble / itemCount) else None

  /** Human-readable duration string */
  def formatted: String = {
    val avgStr = avgPerItemMs.map(avg => f" (${avg}%.1fms/item)").getOrElse("")
    if (durationMs < 1000) s"${durationMs}ms$avgStr"
    else f"${durationSeconds}%.2fs$avgStr"
  }
}

object TimingInfo {

  /** Create timing info with a measured block */
  def measure[A](phase: String, itemCount: Int = 0)(block: => A): (A, TimingInfo) = {
    val start  = System.currentTimeMillis()
    val result = block
    val end    = System.currentTimeMillis()
    (result, TimingInfo(phase, end - start, itemCount))
  }
}

/**
 * Result of a single experiment run.
 *
 * @param config The experiment configuration used
 * @param evalSummary RAGAS evaluation summary with all metric scores
 * @param timings Timing breakdown by phase
 * @param documentCount Number of documents indexed
 * @param chunkCount Number of chunks created
 * @param queryCount Number of queries evaluated
 * @param metadata Additional experiment metadata
 * @param error Optional error if experiment failed
 */
final case class ExperimentResult(
  config: RAGExperimentConfig,
  evalSummary: Option[EvalSummary],
  timings: Seq[TimingInfo] = Seq.empty,
  documentCount: Int = 0,
  chunkCount: Int = 0,
  queryCount: Int = 0,
  metadata: Map[String, String] = Map.empty,
  error: Option[String] = None
) {

  /** Whether this experiment succeeded */
  def success: Boolean = error.isEmpty && evalSummary.isDefined

  /** Overall RAGAS score (0.0 if failed) */
  def ragasScore: Double = evalSummary.map(_.overallRagasScore).getOrElse(0.0)

  /** Get a specific metric average */
  def metricScore(name: String): Option[Double] = evalSummary.flatMap(_.averages.get(name))

  /** Faithfulness score */
  def faithfulness: Option[Double] = metricScore("faithfulness")

  /** Answer relevancy score */
  def answerRelevancy: Option[Double] = metricScore("answer_relevancy")

  /** Context precision score */
  def contextPrecision: Option[Double] = metricScore("context_precision")

  /** Context recall score */
  def contextRecall: Option[Double] = metricScore("context_recall")

  /** Total time across all phases */
  def totalTimeMs: Long = timings.map(_.durationMs).sum

  /** Total time in seconds */
  def totalTimeSeconds: Double = totalTimeMs / 1000.0

  /** Get timing for a specific phase */
  def getTiming(phase: String): Option[TimingInfo] = timings.find(_.phase == phase)

  /** Indexing time */
  def indexingTime: Option[TimingInfo] = getTiming("indexing")

  /** Search/retrieval time */
  def searchTime: Option[TimingInfo] = getTiming("search")

  /** RAGAS evaluation time */
  def evaluationTime: Option[TimingInfo] = getTiming("evaluation")
}

object ExperimentResult {

  /** Create a failed experiment result */
  def failed(config: RAGExperimentConfig, errorMessage: String): ExperimentResult =
    ExperimentResult(config, None, error = Some(errorMessage))

  /** Create a successful experiment result */
  def success(
    config: RAGExperimentConfig,
    evalSummary: EvalSummary,
    timings: Seq[TimingInfo] = Seq.empty,
    documentCount: Int = 0,
    chunkCount: Int = 0,
    queryCount: Int = 0
  ): ExperimentResult = ExperimentResult(
    config = config,
    evalSummary = Some(evalSummary),
    timings = timings,
    documentCount = documentCount,
    chunkCount = chunkCount,
    queryCount = queryCount
  )
}

/**
 * Results from running a complete benchmark suite.
 *
 * @param suite The benchmark suite that was run
 * @param results Results for each experiment
 * @param startTime When the benchmark started
 * @param endTime When the benchmark completed
 */
final case class BenchmarkResults(
  suite: BenchmarkSuite,
  results: Seq[ExperimentResult],
  startTime: Long = System.currentTimeMillis(),
  endTime: Long = System.currentTimeMillis()
) {

  /** Total benchmark duration in milliseconds */
  def totalDurationMs: Long = endTime - startTime

  /** Total benchmark duration in seconds */
  def totalDurationSeconds: Double = totalDurationMs / 1000.0

  /** Number of successful experiments */
  def successCount: Int = results.count(_.success)

  /** Number of failed experiments */
  def failureCount: Int = results.count(!_.success)

  /** All successful results */
  def successfulResults: Seq[ExperimentResult] = results.filter(_.success)

  /** All failed results */
  def failedResults: Seq[ExperimentResult] = results.filterNot(_.success)

  /**
   * Get results ranked by RAGAS score (highest first).
   */
  def rankings: Seq[ExperimentResult] =
    successfulResults.sortBy(-_.ragasScore)

  /**
   * Get the best performing experiment.
   */
  def winner: Option[ExperimentResult] = rankings.headOption

  /**
   * Get result for a specific experiment.
   */
  def getResult(experimentName: String): Option[ExperimentResult] =
    results.find(_.config.name == experimentName)

  /**
   * Compare two experiments by name.
   * Returns (difference in RAGAS score, comparison details)
   */
  def compare(experiment1: String, experiment2: String): Option[(Double, String)] =
    for {
      r1 <- getResult(experiment1)
      r2 <- getResult(experiment2)
      if r1.success && r2.success
    } yield {
      val diff   = r1.ragasScore - r2.ragasScore
      val better = if (diff > 0) experiment1 else experiment2
      val details =
        f"$experiment1: ${r1.ragasScore}%.3f vs $experiment2: ${r2.ragasScore}%.3f ($better is better by ${math.abs(diff)}%.3f)"
      (diff, details)
    }

  /**
   * Get metric comparison table.
   * Returns map of experiment name -> map of metric name -> score
   */
  def metricTable: Map[String, Map[String, Double]] =
    successfulResults.map(r => r.config.name -> r.evalSummary.map(_.averages).getOrElse(Map.empty)).toMap

  /**
   * Get average scores across all experiments for each metric.
   */
  def averageScores: Map[String, Double] = {
    val allMetrics = successfulResults.flatMap(_.evalSummary).flatMap(_.averages)
    allMetrics
      .groupBy(_._1)
      .view
      .mapValues(scores => if (scores.isEmpty) 0.0 else scores.map(_._2).sum / scores.size)
      .toMap
  }
}

object BenchmarkResults {

  /** Create an empty results container */
  def empty(suite: BenchmarkSuite): BenchmarkResults =
    BenchmarkResults(suite, Seq.empty)

  /** Add a result to existing results */
  def add(results: BenchmarkResults, result: ExperimentResult): BenchmarkResults =
    results.copy(results = results.results :+ result)
}

/**
 * Comparison between two experiment results.
 *
 * @param baseline The baseline experiment
 * @param comparison The experiment being compared
 */
final case class ExperimentComparison(
  baseline: ExperimentResult,
  comparison: ExperimentResult
) {

  /** RAGAS score difference (positive = comparison is better) */
  def ragasDiff: Double = comparison.ragasScore - baseline.ragasScore

  /** Relative improvement as percentage */
  def relativeImprovement: Double =
    if (baseline.ragasScore == 0) 0.0
    else (ragasDiff / baseline.ragasScore) * 100

  /** Whether comparison is better than baseline */
  def isImprovement: Boolean = ragasDiff > 0

  /** Per-metric differences */
  def metricDiffs: Map[String, Double] = {
    val baselineMetrics   = baseline.evalSummary.map(_.averages).getOrElse(Map.empty)
    val comparisonMetrics = comparison.evalSummary.map(_.averages).getOrElse(Map.empty)

    (baselineMetrics.keySet ++ comparisonMetrics.keySet).map { metric =>
      metric -> (comparisonMetrics.getOrElse(metric, 0.0) - baselineMetrics.getOrElse(metric, 0.0))
    }.toMap
  }

  /** Summary string */
  def summary: String = {
    val direction = if (isImprovement) "improvement" else "regression"
    f"${comparison.config.name} vs ${baseline.config.name}: ${ragasDiff}%+.3f RAGAS ($direction, ${relativeImprovement}%+.1f%%)"
  }
}
