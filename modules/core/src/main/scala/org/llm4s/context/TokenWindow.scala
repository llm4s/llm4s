package org.llm4s.context

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ContextError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ HeadroomPercent, Result }
import org.slf4j.LoggerFactory

/**
 * Manages conversation token windows by trimming conversations to fit within token budgets.
 *
 * Trimming pins the conversation's leading system messages (its system prompt and any `[HISTORY_SUMMARY]`
 * digests), then keeps the newest turns that fit, dropping older ones whole: an assistant message with tool calls
 * is kept or dropped with the tool results that follow it, so the window never starts at a result whose call is
 * gone. A system message after the first non-system one is history like any other. Headroom is applied to the
 * budget for safety.
 */
@Experimental
object TokenWindow {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Trim a conversation to fit within the specified token budget.
   * Pins the leading system and digest messages, drops older turns whole, and applies headroom for safety.
   *
   * @param conversation The conversation to trim
   * @param tokenCounter Token counter for the specific model
   * @param budget Maximum token budget
   * @param headroom Safety margin (default 8%)
   * @return Trimmed conversation that fits within budget, or a [[org.llm4s.error.ContextError]] when the pinned
   *         messages alone do not fit, or nothing but them would
   */
  def trimToBudget(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: Int,
    headroom: HeadroomPercent = HeadroomPercent.Standard
  ): Result[ConversationWindow] =
    for {
      _               <- validateInputs(conversation, budget)
      currentTokens   <- Right(tokenCounter.countConversation(conversation))
      effectiveBudget <- Right(calculateEffectiveBudget(budget, headroom))
      result          <- performTrimming(conversation, tokenCounter, currentTokens, effectiveBudget, budget)
    } yield result

  /**
   * Check if a conversation fits within the budget (including headroom)
   */
  def fitsInBudget(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: Int,
    headroom: HeadroomPercent = HeadroomPercent.Standard
  ): Boolean = {
    val currentTokens   = tokenCounter.countConversation(conversation)
    val effectiveBudget = calculateEffectiveBudget(budget, headroom)
    currentTokens <= effectiveBudget
  }

  /**
   * Get current token usage information for a conversation
   */
  def getUsageInfo(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: Int
  ): TokenUsageInfo = {
    val currentTokens  = tokenCounter.countConversation(conversation)
    val withinBudget   = currentTokens <= budget
    val utilizationPct = if (budget > 0) (currentTokens.toDouble / budget * 100).round.toInt else 0

    TokenUsageInfo(currentTokens, budget, withinBudget, utilizationPct)
  }

  private def validateInputs(
    conversation: Conversation,
    budget: Int
  ): Result[Unit] =
    if (budget <= 0) {
      Left(ValidationError("Token budget must be positive", "budget"))
    } else if (conversation.messages.isEmpty) {
      Left(ValidationError("Cannot trim empty conversation", "conversation"))
    } else {
      // System messages are injected at send-time, conversations contain only actual history
      Right(())
    }

  private def calculateEffectiveBudget(budget: Int, headroom: HeadroomPercent): Int = {
    val effectiveBudget = (budget * (1.0 - headroom.asRatio)).toInt
    logger.debug(
      s"Effective budget: $effectiveBudget tokens ($headroom headroom applied to $budget)"
    )
    effectiveBudget
  }

  private def performTrimming(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    currentTokens: Int,   // current size of the conversation in tokens before trimming
    effectiveBudget: Int, // usable portion of the budget after applying headroom
    originalBudget: Int   // hard context budget we aim for
  ): Result[ConversationWindow] =
    currentTokens match {
      case tokens if tokens <= effectiveBudget =>
        logger.debug(s"Conversation fits in effective budget: $tokens <= $effectiveBudget tokens")
        Right(
          ConversationWindow.noTrimming(
            conversation,
            getUsageInfo(conversation, tokenCounter, originalBudget)
          )
        )

      case _ =>
        logger.info(s"Trimming conversation: $currentTokens > $effectiveBudget tokens (effective budget)")
        trimToEffectiveBudget(conversation, tokenCounter, effectiveBudget, originalBudget)
    }

  private def trimToEffectiveBudget(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    effectiveBudget: Int,
    originalBudget: Int
  ): Result[ConversationWindow] = {
    val allMessages = conversation.messages

    // The leading system messages - the system prompt and the [HISTORY_SUMMARY] digests - are pinned.
    val (pinned, regularMessages) = allMessages.span {
      case _: SystemMessage => true
      case _                => false
    }
    val pinnedTokens = pinned.map(tokenCounter.countMessage).sum

    val recentMessages =
      findRecentTurnsThatFit(
        turns(regularMessages).reverse,
        tokenCounter,
        effectiveBudget - pinnedTokens
      ).reverse.flatten

    if (pinnedTokens > effectiveBudget)
      Left(ContextError.tokenBudgetExceeded(pinnedTokens, effectiveBudget))
    else if (regularMessages.nonEmpty && recentMessages.isEmpty)
      Left(
        ContextError.invalidTrimming(
          s"the newest turn does not fit beside the ${pinned.size} pinned message(s) in $effectiveBudget tokens"
        )
      )
    else {
      val finalMessages     = pinned ++ recentMessages
      val finalConversation = Conversation(finalMessages)
      val removedCount      = allMessages.length - finalMessages.length

      logger.info(
        s"Trimmed to ${finalMessages.length} messages (removed $removedCount messages, ${pinned.size} pinned)"
      )

      Right(
        ConversationWindow.trimmed(
          finalConversation,
          getUsageInfo(finalConversation, tokenCounter, originalBudget),
          removedCount
        )
      )
    }
  }

  /**
   * `messages` in the units trimming keeps or drops whole: an assistant message with tool calls together with the
   * tool results that follow it, and every other message on its own.
   */
  private def turns(messages: Seq[Message]): Seq[Seq[Message]] =
    messages.foldLeft(Vector.empty[Vector[Message]]) {
      case (acc, tool: ToolMessage) if acc.lastOption.exists(_.headOption.exists(opensToolCalls)) =>
        acc.init :+ (acc.last :+ tool)
      case (acc, message) => acc :+ Vector(message)
    }

  private def opensToolCalls(message: Message): Boolean = message match {
    case a: AssistantMessage => a.toolCalls.nonEmpty
    case _                   => false
  }

  private def findRecentTurnsThatFit(
    turnsNewestFirst: Seq[Seq[Message]],
    tokenCounter: ConversationTokenCounter,
    availableBudget: Int
  ): Seq[Seq[Message]] = {
    @scala.annotation.tailrec
    def loop(remaining: Seq[Seq[Message]], accumulated: Vector[Seq[Message]], tokensUsed: Int): Seq[Seq[Message]] =
      remaining match {
        case Seq() => accumulated
        case turn +: tail =>
          val newTotal = tokensUsed + turn.map(tokenCounter.countMessage).sum
          if (newTotal <= availableBudget) loop(tail, accumulated :+ turn, newTotal)
          else {
            logger.debug(s"Stopping at ${accumulated.length} turns, the next would exceed the available budget")
            accumulated
          }
      }

    loop(turnsNewestFirst, Vector.empty, 0)
  }
}

/**
 * Result of token window processing
 */
@Experimental
final case class ConversationWindow private (
  conversation: Conversation,
  usage: TokenUsageInfo,
  wasTrimmed: Boolean,
  removedMessageCount: Int
) {

  /** Sets the conversation after trimming. */
  def withConversation(conversation: Conversation): ConversationWindow = copy(conversation = conversation)

  /** Sets the token usage of the trimmed conversation. */
  def withUsage(usage: TokenUsageInfo): ConversationWindow = copy(usage = usage)

  /** Sets whether any messages were removed. */
  def withWasTrimmed(wasTrimmed: Boolean): ConversationWindow = copy(wasTrimmed = wasTrimmed)

  /** Sets how many messages were removed. */
  def withRemovedMessageCount(removedMessageCount: Int): ConversationWindow =
    copy(removedMessageCount = removedMessageCount)
}

@Experimental
object ConversationWindow {

  /** Creates a [[ConversationWindow]]. */
  def apply(
    conversation: Conversation,
    usage: TokenUsageInfo,
    wasTrimmed: Boolean,
    removedMessageCount: Int
  ): ConversationWindow =
    new ConversationWindow(conversation, usage, wasTrimmed, removedMessageCount)

  def noTrimming(conversation: Conversation, usage: TokenUsageInfo): ConversationWindow =
    ConversationWindow(conversation, usage, wasTrimmed = false, removedMessageCount = 0)

  def trimmed(conversation: Conversation, usage: TokenUsageInfo, removedCount: Int): ConversationWindow =
    ConversationWindow(conversation, usage, wasTrimmed = true, removedMessageCount = removedCount)
}

/**
 * Token usage information for monitoring and debugging
 */
@Experimental
final case class TokenUsageInfo private (
  currentTokens: Int,
  budgetLimit: Int,
  withinBudget: Boolean,
  utilizationPercentage: Int
) {
  def summary: String =
    f"$currentTokens/$budgetLimit tokens ($utilizationPercentage%%)"

  /** Sets the tokens currently used. */
  def withCurrentTokens(currentTokens: Int): TokenUsageInfo = copy(currentTokens = currentTokens)

  /** Sets the token budget. */
  def withBudgetLimit(budgetLimit: Int): TokenUsageInfo = copy(budgetLimit = budgetLimit)

  /** Sets whether the usage fits the budget. */
  def withWithinBudget(withinBudget: Boolean): TokenUsageInfo = copy(withinBudget = withinBudget)

  /** Sets the budget utilisation, in percent. */
  def withUtilizationPercentage(utilizationPercentage: Int): TokenUsageInfo =
    copy(utilizationPercentage = utilizationPercentage)
}

@Experimental
object TokenUsageInfo {

  /** Creates a [[TokenUsageInfo]]. */
  def apply(
    currentTokens: Int,
    budgetLimit: Int,
    withinBudget: Boolean,
    utilizationPercentage: Int
  ): TokenUsageInfo =
    new TokenUsageInfo(currentTokens, budgetLimit, withinBudget, utilizationPercentage)
}
