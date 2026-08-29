package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.rag.evaluation.metrics._
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

/**
 * Main RAGAS evaluator that orchestrates all metrics.
 *
 * RAGAS (Retrieval Augmented Generation Assessment) evaluates RAG pipelines
 * across four dimensions:
 * - Faithfulness: Are claims in the answer supported by context?
 * - Answer Relevancy: Does the answer address the question?
 * - Context Precision: Are relevant docs ranked at the top?
 * - Context Recall: Were all relevant docs retrieved?
 *
 * The composite RAGAS score is the mean of all evaluated metric scores.
 *
 * @param llmClient LLM client for semantic evaluation (claim verification, relevance)
 * @param embeddingClient Embedding client for similarity calculations
 * @param embeddingModelConfig Configuration for the embedding model
 * @param metrics Custom metrics to use (defaults to all four RAGAS metrics)
 * @param options Evaluation options (parallelism, timeouts)
 * @param tracer Optional tracer for cost tracking
 *
 * @example
 * {{{{
 * val evaluator = RAGASEvaluator(llmClient, embeddingClient, embeddingConfig)
 *
 * val sample = EvalSample(
 *   question = "What is the capital of France?",
 *   answer = "Paris is the capital of France.",
 *   contexts = Seq("Paris is the capital and largest city of France."),
 *   groundTruth = Some("The capital of France is Paris.")
 * )
 *
 * val result = evaluator.evaluate(sample)
 * result match {
 *   case Right(eval) =>
 *     println(s"RAGAS Score: $${eval.ragasScore}")
 *     eval.metrics.foreach { m =>
 *       println(s"  $${m.metricName}: $${m.score}")
 *     }
 *   case Left(error) =>
 *     println(s"Evaluation failed: $${error.message}")
 * }
 * }}}}
 */
class RAGASEvaluator(
  llmClient: LLMClient,
  embeddingClient: EmbeddingClient,
  embeddingModelConfig: EmbeddingModelConfig,
  metrics: Seq[RAGASMetric] = Seq.empty,
  options: EvaluatorOptions = EvaluatorOptions(),
  private val tracer: Option[Tracing] = None
) {

  // Traced embedding client for metrics that use embeddings
  private val tracedEmbeddingClient: EmbeddingClient = tracer match {
    case Some(t) => embeddingClient.withTracing(t).withOperation("evaluation")
    case None    => embeddingClient
  }

  private val defaultMetrics: Seq[RAGASMetric] = Seq(
    Faithfulness(llmClient),
    AnswerRelevancy(llmClient, tracedEmbeddingClient, embeddingModelConfig),
    ContextPrecision(llmClient),
    ContextRecall(llmClient)
  )

  private val activeMetrics: Seq[RAGASMetric] = if (metrics.isEmpty) defaultMetrics else metrics

  /**
   * Get the list of active metrics.
   */
  def getActiveMetrics: Seq[RAGASMetric] = activeMetrics

  /**
   * Evaluate a single sample against all applicable metrics.
   *
   * Only metrics whose required inputs are present in the sample will be evaluated.
   * For example, Context Precision requires ground_truth, so it will be skipped
   * if the sample doesn't have ground_truth.
   *
   * @param sample The evaluation sample
   * @return Evaluation result with all metric scores and composite RAGAS score
   */
  def evaluate(sample: EvalSample): Result[EvalResult] = {
    val startTime         = System.nanoTime()
    val applicableMetrics = activeMetrics.filter(_.canEvaluate(sample))

    if (applicableMetrics.isEmpty) {
      return Left(EvaluationError("No applicable metrics for this sample"))
    }

    val results = applicableMetrics.map(_.evaluate(sample))

    // Collect successes and failures
    val successes = results.collect { case Right(r) => r }
    val failures  = results.collect { case Left(e) => e }

    val evalResult = if (successes.isEmpty && failures.nonEmpty) {
      Left(failures.head)
    } else {
      val ragasScore = if (successes.isEmpty) 0.0 else successes.map(_.score).sum / successes.size

      Right(
        EvalResult(
          sample = sample,
          metrics = successes,
          ragasScore = ragasScore
        )
      )
    }

    // Emit trace event for evaluation completion
    evalResult.foreach { _ =>
      val durationMs = (System.nanoTime() - startTime) / 1_000_000
      tracer.foreach(
        _.traceRAGOperation(
          operation = "evaluate",
          durationMs = durationMs
        )
      )
    }

    evalResult
  }

  /**
   * Evaluate multiple samples.
   *
   * @param samples The evaluation samples
   * @return Summary with individual results and aggregate statistics
   */
  def evaluateBatch(samples: Seq[EvalSample]): Result[EvalSummary] = {
    if (samples.isEmpty) {
      return Right(
        EvalSummary(
          results = Seq.empty,
          averages = Map.empty,
          overallRagasScore = 0.0,
          sampleCount = 0
        )
      )
    }

    val results = samples.map(evaluate)

    // Collect all successes
    val successes = results.collect { case Right(r) => r }
    val failures  = results.collect { case Left(e) => e }

    if (successes.isEmpty && failures.nonEmpty) {
      Left(failures.head)
    } else {
      // Calculate average per metric
      val metricNames = activeMetrics.map(_.name)
      val averages = metricNames.flatMap { metricName =>
        val scores = successes.flatMap(_.getMetric(metricName)).map(_.score)
        if (scores.nonEmpty) {
          Some(metricName -> scores.sum / scores.size)
        } else {
          None
        }
      }.toMap

      val overallScore = if (successes.isEmpty) 0.0 else successes.map(_.ragasScore).sum / successes.size

      Right(
        EvalSummary(
          results = successes,
          averages = averages,
          overallRagasScore = overallScore,
          sampleCount = successes.size
        )
      )
    }
  }

  /**
   * Evaluate from a test dataset.
   *
   * @param dataset The test dataset containing samples
   * @return Summary with individual results and aggregate statistics
   */
  def evaluateDataset(dataset: TestDataset): Result[EvalSummary] =
    evaluateBatch(dataset.samples)

  /**
   * Evaluate a single metric on a sample.
   *
   * Useful for debugging or when only one metric is needed.
   *
   * @param sample The evaluation sample
   * @param metricName The name of the metric to evaluate
   * @return The metric result or an error
   */
  def evaluateMetric(sample: EvalSample, metricName: String): Result[MetricResult] =
    activeMetrics.find(_.name == metricName) match {
      case None =>
        Left(EvaluationError(s"Unknown metric: $metricName. Available: ${activeMetrics.map(_.name).mkString(", ")}"))
      case Some(metric) if !metric.canEvaluate(sample) =>
        Left(EvaluationError(s"Metric '$metricName' cannot be evaluated: missing required inputs"))
      case Some(metric) =>
        metric.evaluate(sample)
    }

  /**
   * Create a new evaluator with only specific metrics enabled.
   *
   * @param metricNames The names of metrics to enable
   * @return A new evaluator with only the specified metrics
   */
  def withMetrics(metricNames: Set[String]): RAGASEvaluator = {
    val selectedMetrics = activeMetrics.filter(m => metricNames.contains(m.name))
    new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, selectedMetrics, options, tracer)
  }

  /**
   * Create a new evaluator with different options.
   *
   * @param newOptions The new evaluation options
   * @return A new evaluator with the specified options
   */
  def withOptions(newOptions: EvaluatorOptions): RAGASEvaluator =
    new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, activeMetrics.toSeq, newOptions, tracer)

  /**
   * Create a new evaluator with tracing enabled.
   *
   * @param newTracer The tracer to use for cost tracking
   * @return A new evaluator with tracing enabled
   */
  def withTracing(newTracer: Tracing): RAGASEvaluator =
    new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, metrics, options, Some(newTracer))
}

object RAGASEvaluator {

  /**
   * Create a new RAGAS evaluator with default metrics.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @return A new evaluator with all four RAGAS metrics enabled
   */
  def apply(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig
  ): RAGASEvaluator = new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig)

  /**
   * Create a new RAGAS evaluator with specific metrics.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @param metrics Custom metrics to use
   * @return A new evaluator with the specified metrics
   */
  def apply(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig,
    metrics: Seq[RAGASMetric]
  ): RAGASEvaluator = new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, metrics)

  /**
   * Create an evaluator with only Faithfulness and Answer Relevancy metrics.
   * These metrics don't require ground truth.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @return A new evaluator with basic metrics only
   */
  def basic(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig
  ): RAGASEvaluator = {
    val metrics = Seq(
      Faithfulness(llmClient),
      AnswerRelevancy(llmClient, embeddingClient, embeddingModelConfig)
    )
    new RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, metrics)
  }

  /**
   * Metric names for reference.
   */
  val FAITHFULNESS: String      = "faithfulness"
  val ANSWER_RELEVANCY: String  = "answer_relevancy"
  val CONTEXT_PRECISION: String = "context_precision"
  val CONTEXT_RECALL: String    = "context_recall"
}
