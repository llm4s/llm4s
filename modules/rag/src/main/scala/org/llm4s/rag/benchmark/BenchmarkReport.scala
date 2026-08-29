package org.llm4s.rag.benchmark

import org.llm4s.rag.evaluation.EvaluationError
import org.llm4s.types.{ Result, TryOps }

import java.nio.file.{ Files, Paths }
import scala.util.Try
import java.time.{ Instant, ZoneId }
import java.time.format.DateTimeFormatter

/**
 * Report generator for benchmark results.
 *
 * Supports multiple output formats:
 * - Console: Formatted text for terminal display
 * - JSON: Machine-readable format for processing
 * - Markdown: Documentation-ready format
 */
object BenchmarkReport {

  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

  /**
   * Generate console-friendly report.
   *
   * @param results Benchmark results
   * @return Formatted string for terminal display
   */
  def console(results: BenchmarkResults): String = {
    val sb = new StringBuilder

    val separator = "=" * 70

    sb.append(s"\n$separator\n")
    sb.append(s"BENCHMARK RESULTS: ${results.suite.name}\n")
    sb.append(s"${results.suite.description}\n")
    sb.append(s"$separator\n\n")

    // Summary
    sb.append(s"Started:     ${formatTime(results.startTime)}\n")
    sb.append(s"Completed:   ${formatTime(results.endTime)}\n")
    sb.append(f"Duration:    ${results.totalDurationSeconds}%.2fs\n")
    sb.append(
      s"Experiments: ${results.results.size} (${results.successCount} passed, ${results.failureCount} failed)\n"
    )
    sb.append("\n")

    // Rankings table
    if (results.successfulResults.nonEmpty) {
      sb.append("RANKINGS BY RAGAS SCORE\n")
      sb.append("-" * 70 + "\n")
      sb.append(f"${"Rank"}%-6s ${"Config"}%-25s ${"RAGAS"}%-10s ${"Faith"}%-10s ${"Relevancy"}%-10s\n")
      sb.append("-" * 70 + "\n")

      results.rankings.zipWithIndex.foreach { case (result, idx) =>
        val rank      = idx + 1
        val name      = result.config.shortName
        val ragas     = f"${result.ragasScore}%.3f"
        val faith     = result.faithfulness.map(s => f"$s%.3f").getOrElse("N/A")
        val relevancy = result.answerRelevancy.map(s => f"$s%.3f").getOrElse("N/A")

        sb.append(f"$rank%-6d $name%-25s $ragas%-10s $faith%-10s $relevancy%-10s\n")
      }
      sb.append("\n")

      // Winner announcement
      results.winner.foreach { winner =>
        sb.append(s"$separator\n")
        sb.append(f"WINNER: ${winner.config.name} (RAGAS: ${winner.ragasScore}%.3f)\n")
        sb.append(s"$separator\n")
      }
    }

    // Failed experiments
    if (results.failedResults.nonEmpty) {
      sb.append("\nFAILED EXPERIMENTS:\n")
      sb.append("-" * 70 + "\n")
      results.failedResults.foreach { result =>
        sb.append(s"  ${result.config.name}: ${result.error.getOrElse("Unknown error")}\n")
      }
    }

    sb.append("\n")
    sb.toString()
  }

  /**
   * Generate detailed console report with per-metric breakdown.
   *
   * @param results Benchmark results
   * @return Detailed formatted string
   */
  def consoleDetailed(results: BenchmarkResults): String = {
    val sb = new StringBuilder

    sb.append(console(results))

    // Detailed metrics for each experiment
    sb.append("\nDETAILED RESULTS\n")
    sb.append("=" * 70 + "\n")

    results.successfulResults.foreach { result =>
      sb.append(s"\n--- ${result.config.name} ---\n")
      sb.append(s"Description: ${result.config.fullDescription}\n")
      sb.append(f"RAGAS Score: ${result.ragasScore}%.4f\n")
      sb.append("\n")

      // Metrics
      sb.append("Metrics:\n")
      result.faithfulness.foreach(s => sb.append(f"  Faithfulness:       $s%.4f\n"))
      result.answerRelevancy.foreach(s => sb.append(f"  Answer Relevancy:   $s%.4f\n"))
      result.contextPrecision.foreach(s => sb.append(f"  Context Precision:  $s%.4f\n"))
      result.contextRecall.foreach(s => sb.append(f"  Context Recall:     $s%.4f\n"))
      sb.append("\n")

      // Timing
      sb.append("Timing:\n")
      result.timings.foreach(t => sb.append(f"  ${t.phase}%-15s ${t.formatted}\n"))
      sb.append(f"  ${"Total"}%-15s ${result.totalTimeSeconds}%.2fs\n")
      sb.append("\n")

      // Stats
      sb.append(s"Documents: ${result.documentCount}\n")
      sb.append(s"Chunks:    ${result.chunkCount}\n")
      sb.append(s"Queries:   ${result.queryCount}\n")
    }

    sb.toString()
  }

  /**
   * Generate JSON report.
   *
   * @param results Benchmark results
   * @param pretty Pretty-print with indentation
   * @return JSON string
   */
  def json(results: BenchmarkResults, pretty: Boolean = true): String = {
    val experiments = results.results.map { result =>
      val metricsObj = ujson.Obj(
        "ragasScore"       -> result.ragasScore,
        "faithfulness"     -> result.faithfulness.map(ujson.Num(_)).getOrElse(ujson.Null),
        "answerRelevancy"  -> result.answerRelevancy.map(ujson.Num(_)).getOrElse(ujson.Null),
        "contextPrecision" -> result.contextPrecision.map(ujson.Num(_)).getOrElse(ujson.Null),
        "contextRecall"    -> result.contextRecall.map(ujson.Num(_)).getOrElse(ujson.Null)
      )

      val configObj = ujson.Obj(
        "name"              -> result.config.name,
        "description"       -> result.config.description,
        "chunkingStrategy"  -> result.config.chunkingStrategy.name,
        "embeddingProvider" -> result.config.embeddingConfig.provider,
        "embeddingModel"    -> result.config.embeddingConfig.model,
        "topK"              -> result.config.topK
      )

      val timingsArr = ujson.Arr(result.timings.map { t =>
        ujson.Obj(
          "phase"      -> t.phase,
          "durationMs" -> t.durationMs,
          "itemCount"  -> t.itemCount
        )
      }: _*)

      ujson.Obj(
        "config"        -> configObj,
        "metrics"       -> metricsObj,
        "timings"       -> timingsArr,
        "documentCount" -> result.documentCount,
        "chunkCount"    -> result.chunkCount,
        "queryCount"    -> result.queryCount,
        "success"       -> result.success,
        "error"         -> result.error.map(ujson.Str(_)).getOrElse(ujson.Null)
      )
    }

    val root = ujson.Obj(
      "suite" -> ujson.Obj(
        "name"        -> results.suite.name,
        "description" -> results.suite.description,
        "datasetPath" -> results.suite.datasetPath
      ),
      "summary" -> ujson.Obj(
        "startTime"       -> results.startTime,
        "endTime"         -> results.endTime,
        "durationMs"      -> results.totalDurationMs,
        "experimentCount" -> results.results.size,
        "successCount"    -> results.successCount,
        "failureCount"    -> results.failureCount,
        "winner"          -> results.winner.map(w => w.config.name).map(ujson.Str(_)).getOrElse(ujson.Null),
        "winnerScore"     -> results.winner.map(_.ragasScore).map(ujson.Num(_)).getOrElse(ujson.Null)
      ),
      "experiments" -> ujson.Arr(experiments: _*)
    )

    if (pretty) ujson.write(root, indent = 2) else ujson.write(root)
  }

  /**
   * Generate Markdown report.
   *
   * @param results Benchmark results
   * @return Markdown string
   */
  def markdown(results: BenchmarkResults): String = {
    val sb = new StringBuilder

    sb.append(s"# Benchmark Results: ${results.suite.name}\n\n")
    sb.append(s"${results.suite.description}\n\n")

    // Summary
    sb.append("## Summary\n\n")
    sb.append(s"| Metric | Value |\n")
    sb.append(s"|--------|-------|\n")
    sb.append(s"| Started | ${formatTime(results.startTime)} |\n")
    sb.append(s"| Duration | ${results.totalDurationSeconds}s |\n")
    sb.append(s"| Experiments | ${results.results.size} |\n")
    sb.append(s"| Passed | ${results.successCount} |\n")
    sb.append(s"| Failed | ${results.failureCount} |\n")
    results.winner.foreach(w => sb.append(f"| **Winner** | **${w.config.name}** (${w.ragasScore}%.3f) |\n"))
    sb.append("\n")

    // Rankings table
    if (results.successfulResults.nonEmpty) {
      sb.append("## Rankings\n\n")
      sb.append(
        "| Rank | Configuration | RAGAS | Faithfulness | Answer Relevancy | Context Precision | Context Recall |\n"
      )
      sb.append(
        "|------|---------------|-------|--------------|------------------|-------------------|----------------|\n"
      )

      results.rankings.zipWithIndex.foreach { case (result, idx) =>
        val rank   = idx + 1
        val name   = result.config.name
        val ragas  = f"${result.ragasScore}%.3f"
        val faith  = result.faithfulness.map(s => f"$s%.3f").getOrElse("-")
        val rel    = result.answerRelevancy.map(s => f"$s%.3f").getOrElse("-")
        val prec   = result.contextPrecision.map(s => f"$s%.3f").getOrElse("-")
        val recall = result.contextRecall.map(s => f"$s%.3f").getOrElse("-")

        sb.append(s"| $rank | $name | $ragas | $faith | $rel | $prec | $recall |\n")
      }
      sb.append("\n")
    }

    // Configuration details
    sb.append("## Configuration Details\n\n")
    results.successfulResults.foreach { result =>
      sb.append(s"### ${result.config.name}\n\n")
      sb.append(s"${result.config.fullDescription}\n\n")
      sb.append(s"- **Chunking**: ${result.config.chunkingStrategy.name}\n")
      sb.append(s"- **Embeddings**: ${result.config.embeddingConfig.provider}/${result.config.embeddingConfig.model}\n")
      sb.append(s"- **Top K**: ${result.config.topK}\n")
      sb.append(s"- **Documents**: ${result.documentCount}\n")
      sb.append(s"- **Chunks**: ${result.chunkCount}\n")
      sb.append(f"- **Total Time**: ${result.totalTimeSeconds}%.2fs\n")
      sb.append("\n")
    }

    sb.toString()
  }

  /**
   * Save report to file.
   *
   * @param results Benchmark results
   * @param path Output file path
   * @param format Output format (console, json, markdown)
   * @return Unit or error
   */
  def save(
    results: BenchmarkResults,
    path: String,
    format: ReportFormat = ReportFormat.Json
  ): Result[Unit] = {
    val content = format match {
      case ReportFormat.Console  => console(results)
      case ReportFormat.Json     => json(results)
      case ReportFormat.Markdown => markdown(results)
    }

    Try {
      val p = Paths.get(path)
      Option(p.getParent).foreach(Files.createDirectories(_))
      Files.write(p, content.getBytes)
      ()
    }.toResult.left.map(e => EvaluationError(s"Failed to save report: ${e.message}"))
  }

  /**
   * Generate comparison report between two results.
   */
  def comparison(comparison: ExperimentComparison): String = {
    val sb = new StringBuilder

    sb.append("\n" + "=" * 50 + "\n")
    sb.append("EXPERIMENT COMPARISON\n")
    sb.append("=" * 50 + "\n\n")

    sb.append(s"Baseline:   ${comparison.baseline.config.name}\n")
    sb.append(s"Comparison: ${comparison.comparison.config.name}\n")
    sb.append("\n")

    sb.append("RAGAS Score:\n")
    sb.append(f"  Baseline:   ${comparison.baseline.ragasScore}%.4f\n")
    sb.append(f"  Comparison: ${comparison.comparison.ragasScore}%.4f\n")
    sb.append(f"  Difference: ${comparison.ragasDiff}%+.4f")
    if (comparison.isImprovement) sb.append(" (improvement)") else sb.append(" (regression)")
    sb.append(f" (${comparison.relativeImprovement}%+.1f%%)\n")
    sb.append("\n")

    sb.append("Per-Metric Differences:\n")
    comparison.metricDiffs.foreach { case (metric, diff) =>
      val sign = if (diff >= 0) "+" else ""
      sb.append(f"  $metric%-20s $sign$diff%.4f\n")
    }

    sb.append("\n" + "=" * 50 + "\n")
    sb.toString()
  }

  private def formatTime(timestamp: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(timestamp))
}

/**
 * Report output format.
 */
sealed trait ReportFormat

object ReportFormat {
  case object Console  extends ReportFormat
  case object Json     extends ReportFormat
  case object Markdown extends ReportFormat
}
