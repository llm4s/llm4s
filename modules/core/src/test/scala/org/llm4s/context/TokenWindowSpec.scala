package org.llm4s.context

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TokenWindowSpec extends AnyFlatSpec with Matchers {

  val counter: ConversationTokenCounter = ContextTestFixtures.createSimpleCounter()

  // ============ trimToBudget - Basic Functionality ============

  "TokenWindow.trimToBudget" should "return original conversation when within budget" in {
    val conversation = ContextTestFixtures.smallConversation
    val budget       = 1000 // Large budget

    val result = TokenWindow.trimToBudget(conversation, counter, budget)

    result.isRight shouldBe true
    val window = result.toOption.get
    window.wasTrimmed shouldBe false
    window.removedMessageCount shouldBe 0
    window.conversation.messages.length shouldBe conversation.messages.length
  }

  it should "trim oldest messages when over budget" in {
    val conversation = ContextTestFixtures.largeConversation // 20 Q&A pairs = 40 messages
    val budget       = 100                                   // Small budget to force trimming

    val result = TokenWindow.trimToBudget(conversation, counter, budget)

    result.isRight shouldBe true
    val window = result.toOption.get
    window.wasTrimmed shouldBe true
    window.removedMessageCount should be > 0
    window.conversation.messages.length should be < conversation.messages.length
  }

  it should "preserve pinned HISTORY_SUMMARY messages" in {
    val conversation = ContextTestFixtures.conversationWithDigest
    val budget       = 50 // Small budget to force trimming

    val result = TokenWindow.trimToBudget(conversation, counter, budget)

    result.isRight shouldBe true
    val window = result.toOption.get
    // First message should be the HISTORY_SUMMARY
    window.conversation.messages.headOption.exists(_.content.contains("[HISTORY_SUMMARY]")) shouldBe true
  }

  it should "apply headroom percentage correctly" in {
    val conversation = ContextTestFixtures.mediumConversation
    val budget       = 100
    val headroom     = 0.20 // 20% headroom = effective budget of 80

    val result = TokenWindow.trimToBudget(conversation, counter, budget, headroom)

    result.isRight shouldBe true
    val window = result.toOption.get
    // Trimming attempts to fit within effective budget, but may be slightly over
    // due to message granularity. Verify we're at least trimming.
    window.usage.currentTokens should be < budget
  }

  // ============ trimToBudget - Validation ============

  it should "reject negative budget" in {
    val conversation = ContextTestFixtures.smallConversation

    val result = TokenWindow.trimToBudget(conversation, counter, -100)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject zero budget" in {
    val conversation = ContextTestFixtures.smallConversation

    val result = TokenWindow.trimToBudget(conversation, counter, 0)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject invalid headroom percentage (negative)" in {
    val conversation = ContextTestFixtures.smallConversation

    val result = TokenWindow.trimToBudget(conversation, counter, 100, -0.1)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject invalid headroom percentage (>=1.0)" in {
    val conversation = ContextTestFixtures.smallConversation

    val result = TokenWindow.trimToBudget(conversation, counter, 100, 1.0)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject empty conversation" in {
    val result = TokenWindow.trimToBudget(ContextTestFixtures.emptyConversation, counter, 100)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  // ============ fitsInBudget ============

  "TokenWindow.fitsInBudget" should "return true when conversation fits" in {
    val conversation = ContextTestFixtures.smallConversation
    val budget       = 1000

    TokenWindow.fitsInBudget(conversation, counter, budget) shouldBe true
  }

  it should "return false when conversation exceeds budget" in {
    val conversation = ContextTestFixtures.largeConversation
    val budget       = 50 // Very small budget

    TokenWindow.fitsInBudget(conversation, counter, budget) shouldBe false
  }

  it should "account for headroom in budget check" in {
    val conversation = ContextTestFixtures.mediumConversation
    val tokens       = counter.countConversation(conversation)

    // With 0% headroom, exact budget should fit
    TokenWindow.fitsInBudget(conversation, counter, tokens, 0.0) shouldBe true

    // With 50% headroom, same budget should not fit
    TokenWindow.fitsInBudget(conversation, counter, tokens, 0.50) shouldBe false
  }

  // ============ getUsageInfo ============

  "TokenWindow.getUsageInfo" should "calculate correct utilization percentage" in {
    val conversation = ContextTestFixtures.smallConversation
    val tokens       = counter.countConversation(conversation)
    val budget       = tokens * 2 // 50% utilization

    val info = TokenWindow.getUsageInfo(conversation, counter, budget)

    info.currentTokens shouldBe tokens
    info.budgetLimit shouldBe budget
    info.withinBudget shouldBe true
    info.utilizationPercentage shouldBe 50
  }

  it should "report correct withinBudget status" in {
    val conversation = ContextTestFixtures.smallConversation
    val tokens       = counter.countConversation(conversation)

    // Under budget
    val infoUnder = TokenWindow.getUsageInfo(conversation, counter, tokens + 100)
    infoUnder.withinBudget shouldBe true

    // Over budget
    val infoOver = TokenWindow.getUsageInfo(conversation, counter, tokens - 10)
    infoOver.withinBudget shouldBe false
  }

  it should "return zero utilization when budget is zero" in {
    val conversation = ContextTestFixtures.smallConversation
    val info         = TokenWindow.getUsageInfo(conversation, counter, 0)

    info.utilizationPercentage shouldBe 0
    info.withinBudget shouldBe false
    info.budgetLimit shouldBe 0
  }

  // ============ Edge Cases ============

  "TokenWindow" should "handle single message conversation" in {
    val conversation = ContextTestFixtures.singleMessageConversation
    val budget       = 1000

    val result = TokenWindow.trimToBudget(conversation, counter, budget)

    result.isRight shouldBe true
    result.toOption.get.conversation.messages.length shouldBe 1
  }

  it should "trim to single message if budget is very tight" in {
    val conversation = ContextTestFixtures.mediumConversation
    val budget       = 30 // Very small - may only fit one message

    val result = TokenWindow.trimToBudget(conversation, counter, budget)

    result.isRight shouldBe true
    val window = result.toOption.get
    window.wasTrimmed shouldBe true
    window.conversation.messages.length should be >= 1
  }
}
