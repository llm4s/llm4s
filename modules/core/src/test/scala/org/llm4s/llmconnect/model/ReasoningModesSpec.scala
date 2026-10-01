package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReasoningModesSpec extends AnyFlatSpec with Matchers {

  // ===========================================
  // ReasoningEffort tests
  // ===========================================

  "ReasoningEffort" should "parse valid effort levels from strings" in {
    ReasoningEffort.fromString("none") shouldBe Some(ReasoningEffort.None)
    ReasoningEffort.fromString("low") shouldBe Some(ReasoningEffort.Low)
    ReasoningEffort.fromString("medium") shouldBe Some(ReasoningEffort.Medium)
    ReasoningEffort.fromString("high") shouldBe Some(ReasoningEffort.High)
  }

  it should "be case-insensitive when parsing" in {
    ReasoningEffort.fromString("NONE") shouldBe Some(ReasoningEffort.None)
    ReasoningEffort.fromString("Low") shouldBe Some(ReasoningEffort.Low)
    ReasoningEffort.fromString("MEDIUM") shouldBe Some(ReasoningEffort.Medium)
    ReasoningEffort.fromString("HIGH") shouldBe Some(ReasoningEffort.High)
  }

  it should "handle whitespace when parsing" in {
    ReasoningEffort.fromString("  low  ") shouldBe Some(ReasoningEffort.Low)
    ReasoningEffort.fromString("medium ") shouldBe Some(ReasoningEffort.Medium)
  }

  it should "return None for invalid strings" in {
    ReasoningEffort.fromString("invalid") shouldBe None
    ReasoningEffort.fromString("") shouldBe None
    ReasoningEffort.fromString("maximum") shouldBe None
  }

  it should "have correct name values" in {
    ReasoningEffort.None.name shouldBe "none"
    ReasoningEffort.Low.name shouldBe "low"
    ReasoningEffort.Medium.name shouldBe "medium"
    ReasoningEffort.High.name shouldBe "high"
  }

  it should "provide default budget tokens for each level" in {
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.None) shouldBe 0
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Low) shouldBe 2048
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Medium) shouldBe 8192
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.High) shouldBe 32768
  }

  it should "contain all values in values sequence" in {
    ReasoningEffort.values should contain theSameElementsAs Seq(
      ReasoningEffort.None,
      ReasoningEffort.Low,
      ReasoningEffort.Medium,
      ReasoningEffort.High
    )
  }

  // ===========================================
  // CompletionOptions tests
  // ===========================================

  "CompletionOptions" should "support withReasoning builder" in {
    val options = CompletionOptions().withReasoning(ReasoningEffort.High)
    options.reasoning shouldBe Some(ReasoningEffort.High)
  }

  it should "support withBudgetTokens builder" in {
    val options = CompletionOptions().withBudgetTokens(16000)
    options.budgetTokens shouldBe Some(16000)
  }

  it should "chain builders correctly" in {
    val options = CompletionOptions()
      .withReasoning(ReasoningEffort.Medium)
      .withBudgetTokens(10000)
      .withMaxTokens(4096)

    options.reasoning shouldBe Some(ReasoningEffort.Medium)
    options.budgetTokens shouldBe Some(10000)
    options.maxTokens shouldBe Some(4096)
  }

  it should "detect when reasoning is enabled" in {
    CompletionOptions().hasReasoning shouldBe false
    CompletionOptions().withReasoning(ReasoningEffort.None).hasReasoning shouldBe false
    CompletionOptions().withReasoning(ReasoningEffort.Low).hasReasoning shouldBe true
    CompletionOptions().withReasoning(ReasoningEffort.High).hasReasoning shouldBe true
    CompletionOptions().withBudgetTokens(1000).hasReasoning shouldBe true
    CompletionOptions().withBudgetTokens(0).hasReasoning shouldBe false
  }

  it should "calculate effective budget tokens" in {
    // No reasoning configured
    CompletionOptions().effectiveBudgetTokens shouldBe None

    // Only reasoning effort set
    CompletionOptions().withReasoning(ReasoningEffort.None).effectiveBudgetTokens shouldBe None
    CompletionOptions().withReasoning(ReasoningEffort.Low).effectiveBudgetTokens shouldBe Some(2048)
    CompletionOptions().withReasoning(ReasoningEffort.Medium).effectiveBudgetTokens shouldBe Some(8192)
    CompletionOptions().withReasoning(ReasoningEffort.High).effectiveBudgetTokens shouldBe Some(32768)

    // Explicit budget tokens override reasoning effort
    CompletionOptions()
      .withReasoning(ReasoningEffort.High)
      .withBudgetTokens(5000)
      .effectiveBudgetTokens shouldBe Some(5000)

    // Budget tokens alone
    CompletionOptions().withBudgetTokens(12000).effectiveBudgetTokens shouldBe Some(12000)
  }

  it should "preserve other options when adding reasoning" in {
    val original = CompletionOptions(
      temperature = 0.5,
      maxTokens = Some(2048),
      presencePenalty = 0.2
    )

    val withReasoning = original.withReasoning(ReasoningEffort.High)

    withReasoning.temperature shouldBe 0.5
    withReasoning.maxTokens shouldBe Some(2048)
    withReasoning.presencePenalty shouldBe 0.2
    withReasoning.reasoning shouldBe Some(ReasoningEffort.High)
  }

  // ===========================================
  // Completion tests
  // ===========================================

  "Completion" should "detect when thinking content is present" in {
    val withoutThinking = Completion(
      id = "1",
      created = 0L,
      content = "response",
      model = "test",
      message = AssistantMessage(Some("response"), Seq.empty)
    )
    withoutThinking.hasThinking shouldBe false

    val withEmptyThinking = withoutThinking.withThinking(Some(""))
    withEmptyThinking.hasThinking shouldBe false

    val withThinking = withoutThinking.withThinking(Some("I need to think about this..."))
    withThinking.hasThinking shouldBe true
  }

  it should "generate fullContent correctly" in {
    val completion = Completion(
      id = "1",
      created = 0L,
      content = "The answer is 42.",
      model = "test",
      message = AssistantMessage(Some("The answer is 42."), Seq.empty),
      thinking = Some("Let me calculate this...")
    )

    completion.fullContent should include("<thinking>")
    completion.fullContent should include("Let me calculate this...")
    completion.fullContent should include("</thinking>")
    completion.fullContent should include("The answer is 42.")
  }

  it should "return just content when no thinking" in {
    val completion = Completion(
      id = "1",
      created = 0L,
      content = "The answer is 42.",
      model = "test",
      message = AssistantMessage(Some("The answer is 42."), Seq.empty)
    )

    completion.fullContent shouldBe "The answer is 42."
  }

  // ===========================================
  // TokenUsage tests
  // ===========================================

  "TokenUsage" should "calculate total output tokens correctly" in {
    val usageNoThinking = TokenUsage(100, 50, 150)
    usageNoThinking.totalOutputTokens shouldBe 50

    val usageWithThinking = TokenUsage(100, 50, 150, Some(200))
    usageWithThinking.totalOutputTokens shouldBe 250
  }

  it should "detect when thinking tokens are present" in {
    TokenUsage(100, 50, 150).hasThinkingTokens shouldBe false
    TokenUsage(100, 50, 150, Some(0)).hasThinkingTokens shouldBe false
    TokenUsage(100, 50, 150, Some(100)).hasThinkingTokens shouldBe true
  }

  // ===========================================
  // StreamedChunk tests
  // ===========================================

  "StreamedChunk" should "detect thinking content" in {
    val noThinking = StreamedChunk("1", Some("content"))
    noThinking.hasThinking shouldBe false

    val emptyThinking = StreamedChunk("1", Some("content"), thinkingDelta = Some(""))
    emptyThinking.hasThinking shouldBe false

    val withThinking = StreamedChunk("1", Some("content"), thinkingDelta = Some("thinking..."))
    withThinking.hasThinking shouldBe true
  }

  it should "detect main content" in {
    val noContent = StreamedChunk("1", None)
    noContent.hasContent shouldBe false

    val emptyContent = StreamedChunk("1", Some(""))
    emptyContent.hasContent shouldBe false

    val withContent = StreamedChunk("1", Some("hello"))
    withContent.hasContent shouldBe true
  }

  it should "handle both content and thinking" in {
    val chunk = StreamedChunk(
      id = "1",
      content = Some("response"),
      thinkingDelta = Some("thinking")
    )

    chunk.hasContent shouldBe true
    chunk.hasThinking shouldBe true
  }
}
