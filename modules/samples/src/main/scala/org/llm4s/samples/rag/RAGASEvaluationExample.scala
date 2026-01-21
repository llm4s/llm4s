package org.llm4s.samples.rag

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.rag.evaluation._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating RAGAS evaluation of RAG pipeline quality.
 *
 * This sample evaluates a RAG response across four dimensions:
 * - Faithfulness: Are claims in the answer supported by context?
 * - Answer Relevancy: Does the answer address the question?
 * - Context Precision: Are relevant docs ranked at top?
 * - Context Recall: Were all relevant docs retrieved?
 *
 * Run with:
 * {{{
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 * export EMBEDDING_PROVIDER=openai
 * export OPENAI_EMBEDDING_MODEL=text-embedding-3-small
 * sbt "samples/runMain org.llm4s.samples.rag.RAGASEvaluationExample"
 * }}}
 */
object RAGASEvaluationExample extends App {

  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("RAGAS Evaluation Example")
  logger.info("=" * 60)

  // Sample evaluation data - simulating a RAG pipeline output
  val sample = EvalSample(
    question = "What are the main symptoms of type 2 diabetes?",
    answer = "The main symptoms of type 2 diabetes include increased thirst, frequent urination, " +
      "fatigue, blurred vision, and slow healing of cuts and wounds. Some people may also " +
      "experience unexplained weight loss and tingling in hands or feet.",
    contexts = Seq(
      "Type 2 diabetes symptoms often develop slowly over several years. Common symptoms include " +
        "increased thirst (polydipsia), frequent urination (polyuria), and extreme fatigue. " +
        "Many people with type 2 diabetes have no symptoms initially.",
      "Diabetes can cause blurred vision due to high blood sugar levels affecting the lens of the eye. " +
        "Slow-healing sores and frequent infections are also common symptoms. Numbness or tingling " +
        "in the hands and feet (neuropathy) may occur.",
      "Risk factors for type 2 diabetes include being overweight, age over 45, family history, " +
        "and physical inactivity. Certain ethnic groups have higher risk."
    ),
    groundTruth = Some(
      "Type 2 diabetes symptoms include increased thirst, frequent urination, fatigue, blurred vision, " +
        "slow wound healing, numbness in extremities, and unexplained weight loss."
    )
  )

  logger.info("--- Evaluation Sample ---")
  logger.info("Question: {}", sample.question)
  logger.info("Answer: {}...", sample.answer.take(100))
  logger.info("Contexts: {} documents", sample.contexts.size)
  logger.info("Ground Truth: {}", sample.groundTruth.map(_.take(80) + "...").getOrElse("None"))

  // Create evaluator from environment
  val result = for {
    providerCfg     <- Llm4sConfig.provider()
    llmClient       <- LLMConnect.getClient(providerCfg)
    embeddingResult <- Llm4sConfig.embeddings()
    (providerName, embeddingConfig) = embeddingResult
    embeddingClient <- EmbeddingClient.from(providerName, embeddingConfig)
    dims        = ModelDimensionRegistry.getDimension(providerName, embeddingConfig.model)
    modelConfig = EmbeddingModelConfig(embeddingConfig.model, dims)
    evaluator   = RAGASEvaluator(llmClient, embeddingClient, modelConfig)
    _           = logger.info("--- Running RAGAS Evaluation ---")
    _           = logger.info("LLM: {}", llmClient.getClass.getSimpleName)
    _           = logger.info("Embedding model: {} (dims: {})", embeddingConfig.model, dims)
    evalResult <- evaluator.evaluate(sample)
  } yield evalResult

  result match {
    case Right(evalResult) =>
      logger.info("=" * 60)
      logger.info("EVALUATION RESULTS")
      logger.info("=" * 60)

      // Print individual metric scores
      evalResult.metrics.foreach { metric =>
        val bar     = "█" * (metric.score * 20).toInt + "░" * (20 - (metric.score * 20).toInt)
        val percent = f"${metric.score * 100}%.1f%%"
        logger.info(f"${metric.metricName}%-20s [$bar] $percent")

        // Print some details
        metric.details.take(3).foreach { case (key, value) =>
          val valueStr = value match {
            case seq: Seq[_] => s"${seq.size} items"
            case other       => other.toString.take(50)
          }
          logger.info(f"  $key%-18s: $valueStr")
        }
      }

      // Print composite RAGAS score
      logger.info("-" * 60)
      val ragasBar = "█" * (evalResult.ragasScore * 20).toInt + "░" * (20 - (evalResult.ragasScore * 20).toInt)
      logger.info(f"RAGAS SCORE          [$ragasBar] ${evalResult.ragasScore * 100}%.1f%%")
      logger.info("=" * 60)

      // Interpretation
      logger.info("Interpretation:")
      if (evalResult.ragasScore >= 0.8) {
        logger.info("  ✓ Excellent RAG quality - answers are faithful, relevant, and well-supported")
      } else if (evalResult.ragasScore >= 0.6) {
        logger.info("  ~ Good RAG quality - some room for improvement")
      } else {
        logger.info("  ✗ RAG quality needs improvement - check retrieval and generation")
      }

      evalResult.metrics.foreach { m =>
        if (m.score < 0.6) {
          val suggestion = m.metricName match {
            case "faithfulness"      => "Answer contains claims not supported by context (hallucination risk)"
            case "answer_relevancy"  => "Answer doesn't fully address the question"
            case "context_precision" => "Relevant documents are not ranked at top - improve reranking"
            case "context_recall"    => "Missing relevant information - improve retrieval"
            case _                   => "Low score"
          }
          logger.info("  ! {}: {}", m.metricName, suggestion)
        }
      }

    case Left(error) =>
      logger.error("Evaluation failed: {}", error.message)
      error.code.foreach(c => logger.error("   Error code: {}", c))
  }

}
