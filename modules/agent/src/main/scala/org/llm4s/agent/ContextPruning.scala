package org.llm4s.agent

import org.llm4s.llmconnect.model._

/**
 * Pruning of a message list to a [[ContextWindowConfig]]. Pure: it neither stores nor mutates.
 */
private[agent] object ContextPruning {

  /**
   * The messages to send under `config`: `messages` unchanged when within the limits, otherwise
   * the current turn - from the latest `UserMessage` to the end - kept whole, after the earlier
   * history pruned by the configured strategy. The strategy, a `Custom` one included, sees only
   * that earlier history, with the budget the current turn leaves (`maxMessages` and `maxTokens`
   * less the turn's size, and `AdaptiveWindowing`'s window and minimum turns likewise); what it
   * returns is then repaired so the result is a valid conversation: no tool call is separated from
   * its results ([[keepToolPairs]]), and (after any leading system messages) the result starts with
   * a `UserMessage`. The result may therefore exceed the configured budget when the current turn
   * alone is larger.
   */
  def prune(messages: Seq[Message], config: ContextWindowConfig, tokenCounter: Message => Int): Seq[Message] = {
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

    if (!needsPruning) {
      messages
    } else {
      // the current turn is never pruned: the strategy runs on what precedes it, by position
      val lastUser        = messages.lastIndexWhere(_.role == MessageRole.User)
      val (earlier, turn) = if (lastUser < 0) (messages, Seq.empty) else messages.splitAt(lastUser)
      val turnTokens      = turn.map(tokenCounter).sum
      val earlierConfig = config.copy(
        maxTokens = config.maxTokens.map(t => math.max(0, t - turnTokens)),
        maxMessages = config.maxMessages.map(m => math.max(0, m - turn.length))
      )
      val pruned = config.pruningStrategy match {
        case PruningStrategy.OldestFirst =>
          pruneOldestFirst(earlier, earlierConfig, tokenCounter)
        case PruningStrategy.MiddleOut =>
          pruneMiddleOut(earlier, earlierConfig, tokenCounter)
        case PruningStrategy.RecentTurnsOnly(turns) =>
          pruneRecentTurnsOnly(earlier, math.max(0, turns - (if (turn.isEmpty) 0 else 1)), earlierConfig)
        case PruningStrategy.Custom(fn) =>
          fn(earlier)
        case adaptive: PruningStrategy.AdaptiveWindowing =>
          pruneWithAdaptiveWindowing(earlier, adaptive, earlierConfig, tokenCounter, turnTokens, turn.length)
      }
      val (leadingSystem, others) = (keepToolPairs(pruned) ++ turn).span(_.role == MessageRole.System)
      leadingSystem ++ others.dropWhile(_.role != MessageRole.User)
    }
  }

  /**
   * Drops what pruning orphaned: a `ToolMessage` whose assistant call is gone, and an assistant
   * message with tool calls unless every one of its calls still has a result.
   */
  private[agent] def keepToolPairs(pruned: Seq[Message]): Seq[Message] = {
    val resultIds = pruned.collect { case t: ToolMessage => t.toolCallId }.toSet
    val kept = pruned.filter {
      case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.forall(c => resultIds.contains(c.id))
      case _                                           => true
    }
    val callIds = kept.collect { case a: AssistantMessage => a.toolCalls.map(_.id) }.flatten.toSet
    kept.filter {
      case t: ToolMessage => callIds.contains(t.toolCallId)
      case _              => true
    }
  }

  /**
   * Default token counter (rough estimate: words * 1.3), over everything a provider is sent for
   * the message: an assistant message's tool calls and thinking count as well as its content, since
   * a short answer can carry long arguments or thousands of reasoning tokens.
   * For more accurate counting, integrate with the org.llm4s.context.tokens module.
   */
  private[agent] def defaultTokenCounter(message: Message): Int = {
    def words(text: String): Int = text.split("\\s+").length
    message match {
      case a: AssistantMessage =>
        val textWords = words(a.content) +
          a.toolCalls.map(c => words(c.name) + words(c.arguments.render())).sum +
          a.thinking.collect { case ThinkingBlock.Text(text, _) if text.nonEmpty => words(text) }.sum
        val opaque = a.thinking.collect {
          case ThinkingBlock.Redacted(data)  => org.llm4s.context.ConversationTokenCounter.estimateOpaqueTokens(data)
          case ThinkingBlock.Opaque(_, data) => org.llm4s.context.ConversationTokenCounter.estimateOpaqueTokens(data)
        }.sum
        (textWords * 1.3).toInt + opaque
      case other => (words(other.content) * 1.3).toInt
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

    val keepFromIndex = if (turns <= 0) {
      otherMsgs.length
    } else if (turnStarts.length > turns) {
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
    tokenCounter: Message => Int,
    reservedTokens: Int,
    reservedMessages: Int
  ): Seq[Message] = {
    // Calculate optimal window using adaptive strategy, less what the current turn already takes
    val optimalTokens = math.max(0, strategy.calculateOptimalWindow - reservedTokens)

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
    val minOtherCount    = math.min(math.max(0, strategy.preserveMinTurns * 2 - reservedMessages), otherMsgs.length)
    val prunedOtherCount = pruned.count(_.role != MessageRole.System)

    if (prunedOtherCount >= minOtherCount) {
      pruned
    } else if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.takeRight(minOtherCount)
    } else {
      messages.takeRight(minOtherCount)
    }
  }

}
