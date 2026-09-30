package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * LLM-based content safety validation guardrail.
 *
 * Uses an LLM to evaluate whether content is safe, appropriate,
 * and non-harmful. This provides more nuanced safety checking than
 * keyword-based filters.
 *
 * Safety categories evaluated:
 * - Harmful or dangerous content
 * - Inappropriate or offensive language
 * - Misinformation or misleading claims
 * - Privacy violations
 * - Illegal activity promotion
 *
 * @param llmClient The LLM client to use for evaluation
 * @param threshold Minimum score to pass (default: 0.8 - higher for safety)
 * @param customCriteria Optional additional safety criteria to check
 *
 * @example
 * {{{
 * val guardrail = LLMSafetyGuardrail(client)
 * agent.run(query, tools, outputGuardrails = Seq(guardrail))
 * }}}
 */
class LLMSafetyGuardrail(
  val llmClient: LLMClient,
  override val threshold: Double = 0.8,
  customCriteria: Option[String] = None
) extends LLMGuardrail {

  val evaluationPrompt: String = {
    val baseCriteria =
      """Rate if this content is safe and appropriate for general audiences.
        |
        |Evaluate for:
        |1. Harmful or dangerous content (instructions for harm, violence)
        |2. Inappropriate or offensive language (profanity, slurs, harassment)
        |3. Misinformation or misleading claims
        |4. Privacy violations (personal information exposure)
        |5. Illegal activity promotion
        |
        |Score 1.0 if the content is completely safe and appropriate.
        |Score 0.5 if there are minor concerns but nothing harmful.
        |Score 0.0 if the content is clearly harmful, dangerous, or inappropriate.""".stripMargin

    customCriteria match {
      case Some(criteria) => s"$baseCriteria\n\nAdditional criteria:\n$criteria"
      case None           => baseCriteria
    }
  }

  val name: String = "LLMSafetyGuardrail"

  override val description: Option[String] = Some(
    "LLM-based safety check for harmful, inappropriate, or dangerous content"
  )
}

object LLMSafetyGuardrail {

  /**
   * Create a standard LLM safety guardrail.
   */
  def apply(client: LLMClient, threshold: Double = 0.8): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold)

  /**
   * Create a strict safety guardrail (higher threshold).
   */
  def strict(client: LLMClient): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold = 0.95)

  /**
   * Create a safety guardrail with custom additional criteria.
   */
  def withCustomCriteria(
    client: LLMClient,
    customCriteria: String,
    threshold: Double = 0.8
  ): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold, Some(customCriteria))

  /**
   * Create a child-safe content guardrail.
   */
  def childSafe(client: LLMClient): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(
      client,
      threshold = 0.95,
      customCriteria = Some(
        """Also evaluate for child-appropriate content:
          |6. Age-inappropriate themes
          |7. Scary or disturbing content
          |8. Complex adult topics""".stripMargin
      )
    )
}
