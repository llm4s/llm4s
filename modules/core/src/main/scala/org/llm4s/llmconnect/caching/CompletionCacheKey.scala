package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, ResponseFormat, Thinking }
import org.llm4s.toolapi.ToolFunction

/**
 * The part of a request's [[CompletionOptions]] that [[CachingLLMClient]] matches a cached answer on: the fields that
 * change the answer (RFC 0008, decision 3).
 *
 * The cache used to match on `CompletionOptions` equality, so every new field joined the key without anyone deciding
 * it should. The key is now listed here, field by field, and a field that does not change the answer is left out: a
 * provider-side setting such as RFC 0008's `promptCache`, which changes what a call costs and not what it returns,
 * must not split one cached answer into two. [[CompletionCacheKey.Excluded]] names those, and a test fails when
 * `CompletionOptions` gains a field that is in neither list, so each new field is a decision.
 */
final private[caching] case class CompletionCacheKey(
  temperature: Double,
  topP: Double,
  maxTokens: Option[Int],
  presencePenalty: Double,
  frequencyPenalty: Double,
  tools: Seq[ToolFunction[?, ?]],
  reasoning: Option[ReasoningEffort],
  thinking: Option[Thinking],
  responseFormat: Option[ResponseFormat]
)

private[caching] object CompletionCacheKey {

  /** The key of `options`. */
  def of(options: CompletionOptions): CompletionCacheKey =
    CompletionCacheKey(
      temperature = options.temperature,
      topP = options.topP,
      maxTokens = options.maxTokens,
      presencePenalty = options.presencePenalty,
      frequencyPenalty = options.frequencyPenalty,
      tools = options.tools,
      reasoning = options.reasoning,
      thinking = options.thinking,
      responseFormat = options.responseFormat
    )

  /**
   * The `CompletionOptions` fields left out of the key because they do not change the answer: `promptCache`, a
   * provider-side cache that changes what a call costs (RFC 0008, PR 6).
   */
  val Excluded: Set[String] = Set("promptCache")
}
