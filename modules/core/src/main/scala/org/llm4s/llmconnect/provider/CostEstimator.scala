package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.model.{ ModelMetadata, ModelRegistry }

/**
 * Centralized cost estimation for LLM completions.
 *
 * This provides a single source of truth for estimating completion costs
 * based on token usage and model pricing information. It integrates with
 * the ModelRegistry to look up pricing data and applies it to usage statistics.
 *
 * The estimator:
 * - Uses existing ModelPricing logic (no duplication)
 * - Returns None if pricing is unavailable
 * - Preserves precision of micro-cost values
 * - Works uniformly across all providers
 *
 * Example usage:
 * {{{
 *   val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
 *   val cost = CostEstimator.estimate("gpt-4o", usage)
 *   // cost: Some(0.0015) for gpt-4o pricing
 * }}}
 */
object CostEstimator {

  /**
   * Estimate the cost of a completion based on model and token usage.
   *
   * This method looks up the model's pricing information and applies it to
   * the provided usage statistics. It handles:
   * - Standard prompt and completion tokens
   * - Thinking tokens (billed at completion token rate)
   * - Missing pricing data (returns None)
   *
   * @param model The model identifier (e.g., "gpt-4o", "claude-3-7-sonnet-latest")
   * @param usage Token usage statistics from the completion
   * @return Estimated cost in USD, or None if pricing is unavailable
   */
  def estimate(model: String, usage: TokenUsage): Option[Double] =
    estimateFromMetadata(ModelRegistry.lookup(model).toOption, usage)

  /**
   * Estimate cost using pre-fetched model metadata.
   *
   * This is useful when the caller already has ModelMetadata (e.g., from
   * a provider implementation that caches metadata lookups).
   *
   * @param metadata Model metadata containing pricing information
   * @param usage Token usage statistics
   * @return Estimated cost in USD, or None if pricing is unavailable
   */
  def estimateFromMetadata(metadata: Option[ModelMetadata], usage: TokenUsage): Option[Double] =
    metadata.flatMap { meta =>
      val pricing = meta.pricing

      val cachedTokens        = usage.cachedTokens.getOrElse(0)
      val cacheCreationTokens = usage.cacheCreationTokens.getOrElse(0)
      val hasCacheBreakdown   = usage.cachedTokens.isDefined || usage.cacheCreationTokens.isDefined

      val normalInputTokens =
        math.max(
          0,
          usage.promptTokens -
            cachedTokens -
            cacheCreationTokens
        )

      def estimateInputCostWithCaching(outputCost: Double): Option[Double] =
        if (hasCacheBreakdown) {
          (usage.cachedTokens, usage.cacheCreationTokens) match {
            case (Some(_), Some(_)) =>
              for {
                inCost      <- pricing.inputCostPerToken
                cacheRead   <- pricing.cacheReadInputTokenCost
                cacheCreate <- pricing.cacheCreationInputTokenCost
              } yield (normalInputTokens * inCost) + (cachedTokens * cacheRead) + (cacheCreationTokens * cacheCreate) + outputCost

            case (Some(_), None) =>
              for {
                inCost    <- pricing.inputCostPerToken
                cacheRead <- pricing.cacheReadInputTokenCost
              } yield (normalInputTokens * inCost) + (cachedTokens * cacheRead) + outputCost

            case (None, Some(_)) =>
              for {
                inCost      <- pricing.inputCostPerToken
                cacheCreate <- pricing.cacheCreationInputTokenCost
              } yield (normalInputTokens * inCost) + (cacheCreationTokens * cacheCreate) + outputCost

            case (None, None) =>
              pricing.estimateCost(normalInputTokens, 0).map(_ + outputCost)
          }
        } else {
          pricing.estimateCost(normalInputTokens, 0).map(_ + outputCost)
        }

      (usage.thinkingTokens, pricing.outputCostPerReasoningToken) match {
        case (Some(thinkingTokens), Some(reasoningCostPerToken)) =>
          pricing.outputCostPerToken.flatMap { outputCostPerToken =>
            val effectiveThinkingTokens   = math.max(0, thinkingTokens)
            val effectiveCompletionTokens = math.max(0, usage.completionTokens)
            val normalCompletionTokens    = math.max(0, effectiveCompletionTokens - effectiveThinkingTokens)
            val normalOutputCost          = normalCompletionTokens * outputCostPerToken
            val reasoningOutputCost       = effectiveThinkingTokens * reasoningCostPerToken
            val outputCost                = normalOutputCost + reasoningOutputCost
            estimateInputCostWithCaching(outputCost)
          }

        case _ =>
          // For models with thinking tokens, include them in the output token count
          // as they are typically billed at the completion token rate
          val effectiveOutputTokens = usage.totalOutputTokens

          val outputCostOpt = pricing.outputCostPerToken.map(outCost => math.max(0, effectiveOutputTokens) * outCost)
          outputCostOpt.flatMap(estimateInputCostWithCaching)
      }
    }

  /**
   * Estimate cost directly from pricing information.
   *
   * This bypasses model lookup entirely and uses the provided pricing.
   * Useful for testing or when pricing is already available.
   *
   * @param inputCost Cost per input token in USD
   * @param outputCost Cost per output token in USD
   * @param usage Token usage statistics
   * @return Estimated cost in USD
   */
  private[provider] def estimateDirect(inputCost: Double, outputCost: Double, usage: TokenUsage): Double = {
    val effectiveOutputTokens = usage.totalOutputTokens
    (usage.promptTokens * inputCost) + (effectiveOutputTokens * outputCost)
  }
}
