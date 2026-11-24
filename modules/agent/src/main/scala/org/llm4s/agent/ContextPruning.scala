package org.llm4s.agent

import org.llm4s.llmconnect.model._

/**
 * Pruning of a message list to a [[ContextWindowConfig]]. Pure: it neither stores nor mutates.
 */
private[agent] object ContextPruning {

  /** What the history before the protected turns may still take: `None` is no limit of that kind. */
  final private case class Budget(tokens: Option[Long], messages: Option[Int]) {
    def fits(count: Int, tokenTotal: Long): Boolean = messages.forall(count <= _) && tokens.forall(tokenTotal <= _)
    def less(count: Int, tokenTotal: Long): Budget  = Budget(tokens.map(_ - tokenTotal), messages.map(_ - count))
  }

  /**
   * The messages to send under `config`: `messages` unchanged when within the limits, otherwise the
   * protected turns - the last `minRecentTurns` turns, each from its `UserMessage`, and always the
   * current one - kept whole, after the earlier history pruned by the configured strategy.
   *
   * `maxTokens` and `maxMessages` are both limits: pruning starts when either is exceeded, and what
   * a strategy keeps of the earlier history satisfies both. `AdaptiveWindowing`'s window is one more
   * token limit, so with `maxTokens` set the smaller of the two applies. The strategy, a `Custom` one
   * included, sees only the earlier history, with the budget the protected turns leave; what it
   * returns is then repaired so the result is a valid conversation: no tool call is separated from
   * its results ([[keepToolPairs]]), and (after any leading system messages) the result starts with
   * a `UserMessage`. `RecentTurnsOnly`'s turn count is a limit too, applied whether or not the other
   * two are set. The result exceeds the limits only when the protected turns alone do, or under a
   * `Custom` strategy, which chooses what to keep without reading them.
   */
  def prune(messages: Seq[Message], config: ContextWindowConfig, tokenCounter: Message => Int): Seq[Message] = {
    val adaptiveWindow = config.pruningStrategy match {
      case adaptive: PruningStrategy.AdaptiveWindowing => Some(adaptive.calculateOptimalWindow)
      case _                                           => None
    }
    val tokenLimit = (config.maxTokens.toSeq ++ adaptiveWindow).minOption
    // a message is counted only under a token limit: the counter encodes the whole message
    def tokens(ms: Seq[Message]): Long = if (tokenLimit.isEmpty) 0L else ms.foldLeft(0L)(_ + tokenCounter(_))
    val budget                         = Budget(tokenLimit.map(_.toLong), config.maxMessages)

    val userAt = messages.indices.filter(i => messages(i).role == MessageRole.User)
    // `RecentTurnsOnly`'s turn count is a limit of its own, with or without the other two
    val withinTurns = config.pruningStrategy match {
      case PruningStrategy.RecentTurnsOnly(n) => userAt.length <= n
      case _                                  => true
    }

    if (withinTurns && budget.fits(messages.length, tokens(messages))) {
      messages
    } else {
      // the last `minRecentTurns` turns are never pruned: the strategy runs on what precedes them, by position
      val turns   = math.min(math.max(1, config.minRecentTurns), userAt.length)
      val cut     = if (userAt.isEmpty) messages.length else userAt(userAt.length - turns)
      val earlier = messages.take(cut)
      val kept    = messages.drop(cut)
      val left    = budget.less(kept.length, tokens(kept))
      val pruned = config.pruningStrategy match {
        case PruningStrategy.OldestFirst | _: PruningStrategy.AdaptiveWindowing =>
          pruneOldestFirst(earlier, left, config.preserveSystemMessage, tokenCounter)
        case PruningStrategy.MiddleOut =>
          pruneMiddleOut(earlier, left, config.preserveSystemMessage, tokenCounter)
        case PruningStrategy.RecentTurnsOnly(n) =>
          // at most `n` turns, the protected ones included, and of those the most recent that fit the limits
          val recent = pruneRecentTurnsOnly(earlier, math.max(0, n - turns), config.preserveSystemMessage)
          pruneOldestFirst(recent, left, config.preserveSystemMessage, tokenCounter)
        case PruningStrategy.Custom(fn) =>
          fn(earlier)
      }
      val (leadingSystem, others) = (keepToolPairs(pruned) ++ kept).span(_.role == MessageRole.System)
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

  /** The `cl100k_base` counter from core, made once; `None` only if jtokkit cannot load it. */
  private lazy val tokenizerCounter: Option[org.llm4s.context.ConversationTokenCounter] =
    tokenizerFrom(org.llm4s.context.ConversationTokenCounter.cl100k())

  /** The loaded counter, or `None` with a warning when loading failed, so pruning counts words instead. */
  private[agent] def tokenizerFrom(
    loaded: org.llm4s.types.Result[org.llm4s.context.ConversationTokenCounter]
  ): Option[org.llm4s.context.ConversationTokenCounter] =
    loaded match {
      case Right(counter) => Some(counter)
      case Left(error) =>
        org.slf4j.LoggerFactory
          .getLogger(getClass)
          .warn(s"cl100k_base tokenizer unavailable, pruning counts words instead: ${error.message}")
        None
    }

  /**
   * Default token counter: `ConversationTokenCounter`'s `cl100k_base` count of everything a provider
   * is sent for the message (content, tool calls and their arguments, thinking, media parts, and
   * the message framing). A word count undercounts text without spaces, such as a minified JSON
   * tool result or a base64 blob, which it read as a single word, so `maxTokens` let it through.
   * `cl100k_base` is an approximation for non-OpenAI models; pass your own counter to
   * `ContextWindowMiddleware` for an exact one. Falls back to [[wordEstimate]] if jtokkit's
   * registry has no `cl100k_base` (it ships with jtokkit, so this is a guard, not a path in use).
   */
  private[agent] def defaultTokenCounter(message: Message): Int = countWith(tokenizerCounter)(message)

  /** Counts with `tokenizer`, or by [[wordEstimate]] when there is none. */
  private[agent] def countWith(tokenizer: Option[org.llm4s.context.ConversationTokenCounter])(message: Message): Int =
    tokenizer.fold(wordEstimate(message)) { counter =>
      if (hasInlineBytes(message)) counter.countMessage(message)
      else
        Option(counted.get(message)) match {
          case Some(n) => n.intValue
          case None =>
            val n = counter.countMessage(message)
            counted.put(message, Integer.valueOf(n))
            n
        }
    }

  /**
   * Counts already made, by message. Every model call prunes the whole history, so without this each call encodes
   * every earlier message again. Equal messages count the same, and an entry goes when its message is no longer
   * referenced. A message with inline media bytes is not cached: its hash reads every byte, while its count reads only
   * their length.
   */
  private val counted: java.util.Map[Message, Integer] =
    java.util.Collections.synchronizedMap(new java.util.WeakHashMap[Message, Integer]())

  private def hasInlineBytes(message: Message): Boolean = {
    def anyInline(parts: Vector[ContentPart]): Boolean = parts.exists {
      case i: ContentPart.Image    => i.source.isInstanceOf[org.llm4s.media.MediaSource.Inline]
      case d: ContentPart.Document => d.source.isInstanceOf[org.llm4s.media.MediaSource.Inline]
      case _                       => false
    }
    message match {
      case u: UserMessage                         => anyInline(u.parts)
      case t: ToolMessage                         => anyInline(t.parts)
      case _: SystemMessage | _: AssistantMessage => false // no parts; a new message type must be added here
    }
  }

  /** Words times 1.3: the fallback when no tokenizer is available. */
  private[agent] def wordEstimate(message: Message): Int = {
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
      case u: UserMessage => (words(u.content) * 1.3).toInt + u.parts.map(ContentPart.estimatedTokens).sum
      case t: ToolMessage => (words(t.content) * 1.3).toInt + t.parts.map(ContentPart.estimatedTokens).sum
      case other          => (words(other.content) * 1.3).toInt
    }
  }

  /**
   * System messages (kept, and counted, when `preserveSystem`) apart from the messages a strategy
   * chooses among, with the budget those have.
   */
  private def pool(
    messages: Seq[Message],
    budget: Budget,
    preserveSystem: Boolean,
    tokenCounter: Message => Int
  ): (Seq[Message], Vector[Message], Budget) =
    if (preserveSystem) {
      val (system, others) = messages.partition(_.role == MessageRole.System)
      val systemTokens     = if (budget.tokens.isEmpty) 0L else system.foldLeft(0L)(_ + tokenCounter(_))
      (system, others.toVector, budget.less(system.length, systemTokens))
    } else (Seq.empty, messages.toVector, budget)

  /** The most recent messages that fit `budget`, both its limits. */
  private def pruneOldestFirst(
    messages: Seq[Message],
    budget: Budget,
    preserveSystem: Boolean,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    val (system, others, left) = pool(messages, budget, preserveSystem, tokenCounter)
    var from                   = others.length
    var total                  = 0L
    var fitting                = true
    while (fitting && from > 0) {
      val next = total + (if (left.tokens.isEmpty) 0L else tokenCounter(others(from - 1)).toLong)
      if (left.fits(others.length - from + 1, next)) {
        from -= 1
        total = next
      } else fitting = false
    }
    system ++ others.drop(from)
  }

  /**
   * The start and the end of the history, as much as fits `budget`: messages are taken from the end
   * and the start in turn, the end first, until the next one would break either limit.
   */
  private def pruneMiddleOut(
    messages: Seq[Message],
    budget: Budget,
    preserveSystem: Boolean,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    val (system, others, left) = pool(messages, budget, preserveSystem, tokenCounter)
    var head                   = 0             // others.take(head) is kept
    var tail                   = others.length // others.drop(tail) is kept
    var total                  = 0L
    var fromEnd                = true
    var fitting                = true
    while (fitting && head < tail) {
      val candidate = if (fromEnd) others(tail - 1) else others(head)
      val next      = total + (if (left.tokens.isEmpty) 0L else tokenCounter(candidate).toLong)
      if (left.fits(head + others.length - tail + 1, next)) {
        if (fromEnd) tail -= 1 else head += 1
        total = next
        fromEnd = !fromEnd
      } else fitting = false
    }
    system ++ others.take(head) ++ others.drop(tail)
  }

  private def pruneRecentTurnsOnly(messages: Seq[Message], turns: Int, preserveSystem: Boolean): Seq[Message] = {
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

    if (preserveSystem) systemMsgs ++ otherMsgs.drop(keepFromIndex) else otherMsgs.drop(keepFromIndex)
  }

}
