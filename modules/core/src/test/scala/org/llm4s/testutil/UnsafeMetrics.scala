package org.llm4s.testutil

import org.llm4s.eval.metrics.{ AnswerRelevance, ContextPrecision, Faithfulness }

/**
 * Test-only unsafe constructors for eval metrics.
 * These throw exceptions on invalid input - use only in tests.
 */
object UnsafeMetrics {

  def faithfulness(threshold: Double = 0.7): Faithfulness =
    Faithfulness(threshold).fold(e => throw new IllegalArgumentException(e.message), identity)

  def answerRelevance(threshold: Double = 0.7): AnswerRelevance =
    AnswerRelevance(threshold).fold(e => throw new IllegalArgumentException(e.message), identity)

  def contextPrecision(threshold: Double = 0.5): ContextPrecision =
    ContextPrecision(threshold).fold(e => throw new IllegalArgumentException(e.message), identity)
}
