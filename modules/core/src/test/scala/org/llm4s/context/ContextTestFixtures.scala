package org.llm4s.context

import org.llm4s.context.tokens.{ StringTokenizer, Token }
import org.llm4s.llmconnect.model._

/**
 * Shared test fixtures for context package tests.
 * Provides mock tokenizers and sample conversations for testing.
 */
object ContextTestFixtures {

  /**
   * A simple mock tokenizer that counts 1 token per 4 characters.
   * This makes token counts predictable for testing.
   */
  val simpleTokenizer: StringTokenizer = new StringTokenizer {
    override def encode(text: String): List[Token] = {
      // 4 characters = 1 token (simple approximation)
      val tokenCount = Math.ceil(text.length / 4.0).toInt
      (0 until tokenCount).map(i => Token(i)).toList
    }
  }

  /**
   * Create a ConversationTokenCounter using the simple mock tokenizer.
   * Uses a test-only factory instead of reflection.
   */
  def createSimpleCounter(): ConversationTokenCounter =
    ConversationTokenCounter.forTest(simpleTokenizer)

  // ============ Sample Messages ============

  val userMessage1: UserMessage           = UserMessage("Hello, how are you?")
  val userMessage2: UserMessage           = UserMessage("What is the weather like today?")
  val userMessage3: UserMessage           = UserMessage("Can you help me with a coding problem?")
  val userMessageLong: UserMessage        = UserMessage("A" * 400) // 100 tokens with simple tokenizer
  val assistantMessage1: AssistantMessage = AssistantMessage("I'm doing well, thank you for asking!")
  val assistantMessage2: AssistantMessage = AssistantMessage("The weather is sunny and warm today.")
  val assistantMessage3: AssistantMessage = AssistantMessage(
    "Of course! I'd be happy to help with your coding problem."
  )
  val assistantMessageLong: AssistantMessage = AssistantMessage("B" * 400) // 100 tokens

  val systemMessage: SystemMessage = SystemMessage("You are a helpful assistant.")

  val toolMessage: ToolMessage = ToolMessage(
    content = """{"result": "success", "data": "test"}""",
    toolCallId = "call_123"
  )

  val historyDigestMessage: AssistantMessage = AssistantMessage(
    "[HISTORY_SUMMARY]\nPrevious conversation discussed coding topics and weather."
  )

  // ============ Sample Conversations ============

  /** Small conversation: ~30 tokens (with simple tokenizer + overhead) */
  val smallConversation: Conversation = Conversation(
    Seq(
      userMessage1,
      assistantMessage1
    )
  )

  /** Medium conversation: ~100 tokens */
  val mediumConversation: Conversation = Conversation(
    Seq(
      userMessage1,
      assistantMessage1,
      userMessage2,
      assistantMessage2,
      userMessage3,
      assistantMessage3
    )
  )

  /** Large conversation with many messages */
  val largeConversation: Conversation = Conversation(
    (1 to 20).flatMap { i =>
      Seq(
        UserMessage(s"Question $i: What is the answer to topic $i?"),
        AssistantMessage(s"Answer $i: Here is the response about topic $i with some details.")
      )
    }
  )

  /** Conversation with tool messages */
  val conversationWithTools: Conversation = Conversation(
    Seq(
      userMessage1,
      AssistantMessage(
        content = "Let me check that for you.",
        toolCalls = Seq(ToolCall(id = "call_123", name = "get_data", arguments = ujson.Obj("query" -> "test")))
      ),
      toolMessage,
      AssistantMessage("Based on the data, here is the answer.")
    )
  )

  /** Conversation with HISTORY_SUMMARY pinned message */
  val conversationWithDigest: Conversation = Conversation(
    Seq(
      historyDigestMessage,
      userMessage1,
      assistantMessage1,
      userMessage2,
      assistantMessage2
    )
  )

  /** Conversation with system message */
  val conversationWithSystem: Conversation = Conversation(
    Seq(
      systemMessage,
      userMessage1,
      assistantMessage1
    )
  )

  /** Empty conversation (for edge case testing) */
  val emptyConversation: Conversation = Conversation(Seq.empty)

  /** Single message conversation */
  val singleMessageConversation: Conversation = Conversation(Seq(userMessage1))

  /** Conversation with very long messages */
  val longMessageConversation: Conversation = Conversation(
    Seq(
      userMessageLong,
      assistantMessageLong
    )
  )

  // ============ Helper Functions ============

  /**
   * Create a conversation with a specific number of Q&A pairs.
   */
  def createConversation(pairs: Int): Conversation = Conversation(
    (1 to pairs).flatMap { i =>
      Seq(
        UserMessage(s"Question $i"),
        AssistantMessage(s"Answer $i")
      )
    }
  )

  /**
   * Create a conversation that approximately fits within a token budget.
   * Uses 20 chars per message (5 tokens) + overhead.
   */
  def createConversationForBudget(targetTokens: Int): Conversation = {
    val tokensPerPair = 10 + 8                                           // 5 tokens each + message overhead (4 each)
    val pairs         = Math.max(1, (targetTokens - 10) / tokensPerPair) // subtract conversation overhead
    createConversation(pairs)
  }
}
