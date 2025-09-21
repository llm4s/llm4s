package org.llm4s.context

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TokenBudget }
import org.slf4j.LoggerFactory

/**
 * Manages conversation token windows by trimming conversations to fit within token budgets.
 * Always preserves system messages and applies configurable headroom for safety.
 */
object TokenWindow {
  private val logger                 = LoggerFactory.getLogger(getClass)
  private val DefaultHeadroomPercent = 0.08 // 8% headroom by default

  /**
   * Trim a conversation to fit within the specified token budget.
   * Always preserves system messages and applies headroom for safety.
   *
   * @param conversation The conversation to trim
   * @param tokenCounter Token counter for the specific model
   * @param budget Maximum token budget
   * @param headroomPercent Safety margin (default 8%)
   * @return Trimmed conversation that fits within budget
   */
  def trimToBudget(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: TokenBudget,
    headroomPercent: Double = DefaultHeadroomPercent
  ): Result[ConversationWindow] =
    for {
      _               <- validateInputs(conversation, budget, headroomPercent)
      currentTokens   <- Right(tokenCounter.countConversation(conversation))
      effectiveBudget <- Right(calculateEffectiveBudget(budget, headroomPercent))
      result          <- performTrimming(conversation, tokenCounter, currentTokens, effectiveBudget, budget)
    } yield result

  /**
   * Check if a conversation fits within the budget (including headroom)
   */
  def fitsInBudget(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: TokenBudget,
    headroomPercent: Double = DefaultHeadroomPercent
  ): Boolean = {
    val currentTokens   = tokenCounter.countConversation(conversation)
    val effectiveBudget = calculateEffectiveBudget(budget, headroomPercent)
    currentTokens <= effectiveBudget
  }

  /**
   * Get current token usage information for a conversation
   */
  def getUsageInfo(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    budget: TokenBudget
  ): TokenUsageInfo = {
    val currentTokens  = tokenCounter.countConversation(conversation)
    val withinBudget   = currentTokens <= budget
    val utilizationPct = (currentTokens.toDouble / budget * 100).round.toInt

    TokenUsageInfo(currentTokens, budget, withinBudget, utilizationPct)
  }

  private def validateInputs(
    conversation: Conversation,
    budget: TokenBudget,
    headroomPercent: Double
  ): Result[Unit] =
    if (budget <= 0) {
      Left(ValidationError("Token budget must be positive", "budget"))
    } else if (headroomPercent < 0 || headroomPercent >= 1.0) {
      Left(ValidationError("Headroom percent must be between 0.0 and 1.0", "headroomPercent"))
    } else if (conversation.messages.isEmpty) {
      Left(ValidationError("Cannot trim empty conversation", "conversation"))
    } else {
      // System messages are injected at send-time, conversations contain only actual history
      Right(())
    }

  private def calculateEffectiveBudget(budget: TokenBudget, headroomPercent: Double): TokenBudget = {
    val effectiveBudget = (budget * (1.0 - headroomPercent)).toInt
    logger.debug(
      s"Effective budget: $effectiveBudget tokens (${(headroomPercent * 100).toInt}% headroom applied to $budget)"
    )
    effectiveBudget
  }

  private def performTrimming(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    currentTokens: Int,           // current size of the conversation in tokens before trimming
    effectiveBudget: TokenBudget, // usable portion of the budget after applying headroom
    originalBudget: TokenBudget   // hard context budget we aim for
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
    effectiveBudget: TokenBudget,
    originalBudget: TokenBudget
  ): Result[ConversationWindow] = {
    val allMessages = conversation.messages

    // Check if first message is a [HISTORY_SUMMARY] digest to pin
    val (pinnedDigest, regularMessages) = allMessages.headOption match {
      case Some(firstMsg) if isHistoryDigestMessage(firstMsg) =>
        logger.debug("Detected [HISTORY_SUMMARY] message to pin")
        (Some(firstMsg), allMessages.tail)
      case _ =>
        (None, allMessages)
    }

    // Calculate remaining budget after reserving tokens for pinned digest
    val remainingBudget = pinnedDigest match {
      case Some(digest) =>
        val digestTokens = tokenCounter.countMessage(digest)
        val remaining    = effectiveBudget - digestTokens
        logger.debug(s"Reserved $digestTokens tokens for pinned digest, remaining budget: $remaining")
        Math.max(0, remaining) // Ensure non-negative
      case None =>
        effectiveBudget
    }

    // Pack remaining messages newest-first into remaining budget
    val recentMessages = findRecentMessagesThatFit(regularMessages.reverse, tokenCounter, remainingBudget).reverse

    // Reconstruct conversation with pinned digest first (if present) + recent messages
    val finalMessages     = pinnedDigest.toSeq ++ recentMessages
    val finalConversation = Conversation(finalMessages)
    val removedCount      = allMessages.length - finalMessages.length

    logger.info(
      s"Trimmed to ${finalMessages.length} messages (removed $removedCount messages)${pinnedDigest.map(_ => ", digest pinned").getOrElse("")}"
    )

    Right(
      ConversationWindow.trimmed(
        finalConversation,
        getUsageInfo(finalConversation, tokenCounter, originalBudget),
        removedCount
      )
    )
  }

  private def isHistoryDigestMessage(message: Message): Boolean =
    message.content.contains("[HISTORY_SUMMARY]")

  private def findRecentMessagesThatFit(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    availableBudget: TokenBudget
  ): Seq[Message] = {
    @scala.annotation.tailrec
    def loop(remaining: Seq[Message], accumulated: Seq[Message], tokensUsed: Int): Seq[Message] =
      remaining match {
        case Nil => accumulated
        case msg +: tail =>
          val msgTokens = tokenCounter.countMessage(msg)
          val newTotal  = tokensUsed + msgTokens

          if (newTotal <= availableBudget) {
            loop(tail, accumulated :+ msg, newTotal)
          } else {
            logger.debug(s"Stopping at ${accumulated.length} messages, next would exceed available budget")
            accumulated
          }
      }

    loop(messages, Seq.empty, 0)
  }
}

/**
 * Result of token window processing
 */
case class ConversationWindow(
  conversation: Conversation,
  usage: TokenUsageInfo,
  wasTrimmed: Boolean,
  removedMessageCount: Int
)

object ConversationWindow {
  def noTrimming(conversation: Conversation, usage: TokenUsageInfo): ConversationWindow =
    ConversationWindow(conversation, usage, wasTrimmed = false, removedMessageCount = 0)

  def trimmed(conversation: Conversation, usage: TokenUsageInfo, removedCount: Int): ConversationWindow =
    ConversationWindow(conversation, usage, wasTrimmed = true, removedMessageCount = removedCount)
}

/**
 * Token usage information for monitoring and debugging
 */
case class TokenUsageInfo(
  currentTokens: Int,
  budgetLimit: TokenBudget,
  withinBudget: Boolean,
  utilizationPercentage: Int
) {
  def summary: String =
    f"$currentTokens/$budgetLimit tokens ($utilizationPercentage%%)"
}
