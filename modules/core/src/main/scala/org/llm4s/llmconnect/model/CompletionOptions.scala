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
 * @param budgetTokens Optional explicit budget for thinking tokens (Anthropic Claude).
 *                     If set, overrides the default budget from reasoning effort level.
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
 * // For Anthropic, set explicit thinking budget
 * val anthropicOptions = CompletionOptions()
 *   .withBudgetTokens(16000)
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
  budgetTokens: Option[Int],
  responseFormat: Option[ResponseFormat]
) {
  def withTemperature(temperature: Double): CompletionOptions              = copy(temperature = temperature)
  def withTopP(topP: Double): CompletionOptions                            = copy(topP = topP)
  def withMaxTokens(maxTokens: Int): CompletionOptions                     = copy(maxTokens = Some(maxTokens))
  def withMaxTokens(maxTokens: Option[Int]): CompletionOptions             = copy(maxTokens = maxTokens)
  def withPresencePenalty(presencePenalty: Double): CompletionOptions      = copy(presencePenalty = presencePenalty)
  def withFrequencyPenalty(frequencyPenalty: Double): CompletionOptions    = copy(frequencyPenalty = frequencyPenalty)
  def withTools(tools: Seq[ToolFunction[_, _]]): CompletionOptions         = copy(tools = tools)
  def withReasoning(reasoning: Option[ReasoningEffort]): CompletionOptions = copy(reasoning = reasoning)
  def withBudgetTokens(budgetTokens: Option[Int]): CompletionOptions       = copy(budgetTokens = budgetTokens)
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
   * Enable reasoning with explicit token budget for thinking (Anthropic Claude).
   *
   * This overrides the default budget from the reasoning effort level.
   *
   * @param tokens the number of tokens to budget for thinking
   * @return new CompletionOptions with budget tokens set
   */
  def withBudgetTokens(tokens: Int): CompletionOptions =
    copy(budgetTokens = Some(tokens))

  /**
   * Check if reasoning is enabled.
   */
  def hasReasoning: Boolean =
    reasoning.exists(_ != ReasoningEffort.None) || budgetTokens.exists(_ > 0)

  /**
   * Get the effective budget tokens for Anthropic extended thinking.
   *
   * Returns explicit budgetTokens if set, otherwise derives from reasoning effort.
   */
  def effectiveBudgetTokens: Option[Int] =
    budgetTokens.orElse(reasoning.map(ReasoningEffort.defaultBudgetTokens)).filter(_ > 0)

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
    budgetTokens: Option[Int] = None,
    responseFormat: Option[ResponseFormat] = None
  ): CompletionOptions =
    new CompletionOptions(
      temperature,
      topP,
      maxTokens,
      presencePenalty,
      frequencyPenalty,
      tools,
      reasoning,
      budgetTokens,
      responseFormat
    )
}
