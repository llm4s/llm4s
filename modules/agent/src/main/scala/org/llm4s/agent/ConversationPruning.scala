package org.llm4s.agent

import org.llm4s.llmconnect.model.{ Message, MessageRole }

/**
 * Context-window pruning of a message list, by a [[ContextWindowConfig]]. Pure: it returns the list to keep.
 * [[AgentThread#pruned]] applies it to a thread's conversation.
 */
private[agent] object ConversationPruning {

  /** Default token counter (rough estimate: words * 1.3). */
  def defaultTokenCounter(message: Message): Int = {
    val words = message.content.split("\\s+").length
    (words * 1.3).toInt
  }

  /** The messages to keep: all of them when no limit is exceeded, otherwise what the strategy keeps. */
  def prune(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int = defaultTokenCounter
  ): Seq[Message] = {
    // Check if pruning is needed
    val needsPruning = (config.maxTokens, config.maxMessages) match {
      case (Some(maxTokens), _) =>
        messages.map(tokenCounter).sum > maxTokens
      case (None, Some(maxMessages)) =>
        messages.length > maxMessages
      case (None, None) =>
        // When no explicit limits are set, AdaptiveWindowing computes its own limit
        config.pruningStrategy match {
          case adaptive: PruningStrategy.AdaptiveWindowing =>
            messages.map(tokenCounter).sum > adaptive.calculateOptimalWindow
          case _ => false
        }
    }

    if (!needsPruning) messages
    else
      config.pruningStrategy match {
        case PruningStrategy.OldestFirst =>
          pruneOldestFirst(messages, config, tokenCounter)
        case PruningStrategy.MiddleOut =>
          pruneMiddleOut(messages, config, tokenCounter)
        case PruningStrategy.RecentTurnsOnly(turns) =>
          pruneRecentTurnsOnly(messages, turns, config)
        case PruningStrategy.Custom(fn) =>
          fn(messages)
        case adaptive: PruningStrategy.AdaptiveWindowing =>
          pruneWithAdaptiveWindowing(messages, adaptive, config, tokenCounter)
      }
  }

  private def pruneOldestFirst(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Separate system message if needed
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Calculate how many messages to keep
    val targetCount = config.maxMessages match {
      case Some(max) => math.max(1, max - systemMsgs.length)
      case None      =>
        // Token-based: iteratively count from the end
        val maxTokens    = config.maxTokens.getOrElse(Int.MaxValue)
        val systemTokens = systemMsgs.map(tokenCounter).sum
        var count        = 0
        var tokens       = 0
        otherMsgs.reverse.takeWhile { msg =>
          val msgTokens = tokenCounter(msg)
          if (tokens + msgTokens + systemTokens <= maxTokens) {
            tokens += msgTokens
            count += 1
            true
          } else {
            false
          }
        }
        math.max(1, count)
    }

    // Keep system messages + recent messages up to limit
    val toKeep = if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.takeRight(targetCount)
    } else {
      messages.takeRight(targetCount)
    }

    toKeep
  }

  private def pruneMiddleOut(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Note: tokenCounter is unused for MiddleOut strategy as it's purely message-count based
    val _           = tokenCounter // Suppress unused warning
    val targetCount = config.maxMessages.getOrElse(messages.length / 2)
    val keepStart   = targetCount / 2
    val keepEnd     = targetCount - keepStart

    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.take(keepStart) ++ otherMsgs.takeRight(keepEnd)
    } else {
      messages.take(keepStart) ++ messages.takeRight(keepEnd)
    }
  }

  private def pruneRecentTurnsOnly(
    messages: Seq[Message],
    turns: Int,
    config: ContextWindowConfig
  ): Seq[Message] = {
    // A turn is a user message + assistant response (+ optional tool messages)
    // Keep the last N complete turns
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Group messages into turns (simplified: every user message starts a turn)
    val turnStarts = otherMsgs.zipWithIndex
      .filter(_._1.role == MessageRole.User)
      .map(_._2)

    val keepFromIndex = if (turnStarts.length > turns) {
      turnStarts(turnStarts.length - turns)
    } else {
      0
    }

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.drop(keepFromIndex)
    } else {
      otherMsgs.drop(keepFromIndex)
    }
  }

  /**
   * Prune using adaptive windowing strategy.
   * Automatically calculates optimal window size based on model context and pricing.
   */
  private def pruneWithAdaptiveWindowing(
    messages: Seq[Message],
    strategy: PruningStrategy.AdaptiveWindowing,
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Calculate optimal window using adaptive strategy
    val optimalTokens = strategy.calculateOptimalWindow

    // Create a modified config with calculated token limit and preserve other settings
    val adaptiveConfig = config.copy(
      maxTokens = Some(optimalTokens),
      maxMessages = None // Use token-based limit, not message count
    )

    // Apply OldestFirst strategy with the calculated window
    val pruned = pruneOldestFirst(messages, adaptiveConfig, tokenCounter)

    // Enforce strategy.preserveMinTurns as a floor: always keep at least that
    // many recent turns (user+assistant pairs) regardless of the token limit.
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)
    val minOtherCount           = math.min(strategy.preserveMinTurns * 2, otherMsgs.length)
    val prunedOtherCount        = pruned.count(_.role != MessageRole.System)

    if (prunedOtherCount >= minOtherCount) {
      pruned
    } else if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.takeRight(minOtherCount)
    } else {
      messages.takeRight(minOtherCount)
    }
  }
}
