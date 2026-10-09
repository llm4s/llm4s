package org.llm4s.context

import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.util.Using

/**
 * Tests for the two small result types at the end of `TokenWindow.scala`:
 * `TokenUsageInfo` (and its `summary`) and `ConversationWindow` (and its `noTrimming` / `trimmed` factories).
 * `TokenWindowSpec` reaches them only through `TokenWindow.trimToBudget`.
 */
class TokenWindowTypesSpec extends AnyFlatSpec with Matchers {

  private val conversation      = Conversation(Seq(UserMessage("hi")))
  private val otherConversation = Conversation(Seq(UserMessage("hi"), UserMessage("again")))
  private val usage =
    TokenUsageInfo(currentTokens = 850, budgetLimit = 1000, withinBudget = true, utilizationPercentage = 85)

  // ============ TokenUsageInfo.summary ============

  "TokenUsageInfo.summary" should "read current/budget tokens (percent%)" in {
    usage.summary shouldBe "850/1000 tokens (85%)"
  }

  it should "report an empty window" in {
    TokenUsageInfo(0, 1000, withinBudget = true, utilizationPercentage = 0).summary shouldBe "0/1000 tokens (0%)"
  }

  it should "report a full window" in {
    TokenUsageInfo(
      1000,
      1000,
      withinBudget = true,
      utilizationPercentage = 100
    ).summary shouldBe "1000/1000 tokens (100%)"
  }

  it should "report an over-budget window with a percentage above 100" in {
    TokenUsageInfo(1500, 1000, withinBudget = false, utilizationPercentage = 150).summary shouldBe
      "1500/1000 tokens (150%)"
  }

  it should "format each of its three fields from the field itself" in {
    // Distinct values, so a summary that swapped two fields or recomputed the percentage would be caught.
    TokenUsageInfo(120, 4096, withinBudget = true, utilizationPercentage = 7).summary shouldBe "120/4096 tokens (7%)"
  }

  it should "not depend on the withinBudget flag" in {
    val within = TokenUsageInfo(1200, 1000, withinBudget = true, utilizationPercentage = 120)
    val over   = within.copy(withinBudget = false)
    over.summary shouldBe within.summary
  }

  it should "print large numbers without grouping separators, whatever the default locale" in {
    val info = TokenUsageInfo(1234567, 2000000, withinBudget = true, utilizationPercentage = 62)
    val summaries = Seq(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-SA")).map { locale =>
      Using.resource(DefaultFormatLocale(locale))(_ => info.summary)
    }
    all(summaries) shouldBe "1234567/2000000 tokens (62%)"
  }

  // ============ ConversationWindow.noTrimming ============

  "ConversationWindow.noTrimming" should "keep the conversation and the usage it is given" in {
    val window = ConversationWindow.noTrimming(conversation, usage)
    window.conversation shouldBe conversation
    window.usage shouldBe usage
  }

  it should "report that nothing was trimmed" in {
    val window = ConversationWindow.noTrimming(conversation, usage)
    window.wasTrimmed shouldBe false
    window.removedMessageCount shouldBe 0
  }

  // ============ ConversationWindow.trimmed ============

  "ConversationWindow.trimmed" should "keep the conversation and the usage it is given" in {
    val window = ConversationWindow.trimmed(conversation, usage, 3)
    window.conversation shouldBe conversation
    window.usage shouldBe usage
  }

  it should "report that messages were trimmed, and how many" in {
    val window = ConversationWindow.trimmed(conversation, usage, 3)
    window.wasTrimmed shouldBe true
    window.removedMessageCount shouldBe 3
  }

  it should "carry the removed count it is given, whatever it is" in {
    Seq(1, 7, 40).foreach { removed =>
      ConversationWindow.trimmed(conversation, usage, removed).removedMessageCount shouldBe removed
    }
  }

  it should "mark the window trimmed even for a removed count of 0, because the factory does not inspect the count" in {
    val window = ConversationWindow.trimmed(conversation, usage, 0)
    window.wasTrimmed shouldBe true
    window.removedMessageCount shouldBe 0
  }

  "The two ConversationWindow factories" should "build different windows from the same inputs" in {
    ConversationWindow.trimmed(conversation, usage, 3) should not be ConversationWindow.noTrimming(conversation, usage)
  }

  // ============ equality ============

  "ConversationWindow" should "compare equal when all its fields are equal" in {
    val a = ConversationWindow.trimmed(conversation, usage, 3)
    val b = ConversationWindow(conversation, usage, wasTrimmed = true, removedMessageCount = 3)
    a shouldBe b
    a.hashCode shouldBe b.hashCode
  }

  it should "differ when any one field differs" in {
    val base = ConversationWindow(conversation, usage, wasTrimmed = true, removedMessageCount = 3)
    base should not be base.copy(conversation = otherConversation)
    base should not be base.copy(usage = usage.copy(currentTokens = 851))
    base should not be base.copy(wasTrimmed = false)
    base should not be base.copy(removedMessageCount = 4)
  }

  "TokenUsageInfo" should "compare equal when all its fields are equal" in {
    val a = TokenUsageInfo(850, 1000, withinBudget = true, utilizationPercentage = 85)
    a shouldBe usage
    a.hashCode shouldBe usage.hashCode
  }

  it should "differ when any one field differs" in {
    usage should not be usage.copy(currentTokens = 851)
    usage should not be usage.copy(budgetLimit = 1001)
    usage should not be usage.copy(withinBudget = false)
    usage should not be usage.copy(utilizationPercentage = 86)
  }

  /** Sets the default FORMAT locale for the length of a `Using.resource` block, then restores the previous one. */
  final private class DefaultFormatLocale(replacement: Locale) extends AutoCloseable {
    private val previous = Locale.getDefault(Locale.Category.FORMAT)
    Locale.setDefault(Locale.Category.FORMAT, replacement)
    override def close(): Unit = Locale.setDefault(Locale.Category.FORMAT, previous)
  }

  private object DefaultFormatLocale {
    def apply(replacement: Locale): DefaultFormatLocale = new DefaultFormatLocale(replacement)
  }
}
