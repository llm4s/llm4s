package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * LLM-based tone validation guardrail.
 *
 * Uses an LLM to evaluate whether content matches the specified tone(s).
 * This is more accurate than the keyword-based ToneValidator for nuanced
 * tone detection, but has higher latency due to the LLM API call.
 *
 * @param llmClient The LLM client to use for evaluation
 * @param allowedTones Set of acceptable tones (e.g., "professional", "friendly")
 * @param threshold Minimum score to pass (default: 0.7)
 *
 * @example
 * {{{
 * val guardrail = LLMToneGuardrail(
 *   client,
 *   Set("professional", "friendly"),
 *   threshold = 0.8
 * )
 * agent.run(query, tools, outputGuardrails = Seq(guardrail))
 * }}}
 */
class LLMToneGuardrail(
  val llmClient: LLMClient,
  allowedTones: Set[String],
  override val threshold: Double = 0.7
) extends LLMGuardrail {

  val evaluationPrompt: String = {
    val tonesStr = allowedTones.mkString(", ")
    s"""Rate if this content has one of these tones: $tonesStr.
       |
       |Consider:
       |- Word choice and vocabulary
       |- Sentence structure and formality
       |- Overall impression and feel
       |
       |Score 1.0 if the tone clearly matches one of the allowed tones.
       |Score 0.0 if the tone is completely different or inappropriate.
       |Score intermediate values for partial matches.""".stripMargin
  }

  val name: String = "LLMToneGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based tone validation for: ${allowedTones.mkString(", ")}"
  )
}

object LLMToneGuardrail {

  /**
   * Create an LLM tone guardrail.
   */
  def apply(
    client: LLMClient,
    allowedTones: Set[String],
    threshold: Double = 0.7
  ): LLMToneGuardrail =
    new LLMToneGuardrail(client, allowedTones, threshold)

  /**
   * Create a professional tone guardrail.
   */
  def professional(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("professional", "formal", "business-appropriate"), threshold)

  /**
   * Create a friendly/casual tone guardrail.
   */
  def friendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("friendly", "warm", "approachable"), threshold)

  /**
   * Create a professional-or-friendly tone guardrail.
   */
  def professionalOrFriendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("professional", "friendly", "warm", "approachable"), threshold)
}
