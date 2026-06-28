package org.llm4s.context

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.error.TokenizerError
import org.llm4s.identity.TokenizerId
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import org.slf4j.LoggerFactory

class ConversationTokenCounterSpec extends AnyFlatSpec with Matchers {

  override def withFixture(test: NoArgTest): Outcome = {
    val tokenizerMappingLogger = LoggerFactory
      .getLogger("org.llm4s.context.tokens.TokenizerMapping$")
      .asInstanceOf[LBLogger]
    val conversationCounterLogger = LoggerFactory
      .getLogger("org.llm4s.context.ConversationTokenCounter$")
      .asInstanceOf[LBLogger]

    val previousTokenizerMappingLevel    = tokenizerMappingLogger.getLevel
    val previousConversationCounterLevel = conversationCounterLogger.getLevel

    tokenizerMappingLogger.setLevel(Level.OFF)
    conversationCounterLogger.setLevel(Level.OFF)

    try super.withFixture(test)
    finally {
      tokenizerMappingLogger.setLevel(previousTokenizerMappingLevel)
      conversationCounterLogger.setLevel(previousConversationCounterLevel)
    }
  }

  // ============ Factory Methods ============

  "ConversationTokenCounter.forModel" should "select correct tokenizer for GPT-4o models" in {
    val result = ConversationTokenCounter.forModel("gpt-4o")

    result.isRight shouldBe true
  }

  it should "select correct tokenizer for GPT-4 models" in {
    val result = ConversationTokenCounter.forModel("gpt-4")

    result.isRight shouldBe true
  }

  it should "select correct tokenizer for Claude models" in {
    // Claude uses cl100k_base as approximation
    val result = ConversationTokenCounter.forModel("claude-3-sonnet")

    result.isRight shouldBe true
  }

  it should "fallback gracefully for unknown models" in {
    val result = ConversationTokenCounter.forModel("unknown-model-xyz")

    // Should still succeed with fallback tokenizer
    result.isRight shouldBe true
  }

  "ConversationTokenCounter.openAI" should "create counter with cl100k_base tokenizer" in {
    val result = ConversationTokenCounter.openAI()

    result.isRight shouldBe true
  }

  "ConversationTokenCounter.openAI_o200k" should "create counter with o200k_base tokenizer" in {
    val result = ConversationTokenCounter.openAI_o200k()

    result.isRight shouldBe true
  }

  "ConversationTokenCounter.apply" should "accept valid tokenizer IDs" in {
    val result = ConversationTokenCounter(TokenizerId.CL100K_BASE)

    result.isRight shouldBe true
  }

  it should "fail for invalid tokenizer IDs" in {
    val result = ConversationTokenCounter(TokenizerId("invalid_tokenizer"))

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[TokenizerError]
  }

  // ============ countMessage ============

  "ConversationTokenCounter.countMessage" should "count user messages correctly" in {
    val counter = ConversationTokenCounter.openAI().toOption.get
    val message = UserMessage("Hello world")

    val tokens = counter.countMessage(message)

    // Should return positive count
    tokens should be > 0
  }

  it should "count assistant messages with tool calls" in {
    val counter = ConversationTokenCounter.openAI().toOption.get
    val message = AssistantMessage(
      content = "Let me check that.",
      toolCalls = Seq(
        ToolCall(id = "call_1", name = "get_weather", arguments = ujson.Obj("city" -> "London"))
      )
    )

    val tokens = counter.countMessage(message)

    // Should include both content and tool call tokens
    tokens should be > counter.countMessage(AssistantMessage("Let me check that."))
  }

  it should "count tool messages correctly" in {
    val counter = ConversationTokenCounter.openAI().toOption.get
    val message = ToolMessage(content = """{"temp": 20}""", toolCallId = "call_1")

    val tokens = counter.countMessage(message)

    tokens should be > 0
  }

  it should "count system messages correctly" in {
    val counter = ConversationTokenCounter.openAI().toOption.get
    val message = SystemMessage("You are a helpful assistant.")

    val tokens = counter.countMessage(message)

    tokens should be > 0
  }

  it should "include message overhead in count" in {
    val counter  = ConversationTokenCounter.openAI().toOption.get
    val emptyish = UserMessage("a") // minimal content
    val tokens   = counter.countMessage(emptyish)

    // Should be more than just 1 token due to overhead (typically 4)
    tokens should be >= 4
  }

  // ============ countConversation ============

  "ConversationTokenCounter.countConversation" should "sum all message tokens plus overhead" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val total = counter.countConversation(conversation)

    // Should be sum of individual messages + conversation overhead (10)
    val individualSum = conversation.messages.map(counter.countMessage).sum
    total shouldBe (individualSum + 10) // 10 is conversation overhead
  }

  it should "return just overhead for empty conversation" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val conversation = Conversation(Seq.empty)

    val total = counter.countConversation(conversation)

    total shouldBe 10 // Just conversation overhead
  }

  it should "scale with conversation size" in {
    val counter = ConversationTokenCounter.openAI().toOption.get

    val small  = counter.countConversation(ContextTestFixtures.smallConversation)
    val medium = counter.countConversation(ContextTestFixtures.mediumConversation)
    val large  = counter.countConversation(ContextTestFixtures.largeConversation)

    small should be < medium
    medium should be < large
  }

  // ============ getTokenBreakdown ============

  "ConversationTokenCounter.getTokenBreakdown" should "return per-message counts" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val breakdown = counter.getTokenBreakdown(conversation)

    breakdown.messages.length shouldBe conversation.messages.length
    breakdown.overhead shouldBe 10
    breakdown.totalTokens shouldBe counter.countConversation(conversation)
  }

  it should "provide message role in breakdown" in {
    val counter = ConversationTokenCounter.openAI().toOption.get
    val conversation = Conversation(
      Seq(
        UserMessage("Hello"),
        AssistantMessage("Hi there")
      )
    )

    val breakdown = counter.getTokenBreakdown(conversation)

    breakdown.messages(0).role shouldBe "user"
    breakdown.messages(1).role shouldBe "assistant"
  }

  it should "provide message preview in breakdown" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val longContent  = "A" * 100
    val conversation = Conversation(Seq(UserMessage(longContent)))

    val breakdown = counter.getTokenBreakdown(conversation)

    // Preview should be truncated to 50 chars
    breakdown.messages(0).preview.length shouldBe 50
  }

  it should "produce readable prettyPrint output" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val breakdown = counter.getTokenBreakdown(conversation)
    val output    = breakdown.prettyPrint()

    output should include("Token Breakdown")
    output should include("Total:")
    output should include("Overhead:")
  }

  // ============ Consistency Tests ============

  "ConversationTokenCounter" should "produce consistent counts for same input" in {
    val counter      = ConversationTokenCounter.openAI().toOption.get
    val conversation = ContextTestFixtures.mediumConversation

    val count1 = counter.countConversation(conversation)
    val count2 = counter.countConversation(conversation)
    val count3 = counter.countConversation(conversation)

    count1 shouldBe count2
    count2 shouldBe count3
  }

  it should "count differently sized messages proportionally" in {
    val counter = ConversationTokenCounter.openAI().toOption.get

    val short = counter.countMessage(UserMessage("Hi"))
    val long  = counter.countMessage(UserMessage("This is a much longer message with many more words"))

    long should be > short
  }
}
