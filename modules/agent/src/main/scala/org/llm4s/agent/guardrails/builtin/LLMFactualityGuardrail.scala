package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

/**
 * An LLM-as-Judge guardrail that rates whether content is supported by a reference text, typically the
 * documents a RAG answer was built from.
 *
 * **What it evaluates:** the judge is given `referenceContext` and asked to rate the content's factual claims
 * against it: 1.0 when all claims are supported by the context, 0.5 when some are supported and others cannot be
 * verified, 0.0 when claims directly contradict it. It is told to evaluate factual claims only and to ignore
 * stylistic differences. It checks the content against the text you supply, not against the world: by the
 * prompt's rubric a claim the context does not mention is one that "cannot be verified", which scores in the
 * middle, not one that is false.
 *
 * **When to use it:** to catch answers that drift from retrieved documents. No rule-based guardrail can compare
 * meaning with a source text. The RAG-specific guardrails in `org.llm4s.agent.guardrails.rag` are the alternative
 * to look at first for retrieval pipelines.
 *
 * **Cost and side:** every validation makes one extra LLM call whose prompt contains the whole of
 * `referenceContext` as well as the content, so cost and latency grow with the context size, and both go to the
 * provider of `llmClient`. It is an output guardrail only. The scoring rules and the other limits are described on
 * [[org.llm4s.agent.guardrails.LLMGuardrail]].
 *
 * **One context per instance:** `referenceContext` is fixed when the guardrail is built. A guardrail built once for
 * an agent judges every later answer against that same text, so build a new one when the context changes (for
 * example per retrieval). Its `description` embeds the first 50 characters of the context followed by `...`, so
 * keep secrets out of the start of the context.
 *
 * **Failure:** `Left` with a [[org.llm4s.error.ValidationError]] on field `output`, for example
 * `LLM judge score (0.40) below threshold (0.70) for LLMFactualityGuardrail`. It does not quote the unsupported
 * claim. An unreadable reply or a failing `llmClient` is a `Left` too, never a pass.
 *
 * @param llmClient the client that makes the judge call; it can be the agent's own or a separate model
 * @param referenceContext the text the content is checked against; it is inserted into the prompt as is
 * @param threshold the lowest score that passes (a score equal to it passes); default 0.7
 *
 * @example
 * {{{
 * import org.llm4s.agent.Agent
 * import org.llm4s.agent.graph.middleware.GuardrailMiddleware
 * import org.llm4s.agent.guardrails.builtin.LLMFactualityGuardrail
 *
 * val context = "Paris is the capital of France. It has a population of 2.1 million."
 * val guardrail = LLMFactualityGuardrail(client, context, threshold = 0.8)
 * val agent = Agent
 *   .builder("assistant", client)
 *   .withMiddleware(new GuardrailMiddleware(Nil, Seq(guardrail)))
 *   .build()
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
   * Builds a factuality guardrail for a reference text.
   *
   * @param client the client that makes the judge call
   * @param referenceContext the text the content is checked against
   * @param threshold the lowest score that passes (default 0.7)
   */
  def apply(
    client: LLMClient,
    referenceContext: String,
    threshold: Double = 0.7
  ): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold)

  /**
   * Builds a factuality guardrail with a threshold of 0.9.
   *
   * @param client the client that makes the judge call
   * @param referenceContext the text the content is checked against
   */
  def strict(client: LLMClient, referenceContext: String): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold = 0.9)

  /**
   * Builds a factuality guardrail with a threshold of 0.5, which tolerates claims the context does not cover.
   *
   * @param client the client that makes the judge call
   * @param referenceContext the text the content is checked against
   */
  def lenient(client: LLMClient, referenceContext: String): LLMFactualityGuardrail =
    new LLMFactualityGuardrail(client, referenceContext, threshold = 0.5)
}
