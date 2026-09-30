package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * LLM-based factual accuracy validation guardrail.
 *
 * Uses an LLM to evaluate whether content is factually accurate
 * given a reference context. Useful for RAG applications where
 * you want to ensure the model's response aligns with retrieved documents.
 *
 * @param llmClient The LLM client to use for evaluation
 * @param referenceContext The reference text to fact-check against
 * @param threshold Minimum score to pass (default: 0.7)
 *
 * @example
 * {{{
 * val context = "Paris is the capital of France. It has a population of 2.1 million."
 * val guardrail = LLMFactualityGuardrail(client, context, threshold = 0.8)
 * agent.run(query, tools, outputGuardrails = Seq(guardrail))
 * }}}
 */
class LLMFactualityGuardrail(
  val llmClient: LLMClient,
  referenceContext: String,
  override val threshold: Double = 0.7
) extends LLMGuardrail {

  val evaluationPrompt: String =
    s"""Rate the factual accuracy of this content based on the reference context below.
       |
       |Reference Context:
       |\"\"\"
       |$referenceContext
       |\"\"\"
       |
       |Evaluation criteria:
       |- Score 1.0 if all claims are supported by the reference context
       |- Score 0.5 if some claims are supported but others cannot be verified
       |- Score 0.0 if claims directly contradict the reference context
       |
       |Only evaluate factual claims. Ignore stylistic differences.""".stripMargin

  val name: String = "LLMFactualityGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based factuality check against provided context (${referenceContext.take(50)}...)"
  )
}

object LLMFactualityGuardrail {

  /**
   * Create an LLM factuality guardrail with reference context.
   */
  def apply(
    client: LLMClient,
    referenceContext: String,
    threshold: Double = 0.7
  ): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold)

  /**
   * Create a strict factuality guardrail (higher threshold).
   */
  def strict(client: LLMClient, referenceContext: String): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold = 0.9)

  /**
   * Create a lenient factuality guardrail (lower threshold).
   */
  def lenient(client: LLMClient, referenceContext: String): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold = 0.5)
}
