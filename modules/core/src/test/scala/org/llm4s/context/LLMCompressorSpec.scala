package org.llm4s.context

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMCompressorSpec extends AnyFlatSpec with Matchers {

  val counter: ConversationTokenCounter = ContextTestFixtures.createSimpleCounter()

  /**
   * Mock LLM client that returns a compressed version of input
   */
  val mockLLMClient: LLMClient = new LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      // Return a "compressed" response - just take first 50 chars
      val lastUserContent = conversation.messages.collect { case m: UserMessage => m.content }.lastOption
      val compressed      = lastUserContent.map(_.take(50) + "...").getOrElse("Compressed.")
      Right(
        Completion(
          id = "mock-completion-id",
          created = System.currentTimeMillis() / 1000,
          content = compressed,
          model = "mock-model",
          message = AssistantMessage(compressed),
          toolCalls = Nil,
          usage = Some(TokenUsage(10, 5, 15))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      onChunk(StreamedChunk(id = "chunk-1", content = Some("Compressed.")))
      Right(
        Completion(
          id = "mock-completion-id",
          created = System.currentTimeMillis() / 1000,
          content = "Compressed.",
          model = "mock-model",
          message = AssistantMessage("Compressed."),
          toolCalls = Nil,
          usage = Some(TokenUsage(10, 5, 15))
        )
      )
    }

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1000
  }

  // ============ squeezeDigest - Skip Cases ============

  "LLMCompressor.squeezeDigest" should "skip compression when no HISTORY_SUMMARY messages present" in {
    val messages = Seq(
      UserMessage("Hello"),
      AssistantMessage("Hi there!")
    )
    val capTokens = 100

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
    result.toOption.get shouldBe messages
  }

  it should "skip compression when digests already within cap" in {
    // Create a short HISTORY_SUMMARY that's already under cap
    val shortSummary = "[HISTORY_SUMMARY]\nBrief summary."
    val messages = Seq(
      SystemMessage(shortSummary),
      UserMessage("Current question"),
      AssistantMessage("Current answer")
    )
    val capTokens = 500 // Large cap - should not trigger compression

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
    // Should return original messages since under cap
    result.toOption.get.exists(_.content == shortSummary) shouldBe true
  }

  it should "preserve non-HISTORY_SUMMARY messages" in {
    val messages = Seq(
      SystemMessage("[HISTORY_SUMMARY]\n" + ("Summary content. " * 100)), // Long to exceed cap
      UserMessage("Current question"),
      AssistantMessage("Current answer")
    )
    val capTokens = 10 // Small cap to trigger compression

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Non-digest messages should be preserved
    processed.exists(_.content == "Current question") shouldBe true
    processed.exists(_.content == "Current answer") shouldBe true
  }

  // ============ squeezeDigest - Compression ============

  it should "compress HISTORY_SUMMARY messages when over cap" in {
    // Create a long HISTORY_SUMMARY that exceeds cap
    val longSummary = "[HISTORY_SUMMARY]\n" + ("Detailed history information. " * 50)
    val messages = Seq(
      SystemMessage(longSummary),
      UserMessage("Question"),
      AssistantMessage("Answer")
    )
    val summaryTokens = counter.countMessage(SystemMessage(longSummary))
    val capTokens     = summaryTokens / 2 // Force compression

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Total messages should be same (non-digests + compressed digest)
    processed.length shouldBe 3
  }

  // ============ Edge Cases ============

  "LLMCompressor" should "handle empty message list" in {
    val result = LLMCompressor.squeezeDigest(Seq.empty, counter, mockLLMClient, 100)

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  it should "handle single HISTORY_SUMMARY message" in {
    val summary   = "[HISTORY_SUMMARY]\nSingle summary"
    val messages  = Seq(SystemMessage(summary))
    val capTokens = 500 // Within cap

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
  }

  it should "handle multiple HISTORY_SUMMARY messages" in {
    val messages = Seq(
      SystemMessage("[HISTORY_SUMMARY]\n" + ("Summary 1. " * 50)),
      SystemMessage("[HISTORY_SUMMARY]\n" + ("Summary 2. " * 50)),
      UserMessage("Question"),
      AssistantMessage("Answer")
    )
    val capTokens = 20 // Small cap to trigger compression

    val result = LLMCompressor.squeezeDigest(messages, counter, mockLLMClient, capTokens)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Non-digest messages should still be present
    processed.exists(_.content == "Question") shouldBe true
    processed.exists(_.content == "Answer") shouldBe true
  }
}
