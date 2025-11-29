package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry, ProviderConfig }
import org.llm4s.rag.evaluation.metrics._
import org.llm4s.types.Result
import scala.util.Try

/**
 * Factory for creating RAGAS evaluators and individual metrics.
 *
 * Provides convenient methods to create evaluators from environment configuration
 * or with specific settings.
 *
 * @example
 * {{{{
 * // Create from environment
 * val evaluator = RAGASFactory.fromConfigs(providerCfg, embeddingCfg)
 *
 * // Create with specific metrics
 * val basicEvaluator = RAGASFactory.withMetrics(
 *   llmClient, embeddingClient, embeddingConfig,
 *   Set("faithfulness", "answer_relevancy")
 * )
 *
 * // Create individual metrics
 * val faithfulness = RAGASFactory.faithfulness(llmClient)
 * }}}}
 */
object RAGASFactory {

  /**
   * Create evaluator with all default metrics from explicit configurations.
   */
  def fromConfigs(
    providerCfg: ProviderConfig,
    embedding: (String, org.llm4s.llmconnect.config.EmbeddingProviderConfig)
  ): Result[RAGASEvaluator] =
    for {
      llmClient <- LLMConnect.getClient(providerCfg)
      (providerName, embeddingConfig) = embedding
      embeddingClient <- EmbeddingClient.from(providerName, embeddingConfig)
      dims              = Try(ModelDimensionRegistry.getDimension(providerName, embeddingConfig.model)).getOrElse(1536)
      embeddingModelCfg = EmbeddingModelConfig(embeddingConfig.model, dims)
    } yield RAGASEvaluator(llmClient, embeddingClient, embeddingModelCfg)

  /**
   * Create evaluator with all default metrics.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @return A configured evaluator
   */
  def create(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig
  ): RAGASEvaluator = RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig)

  /**
   * Create evaluator with specific metrics only.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @param metricNames Names of metrics to enable (faithfulness, answer_relevancy, context_precision, context_recall)
   * @return A configured evaluator with only specified metrics
   */
  def withMetrics(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig,
    metricNames: Set[String]
  ): RAGASEvaluator = {
    val allMetrics: Map[String, RAGASMetric] = Map(
      "faithfulness"      -> Faithfulness(llmClient),
      "answer_relevancy"  -> AnswerRelevancy(llmClient, embeddingClient, embeddingModelConfig),
      "context_precision" -> ContextPrecision(llmClient),
      "context_recall"    -> ContextRecall(llmClient)
    )

    val selectedMetrics = metricNames.flatMap(name => allMetrics.get(name)).toSeq
    RAGASEvaluator(llmClient, embeddingClient, embeddingModelConfig, selectedMetrics)
  }

  /**
   * Create a basic evaluator with only Faithfulness and Answer Relevancy metrics.
   * These metrics don't require ground truth, making them suitable for production evaluation.
   *
   * @param llmClient LLM client for semantic evaluation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @return A basic evaluator without ground truth requirements
   */
  def basic(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig
  ): RAGASEvaluator = RAGASEvaluator.basic(llmClient, embeddingClient, embeddingModelConfig)

  /**
   * Create a basic evaluator from explicit configurations.
   */
  def basicFromConfigs(
    providerCfg: ProviderConfig,
    embedding: (String, org.llm4s.llmconnect.config.EmbeddingProviderConfig)
  ): Result[RAGASEvaluator] =
    for {
      llmClient <- LLMConnect.getClient(providerCfg)
      (providerName, embeddingConfig) = embedding
      embeddingClient <- EmbeddingClient.from(providerName, embeddingConfig)
      dims              = Try(ModelDimensionRegistry.getDimension(providerName, embeddingConfig.model)).getOrElse(1536)
      embeddingModelCfg = EmbeddingModelConfig(embeddingConfig.model, dims)
    } yield RAGASEvaluator.basic(llmClient, embeddingClient, embeddingModelCfg)

  // Individual metric factories

  /**
   * Create a Faithfulness metric.
   *
   * @param llmClient LLM client for claim extraction and verification
   * @param batchSize Number of claims to verify per LLM call
   * @return A configured Faithfulness metric
   */
  def faithfulness(llmClient: LLMClient, batchSize: Int = Faithfulness.DEFAULT_BATCH_SIZE): Faithfulness =
    Faithfulness(llmClient, batchSize)

  /**
   * Create an Answer Relevancy metric.
   *
   * @param llmClient LLM client for question generation
   * @param embeddingClient Embedding client for similarity calculations
   * @param embeddingModelConfig Configuration for the embedding model
   * @param numGeneratedQuestions Number of questions to generate
   * @return A configured Answer Relevancy metric
   */
  def answerRelevancy(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig,
    numGeneratedQuestions: Int = AnswerRelevancy.DEFAULT_NUM_QUESTIONS
  ): AnswerRelevancy = AnswerRelevancy(llmClient, embeddingClient, embeddingModelConfig, numGeneratedQuestions)

  /**
   * Create a Context Precision metric.
   *
   * @param llmClient LLM client for relevance assessment
   * @return A configured Context Precision metric
   */
  def contextPrecision(llmClient: LLMClient): ContextPrecision =
    ContextPrecision(llmClient)

  /**
   * Create a Context Recall metric.
   *
   * @param llmClient LLM client for fact extraction and attribution
   * @return A configured Context Recall metric
   */
  def contextRecall(llmClient: LLMClient): ContextRecall =
    ContextRecall(llmClient)

  /**
   * Available metric names.
   */
  val availableMetrics: Set[String] = Set(
    RAGASEvaluator.FAITHFULNESS,
    RAGASEvaluator.ANSWER_RELEVANCY,
    RAGASEvaluator.CONTEXT_PRECISION,
    RAGASEvaluator.CONTEXT_RECALL
  )

  /**
   * Metrics that require ground truth.
   */
  val metricsRequiringGroundTruth: Set[String] = Set(
    RAGASEvaluator.CONTEXT_PRECISION,
    RAGASEvaluator.CONTEXT_RECALL
  )

  /**
   * Metrics that work without ground truth.
   */
  val metricsWithoutGroundTruth: Set[String] = Set(
    RAGASEvaluator.FAITHFULNESS,
    RAGASEvaluator.ANSWER_RELEVANCY
  )
}
