package org.llm4s.rag.evaluation

import org.llm4s.types.Result

/**
 * Base trait for RAGAS evaluation metrics.
 *
 * Each metric evaluates a specific aspect of RAG quality and returns
 * a score between 0.0 (worst) and 1.0 (best).
 *
 * Implementations should:
 * - Use LLM calls for semantic evaluation (faithfulness, relevancy)
 * - Use embeddings for similarity calculations (answer relevancy)
 * - Return detailed breakdowns in the MetricResult.details map
 *
 * @example
 * {{{
 * val faithfulness = new Faithfulness(llmClient)
 * val result = faithfulness.evaluate(sample)
 * result match {
 *   case Right(r) => println(s"Faithfulness: $${r.score}")
 *   case Left(e) => println(s"Error: $${e.message}")
 * }
 * }}}
 */
trait RAGASMetric {

  /**
   * Unique name of this metric (e.g., "faithfulness", "answer_relevancy").
   * Used as an identifier in results and configuration.
   */
  def name: String

  /**
   * Human-readable description of what this metric measures.
   */
  def description: String

  /**
   * Which inputs this metric requires from an EvalSample.
   * Used to skip metrics when required inputs are missing.
   */
  def requiredInputs: Set[RequiredInput]

  /**
   * Evaluate a single sample.
   *
   * @param sample The evaluation sample containing question, answer, contexts
   * @return Score between 0.0 and 1.0, with optional details
   */
  def evaluate(sample: EvalSample): Result[MetricResult]

  /**
   * Evaluate multiple samples.
   *
   * Default implementation evaluates sequentially.
   * Override for batch optimizations (e.g., batched LLM calls).
   *
   * @param samples The evaluation samples
   * @return Results for each sample in order
   */
  def evaluateBatch(samples: Seq[EvalSample]): Result[Seq[MetricResult]] = {
    val results = samples.map(evaluate)

    // Collect all results, fail if any failed
    val successes = results.collect { case Right(r) => r }
    val failures  = results.collect { case Left(e) => e }

    if (failures.nonEmpty) {
      Left(failures.head) // Return first error
    } else {
      Right(successes)
    }
  }

  /**
   * Check if this metric can be evaluated for a given sample.
   */
  def canEvaluate(sample: EvalSample): Boolean = {
    val available: Set[RequiredInput] =
      Set(RequiredInput.Question, RequiredInput.Answer, RequiredInput.Contexts) ++
        sample.groundTruth.map(_ => RequiredInput.GroundTruth).toSet

    def implies(a: Boolean, b: => Boolean): Boolean = !a || b

    requiredInputs.subsetOf(available) &&
    implies(requiredInputs.contains(RequiredInput.Question), sample.question.nonEmpty) &&
    implies(requiredInputs.contains(RequiredInput.Answer), sample.answer.nonEmpty) &&
    implies(requiredInputs.contains(RequiredInput.Contexts), sample.contexts.nonEmpty) &&
    implies(requiredInputs.contains(RequiredInput.GroundTruth), sample.groundTruth.isDefined)
  }
}

/**
 * Enumeration of possible required inputs for RAGAS metrics.
 */
sealed trait RequiredInput

object RequiredInput {

  /**
   * The user's query/question.
   */
  case object Question extends RequiredInput

  /**
   * The generated answer from the RAG system.
   */
  case object Answer extends RequiredInput

  /**
   * The retrieved context documents.
   */
  case object Contexts extends RequiredInput

  /**
   * Ground truth answer (for precision/recall metrics).
   */
  case object GroundTruth extends RequiredInput

  /**
   * All possible inputs.
   */
  val all: Set[RequiredInput] = Set(Question, Answer, Contexts, GroundTruth)
}
