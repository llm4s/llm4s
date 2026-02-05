package org.llm4s.eval

import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

/**
 * Evaluator for RAG responses using LLM-as-a-Judge.
 */
class Evaluator(val llmClient: LLMClient) {

  def evaluate(metric: EvalMetric, context: EvalContext): Result[EvalResult] =
    metric.evaluate(context, llmClient)

  /**
   * Evaluate all metrics against a single context.
   * Stops on first error (fail-fast).
   */
  def evaluateAll(metrics: Seq[EvalMetric], context: EvalContext): Result[Seq[EvalResult]] =
    metrics
      .foldLeft[Result[List[EvalResult]]](Right(Nil)) { (acc, metric) =>
        for {
          results <- acc
          result  <- evaluate(metric, context)
        } yield result :: results
      }
      .map(_.reverse)
}

object Evaluator {

  def apply(llmClient: LLMClient): Evaluator = new Evaluator(llmClient)
}
