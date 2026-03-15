package org.llm4s.rag.transform

import org.llm4s.error.{ APIError, ProcessingError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for LLMQueryRewriter.
 */
class LLMQueryRewriterSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Mock LLM Client
  // ==========================================================================

  private class MockLLMClient(response: String) extends LLMClient {
    var lastConversation: Option[Conversation] = None
    var lastOptions: Option[CompletionOptions] = None
    var callCount: Int                         = 0

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      callCount += 1
      lastConversation = Some(conversation)
      lastOptions = Some(options)
      Right(
        Completion(
          id = s"mock-${System.currentTimeMillis()}",
          created = System.currentTimeMillis() / 1000,
          content = response,
          model = "mock-model",
          message = AssistantMessage(contentOpt = Some(response)),
          usage = Some(TokenUsage(promptTokens = 50, completionTokens = 20, totalTokens = 70))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private class FailingLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Left(APIError("mock-provider", "LLM service unavailable", statusCode = Some(500)))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ==========================================================================
  // Constructor Tests
  // ==========================================================================

  "LLMQueryRewriter" should "have correct name" in {
    val rewriter = LLMQueryRewriter(new MockLLMClient("rewritten"))
    rewriter.name shouldBe "llm-query-rewriter"
  }

  it should "use default system prompt" in {
    val mock     = new MockLLMClient("rewritten query")
    val rewriter = LLMQueryRewriter(mock)

    rewriter.transform("test query")

    val conversation = mock.lastConversation.get
    conversation.messages.head shouldBe a[SystemMessage]
    val systemMsg = conversation.messages.head.asInstanceOf[SystemMessage]
    systemMsg.content should include("query rewriting assistant")
  }

  it should "accept custom system prompt" in {
    val mock     = new MockLLMClient("rewritten")
    val rewriter = LLMQueryRewriter(mock, "Custom prompt: rewrite this")

    rewriter.transform("test")

    val conversation = mock.lastConversation.get
    val systemMsg    = conversation.messages.head.asInstanceOf[SystemMessage]
    systemMsg.content shouldBe "Custom prompt: rewrite this"
  }

  // ==========================================================================
  // Transform Tests
  // ==========================================================================

  it should "return rewritten query on success" in {
    val rewriter = LLMQueryRewriter(new MockLLMClient("RAGConfig configuration options"))
    val result   = rewriter.transform("tell me about that config thing")

    result shouldBe Right("RAGConfig configuration options")
  }

  it should "trim whitespace from LLM response" in {
    val rewriter = LLMQueryRewriter(new MockLLMClient("  rewritten query  \n"))
    val result   = rewriter.transform("test")

    result shouldBe Right("rewritten query")
  }

  it should "send user query as user message" in {
    val mock     = new MockLLMClient("rewritten")
    val rewriter = LLMQueryRewriter(mock)

    rewriter.transform("my original query")

    val conversation = mock.lastConversation.get
    conversation.messages should have size 2
    conversation.messages(1) shouldBe a[UserMessage]
    val userMsg = conversation.messages(1).asInstanceOf[UserMessage]
    userMsg.content shouldBe "my original query"
  }

  it should "use temperature 0 for deterministic rewriting" in {
    val mock     = new MockLLMClient("rewritten")
    val rewriter = LLMQueryRewriter(mock)

    rewriter.transform("test")
    mock.callCount shouldBe 1
    mock.lastOptions.get.temperature shouldBe 0.0
  }

  // ==========================================================================
  // Error Handling Tests
  // ==========================================================================

  it should "return ProcessingError when LLM fails" in {
    val rewriter = LLMQueryRewriter(new FailingLLMClient)
    val result   = rewriter.transform("test query")

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ProcessingError]
    result.left.toOption.get.message should include("Failed to rewrite query")
  }

  // ==========================================================================
  // Companion Object Tests
  // ==========================================================================

  "LLMQueryRewriter.apply" should "create with default prompt" in {
    val rewriter = LLMQueryRewriter(new MockLLMClient("test"))
    rewriter shouldBe a[LLMQueryRewriter]
  }

  it should "create with custom prompt" in {
    val rewriter = LLMQueryRewriter(new MockLLMClient("test"), "custom prompt")
    rewriter shouldBe a[LLMQueryRewriter]
  }

  "LLMQueryRewriter.DefaultSystemPrompt" should "not be empty" in {
    LLMQueryRewriter.DefaultSystemPrompt should not be empty
  }

  it should "contain key instructions" in {
    LLMQueryRewriter.DefaultSystemPrompt should include("rewritten query")
    LLMQueryRewriter.DefaultSystemPrompt should include("semantic")
  }
}
