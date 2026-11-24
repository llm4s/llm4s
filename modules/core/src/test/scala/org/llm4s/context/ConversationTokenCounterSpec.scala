package org.llm4s.context

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.error.TokenizerError
import org.llm4s.context.tokens.{ StringTokenizer, Token, Tokenizer, TokenizerId }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import org.slf4j.LoggerFactory

class ConversationTokenCounterSpec extends AnyFlatSpec with Matchers {

  override def withFixture(test: NoArgTest): Outcome = {
    val conversationCounterLogger = LoggerFactory
      .getLogger("org.llm4s.context.ConversationTokenCounter$")
      .asInstanceOf[LBLogger]

    val previousConversationCounterLevel = conversationCounterLogger.getLevel

    conversationCounterLogger.setLevel(Level.OFF)

    try super.withFixture(test)
    finally
      conversationCounterLogger.setLevel(previousConversationCounterLevel)
  }

  // ============ Factory Methods ============

  "ConversationTokenCounter.of" should "count with a tokenizer the caller supplies" in {
    // one token per word, counted without building the token list
    val words = new StringTokenizer {
      def encode(text: String): List[Token] = fail("counting must go through count")
      override def count(text: String): Int = text.split("\\s+").count(_.nonEmpty)
    }
    val counter = ConversationTokenCounter.of(words)

    // 3 words + 4 tokens of message overhead
    counter.countMessage(UserMessage("three little words")) shouldBe 7
  }

  "StringTokenizer.count" should "default to the length of the encoding" in {
    val tokenizer = new StringTokenizer {
      def encode(text: String): List[Token] = text.toList.map(c => Token(c.toInt))
    }
    tokenizer.count("abcd") shouldBe 4
  }

  it should "agree with encode for the bundled vocabularies" in {
    val text = "Counting tokens, <|endoftext|> included: naïve café 東京."
    Seq(TokenizerId.CL100K_BASE, TokenizerId.O200K_BASE, TokenizerId.R50K_BASE).foreach { id =>
      val tokenizer = Tokenizer.lookupStringTokenizer(id).getOrElse(fail(s"no tokenizer for $id"))
      withClue(id.name)(tokenizer.count(text) shouldBe tokenizer.encode(text).length)
    }
  }

  "ConversationTokenCounter.openAI" should "create counter with cl100k_base tokenizer" in {
    val result = ConversationTokenCounter.cl100k()

    result.isRight shouldBe true
  }

  // Message text is data: a tokenizer's special-token names in it (a web page about LLMs, a model card) are counted as
  // ordinary text, never read as control tokens or refused.
  it should "count text containing special-token names instead of failing" in {
    val specials = "page <|endoftext|> more <|fim_prefix|> x <|fim_middle|> y <|fim_suffix|> z <|endofprompt|>"
    Seq(ConversationTokenCounter.cl100k(), ConversationTokenCounter.o200k()).foreach { result =>
      val counter = result.fold(e => fail(e.message), identity)
      counter.countMessage(ToolMessage(specials, "c1")) should be > counter.countMessage(ToolMessage("page", "c1"))
      counter.countMessage(UserMessage(specials)) should be > 0
      counter.countMessage(AssistantMessage(specials)) should be > 0
      counter.countConversation(Conversation(Seq(UserMessage(specials)))) should be > 0
    }
  }

  "Tokenizer.lookupStringTokenizer" should "encode special-token names as ordinary text" in {
    val tokenizer = org.llm4s.context.tokens.Tokenizer
      .lookupStringTokenizer(TokenizerId.CL100K_BASE)
      .getOrElse(fail("cl100k_base missing"))
    // `<|endoftext|>` as text is several ordinary tokens, never the single special token 100257
    val tokens = tokenizer.encode("<|endoftext|>")
    tokens.length should be > 1
    tokens.map(_.tokenId) should not contain 100257
  }

  "ConversationTokenCounter.o200k" should "create counter with o200k_base tokenizer" in {
    val result = ConversationTokenCounter.o200k()

    result.isRight shouldBe true
  }

  "ConversationTokenCounter.apply" should "accept valid tokenizer IDs" in {
    val result = ConversationTokenCounter.create(TokenizerId.CL100K_BASE)

    result.isRight shouldBe true
  }

  it should "fail for invalid tokenizer IDs" in {
    val result = ConversationTokenCounter.create(TokenizerId("invalid_tokenizer"))

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[TokenizerError]
  }

  // ============ countMessage ============

  "ConversationTokenCounter.countMessage" should "count user messages correctly" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get
    val message = UserMessage("Hello world")

    val tokens = counter.countMessage(message)

    // Should return positive count
    tokens should be > 0
  }

  it should "count assistant messages with tool calls" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get
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
    val counter = ConversationTokenCounter.cl100k().toOption.get
    val message = ToolMessage(content = """{"temp": 20}""", toolCallId = "call_1")

    val tokens = counter.countMessage(message)

    tokens should be > 0
  }

  it should "count system messages correctly" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get
    val message = SystemMessage("You are a helpful assistant.")

    val tokens = counter.countMessage(message)

    tokens should be > 0
  }

  it should "include message overhead in count" in {
    val counter  = ConversationTokenCounter.cl100k().toOption.get
    val emptyish = UserMessage("a") // minimal content
    val tokens   = counter.countMessage(emptyish)

    // Should be more than just 1 token due to overhead (typically 4)
    tokens should be >= 4
  }

  // ============ countConversation ============

  "ConversationTokenCounter.countConversation" should "sum all message tokens plus overhead" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val total = counter.countConversation(conversation)

    // Should be sum of individual messages + conversation overhead (10)
    val individualSum = conversation.messages.map(counter.countMessage).sum
    total shouldBe (individualSum + 10) // 10 is conversation overhead
  }

  it should "return just overhead for empty conversation" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val conversation = Conversation(Seq.empty)

    val total = counter.countConversation(conversation)

    total shouldBe 10 // Just conversation overhead
  }

  it should "scale with conversation size" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get

    val small  = counter.countConversation(ContextTestFixtures.smallConversation)
    val medium = counter.countConversation(ContextTestFixtures.mediumConversation)
    val large  = counter.countConversation(ContextTestFixtures.largeConversation)

    small should be < medium
    medium should be < large
  }

  // ============ getTokenBreakdown ============

  "ConversationTokenCounter.getTokenBreakdown" should "return per-message counts" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val breakdown = counter.getTokenBreakdown(conversation)

    breakdown.messages.length shouldBe conversation.messages.length
    breakdown.overhead shouldBe 10
    breakdown.totalTokens shouldBe counter.countConversation(conversation)
  }

  it should "provide message role in breakdown" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get
    val conversation = Conversation(
      Seq(
        UserMessage("Hello"),
        AssistantMessage("Hi there")
      )
    )

    val breakdown = counter.getTokenBreakdown(conversation)

    breakdown.messages(0).role shouldBe MessageRole.User
    breakdown.messages(1).role shouldBe MessageRole.Assistant
  }

  it should "provide message preview in breakdown" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val longContent  = "A" * 100
    val conversation = Conversation(Seq(UserMessage(longContent)))

    val breakdown = counter.getTokenBreakdown(conversation)

    // Preview should be truncated to 50 chars
    breakdown.messages(0).preview.length shouldBe 50
  }

  it should "produce readable prettyPrint output" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val conversation = ContextTestFixtures.smallConversation

    val breakdown = counter.getTokenBreakdown(conversation)
    val output    = breakdown.prettyPrint()

    output should include("Token Breakdown")
    output should include("Total:")
    output should include("Overhead:")
  }

  // ============ Consistency Tests ============

  "ConversationTokenCounter" should "produce consistent counts for same input" in {
    val counter      = ConversationTokenCounter.cl100k().toOption.get
    val conversation = ContextTestFixtures.mediumConversation

    val count1 = counter.countConversation(conversation)
    val count2 = counter.countConversation(conversation)
    val count3 = counter.countConversation(conversation)

    count1 shouldBe count2
    count2 shouldBe count3
  }

  it should "count differently sized messages proportionally" in {
    val counter = ConversationTokenCounter.cl100k().toOption.get

    val short = counter.countMessage(UserMessage("Hi"))
    val long  = counter.countMessage(UserMessage("This is a much longer message with many more words"))

    long should be > short
  }

  // ============ Thinking ============

  "ConversationTokenCounter" should "count an assistant message's thinking text, which providers send back" in {
    val counter = ContextTestFixtures.createSimpleCounter()
    val plain   = AssistantMessage("Done.")
    // 4 characters per token with the simple tokenizer
    counter.countMessage(plain.withThinking("x" * 4000)) shouldBe counter.countMessage(plain) + 1000
    counter.countMessage(
      plain.withThinking(Seq(ThinkingBlock.Text("x" * 400, Some("sig")), ThinkingBlock.Text("y" * 400)))
    ) shouldBe counter.countMessage(plain) + 200
  }

  it should "estimate redacted thinking from its size" in {
    val counter = ContextTestFixtures.createSimpleCounter()
    val plain   = AssistantMessage("Done.")
    counter.countMessage(plain.withThinking(Seq(ThinkingBlock.Redacted("z" * 401)))) shouldBe
      counter.countMessage(plain) + 101
  }

  it should "let thinking count toward the conversation, so TokenWindow trims a reasoning-heavy turn" in {
    val counter  = ContextTestFixtures.createSimpleCounter()
    val thinking = AssistantMessage("Ok.").withThinking("r" * 40000)
    val convo    = Conversation(Seq(UserMessage("Q1"), thinking, UserMessage("Q2")))
    counter.countConversation(convo) should be > 10000
    TokenWindow.fitsInBudget(convo, counter, 1000) shouldBe false
    val window = TokenWindow.trimToBudget(convo, counter, 1000)
    window.map(_.wasTrimmed) shouldBe Right(true)
    window.map(_.conversation.messages.contains(thinking)) shouldBe Right(false)
  }

  "countMessage" should "count a message's media, which its text view leaves out" in {
    val counter = ConversationTokenCounter.o200k().toOption.get
    val image = ContentPart
      .image(org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/a.png")), org.llm4s.media.MediaType.Png)
      .toOption
      .get
    val text = counter.countMessage(UserMessage("look"))
    counter.countMessage(UserMessage(Vector(ContentPart.Text("look"), image, image))) shouldBe
      text + 2 * ContentPart.EstimatedImageTokens
    counter.countMessage(ToolMessage(Vector(image), "c")) should be >= ContentPart.EstimatedImageTokens
  }
}
