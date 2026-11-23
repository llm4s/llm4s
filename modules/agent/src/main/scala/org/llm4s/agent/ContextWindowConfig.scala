package org.llm4s.agent

import org.llm4s.llmconnect.model.Message

/**
 * Configuration for automatic context window management: the limits on what a model call is sent, and
 * how the conversation is pruned to them.
 *
 * Nothing is pruned while the conversation is within every limit. When one is exceeded, in order of
 * precedence:
 *
 *  1. What is sent is a valid conversation. A tool call is never sent without its results, nor a
 *     result without its call, and after any leading system messages it starts with a user message.
 *  1. The last `minRecentTurns` turns are sent whole, whatever the limits and under every strategy.
 *     A turn is a user message and everything up to the next one: the assistant's replies, its tool
 *     calls and their results. The current turn is always one of them, so a value below 1 acts as 1;
 *     a conversation with no more turns than this is sent whole.
 *  1. Everything else sent fits what those turns leave of every limit: `maxTokens` and `maxMessages`
 *     are both limits when both are set, and `AdaptiveWindowing`'s window is one more token limit.
 *     System messages kept by `preserveSystemMessage` are kept first and count against the limits.
 *  1. The strategy chooses what to keep of the history before those turns, within the limits.
 *
 * So what is sent exceeds a limit only when the last `minRecentTurns` turns (with any preserved
 * system messages) do alone: then they are all that is sent, over the limit. Lower `minRecentTurns` -
 * to 1 for the current turn only - where staying within the limit matters more than the recent turns.
 * A [[PruningStrategy.Custom]] function is the one exception to 3: its result is not measured.
 *
 * @param maxTokens Maximum tokens to send, by the middleware's token counter. With `maxMessages` also
 *                  set, both apply.
 * @param maxMessages Maximum number of messages to send. With `maxTokens` also set, both apply.
 * @param preserveSystemMessage Always keep system messages (recommended). `ContextWindowMiddleware`
 *                              keeps the agent's system prompt outside pruning and the limits
 *                              whatever this is.
 * @param minRecentTurns How many of the most recent turns are never pruned, even when they alone
 *                       exceed the limits (default 3, at least 1)
 * @param pruningStrategy Strategy for choosing what to keep of the history before those turns
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
   * Preserves system message (if configured) and the most recent messages that fit every limit.
   */
  case object OldestFirst extends PruningStrategy

  /**
   * Remove messages from the middle, keeping start and end.
   * Useful for preserving both initial context and recent exchanges. What the last `minRecentTurns`
   * turns leave of the limits is shared between the start of the history before them and its end, a
   * message from each in turn (the end first) while it fits every limit.
   */
  case object MiddleOut extends PruningStrategy

  /**
   * Keep only the most recent N complete turns (a user message and everything up to the next one),
   * the current turn included. Drops everything older than the specified number of turns.
   *
   * The turn count is a limit of its own: it prunes with no `maxTokens` or `maxMessages` set. With one
   * set, fewer turns are kept when `turns` of them do not fit; `minRecentTurns` turns are kept when
   * that is more than `turns`.
   *
   * @param turns Number of recent turns to keep
   */
  case class RecentTurnsOnly(turns: Int) extends PruningStrategy

  /**
   * Custom pruning function.
   * Called when a limit is exceeded. Receives the history before the last `minRecentTurns` turns
   * (which are never pruned and are appended afterwards; with `minRecentTurns = 1`, the history before
   * the current turn) and returns the messages to send in its place - a subset, or copies it has
   * changed. The result is then repaired: a tool call left without its results, or a result without
   * its call, is dropped, and the history starts with a user message. It is not measured against
   * `maxTokens` or `maxMessages`: keeping within them is the function's own business.
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
   * The window is a token limit beside `ContextWindowConfig.maxTokens` (the lower applies) and
   * `maxMessages`; within it the oldest messages go first. The recent turns kept past the window are
   * `ContextWindowConfig.minRecentTurns`, as for every strategy.
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
  case class AdaptiveWindowing(
    contextWindowSize: Int,
    inputCostPerToken: Option[Double] = None,
    outputCostPerToken: Option[Double] = None,
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
