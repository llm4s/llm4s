package org.llm4s.llmconnect.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CompletionSpec extends AnyFunSuite with Matchers {

  // ================================= COMPLETION =================================

  test("Completion.asText returns content") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "Hello, world!",
      model = "gpt-4",
      message = AssistantMessage("Hello, world!")
    )

    completion.asText shouldBe "Hello, world!"
  }

  test("Completion.hasToolCalls returns true when tool calls present") {
    val toolCall = ToolCall("call-1", "get_weather", ujson.Obj("location" -> "NYC"))
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "",
      model = "gpt-4",
      message = AssistantMessage(None, Seq(toolCall)),
      toolCalls = List(toolCall)
    )

    completion.hasToolCalls shouldBe true
  }

  test("Completion.hasToolCalls returns false when no tool calls") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "Hello",
      model = "gpt-4",
      message = AssistantMessage("Hello")
    )

    completion.hasToolCalls shouldBe false
  }

  test("Completion.hasThinking returns true when thinking content present") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "The answer is 4",
      model = "claude-3",
      message = AssistantMessage("The answer is 4"),
      thinking = Some("Let me calculate 2 + 2...")
    )

    completion.hasThinking shouldBe true
  }

  test("Completion.hasThinking returns false when thinking is empty") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "Hello",
      model = "gpt-4",
      message = AssistantMessage("Hello"),
      thinking = Some("")
    )

    completion.hasThinking shouldBe false
  }

  test("Completion.fullContent includes thinking when present") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "The answer is 4",
      model = "claude-3",
      message = AssistantMessage("The answer is 4"),
      thinking = Some("Let me think...")
    )

    completion.fullContent should include("<thinking>")
    completion.fullContent should include("Let me think...")
    completion.fullContent should include("The answer is 4")
  }

  test("Completion.fullContent returns only content when no thinking") {
    val completion = Completion(
      id = "test-id",
      created = 1234567890L,
      content = "Hello",
      model = "gpt-4",
      message = AssistantMessage("Hello")
    )

    completion.fullContent shouldBe "Hello"
  }

  // ================================= TOKEN USAGE =================================

  test("TokenUsage.totalOutputTokens includes thinking tokens") {
    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      thinkingTokens = Some(200)
    )

    usage.totalOutputTokens shouldBe 250
  }

  test("TokenUsage.totalOutputTokens returns completionTokens when no thinking") {
    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150
    )

    usage.totalOutputTokens shouldBe 50
  }

  test("TokenUsage.hasThinkingTokens returns true when thinking tokens present") {
    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      thinkingTokens = Some(200)
    )

    usage.hasThinkingTokens shouldBe true
  }

  test("TokenUsage.hasThinkingTokens returns false when thinking tokens is zero") {
    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      thinkingTokens = Some(0)
    )

    usage.hasThinkingTokens shouldBe false
  }

  // ================================= STREAMED CHUNK =================================

  test("StreamedChunk.hasThinking returns true when thinking delta present") {
    val chunk = StreamedChunk(
      id = "msg-1",
      content = None,
      thinkingDelta = Some("Thinking...")
    )

    chunk.hasThinking shouldBe true
  }

  test("StreamedChunk.hasContent returns true when content present") {
    val chunk = StreamedChunk(
      id = "msg-1",
      content = Some("Hello")
    )

    chunk.hasContent shouldBe true
  }

  test("StreamedChunk.hasContent returns false when content is empty") {
    val chunk = StreamedChunk(
      id = "msg-1",
      content = Some("")
    )

    chunk.hasContent shouldBe false
  }

  // ================================= COMPLETION CHUNK =================================

  test("CompletionChunk.isComplete returns true when finishReason is set") {
    val chunk = CompletionChunk(
      id = "chunk-1",
      finishReason = Some("stop")
    )

    chunk.isComplete shouldBe true
  }

  test("CompletionChunk.isComplete returns false when finishReason is None") {
    val chunk = CompletionChunk(
      id = "chunk-1",
      content = Some("Hello")
    )

    chunk.isComplete shouldBe false
  }

  test("CompletionChunk.asText returns content or empty string") {
    val chunkWithContent    = CompletionChunk(id = "chunk-1", content = Some("Hello"))
    val chunkWithoutContent = CompletionChunk(id = "chunk-2")

    chunkWithContent.asText shouldBe "Hello"
    chunkWithoutContent.asText shouldBe ""
  }

  // ================================= CHUNK DELTA =================================

  test("ChunkDelta.empty creates empty delta") {
    val delta = ChunkDelta.empty

    delta.content shouldBe None
    delta.role shouldBe None
    delta.toolCalls shouldBe empty
  }
}
