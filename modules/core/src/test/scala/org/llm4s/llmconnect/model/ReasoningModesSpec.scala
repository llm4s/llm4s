package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ReasoningModesSpec extends AnyFlatSpec with Matchers {

  // ===========================================
  // ReasoningEffort tests
  // ===========================================

  "ReasoningEffort" should "parse valid effort levels from strings" in {
    ReasoningEffort.fromString("minimal") shouldBe Some(ReasoningEffort.Minimal)
    ReasoningEffort.fromString("low") shouldBe Some(ReasoningEffort.Low)
    ReasoningEffort.fromString("medium") shouldBe Some(ReasoningEffort.Medium)
    ReasoningEffort.fromString("high") shouldBe Some(ReasoningEffort.High)
    ReasoningEffort.fromString("xhigh") shouldBe Some(ReasoningEffort.XHigh)
    ReasoningEffort.fromString("max") shouldBe Some(ReasoningEffort.Max)
  }

  it should "be case-insensitive when parsing" in {
    ReasoningEffort.fromString("MINIMAL") shouldBe Some(ReasoningEffort.Minimal)
    ReasoningEffort.fromString("Low") shouldBe Some(ReasoningEffort.Low)
    ReasoningEffort.fromString("MEDIUM") shouldBe Some(ReasoningEffort.Medium)
    ReasoningEffort.fromString("HIGH") shouldBe Some(ReasoningEffort.High)
    ReasoningEffort.fromString("XHigh") shouldBe Some(ReasoningEffort.XHigh)
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

  it should "not parse none, which is Thinking.Off now" in {
    ReasoningEffort.fromString("none") shouldBe None
    ReasoningEffort.fromString("NONE") shouldBe None
  }

  it should "refuse to read a stored none, naming its replacement" in {
    val error = intercept[Exception](upickle.default.read[ReasoningEffort]("\"none\""))
    Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" ") should include(
      "use Thinking.Off"
    )
  }

  it should "round-trip every level through JSON" in {
    ReasoningEffort.values.foreach { level =>
      upickle.default.read[ReasoningEffort](upickle.default.write(level)) shouldBe level
    }
    upickle.default.write[ReasoningEffort](ReasoningEffort.XHigh) shouldBe "\"xhigh\""
  }

  it should "have correct name values" in {
    ReasoningEffort.Minimal.name shouldBe "minimal"
    ReasoningEffort.Low.name shouldBe "low"
    ReasoningEffort.Medium.name shouldBe "medium"
    ReasoningEffort.High.name shouldBe "high"
    ReasoningEffort.XHigh.name shouldBe "xhigh"
    ReasoningEffort.Max.name shouldBe "max"
  }

  it should "provide default budget tokens for each level, rising with the level" in {
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Minimal) shouldBe 1024
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Low) shouldBe 2048
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Medium) shouldBe 8192
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.High) shouldBe 32768
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.XHigh) shouldBe 49152
    ReasoningEffort.defaultBudgetTokens(ReasoningEffort.Max) shouldBe 65536
    val budgets = ReasoningEffort.values.map(ReasoningEffort.defaultBudgetTokens)
    budgets shouldBe budgets.sorted
  }

  it should "list every level from least to most effort" in {
    ReasoningEffort.values shouldBe Seq(
      ReasoningEffort.Minimal,
      ReasoningEffort.Low,
      ReasoningEffort.Medium,
      ReasoningEffort.High,
      ReasoningEffort.XHigh,
      ReasoningEffort.Max
    )
  }

  // ===========================================
  // CompletionOptions reasoning tests
  // ===========================================

  "CompletionOptions" should "support withReasoning builder" in {
    val options = CompletionOptions().withReasoning(ReasoningEffort.High)
    options.reasoning shouldBe Some(ReasoningEffort.High)
  }

  it should "support withThinking builder" in {
    CompletionOptions().withThinking(Thinking.Adaptive).thinking shouldBe Some(Thinking.Adaptive)
    CompletionOptions(thinking = Some(Thinking.Off)).thinking shouldBe Some(Thinking.Off)
  }

  it should "chain builders correctly" in {
    val budget = Thinking.budget(10000).toOption.get
    val options = CompletionOptions()
      .withReasoning(ReasoningEffort.Medium)
      .withThinking(budget)
      .withMaxTokens(4096)
    options.reasoning shouldBe Some(ReasoningEffort.Medium)
    options.thinking shouldBe Some(budget)
    options.maxTokens shouldBe Some(4096)
  }

  it should "detect when reasoning is enabled" in {
    CompletionOptions().hasReasoning shouldBe false
    CompletionOptions().withReasoning(ReasoningEffort.Minimal).hasReasoning shouldBe true
    CompletionOptions().withReasoning(ReasoningEffort.High).hasReasoning shouldBe true
    CompletionOptions().withThinking(Thinking.Adaptive).hasReasoning shouldBe true
    CompletionOptions().withThinking(Thinking.budget(1000).toOption.get).hasReasoning shouldBe true
    CompletionOptions().withThinking(Thinking.Off).hasReasoning shouldBe false
    // a level still counts with thinking off: on Anthropic it is sent as the effort
    CompletionOptions().withThinking(Thinking.Off).withReasoning(ReasoningEffort.High).hasReasoning shouldBe true
  }

  it should "calculate effective budget tokens" in {
    // No reasoning set
    CompletionOptions().effectiveBudgetTokens shouldBe None

    // Default budgets from effort levels, with no thinking set: as before RFC 0008
    CompletionOptions().withReasoning(ReasoningEffort.Low).effectiveBudgetTokens shouldBe Some(2048)
    CompletionOptions().withReasoning(ReasoningEffort.Medium).effectiveBudgetTokens shouldBe Some(8192)
    CompletionOptions().withReasoning(ReasoningEffort.High).effectiveBudgetTokens shouldBe Some(32768)

    // An explicit thinking budget wins over the effort's
    val budget = Thinking.budget(5000).toOption.get
    CompletionOptions()
      .withReasoning(ReasoningEffort.High)
      .withThinking(budget)
      .effectiveBudgetTokens shouldBe Some(5000)

    // A thinking budget alone
    CompletionOptions().withThinking(Thinking.budget(12000).toOption.get).effectiveBudgetTokens shouldBe Some(12000)

    // Off and Adaptive take no budget, whatever the effort
    CompletionOptions()
      .withReasoning(ReasoningEffort.High)
      .withThinking(Thinking.Off)
      .effectiveBudgetTokens shouldBe None
    CompletionOptions()
      .withReasoning(ReasoningEffort.High)
      .withThinking(Thinking.Adaptive)
      .effectiveBudgetTokens shouldBe None
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
      created = Instant.EPOCH,
      content = "response",
      model = "test",
      message = AssistantMessage(Some("response"), Seq.empty)
    )
    withoutThinking.hasThinking shouldBe false

    val withEmptyThinking = withoutThinking.withMessage(withoutThinking.message.withThinking(""))
    withEmptyThinking.hasThinking shouldBe false

    val withThinking =
      withoutThinking.withMessage(withoutThinking.message.withThinking("I need to think about this..."))
    withThinking.hasThinking shouldBe true
  }

  it should "generate fullContent correctly" in {
    val completion = Completion(
      id = "1",
      created = Instant.EPOCH,
      content = "The answer is 42.",
      model = "test",
      message = AssistantMessage(Some("The answer is 42."), Seq.empty).withThinking("Let me calculate this...")
    )

    completion.fullContent should include("<thinking>")
    completion.fullContent should include("Let me calculate this...")
    completion.fullContent should include("</thinking>")
    completion.fullContent should include("The answer is 42.")
  }

  it should "return just content when no thinking" in {
    val completion = Completion(
      id = "1",
      created = Instant.EPOCH,
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
