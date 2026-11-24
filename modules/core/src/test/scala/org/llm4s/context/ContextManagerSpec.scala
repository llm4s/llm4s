package org.llm4s.context

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.HeadroomPercent
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ContextManagerSpec extends AnyFlatSpec with Matchers with EitherValues {

  val counter: ConversationTokenCounter = ContextTestFixtures.createSimpleCounter()

  // ============ Factory Methods ============

  "ContextManager.create" should "create manager with valid config" in {
    val config = ContextConfig.default

    val result = ContextManager.create(counter, config)

    result.isRight shouldBe true
  }

  it should "reject a negative number of semantic blocks" in {
    val error = ContextManager.create(counter, ContextConfig.default.withMaxSemanticBlocks(-1)).left.value

    error shouldBe a[ValidationError]
    error.message should include("maxSemanticBlocks")
  }

  it should "reject a summary token target that is not positive" in {
    val error = ContextManager.create(counter, ContextConfig.default.withSummaryTokenTarget(0)).left.value

    error shouldBe a[ValidationError]
    error.message should include("summaryTokenTarget")
  }

  it should "be the only way to build a manager" in {
    assertDoesNotCompile("new ContextManager(counter, ContextConfig.default, None, None)")
  }

  it should "create manager with default configuration" in {
    val result = ContextManager.create(counter)

    result.isRight shouldBe true
  }

  // ============ Pipeline Execution ============

  "ContextManager.manageContext" should "skip all steps when conversation fits budget" in {
    val conversation = ContextTestFixtures.smallConversation
    val budget       = 1000 // Large budget

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // All steps should be skipped (not applied)
    managed.stepsApplied shouldBe empty
    // Tokens should remain the same
    managed.originalTokens shouldBe managed.finalTokens
  }

  it should "apply steps in correct order" in {
    val conversation = ContextTestFixtures.largeConversation
    val budget       = 100 // Small budget to force all steps

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // Steps should be in order
    managed.steps.length shouldBe 4
    managed.steps(0).stage shouldBe ContextStage.ToolDeterministicCompaction
    managed.steps(1).stage shouldBe ContextStage.HistoryCompression
    managed.steps(2).stage shouldBe ContextStage.LLMHistorySqueeze
    managed.steps(3).stage shouldBe ContextStage.FinalTokenTrim
  }

  it should "achieve budget target in multi-step scenario" in {
    val conversation = ContextTestFixtures.largeConversation
    val budget       = 200

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // Final tokens should be within budget (accounting for headroom)
    managed.finalTokens should be <= budget
    // Should have reduced tokens
    managed.totalTokensSaved should be > 0
  }

  // ============ Step Behavior ============

  it should "skip deterministic compression when disabled" in {
    val config  = ContextConfig.default.withEnableDeterministicCompression(false)
    val manager = ContextManager.create(counter, config).toOption.get

    val conversation = ContextTestFixtures.conversationWithTools
    val budget       = 100

    val result = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // First step should not be applied
    managed.steps.head.applied shouldBe false
    managed.steps.head.stage shouldBe ContextStage.ToolDeterministicCompaction
  }

  it should "skip LLM compression when disabled" in {
    val config  = ContextConfig.default.withEnableLLMCompression(false)
    val manager = ContextManager.create(counter, config).toOption.get

    val conversation = ContextTestFixtures.largeConversation
    val budget       = 100

    val result = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // LLMHistorySqueeze step should not be applied
    val llmStep = managed.steps.find(_.stage == ContextStage.LLMHistorySqueeze).get
    llmStep.applied shouldBe false
  }

  it should "skip LLM compression when no client provided" in {
    // Default manager has no LLM client
    val manager = ContextManager.create(counter, llmClient = None).toOption.get

    val conversation = ContextTestFixtures.largeConversation
    val budget       = 100

    val result = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // LLMHistorySqueeze should be skipped
    val llmStep = managed.steps.find(_.stage == ContextStage.LLMHistorySqueeze).get
    llmStep.applied shouldBe false
  }

  // ============ ManagedConversation ============

  "ManagedConversation" should "calculate correct summary statistics" in {
    val conversation = ContextTestFixtures.mediumConversation
    val budget       = 50

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    managed.totalTokensSaved shouldBe (managed.originalTokens - managed.finalTokens)
    managed.overallCompressionRatio shouldBe (managed.finalTokens.toDouble / managed.originalTokens)
    managed.stepsApplied shouldBe managed.steps.filter(_.applied)
  }

  it should "provide readable summary" in {
    val conversation = ContextTestFixtures.smallConversation
    val budget       = 1000

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    val summary = managed.summary
    summary should include("Context management")
    summary should include("tokens")
  }

  // ============ ContextStep ============

  "ContextStep" should "provide correct summary for applied step" in {
    val step = ContextStep(
      stage = ContextStage.FinalTokenTrim,
      conversation = ContextTestFixtures.smallConversation,
      tokensBefore = 100,
      tokensAfter = 80,
      applied = true
    )

    step.tokensSaved shouldBe 20
    step.compressionRatio shouldBe 0.8
    step.summary should include("100 → 80")
    step.summary should include("saved")
  }

  it should "provide correct summary for skipped step" in {
    val step = ContextStep(
      stage = ContextStage.FinalTokenTrim,
      conversation = ContextTestFixtures.smallConversation,
      tokensBefore = 100,
      tokensAfter = 100,
      applied = false
    )

    step.tokensSaved shouldBe 0
    step.summary should include("skipped")
  }

  // ============ ContextConfig ============

  "ContextConfig.default" should "have reasonable default values" in {
    val config = ContextConfig.default

    config.headroomPercent shouldBe HeadroomPercent.Standard
    config.maxSemanticBlocks shouldBe 5
    config.enableDeterministicCompression shouldBe true
    config.enableLLMCompression shouldBe true
    config.summaryTokenTarget shouldBe 400
  }

  // ============ Edge Cases ============

  "ContextManager" should "handle empty conversation" in {
    val conversation = Conversation(Seq.empty)
    val budget       = 100

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    // Empty conversation should fail validation (no messages to manage)
    result.isLeft shouldBe true
  }

  it should "handle single message conversation" in {
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val budget       = 100

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
  }

  it should "handle conversation with existing HISTORY_SUMMARY" in {
    val conversation = ContextTestFixtures.conversationWithDigest
    val budget       = 100

    val manager = ContextManager.create(counter).toOption.get
    val result  = manager.manageContext(conversation, budget)

    result.isRight shouldBe true
    val managed = result.toOption.get

    // HISTORY_SUMMARY should be preserved through pipeline
    managed.conversation.messages.exists(_.content.contains("[HISTORY_SUMMARY]")) shouldBe true
  }
}
