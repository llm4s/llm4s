package org.llm4s.context

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, Message, MessageRole, UserMessage }
import org.llm4s.types.SemanticBlockId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The `org.llm4s.context` result types are growth-safe (#2380): a private constructor and `copy`, a companion
 * `apply` with the previous parameters, and a `with*` setter per field. Each setter changes its field and nothing
 * else; `new X(...)` and `.copy(...)` no longer compile outside the type.
 */
class ContextGrowthSafeTypesSpec extends AnyFlatSpec with Matchers {

  private val conversation           = Conversation(Seq(UserMessage("hi")))
  private val otherConversation      = Conversation(Seq(UserMessage("hi"), UserMessage("again")))
  private val messages: Seq[Message] = Seq(UserMessage("q"), AssistantMessage("a"))

  "CompressionRule" should "build through apply and set each field" in {
    val identity: Seq[Message] => Seq[Message] = ms => ms
    val drop: Seq[Message] => Seq[Message]     = _ => Seq.empty
    val rule                                   = CompressionRule("keep", identity)
    rule.name shouldBe "keep"
    rule.transform(messages) shouldBe messages

    rule.withName("renamed").name shouldBe "renamed"
    rule.withName("renamed").transform shouldBe identity
    val dropping = rule.withTransform(drop)
    dropping.name shouldBe "keep"
    dropping.transform(messages) shouldBe empty
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new CompressionRule("n", identity)""")
    assertDoesNotCompile("""CompressionRule.removeFillerWords.copy(name = "x")""")
  }

  "ContextStep" should "build through apply and set each field" in {
    val step = ContextStep(ContextStage.FinalTokenTrim, conversation, 100, 80, applied = true)
    step.withStage(ContextStage.HistoryCompression) shouldBe ContextStep(
      ContextStage.HistoryCompression,
      conversation,
      100,
      80,
      applied = true
    )
    step.withConversation(otherConversation) shouldBe ContextStep(
      ContextStage.FinalTokenTrim,
      otherConversation,
      100,
      80,
      applied = true
    )
    step.withTokensBefore(120) shouldBe ContextStep(ContextStage.FinalTokenTrim, conversation, 120, 80, applied = true)
    step.withTokensAfter(60) shouldBe ContextStep(ContextStage.FinalTokenTrim, conversation, 100, 60, applied = true)
    step.withApplied(false) shouldBe ContextStep(ContextStage.FinalTokenTrim, conversation, 100, 80, applied = false)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new ContextStep(ContextStage.FinalTokenTrim, Conversation(Seq.empty), 1, 1, false)""")
    assertDoesNotCompile(
      """ContextStep.noOperation(ContextStage.FinalTokenTrim, Conversation(Seq.empty), 1).copy(applied = true)"""
    )
  }

  "ManagedConversation" should "build through apply and set each field" in {
    val step    = ContextStep.noOperation(ContextStage.LLMHistorySqueeze, conversation, 10)
    val managed = ManagedConversation(conversation, 100, 80, Seq(step))
    managed.withConversation(otherConversation) shouldBe ManagedConversation(otherConversation, 100, 80, Seq(step))
    managed.withOriginalTokens(120) shouldBe ManagedConversation(conversation, 120, 80, Seq(step))
    managed.withFinalTokens(60) shouldBe ManagedConversation(conversation, 100, 60, Seq(step))
    managed.withSteps(Seq.empty) shouldBe ManagedConversation(conversation, 100, 80, Seq.empty)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new ManagedConversation(Conversation(Seq.empty), 1, 1, Seq.empty)""")
    assertDoesNotCompile("""ManagedConversation(Conversation(Seq.empty), 1, 1, Seq.empty).copy(finalTokens = 2)""")
  }

  "ConversationWindow" should "build through apply and set each field" in {
    val usage  = TokenUsageInfo(50, 100, withinBudget = true, utilizationPercentage = 50)
    val usage2 = usage.withCurrentTokens(60)
    val window = ConversationWindow(conversation, usage, wasTrimmed = true, removedMessageCount = 2)
    window.withConversation(otherConversation) shouldBe ConversationWindow(otherConversation, usage, true, 2)
    window.withUsage(usage2) shouldBe ConversationWindow(conversation, usage2, true, 2)
    window.withWasTrimmed(false) shouldBe ConversationWindow(conversation, usage, false, 2)
    window.withRemovedMessageCount(5) shouldBe ConversationWindow(conversation, usage, true, 5)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile(
      """new ConversationWindow(Conversation(Seq.empty), TokenUsageInfo(1, 1, true, 1), false, 0)"""
    )
    assertDoesNotCompile(
      """ConversationWindow(Conversation(Seq.empty), TokenUsageInfo(1, 1, true, 1), false, 0).copy(wasTrimmed = true)"""
    )
  }

  "TokenUsageInfo" should "build through apply and set each field" in {
    val usage = TokenUsageInfo(50, 100, withinBudget = true, utilizationPercentage = 50)
    usage.withCurrentTokens(70) shouldBe TokenUsageInfo(70, 100, true, 50)
    usage.withBudgetLimit(200) shouldBe TokenUsageInfo(50, 200, true, 50)
    usage.withWithinBudget(false) shouldBe TokenUsageInfo(50, 100, false, 50)
    usage.withUtilizationPercentage(25) shouldBe TokenUsageInfo(50, 100, true, 25)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new TokenUsageInfo(1, 1, true, 1)""")
    assertDoesNotCompile("""TokenUsageInfo(1, 1, true, 1).copy(currentTokens = 2)""")
  }

  "TokenBreakdown" should "build through apply and set each field" in {
    val info      = MessageTokenInfo(MessageRole.User, 5, "hello")
    val breakdown = TokenBreakdown(10, Seq(info), 3)
    breakdown.withTotalTokens(12) shouldBe TokenBreakdown(12, Seq(info), 3)
    breakdown.withMessages(Seq.empty) shouldBe TokenBreakdown(10, Seq.empty, 3)
    breakdown.withOverhead(4) shouldBe TokenBreakdown(10, Seq(info), 4)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new TokenBreakdown(1, Seq.empty, 0)""")
    assertDoesNotCompile("""TokenBreakdown(1, Seq.empty, 0).copy(overhead = 2)""")
  }

  "MessageTokenInfo" should "build through apply and set each field" in {
    val info = MessageTokenInfo(MessageRole.User, 5, "hello")
    info.withRole(MessageRole.Assistant) shouldBe MessageTokenInfo(MessageRole.Assistant, 5, "hello")
    info.withTokens(9) shouldBe MessageTokenInfo(MessageRole.User, 9, "hello")
    info.withPreview("bye") shouldBe MessageTokenInfo(MessageRole.User, 5, "bye")
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new MessageTokenInfo(MessageRole.User, 1, "p")""")
    assertDoesNotCompile("""MessageTokenInfo(MessageRole.User, 1, "p").copy(tokens = 2)""")
  }

  "SemanticBlock" should "default expectingAssistantResponse to false in apply" in {
    val id = SemanticBlockId.generate()
    SemanticBlock(id, messages, SemanticBlockType.UserAssistantPair).expectingAssistantResponse shouldBe false
  }

  it should "set each field" in {
    val id    = SemanticBlockId.generate()
    val other = SemanticBlockId.generate()
    val block = SemanticBlock(id, messages, SemanticBlockType.UserAssistantPair, expectingAssistantResponse = true)
    block.withId(other) shouldBe SemanticBlock(other, messages, SemanticBlockType.UserAssistantPair, true)
    block.withMessages(messages.take(1)) shouldBe
      SemanticBlock(id, messages.take(1), SemanticBlockType.UserAssistantPair, true)
    block.withBlockType(SemanticBlockType.Other) shouldBe SemanticBlock(id, messages, SemanticBlockType.Other, true)
    block.withExpectingAssistantResponse(false) shouldBe
      SemanticBlock(id, messages, SemanticBlockType.UserAssistantPair, false)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new SemanticBlock(SemanticBlockId.generate(), Seq.empty, SemanticBlockType.Other)""")
    assertDoesNotCompile(
      """SemanticBlock(SemanticBlockId.generate(), Seq.empty, SemanticBlockType.Other).copy(messages = Seq.empty)"""
    )
  }
}
