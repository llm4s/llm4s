package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * An LLM-as-Judge guardrail that rates whether content has one of a set of allowed tones.
 *
 * **What it evaluates:** the judge is asked to rate whether the content has one of the tones in `allowedTones`,
 * considering word choice and vocabulary, sentence structure and formality, and the overall impression. It is told
 * that 1.0 means the tone clearly matches one of the allowed tones, 0.0 that it is completely different or
 * inappropriate, and to use intermediate values for partial matches. Tone names are free text passed to the judge
 * as they are, so `"warm"` or `"playful"` work as well as `"professional"`.
 *
 * **When to use it:** when the tones you need are not covered by the fixed categories of [[ToneValidator]], which
 * detects them from keywords and sentence shapes without any LLM call and is free, instant and repeatable. This
 * guardrail judges meaning instead, at the cost of the points below.
 *
 * **Cost and side:** every validation makes one extra `llmClient.complete` call, and the content goes to the provider of
 * `llmClient`. It is an output guardrail only. The scoring rules and the other limits are described on
 * [[org.llm4s.agent.guardrails.LLMGuardrail]].
 *
 * **Details:** the tones appear in the prompt joined with `, ` in the iteration order of the `Set`, which is
 * unspecified for larger sets. An empty set is not rejected: the prompt then names no tone, and the judge has
 * nothing to match.
 *
 * **Failure:** `Left` with a [[org.llm4s.error.ValidationError]] on field `output`, for example
 * `LLM judge score (0.30) below threshold (0.70) for LLMToneGuardrail`. It does not name the tone the judge
 * perceived. An unreadable reply or a failing `llmClient` is a `Left` too, never a pass.
 *
 * @param llmClient the client that makes the judge call; it can be the agent's own or a separate model
 * @param allowedTones the acceptable tones, for example `Set("professional", "friendly")`
 * @param threshold the lowest score that passes (a score equal to it passes), between 0.0 and 1.0 or `validate`
 *                  fails; default 0.7
 *
 * @example
 * {{{
 * import org.llm4s.agent.Agent
 * import org.llm4s.agent.graph.middleware.GuardrailMiddleware
 * import org.llm4s.agent.guardrails.builtin.LLMToneGuardrail
 *
 * val guardrail = LLMToneGuardrail(client, Set("professional", "friendly"), threshold = 0.8)
 * val agent = Agent
 *   .builder("assistant", client)
 *   .withMiddleware(new GuardrailMiddleware(Nil, Seq(guardrail)))
 *   .build()
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
   * Builds a tone guardrail for a set of tones.
   *
   * @param client the client that makes the judge call
   * @param allowedTones the acceptable tones
   * @param threshold the lowest score that passes (default 0.7)
   */
  def apply(
    client: LLMClient,
    allowedTones: Set[String],
    threshold: Double = 0.7
  ): LLMToneGuardrail =
    new LLMToneGuardrail(client, allowedTones, threshold)

  /**
   * Builds a guardrail for the tones `professional`, `formal` and `business-appropriate`.
   *
   * @param client the client that makes the judge call
   * @param threshold the lowest score that passes (default 0.7)
   */
  def professional(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("professional", "formal", "business-appropriate"), threshold)

  /**
   * Builds a guardrail for the tones `friendly`, `warm` and `approachable`.
   *
   * @param client the client that makes the judge call
   * @param threshold the lowest score that passes (default 0.7)
   */
  def friendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("friendly", "warm", "approachable"), threshold)

  /**
   * Builds a guardrail for the tones `professional`, `friendly`, `warm` and `approachable`.
   *
   * @param client the client that makes the judge call
   * @param threshold the lowest score that passes (default 0.7)
   */
  def professionalOrFriendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail =
    new LLMToneGuardrail(client, Set("professional", "friendly", "warm", "approachable"), threshold)
}
