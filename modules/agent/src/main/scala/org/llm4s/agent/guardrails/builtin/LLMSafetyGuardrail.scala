package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * An LLM-as-Judge guardrail that rates whether content is safe and appropriate for general audiences.
 *
 * **What it evaluates:** the judge is asked to rate the content against five criteria: harmful or dangerous
 * content, inappropriate or offensive language, misinformation or misleading claims, privacy violations, and
 * promotion of illegal activity. It is told that 1.0 means completely safe, 0.5 means minor concerns but nothing
 * harmful, and 0.0 means clearly harmful, dangerous or inappropriate. `customCriteria`, when given, is appended
 * to that prompt under `Additional criteria:`.
 *
 * **When to use it:** when whether something is acceptable depends on context. For a fixed word list use
 * `ProfanityFilter`, and for personal-data patterns use `PIIDetector`: they are free, instant and repeatable,
 * where this guardrail is none of those.
 *
 * **Cost and side:** every validation makes one extra LLM call, so it does not suit latency-sensitive paths, and
 * the content is sent to the provider of `llmClient`. It is an output guardrail only: it judges the agent's
 * final answer. The scoring rules, their edges (a reply of `85` reads as 1.0 and passes) and the other limits are
 * described on [[org.llm4s.agent.guardrails.LLMGuardrail]].
 *
 * **Failure:** `Left` with a [[org.llm4s.error.ValidationError]] on field `output`, for example
 * `LLM judge score (0.60) below threshold (0.80) for LLMSafetyGuardrail`. It does not say which criterion failed.
 * An unreadable reply or a failing `llmClient` is a `Left` too, never a pass.
 *
 * @param llmClient the client that makes the judge call; it can be the agent's own or a separate model
 * @param threshold the lowest score that passes (a score equal to it passes); the default 0.8 is higher than the
 *                  base default of 0.7, as the original comment put it, "higher for safety"
 * @param customCriteria extra criteria appended to the prompt verbatim under `Additional criteria:`; `None` keeps
 *                       the five built-in criteria only
 *
 * @example
 * {{{
 * import org.llm4s.agent.Agent
 * import org.llm4s.agent.graph.middleware.GuardrailMiddleware
 * import org.llm4s.agent.guardrails.builtin.LLMSafetyGuardrail
 *
 * val guardrail = LLMSafetyGuardrail(client)
 * val agent = Agent
 *   .builder("assistant", client)
 *   .withMiddleware(new GuardrailMiddleware(Nil, Seq(guardrail)))
 *   .build()
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
   * Builds a safety guardrail with the five built-in criteria.
   *
   * @param client the client that makes the judge call
   * @param threshold the lowest score that passes (default 0.8)
   */
  def apply(client: LLMClient, threshold: Double = 0.8): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold)

  /**
   * Builds a safety guardrail with a threshold of 0.95, which passes only content the judge rates as safe.
   *
   * @param client the client that makes the judge call
   */
  def strict(client: LLMClient): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold = 0.95)

  /**
   * Builds a safety guardrail whose prompt carries extra criteria after the five built-in ones.
   *
   * @param client the client that makes the judge call
   * @param customCriteria the extra criteria, appended verbatim under `Additional criteria:`
   * @param threshold the lowest score that passes (default 0.8)
   */
  def withCustomCriteria(
    client: LLMClient,
    customCriteria: String,
    threshold: Double = 0.8
  ): LLMSafetyGuardrail =
    new LLMSafetyGuardrail(client, threshold, Some(customCriteria))

  /**
   * Builds a guardrail for child audiences: a threshold of 0.95 and three extra criteria, numbered 6 to 8, for
   * age-inappropriate themes, scary or disturbing content, and complex adult topics.
   *
   * @param client the client that makes the judge call
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
