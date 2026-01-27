package org.llm4s.eval

/**
 * Result of evaluating a metric.
 *
 * @param metricName Name of the metric that produced this result
 * @param score Numeric score between 0.0 and 1.0
 * @param passed Whether the score met the metric's threshold
 * @param explanation Human-readable explanation of the evaluation
 */
final case class EvalResult(
  metricName: String,
  score: Double,
  passed: Boolean,
  explanation: String
) {

  def summary: String =
    s"$metricName: ${"%.2f".format(score)} (${if (passed) "PASS" else "FAIL"})"
}

object EvalResult {

  def pass(metricName: String, score: Double, explanation: String): EvalResult =
    EvalResult(metricName, score, passed = true, explanation)

  def fail(metricName: String, score: Double, explanation: String): EvalResult =
    EvalResult(metricName, score, passed = false, explanation)
}
