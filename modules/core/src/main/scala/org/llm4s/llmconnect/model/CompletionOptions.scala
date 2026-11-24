package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.toolapi.ToolFunction

/**
 * Represents options for a completion request.
 *
 * The four sampling parameters (`temperature`, `topP` and the two penalties) are `None` unless set: llm4s then sends
 * none, and the provider's own default applies. A value is sent only when the caller set one.
 *
 * @param temperature Controls the randomness of the output. Higher values make the output more random. `None`
 *                    sends none, so the provider's default applies. Note: Reasoning models (o1/o3) ignore this setting.
 * @param topP Controls the diversity of the output. Lower values make the output more focused. `None` sends none.
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
 * @param serviceTier Optional processing tier (see [[ServiceTier]]): what a call costs and how it is scheduled; `None`
 *                    sends no tier, so the provider serves the request at its default.
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
 *   .withMaxTokens(4096)
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
  temperature: Option[Double],
  topP: Option[Double],
  maxTokens: Option[Int],
  presencePenalty: Option[Double],
  frequencyPenalty: Option[Double],
  tools: Seq[ToolFunction[_, _]],
  reasoning: Option[ReasoningEffort],
  thinking: Option[Thinking],
  responseFormat: Option[ResponseFormat],
  promptCache: Option[PromptCache],
  serviceTier: Option[ServiceTier]
) {

  /**
   * Sets the sampling temperature. Higher values make the output more random.
   * Reasoning models (o1/o3) ignore this setting.
   *
   * @param temperature the sampling temperature
   * @return new CompletionOptions with temperature set to `Some(temperature)`
   */
  def withTemperature(temperature: Double): CompletionOptions = copy(temperature = Some(temperature))

  /**
   * Sets or clears the sampling temperature.
   *
   * @param temperature the sampling temperature; `None` sends none, so the provider's default applies (the default)
   * @return new CompletionOptions with temperature set
   */
  def withTemperature(temperature: Option[Double]): CompletionOptions = copy(temperature = temperature)

  /**
   * Sets the nucleus sampling value. Lower values make the output more focused.
   *
   * @param topP the top-p value
   * @return new CompletionOptions with topP set to `Some(topP)`
   */
  def withTopP(topP: Double): CompletionOptions = copy(topP = Some(topP))

  /**
   * Sets or clears the nucleus sampling value.
   *
   * @param topP the top-p value; `None` sends none, so the provider's default applies (the default)
   * @return new CompletionOptions with topP set
   */
  def withTopP(topP: Option[Double]): CompletionOptions = copy(topP = topP)

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
   * @param presencePenalty the presence penalty
   * @return new CompletionOptions with presencePenalty set to `Some(presencePenalty)`
   */
  def withPresencePenalty(presencePenalty: Double): CompletionOptions = copy(presencePenalty = Some(presencePenalty))

  /**
   * Sets or clears the presence penalty.
   *
   * @param presencePenalty the presence penalty; `None` sends none, so the provider's default applies (the default)
   * @return new CompletionOptions with presencePenalty set
   */
  def withPresencePenalty(presencePenalty: Option[Double]): CompletionOptions = copy(presencePenalty = presencePenalty)

  /**
   * Sets the frequency penalty, which penalizes new tokens based on their existing frequency in the text so far,
   * discouraging repetition.
   *
   * @param frequencyPenalty the frequency penalty
   * @return new CompletionOptions with frequencyPenalty set to `Some(frequencyPenalty)`
   */
  def withFrequencyPenalty(frequencyPenalty: Double): CompletionOptions =
    copy(frequencyPenalty = Some(frequencyPenalty))

  /**
   * Sets or clears the frequency penalty.
   *
   * @param frequencyPenalty the frequency penalty; `None` sends none, so the provider's default applies (the default)
   * @return new CompletionOptions with frequencyPenalty set
   */
  def withFrequencyPenalty(frequencyPenalty: Option[Double]): CompletionOptions =
    copy(frequencyPenalty = frequencyPenalty)

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
   * Asks the provider to serve the request at a processing tier, such as `ServiceTier.Flex` for a cheaper, slower
   * answer or `ServiceTier.Priority` for a faster one. The answer is the same either way; what the call costs, and how
   * readily the provider sheds it under load, is not. A provider that cannot send the tier as asked reports it on
   * [[Completion.adjustments]]; see [[ServiceTier]] for each provider's mapping.
   *
   * @param serviceTier the tier; `None` sends none, so the provider serves the request at its default (the default)
   * @return new CompletionOptions with serviceTier set
   */
  def withServiceTier(serviceTier: Option[ServiceTier]): CompletionOptions = copy(serviceTier = serviceTier)

  /**
   * Asks the provider to serve the request at a processing tier; see the `Option` overload.
   *
   * @param serviceTier the tier, such as `ServiceTier.Flex`
   * @return new CompletionOptions with serviceTier set
   */
  def withServiceTier(serviceTier: ServiceTier): CompletionOptions = copy(serviceTier = Some(serviceTier))

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
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    maxTokens: Option[Int] = None,
    presencePenalty: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    tools: Seq[ToolFunction[_, _]] = Seq.empty,
    reasoning: Option[ReasoningEffort] = None,
    thinking: Option[Thinking] = None,
    responseFormat: Option[ResponseFormat] = None,
    promptCache: Option[PromptCache] = None,
    serviceTier: Option[ServiceTier] = None
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
      promptCache,
      serviceTier
    )
}
