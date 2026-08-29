package org.llm4s.rag.evaluation

import org.llm4s.error.LLMError

/**
 * Core data types for RAGAS (Retrieval Augmented Generation Assessment) evaluation.
 *
 * RAGAS provides a framework for evaluating RAG pipelines across four dimensions:
 * - Faithfulness: Are answer claims supported by retrieved context?
 * - Answer Relevancy: Does the answer address the question?
 * - Context Precision: Are relevant docs ranked at the top?
 * - Context Recall: Were all relevant docs retrieved?
 */

/**
 * A single evaluation sample containing all inputs needed for RAGAS metrics.
 *
 * @param question The user's query
 * @param answer The generated answer from the RAG system
 * @param contexts The retrieved context documents used to generate the answer
 * @param groundTruth Optional ground truth answer (required for precision/recall metrics)
 * @param metadata Additional metadata for tracking/filtering
 */
final case class EvalSample(
  question: String,
  answer: String,
  contexts: Seq[String],
  groundTruth: Option[String] = None,
  metadata: Map[String, String] = Map.empty
) {
  require(question.nonEmpty, "question must not be empty")
  require(answer.nonEmpty, "answer must not be empty")
}

/**
 * Result of evaluating a single metric.
 *
 * @param metricName Unique identifier of the metric (e.g., "faithfulness")
 * @param score Score between 0.0 (worst) and 1.0 (best)
 * @param details Metric-specific breakdown (e.g., individual claim scores)
 */
final case class MetricResult(
  metricName: String,
  score: Double,
  details: Map[String, Any] = Map.empty
) {
  require(score >= 0.0 && score <= 1.0, s"score must be between 0.0 and 1.0, got $score")
}

/**
 * Complete evaluation result for a single sample.
 *
 * @param sample The evaluated sample
 * @param metrics Results from each evaluated metric
 * @param ragasScore Composite RAGAS score (mean of all metric scores)
 * @param evaluatedAt Timestamp of evaluation
 */
final case class EvalResult(
  sample: EvalSample,
  metrics: Seq[MetricResult],
  ragasScore: Double,
  evaluatedAt: Long = System.currentTimeMillis()
) {

  /**
   * Get a specific metric result by name.
   */
  def getMetric(name: String): Option[MetricResult] =
    metrics.find(_.metricName == name)

  /**
   * Check if a specific metric passed a threshold.
   */
  def metricPassed(name: String, threshold: Double): Boolean =
    getMetric(name).exists(_.score >= threshold)
}

/**
 * Summary of batch evaluation across multiple samples.
 *
 * @param results Individual results for each sample
 * @param averages Average score per metric across all samples
 * @param overallRagasScore Average RAGAS score across all samples
 * @param sampleCount Number of samples evaluated
 */
final case class EvalSummary(
  results: Seq[EvalResult],
  averages: Map[String, Double],
  overallRagasScore: Double,
  sampleCount: Int
) {

  /**
   * Get samples that scored below a threshold.
   */
  def lowScoringSamples(threshold: Double): Seq[EvalResult] =
    results.filter(_.ragasScore < threshold)

  /**
   * Get samples where a specific metric scored below a threshold.
   */
  def lowScoringForMetric(metricName: String, threshold: Double): Seq[EvalResult] =
    results.filter(r => r.getMetric(metricName).exists(_.score < threshold))
}

/**
 * Verification result for a single claim in the Faithfulness metric.
 *
 * @param claim The extracted claim from the answer
 * @param supported Whether the claim is supported by the context
 * @param evidence Optional evidence from context that supports/refutes the claim
 */
final case class ClaimVerification(
  claim: String,
  supported: Boolean,
  evidence: Option[String] = None
)

/**
 * Error type for evaluation failures.
 */
final case class EvaluationError(
  override val code: Option[String],
  override val message: String
) extends LLMError

object EvaluationError {
  def apply(message: String): EvaluationError =
    EvaluationError(code = Some("EVALUATION_ERROR"), message = message)

  def missingInput(inputName: String): EvaluationError =
    EvaluationError(code = Some("MISSING_INPUT"), message = s"Required input missing: $inputName")

  def parseError(details: String): EvaluationError =
    EvaluationError(code = Some("PARSE_ERROR"), message = s"Failed to parse LLM response: $details")
}

/**
 * Configuration options for the RAGAS evaluator.
 *
 * @param parallelEvaluation Whether to evaluate metrics in parallel
 * @param maxConcurrency Maximum concurrent metric evaluations
 * @param timeoutMs Timeout per metric evaluation in milliseconds
 */
final case class EvaluatorOptions(
  parallelEvaluation: Boolean = false, // Sequential by default for predictable behavior
  maxConcurrency: Int = 4,
  timeoutMs: Long = 30000
)
