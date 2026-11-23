package org.llm4s.agent

import org.llm4s.llmconnect.model._

/**
 * Pruning of a message list to a [[ContextWindowConfig]]. Pure: it neither stores nor mutates.
 */
private[agent] object ContextPruning {

  /**
   * The messages to send under `config`: `messages` unchanged when within every limit, otherwise
   * pruned. See [[ContextWindowConfig]] for what is guaranteed; in order of precedence:
   *
   *  1. The result is a valid conversation: no tool call is separated from its results
   *     ([[keepToolPairs]]), and (after any leading system messages) it starts with a `UserMessage`.
   *  1. The protected turns - the last `minRecentTurns` user turns, never fewer than the current one -
   *     are kept whole, whatever the limits.
   *  1. Everything else kept fits what the protected turns leave of every limit: `maxTokens`,
   *     `maxMessages` and, under `AdaptiveWindowing`, its window. Preserved system messages are kept
   *     first and count against it.
   *  1. The strategy chooses what to keep within that. It sees only the history before the protected
   *     turns. A `Custom` function's result is repaired (1) but not measured against the limits.
   *
   * So the result exceeds a limit only when the protected turns (and preserved system messages) do
   * alone, or a `Custom` function returns more.
   */
  def prune(messages: Seq[Message], config: ContextWindowConfig, tokenCounter: Message => Int): Seq[Message] = {
    val tokenLimit = config.pruningStrategy match {
      case adaptive: PruningStrategy.AdaptiveWindowing =>
        Some(config.maxTokens.fold(adaptive.calculateOptimalWindow)(math.min(_, adaptive.calculateOptimalWindow)))
      case _ => config.maxTokens
    }
    val floorTurns = math.max(1, config.minRecentTurns)
    val all        = messages.toIndexedSeq
    val userAt     = all.indices.filter(i => all(i).role == MessageRole.User)
    // RecentTurnsOnly's turn count is a limit of its own, as AdaptiveWindowing's window is
    val turnLimit = config.pruningStrategy match {
      case PruningStrategy.RecentTurnsOnly(turns) => Some(math.max(turns, floorTurns))
      case _                                      => None
    }

    val needsPruning =
      config.maxMessages.exists(messages.length > _) ||
        turnLimit.exists(userAt.length > _) ||
        tokenLimit.exists(messages.map(tokenCounter).sum > _)

    if (!needsPruning) {
      messages
    } else {
      // the protected turns are never pruned: the strategy runs on what precedes them, by position
      val protectedFrom     = if (userAt.isEmpty) all.length else userAt(math.max(0, userAt.length - floorTurns))
      val (earlier, recent) = all.splitAt(protectedFrom)
      val budget = Budget(
        tokens = tokenLimit.map(t => math.max(0, t - recent.map(tokenCounter).sum)),
        messages = config.maxMessages.map(m => math.max(0, m - recent.length))
      )
      val (pinned, prunable) =
        if (config.preserveSystemMessage) earlier.partition(_.role == MessageRole.System) else (Seq.empty, earlier)
      val room = new Room(budget, tokenCounter)
      pinned.foreach(room.charge)

      val pruned = config.pruningStrategy match {
        case PruningStrategy.OldestFirst | _: PruningStrategy.AdaptiveWindowing =>
          pinned ++ newestWithin(prunable, room)
        case PruningStrategy.MiddleOut =>
          pinned ++ endsWithin(prunable, room)
        case PruningStrategy.RecentTurnsOnly(turns) =>
          val starts = prunable.indices.filter(i => prunable(i).role == MessageRole.User)
          val wanted = math.max(0, turns - math.min(floorTurns, userAt.length))
          val from =
            if (wanted == 0) prunable.length else if (starts.length > wanted) starts(starts.length - wanted) else 0
          pinned ++ newestWithin(prunable.drop(from), room)
        case PruningStrategy.Custom(fn) =>
          fn(earlier)
      }
      val (leadingSystem, others) = (keepToolPairs(pruned) ++ recent).span(_.role == MessageRole.System)
      leadingSystem ++ others.dropWhile(_.role != MessageRole.User)
    }
  }

  /** What may still be kept of the history before the protected turns; `None` is no limit. */
  final private case class Budget(tokens: Option[Int], messages: Option[Int])

  /** The room left in a [[Budget]] as messages are kept. Tokens are counted only under a token limit. */
  final private class Room(budget: Budget, tokenCounter: Message => Int) {
    private var tokensLeft   = budget.tokens.map(_.toLong)
    private var messagesLeft = budget.messages

    /** Counts a message that is kept whether or not it fits. */
    def charge(message: Message): Unit = {
      tokensLeft = tokensLeft.map(_ - tokenCounter(message))
      messagesLeft = messagesLeft.map(_ - 1)
    }

    /** Counts `message` if it fits every limit; false, counting nothing, if it does not. */
    def take(message: Message): Boolean =
      if (messagesLeft.exists(_ < 1)) false
      else {
        val cost = if (tokensLeft.isDefined) tokenCounter(message).toLong else 0L
        if (tokensLeft.exists(_ < cost)) false
        else {
          tokensLeft = tokensLeft.map(_ - cost)
          messagesLeft = messagesLeft.map(_ - 1)
          true
        }
      }
  }

  /** The longest run of the newest `messages` that fits `room`. */
  private def newestWithin(messages: Seq[Message], room: Room): Seq[Message] = {
    val indexed = messages.toIndexedSeq
    var from    = indexed.length
    while (from > 0 && room.take(indexed(from - 1))) from -= 1
    indexed.drop(from)
  }

  /**
   * The start and the end of `messages` that fit `room`, taken in turn from the end and the start,
   * the end first; once the next message of one end does not fit, the other end takes what is left.
   */
  private def endsWithin(messages: Seq[Message], room: Room): Seq[Message] = {
    val indexed  = messages.toIndexedSeq
    var head     = 0
    var tail     = indexed.length
    var headOpen = true
    var tailOpen = true
    var fromTail = true
    while (head < tail && (headOpen || tailOpen)) {
      if (tailOpen && (fromTail || !headOpen)) {
        if (room.take(indexed(tail - 1))) tail -= 1 else tailOpen = false
      } else {
        if (room.take(indexed(head))) head += 1 else headOpen = false
      }
      fromTail = !fromTail
    }
    indexed.take(head) ++ indexed.drop(tail)
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
    tokenizerFrom(org.llm4s.context.ConversationTokenCounter.openAI())

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

}
