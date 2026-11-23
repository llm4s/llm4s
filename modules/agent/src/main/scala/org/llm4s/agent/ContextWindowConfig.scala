package org.llm4s.agent

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.Message

/**
 * Configuration for automatic context window management.
 *
 * Provides flexible strategies for pruning conversation history
 * to stay within token or message count limits.
 *
 * `maxTokens` and `maxMessages` are both limits when both are set: the history is pruned when either
 * is exceeded, and what is kept satisfies both.
 *
 * @param maxTokens Maximum number of tokens to send, by the middleware's token counter
 * @param maxMessages Maximum number of messages to send
 * @param preserveSystemMessage Always keep the system message (recommended)
 * @param minRecentTurns The most recent turns that are never pruned, even past a limit: a turn runs
 *                       from a user message to the next. The current turn is always kept, so a
 *                       value below 1 acts as 1, the default. A higher value keeps more of the
 *                       conversation coherent, and can send more than the limits allow.
 * @param pruningStrategy Strategy for pruning the history before those turns when a limit is exceeded
 */
@Stable
final case class ContextWindowConfig private (
  maxTokens: Option[Int],
  maxMessages: Option[Int],
  preserveSystemMessage: Boolean,
  minRecentTurns: Int,
  pruningStrategy: PruningStrategy
) {
  def withMaxTokens(value: Int): ContextWindowConfig                   = copy(maxTokens = Some(value))
  def withMaxTokens(value: Option[Int]): ContextWindowConfig           = copy(maxTokens = value)
  def withMaxMessages(value: Int): ContextWindowConfig                 = copy(maxMessages = Some(value))
  def withMaxMessages(value: Option[Int]): ContextWindowConfig         = copy(maxMessages = value)
  def withPreserveSystemMessage(value: Boolean): ContextWindowConfig   = copy(preserveSystemMessage = value)
  def withMinRecentTurns(value: Int): ContextWindowConfig              = copy(minRecentTurns = value)
  def withPruningStrategy(value: PruningStrategy): ContextWindowConfig = copy(pruningStrategy = value)
}

object ContextWindowConfig {
  def apply(
    maxTokens: Option[Int] = None,
    maxMessages: Option[Int] = None,
    preserveSystemMessage: Boolean = true,
    minRecentTurns: Int = 1,
    pruningStrategy: PruningStrategy = PruningStrategy.OldestFirst
  ): ContextWindowConfig =
    new ContextWindowConfig(maxTokens, maxMessages, preserveSystemMessage, minRecentTurns, pruningStrategy)
}

/**
 * Strategies for pruning conversation history when context limits are exceeded.
 */
@Stable
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
   * Keep only the most recent N turns, each from its user message with the replies, tool calls and
   * results that follow. The turn count is a limit of its own: a longer history is pruned whether
   * or not `maxTokens` or `maxMessages` is set. When one is set and those turns exceed it, the
   * oldest of them are dropped until they fit. `ContextWindowConfig.minRecentTurns` turns are
   * always kept, so a lower `turns` acts as that.
   *
   * @param turns Number of recent turns to keep, the current one included
   */
  case class RecentTurnsOnly(turns: Int) extends PruningStrategy

  /**
   * Custom pruning function.
   * Receives the history before the protected turns (the last `minRecentTurns` turns, the current one
   * included, which are never pruned and are appended afterwards) and returns the messages to send
   * in its place - a subset, or copies it has changed. The result is then repaired: a tool call left without its results, or a
   * result without its call, is dropped, and the history starts with a user message.
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
   * - Can optionally use cost-sensitive tuning
   *
   * The window is a token limit like `maxTokens`: with both set, the smaller applies, and `maxMessages`
   * applies as well. The turns kept past the window are `ContextWindowConfig.minRecentTurns`.
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
   * @param costSensitivity How aggressively to optimize for cost (0.0-1.0, default: 0.5)
   *                        0.0 = maximize quality, 1.0 = minimize cost
   */
  final case class AdaptiveWindowing private (
    contextWindowSize: Int,
    inputCostPerToken: Option[Double],
    outputCostPerToken: Option[Double],
    costSensitivity: Double
  ) extends PruningStrategy {
    def withContextWindowSize(value: Int): AdaptiveWindowing             = copy(contextWindowSize = value)
    def withInputCostPerToken(value: Double): AdaptiveWindowing          = copy(inputCostPerToken = Some(value))
    def withInputCostPerToken(value: Option[Double]): AdaptiveWindowing  = copy(inputCostPerToken = value)
    def withOutputCostPerToken(value: Double): AdaptiveWindowing         = copy(outputCostPerToken = Some(value))
    def withOutputCostPerToken(value: Option[Double]): AdaptiveWindowing = copy(outputCostPerToken = value)
    def withCostSensitivity(value: Double): AdaptiveWindowing            = copy(costSensitivity = value)

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

  object AdaptiveWindowing {
    def apply(
      contextWindowSize: Int,
      inputCostPerToken: Option[Double] = None,
      outputCostPerToken: Option[Double] = None,
      costSensitivity: Double = 0.5
    ): AdaptiveWindowing =
      new AdaptiveWindowing(contextWindowSize, inputCostPerToken, outputCostPerToken, costSensitivity)
  }

}
