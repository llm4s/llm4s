package org.llm4s.eval

import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

/**
 * Base trait for evaluation metrics.
 */
trait EvalMetric {

  def name: String

  def description: String

  def threshold: Double = 0.7

  def evaluate(context: EvalContext, llmClient: LLMClient): Result[EvalResult]

  def passes(score: Double): Boolean = score >= threshold
}
