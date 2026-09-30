package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * LLM-based response quality validation guardrail.
 *
 * Uses an LLM to evaluate the overall quality of a response including
 * helpfulness, completeness, clarity, and relevance.
 *
 * @param llmClient The LLM client to use for evaluation
 * @param originalQuery The original user query (for relevance checking)
 * @param threshold Minimum score to pass (default: 0.7)
 *
 * @example
 * {{{
 * val guardrail = LLMQualityGuardrail(client, "What is Scala?")
 * agent.run(query, tools, outputGuardrails = Seq(guardrail))
 * }}}
 */
class LLMQualityGuardrail(
  val llmClient: LLMClient,
  originalQuery: String,
  override val threshold: Double = 0.7
) extends LLMGuardrail {

  val evaluationPrompt: String =
    s"""Rate the quality of this response to the following query:
       |
       |Original Query: "$originalQuery"
       |
       |Evaluate based on:
       |1. Relevance - Does it directly address the query?
       |2. Helpfulness - Does it provide useful information?
       |3. Completeness - Does it cover the key aspects?
       |4. Clarity - Is it easy to understand?
       |5. Accuracy - Does it appear factually correct?
       |
       |Score 1.0 for an excellent, comprehensive response.
       |Score 0.5 for an adequate but incomplete response.
       |Score 0.0 for an irrelevant or unhelpful response.""".stripMargin

  val name: String = "LLMQualityGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based quality check for response to: ${originalQuery.take(50)}..."
  )
}

object LLMQualityGuardrail {

  /**
   * Create an LLM quality guardrail.
   */
  def apply(
    client: LLMClient,
    originalQuery: String,
    threshold: Double = 0.7
  ): LLMQualityGuardrail =
    new LLMQualityGuardrail(client, originalQuery, threshold)

  /**
   * Create a high-quality response guardrail (higher threshold).
   */
  def highQuality(client: LLMClient, originalQuery: String): LLMQualityGuardrail =
    new LLMQualityGuardrail(client, originalQuery, threshold = 0.85)
}
