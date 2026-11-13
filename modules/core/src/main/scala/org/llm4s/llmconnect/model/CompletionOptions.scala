package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.toolapi.ToolFunction

/**
 * Represents options for a completion request.
 *
 * @param temperature Controls the randomness of the output. Higher values make the output more random.
 *                    Note: Reasoning models (o1/o3) ignore this setting.
 * @param topP Controls the diversity of the output. Lower values make the output more focused.
 * @param maxTokens Optional maximum number of tokens to generate in the completion.
 * @param presencePenalty Penalizes new tokens based on whether they appear in the text so far, encouraging new topics.
 * @param frequencyPenalty Penalizes new tokens based on their existing frequency in the text so far, discouraging repetition.
 * @param tools Optional sequence of tool function definitions that can be requested by the LLM during a completion.
 * @param reasoning Optional reasoning effort level for models that support extended thinking (o1/o3, Claude).
 *                  For non-reasoning models, this setting is silently ignored.
 * @param thinking Optional thinking mode: off, adaptive, or a token budget (see [[Thinking]]). An explicit
 *                 `thinking` wins over `reasoning`; see `withThinking`.
 * @param promptCache Optional provider-side prompt cache (see [[PromptCache]]): changes what a call costs, not what it
 *                    returns. Not [[org.llm4s.llmconnect.caching.CachingLLMClient]], which caches whole answers.
 * @param responseFormat Optional structured output format (e.g. JSON or JSON schema). Support is
 *                        provider- and model-dependent; see capability validation in RequestTransformer.
 *
 * @example
 * {{{
 * import org.llm4s.llmconnect.model._
 *
 * // Enable high reasoning for complex tasks
 * val options = CompletionOptions()
 *   .withReasoning(ReasoningEffort.High)
 *   .copy(maxTokens = Some(4096))
 *
 * // Let the model decide how much to think, at low effort
 * val adaptiveOptions = CompletionOptions()
 *   .withThinking(Thinking.Adaptive)
 *   .withReasoning(ReasoningEffort.Low)
 *
 * // Think within an explicit budget, fitted to the provider's range
 * val budgetOptions = Thinking.budget(16000).map(CompletionOptions().withThinking)
 *
 * // Request JSON output (provider-dependent)
 * val jsonOptions = CompletionOptions().withResponseFormat(ResponseFormat.Json)
 * }}}
 */
@Stable
final case class CompletionOptions private (
  temperature: Double,
  topP: Double,
  maxTokens: Option[Int],
  presencePenalty: Double,
  frequencyPenalty: Double,
  tools: Seq[ToolFunction[_, _]],
  reasoning: Option[ReasoningEffort],
  thinking: Option[Thinking],
  responseFormat: Option[ResponseFormat],
  promptCache: Option[PromptCache]
) {

  /**
   * Sets the sampling temperature. Higher values make the output more random.
   * Reasoning models (o1/o3) ignore this setting.
   *
   * @param temperature the sampling temperature (default 0.7)
   * @return new CompletionOptions with temperature set
   */
  def withTemperature(temperature: Double): CompletionOptions = copy(temperature = temperature)

  /**
   * Sets the nucleus sampling value. Lower values make the output more focused.
   *
   * @param topP the top-p value (default 1.0)
   * @return new CompletionOptions with topP set
   */
  def withTopP(topP: Double): CompletionOptions = copy(topP = topP)

  /**
   * Limits the number of tokens generated in the completion.
   *
   * @param maxTokens the maximum number of tokens to generate
   * @return new CompletionOptions with maxTokens set to `Some(maxTokens)`
   */
  def withMaxTokens(maxTokens: Int): CompletionOptions = copy(maxTokens = Some(maxTokens))

  /**
   * Sets or clears the limit on the number of tokens generated in the completion.
   *
   * @param maxTokens the maximum number of tokens to generate; `None` sets no limit (the default)
   * @return new CompletionOptions with maxTokens set
   */
  def withMaxTokens(maxTokens: Option[Int]): CompletionOptions = copy(maxTokens = maxTokens)

  /**
   * Sets the presence penalty, which penalizes new tokens based on whether they appear in the text so far,
   * encouraging new topics.
   *
   * @param presencePenalty the presence penalty (default 0.0)
   * @return new CompletionOptions with presencePenalty set
   */
  def withPresencePenalty(presencePenalty: Double): CompletionOptions = copy(presencePenalty = presencePenalty)

  /**
   * Sets the frequency penalty, which penalizes new tokens based on their existing frequency in the text so far,
   * discouraging repetition.
   *
   * @param frequencyPenalty the frequency penalty (default 0.0)
   * @return new CompletionOptions with frequencyPenalty set
   */
  def withFrequencyPenalty(frequencyPenalty: Double): CompletionOptions = copy(frequencyPenalty = frequencyPenalty)

  /**
   * Sets the tool function definitions that the LLM can request during a completion.
   * Replaces any tools set before.
   *
   * @param tools the tools to offer; an empty sequence offers none (the default)
   * @return new CompletionOptions with tools set
   */
  def withTools(tools: Seq[ToolFunction[_, _]]): CompletionOptions = copy(tools = tools)

  /**
   * Sets or clears the reasoning effort level for models that support extended thinking (o1/o3, Claude).
   * For non-reasoning models, this setting is silently ignored.
   *
   * @param reasoning the reasoning effort level; `None` sets no reasoning effort (the default)
   * @return new CompletionOptions with reasoning set
   */
  def withReasoning(reasoning: Option[ReasoningEffort]): CompletionOptions = copy(reasoning = reasoning)

  /**
   * Sets or clears how the model thinks: [[Thinking.Off]], [[Thinking.Adaptive]] or a [[Thinking.Budget]].
   *
   * '''Precedence.''' An explicit `thinking` wins. Without one, `reasoning` decides as it always has: on a provider
   * that thinks within a budget, a `reasoning`-only request sends the level's default budget. A contradictory pair,
   * such as `thinking = Off` with `reasoning = High` on a provider with one control for both, is resolved by this
   * rule and reported as an `Adjusted` on `Reasoning`.
   *
   * '''The exception.''' A model whose capabilities say it rejects a manual budget
   * (`ModelCapabilities.supportsBudgetThinking = Some(false)`) is sent a `reasoning`-only request as adaptive
   * thinking at that effort, instead of a budget it would refuse with a 400, reported as an `Adjusted` on
   * `Reasoning`.
   *
   * @param thinking the thinking mode; `None` leaves it to `reasoning` and the provider (the default)
   * @return new CompletionOptions with thinking set
   */
  def withThinking(thinking: Option[Thinking]): CompletionOptions = copy(thinking = thinking)

  /**
   * Sets how the model thinks; see the `Option` overload for how it combines with `reasoning`.
   *
   * @param thinking the thinking mode
   * @return new CompletionOptions with thinking set
   */
  def withThinking(thinking: Thinking): CompletionOptions = copy(thinking = Some(thinking))

  /**
   * Sets or clears the structured output format (e.g. JSON or JSON schema).
   * Support is provider- and model-dependent; see capability validation in RequestTransformer.
   *
   * @param responseFormat the desired response format; `None` requests no particular format (the default)
   * @return new CompletionOptions with responseFormat set
   */
  def withResponseFormat(responseFormat: Option[ResponseFormat]): CompletionOptions =
    copy(responseFormat = responseFormat)

  /**
   * Enable reasoning with the specified effort level.
   *
   * @param effort the reasoning effort level
   * @return new CompletionOptions with reasoning enabled
   */
  def withReasoning(effort: ReasoningEffort): CompletionOptions =
    copy(reasoning = Some(effort))

  /**
   * Check if reasoning is enabled: `reasoning` is set, or `thinking` is set and is not [[Thinking.Off]].
   */
  def hasReasoning: Boolean =
    reasoning.isDefined || thinking.exists(_ != Thinking.Off)

  /**
   * Get the effective budget tokens for budget-based thinking (Anthropic manual thinking).
   *
   * `thinking`'s [[Thinking.Budget]] if set; with no `thinking`, the default budget of `reasoning`, as before
   * `thinking` existed; with `thinking` set to `Off` or `Adaptive`, none.
   */
  def effectiveBudgetTokens: Option[Int] =
    thinking match {
      case Some(Thinking.Budget(tokens))                => Some(tokens)
      case Some(Thinking.Off) | Some(Thinking.Adaptive) => None
      case None                                         => reasoning.map(ReasoningEffort.defaultBudgetTokens)
    }

  /**
   * Sets or clears a provider-side prompt cache: the provider keeps the start of the request, so a later request
   * with the same start costs less and answers sooner. The answer is the same either way.
   *
   * Not [[org.llm4s.llmconnect.caching.CachingLLMClient]], which keeps whole answers on the client and skips the
   * call. To fan out many requests that share a start, send one first and wait for it: requests sent together all
   * miss a cache that is not yet written. See [[PromptCache]].
   *
   * @param promptCache what to cache and for how long; `None` asks for no prompt cache (the default)
   * @return new CompletionOptions with promptCache set
   */
  def withPromptCache(promptCache: Option[PromptCache]): CompletionOptions = copy(promptCache = promptCache)

  /**
   * Asks for a provider-side prompt cache; see the `Option` overload.
   *
   * @param promptCache what to cache and for how long, such as `PromptCache.default`
   * @return new CompletionOptions with promptCache set
   */
  def withPromptCache(promptCache: PromptCache): CompletionOptions = copy(promptCache = Some(promptCache))

  /**
   * Request structured output in the given format.
   *
   * Support is provider- and model-dependent. Capability validation is applied
   * via RequestTransformer (Json: fallback when unsupported; JsonSchema: strict error).
   *
   * @param format the desired response format (e.g. ResponseFormat.Json or ResponseFormat.JsonSchema(schema))
   * @return new CompletionOptions with responseFormat set
   */
  def withResponseFormat(format: ResponseFormat): CompletionOptions =
    copy(responseFormat = Some(format))
}

object CompletionOptions {

  /** Creates a [[CompletionOptions]]. Named arguments are the supported way to construct one. */
  def apply(
    temperature: Double = 0.7,
    topP: Double = 1.0,
    maxTokens: Option[Int] = None,
    presencePenalty: Double = 0.0,
    frequencyPenalty: Double = 0.0,
    tools: Seq[ToolFunction[_, _]] = Seq.empty,
    reasoning: Option[ReasoningEffort] = None,
    thinking: Option[Thinking] = None,
    responseFormat: Option[ResponseFormat] = None,
    promptCache: Option[PromptCache] = None
  ): CompletionOptions =
    new CompletionOptions(
      temperature,
      topP,
      maxTokens,
      presencePenalty,
      frequencyPenalty,
      tools,
      reasoning,
      thinking,
      responseFormat,
      promptCache
    )
}
