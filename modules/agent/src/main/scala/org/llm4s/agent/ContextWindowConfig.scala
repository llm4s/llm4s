package org.llm4s.agent

import org.llm4s.llmconnect.model.Message

/**
 * Configuration for automatic context window management.
 *
 * Provides flexible strategies for pruning conversation history
 * to stay within token or message count limits.
 *
 * @param maxTokens Maximum number of tokens to keep (if specified, requires tokenCounter)
 * @param maxMessages Maximum number of messages to keep (simpler alternative to token-based)
 * @param preserveSystemMessage Always keep the system message (recommended)
 * @param minRecentTurns Minimum number of recent turns to preserve (even if limit exceeded)
 * @param pruningStrategy Strategy for pruning messages when limits are exceeded
 */
case class ContextWindowConfig(
  maxTokens: Option[Int] = None,
  maxMessages: Option[Int] = None,
  preserveSystemMessage: Boolean = true,
  minRecentTurns: Int = 3,
  pruningStrategy: PruningStrategy = PruningStrategy.OldestFirst
)

/**
 * Strategies for pruning conversation history when context limits are exceeded.
 */
sealed trait PruningStrategy

object PruningStrategy {

  /**
   * Remove oldest messages first (FIFO).
   * Preserves system message (if configured) and most recent messages.
   */
  case object OldestFirst extends PruningStrategy

  /**
   * Remove messages from the middle, keeping start and end.
   * Useful for preserving both initial context and recent exchanges.
   */
  case object MiddleOut extends PruningStrategy

  /**
   * Keep only the most recent N complete turns (user+assistant pairs).
   * Drops everything older than the specified number of turns.
   *
   * @param turns Number of recent turns to keep
   */
  case class RecentTurnsOnly(turns: Int) extends PruningStrategy

  /**
   * Custom pruning function.
   * Receives all messages and returns the subset to keep.
   * The function should be pure (no side effects) and deterministic.
   *
   * @param fn Function that takes messages and returns pruned messages
   */
  case class Custom(fn: Seq[Message] => Seq[Message]) extends PruningStrategy

  /**
   * Adaptive windowing strategy that automatically determines the best context window size
   * based on the model's context size and pricing information.
   *
   * This strategy optimizes for cost-quality balance:
   * - Uses tier-appropriate percentage of model's context window (reserves space for outputs)
   * - Adjusts ratio based on model size (larger models use larger windows)
   * - Preserves minimum recent turns for conversation coherence
   * - Can optionally use cost-sensitive tuning
   *
   * Model tiers and window ratios:
   * - Small (≤32K): 60% window to reduce costs
   * - Medium (32K-100K): 70% balanced cost/quality
   * - Large (100K-200K): 75% for more complex context
   * - Extra large (>200K): 80% very generous window
   *
   * @param contextWindowSize The model's actual context window in tokens
   * @param inputCostPerToken Optional cost per input token (enables cost optimization)
   * @param outputCostPerToken Optional cost per output token
   * @param preserveMinTurns Minimum number of recent turns to always preserve (default: 3)
   * @param costSensitivity How aggressively to optimize for cost (0.0-1.0, default: 0.5)
   *                        0.0 = maximize quality, 1.0 = minimize cost
   */
  case class AdaptiveWindowing(
    contextWindowSize: Int,
    inputCostPerToken: Option[Double] = None,
    outputCostPerToken: Option[Double] = None,
    preserveMinTurns: Int = 3,
    costSensitivity: Double = 0.5
  ) extends PruningStrategy {
    require(contextWindowSize > 0, "Context window size must be positive")
    require(costSensitivity >= 0.0 && costSensitivity <= 1.0, "Cost sensitivity must be between 0.0 and 1.0")

    /**
     * Calculate the optimal token window based on model capabilities and costs.
     * Returns the safe upper bound for conversation tokens.
     */
    def calculateOptimalWindow: Int = {
      // Model tier-based ratio: percentage of context window safe to use
      // (reserves space for model outputs and safety margin)
      val tierRatio = contextWindowSize match {
        case size if size <= 32_000  => 0.6  // Small (≤32K): conservative window to reduce costs
        case size if size <= 100_000 => 0.7  // Medium (32K-100K): balanced cost/quality
        case size if size <= 200_000 => 0.75 // Large (100K-200K): more generous window
        case _                       => 0.8  // Extra large (>200K): very generous window
      }

      // Cost-sensitive adjustment
      val costAdjustment = (inputCostPerToken, outputCostPerToken) match {
        case (Some(inCost), Some(outCost)) if inCost > 0 && outCost > 0 =>
          val costRatio = inCost / (inCost + outCost)
          // If input is expensive relative to output, reduce window more aggressively
          // costRatio close to 1.0 = input is expensive, reduce more
          // costRatio close to 0.0 = output is expensive, reduce less
          val reduction = costRatio * costSensitivity * 0.2 // Up to 20% reduction
          1.0 - reduction
        case _ => 1.0 // No cost data, no adjustment
      }

      val optimalSize = (contextWindowSize * tierRatio * costAdjustment).toInt
      math.max(1000, optimalSize) // Minimum 1K tokens
    }

    /**
     * Get a human-readable explanation of the calculated window.
     */
    def explanation: String = {
      val optimal = calculateOptimalWindow
      val used    = (optimal.toDouble / contextWindowSize * 100).toInt
      val tierName = contextWindowSize match {
        case size if size <= 32_000  => "small"
        case size if size <= 100_000 => "medium"
        case _                       => "large"
      }
      s"AdaptiveWindowing: using ${optimal} tokens ($used% of ${contextWindowSize / 1000}K $tierName model)"
    }

    override def toString: String = explanation
  }
}
