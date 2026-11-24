package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.model.{ ModelMetadata, ModelRegistryService }

/**
 * Centralized cost estimation for LLM completions.
 *
 * This provides a single source of truth for estimating completion costs
 * based on token usage and model pricing information. It integrates with
 * the ModelRegistryService to look up pricing data and applies it to usage statistics.
 *
 * The estimator:
 * - Uses existing ModelPricing logic (no duplication)
 * - Returns None if pricing is unavailable
 * - Computes in `BigDecimal`, so a micro-cost is exact and sums of them do not drift
 * - Works uniformly across all providers
 *
 * Example usage:
 * {{{
 *   val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
 *   val cost = CostEstimator.estimate("gpt-4o", usage)
 *   // cost: Some(BigDecimal("0.0015")) for gpt-4o pricing
 * }}}
 */
@Stable
object CostEstimator {

  /**
   * Estimate the cost of a completion based on model and token usage.
   *
   * This method looks up the model's pricing information and applies it to
   * the provided usage statistics. It handles:
   * - Standard prompt and completion tokens
   * - Thinking tokens (billed at completion token rate)
   * - Prompt-cache reads and writes, a write with the 1-hour lifetime at its own price
   * - Missing pricing data (returns None)
   *
   * @param model The model identifier (e.g., "gpt-4o", "claude-3-7-sonnet-latest")
   * @param usage Token usage statistics from the completion
   * @return Estimated cost in USD, or None if pricing is unavailable
   */
  def estimate(model: String, usage: TokenUsage)(using service: ModelRegistryService): Option[BigDecimal] =
    estimateFromMetadata(service.lookup(model).toOption, usage)

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
  private[llm4s] def estimateFromMetadata(metadata: Option[ModelMetadata], usage: TokenUsage): Option[BigDecimal] =
    metadata.flatMap { meta =>
      val pricing = meta.pricing
      // a price is a decimal fraction of a dollar: `BigDecimal.decimal` reads it as written, so the sums below are exact
      def price(perToken: Option[Double]): Option[BigDecimal] = perToken.map(BigDecimal.decimal)

      val cachedTokens        = usage.cachedTokens.getOrElse(0)
      val cacheCreationTokens = usage.cacheCreationTokens.getOrElse(0)
      // a write with the 1-hour lifetime has its own price; without one, it is priced as a short write
      val longWrites  = math.min(math.max(0, usage.longCacheCreationTokens.getOrElse(0)), cacheCreationTokens)
      val shortWrites = cacheCreationTokens - longWrites
      def writeCost(cacheCreate: BigDecimal): BigDecimal =
        cacheCreate * shortWrites + price(pricing.cacheCreationInputTokenCostAbove1hr).getOrElse(
          cacheCreate
        ) * longWrites
      val hasCacheBreakdown = usage.cachedTokens.isDefined || usage.cacheCreationTokens.isDefined

      val normalInputTokens =
        math.max(
          0,
          usage.promptTokens -
            cachedTokens -
            cacheCreationTokens
        )

      // the uncached input at the input rate; an output rate must be known too, as `ModelPricing.estimateCost` requires
      def plainInputCost: Option[BigDecimal] =
        for {
          inCost <- price(pricing.inputCostPerToken)
          _      <- pricing.outputCostPerToken
        } yield inCost * normalInputTokens

      def estimateInputCostWithCaching(outputCost: BigDecimal): Option[BigDecimal] =
        if (hasCacheBreakdown) {
          (usage.cachedTokens, usage.cacheCreationTokens) match {
            case (Some(_), Some(_)) =>
              for {
                inCost      <- price(pricing.inputCostPerToken)
                cacheRead   <- price(pricing.cacheReadInputTokenCost)
                cacheCreate <- price(pricing.cacheCreationInputTokenCost)
              } yield (inCost * normalInputTokens) + (cacheRead * cachedTokens) + writeCost(cacheCreate) + outputCost

            case (Some(_), None) =>
              for {
                inCost    <- price(pricing.inputCostPerToken)
                cacheRead <- price(pricing.cacheReadInputTokenCost)
              } yield (inCost * normalInputTokens) + (cacheRead * cachedTokens) + outputCost

            case (None, Some(_)) =>
              for {
                inCost      <- price(pricing.inputCostPerToken)
                cacheCreate <- price(pricing.cacheCreationInputTokenCost)
              } yield (inCost * normalInputTokens) + writeCost(cacheCreate) + outputCost

            case (None, None) =>
              plainInputCost.map(_ + outputCost)
          }
        } else {
          plainInputCost.map(_ + outputCost)
        }

      (usage.thinkingTokens, price(pricing.outputCostPerReasoningToken)) match {
        case (Some(thinkingTokens), Some(reasoningCostPerToken)) =>
          price(pricing.outputCostPerToken).flatMap { outputCostPerToken =>
            // completionTokens never includes thinking: each client takes it out where its provider counted it in
            val effectiveThinkingTokens = math.max(0, thinkingTokens)
            val normalCompletionTokens  = math.max(0, usage.completionTokens)
            val normalOutputCost        = outputCostPerToken * normalCompletionTokens
            val reasoningOutputCost     = reasoningCostPerToken * effectiveThinkingTokens
            val outputCost              = normalOutputCost + reasoningOutputCost
            estimateInputCostWithCaching(outputCost)
          }

        case _ =>
          // Thinking is billed at the output rate: totalOutputTokens is the completion plus thinking
          val effectiveOutputTokens = usage.totalOutputTokens

          val outputCostOpt = price(pricing.outputCostPerToken).map(_ * math.max(0, effectiveOutputTokens))
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
  private[provider] def estimateDirect(inputCost: Double, outputCost: Double, usage: TokenUsage): BigDecimal = {
    val effectiveOutputTokens = usage.totalOutputTokens
    (BigDecimal.decimal(inputCost) * usage.promptTokens) + (BigDecimal.decimal(outputCost) * effectiveOutputTokens)
  }
}
