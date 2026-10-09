package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * An LLM-as-Judge guardrail that rates the overall quality of a response to a given query.
 *
 * **What it evaluates:** the judge is shown the original query and asked to rate the response on five points:
 * relevance (does it address the query), helpfulness, completeness, clarity, and apparent accuracy. It is told that
 * 1.0 means an excellent, comprehensive response, 0.5 an adequate but incomplete one, and 0.0 an irrelevant or
 * unhelpful one. "Accuracy" here is the judge's impression: nothing is checked against a source (use
 * [[LLMFactualityGuardrail]] for that).
 *
 * **When to use it:** for a subjective check that the answer is on topic and useful. For size limits use
 * `LengthCheck`, and for a required format use `RegexValidator` or `JSONValidator`: they are free, instant and
 * repeatable.
 *
 * **Cost and side:** every validation makes one extra `llmClient.complete` call, and the response goes to the provider of
 * `llmClient`. It is an output guardrail only. The scoring rules and the other limits are described on
 * [[org.llm4s.agent.guardrails.LLMGuardrail]].
 *
 * **One query per instance:** `originalQuery` is fixed when the guardrail is built, and it is placed in the prompt
 * inside double quotes without escaping. A guardrail built once for an agent judges every later answer against that
 * same query, so build a new one per query when answers should be judged against the question that produced them.
 * Its `description` embeds the first 50 characters of the query followed by `...`.
 *
 * **Failure:** `Left` with a [[org.llm4s.error.ValidationError]] on field `output`, for example
 * `LLM judge score (0.55) below threshold (0.70) for LLMQualityGuardrail`. It does not say which of the five points
 * fell short. An unreadable reply or a failing `llmClient` is a `Left` too, never a pass.
 *
 * @param llmClient the client that makes the judge call; it can be the agent's own or a separate model
 * @param originalQuery the query the response is judged against
 * @param threshold the lowest score that passes (a score equal to it passes), between 0.0 and 1.0 or `validate`
 *                  fails; default 0.7
 *
 * @example
 * {{{
 * import org.llm4s.agent.Agent
 * import org.llm4s.agent.graph.middleware.GuardrailMiddleware
 * import org.llm4s.agent.guardrails.builtin.LLMQualityGuardrail
 *
 * val guardrail = LLMQualityGuardrail(client, "What is Scala?")
 * val agent = Agent
 *   .builder("assistant", client)
 *   .withMiddleware(new GuardrailMiddleware(Nil, Seq(guardrail)))
 *   .build()
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
   * Builds a quality guardrail for a query.
   *
   * @param client the client that makes the judge call
   * @param originalQuery the query the response is judged against
   * @param threshold the lowest score that passes (default 0.7)
   */
  def apply(
    client: LLMClient,
    originalQuery: String,
    threshold: Double = 0.7
  ): LLMQualityGuardrail =
    new LLMQualityGuardrail(client, originalQuery, threshold)

  /**
   * Builds a quality guardrail with a threshold of 0.85.
   *
   * @param client the client that makes the judge call
   * @param originalQuery the query the response is judged against
   */
  def highQuality(client: LLMClient, originalQuery: String): LLMQualityGuardrail =
    new LLMQualityGuardrail(client, originalQuery, threshold = 0.85)
}
