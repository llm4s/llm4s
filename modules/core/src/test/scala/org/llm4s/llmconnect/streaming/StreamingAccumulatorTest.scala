package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingAccumulatorTest extends AnyFunSuite with Matchers {

  test("should accumulate content from multiple chunks") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("Hello "), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("world"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("!"), None, None))

    accumulator.getCurrentContent shouldBe "Hello world!"
  }

  test("should handle chunks with no content") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("test"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))

    accumulator.getCurrentContent shouldBe "test"
  }

  test("should track message ID") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-123", Some("content"), None, None))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true
    completion.toOption.get.id shouldBe "msg-123"
  }

  test("should accumulate tool calls") {
    val accumulator = StreamingAccumulator.create()

    val toolCall1 = ToolCall("tool-1", "function1", ujson.Obj("arg" -> "value1"))
    val toolCall2 = ToolCall("tool-2", "function2", ujson.Obj("arg" -> "value2"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(toolCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("text"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(toolCall2), None))

    val toolCalls = accumulator.getCurrentToolCalls
    (toolCalls should have).length(2)
    toolCalls.head.name shouldBe "function1"
    toolCalls(1).name shouldBe "function2"
  }

  test("should track finish reason") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.isComplete shouldBe false

    accumulator.addChunk(StreamedChunk("msg-1", None, None, Some("stop")))
    accumulator.isComplete shouldBe true
  }

  test("should update token counts") {
    val accumulator = StreamingAccumulator.create()

    accumulator.updateTokens(100, 50)
    accumulator.addChunk(StreamedChunk("msg-1", Some("response"), None, None))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val usage = completion.toOption.get.usage
    usage.isDefined shouldBe true
    usage.get.promptTokens shouldBe 100
    usage.get.completionTokens shouldBe 50
    usage.get.totalTokens shouldBe 150
  }

  test("should create completion with accumulated data") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-456", Some("Hello "), None, None))
    accumulator.addChunk(StreamedChunk("msg-456", Some("world!"), None, None))
    accumulator.updateTokens(10, 5)
    accumulator.addChunk(StreamedChunk("msg-456", None, None, Some("stop")))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val comp = completion.toOption.get
    comp.id shouldBe "msg-456"
    comp.message.contentOpt shouldBe Some("Hello world!")
    comp.usage.get.totalTokens shouldBe 15
  }

  test("should clear accumulator state") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.updateTokens(10, 5)

    accumulator.getCurrentContent shouldBe "content"

    accumulator.clear()

    accumulator.getCurrentContent shouldBe ""
    accumulator.isComplete shouldBe false
  }

  test("should create snapshot of current state") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.updateTokens(10, 5)

    val snapshot = accumulator.snapshot()

    snapshot.content shouldBe "content"
    snapshot.messageId shouldBe Some("msg-1")
    snapshot.promptTokens shouldBe 10
    snapshot.completionTokens shouldBe 5
  }

  test("should handle partial tool call accumulation") {
    val accumulator = StreamingAccumulator.create()

    // Simulate receiving tool call in parts — empty sentinel signals "no args yet"
    val partialCall1 = ToolCall("tool-1", "get_weather", ujson.Obj())
    val partialCall2 = ToolCall("tool-1", "", ujson.Obj("location" -> "SF"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall2), None))

    val toolCalls = accumulator.getCurrentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.id shouldBe "tool-1"
    toolCalls.head.name shouldBe "get_weather"
  }

  test("should filter empty-object sentinel from tool call arguments") {
    val accumulator = StreamingAccumulator.create()

    // Empty Obj() is the sentinel for "no arguments yet" — should not be appended
    val sentinelCall = ToolCall("tool-1", "get_weather", ujson.Obj())
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(sentinelCall), None))

    val toolCalls = accumulator.getCurrentToolCalls
    (toolCalls should have).length(1)
    // Arguments should parse back to empty Obj since nothing was appended
    toolCalls.head.arguments shouldBe ujson.Obj()
  }

  test("should preserve raw string fragments for later assembly") {
    val accumulator = StreamingAccumulator.create()

    // Partial JSON arrives as ujson.Str fragments via StreamingToolArgumentParser
    val partial1 = ToolCall("tool-1", "search", ujson.Str("{\"query\":"))
    val partial2 = ToolCall("tool-1", "", ujson.Str("\"hello world\"}"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partial1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partial2), None))

    val toolCalls = accumulator.getCurrentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.arguments("query").str shouldBe "hello world"
  }

  test("should assemble tool call arguments from raw JSON fragments") {
    val accumulator = StreamingAccumulator.create()

    val partialCall1 = ToolCall("tool-1", "get_weather", ujson.Str("{\"location\":"))
    val partialCall2 = ToolCall("tool-1", "", ujson.Str("\"SF\"}"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall2), None))

    val toolCalls = accumulator.getCurrentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.arguments("location").str shouldBe "SF"
  }

  // ===========================================
  // Thinking content tests (Phase 4.1)
  // ===========================================

  test("should accumulate thinking content from chunks") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Let me ")))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("think about ")))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("this...")))

    accumulator.getCurrentThinking shouldBe Some("Let me think about this...")
    accumulator.hasThinking shouldBe true
  }

  test("should return None for thinking when no thinking content") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, None))

    accumulator.getCurrentThinking shouldBe None
    accumulator.hasThinking shouldBe false
  }

  test("should handle mixed content and thinking chunks") {
    val accumulator = StreamingAccumulator.create()

    // Thinking comes first
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Thinking...")))
    // Then content
    accumulator.addChunk(StreamedChunk("msg-1", Some("The answer is 42."), None, None, None))

    accumulator.getCurrentThinking shouldBe Some("Thinking...")
    accumulator.getCurrentContent shouldBe "The answer is 42."
  }

  test("should include thinking in completion") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Let me calculate...")))
    accumulator.addChunk(StreamedChunk("msg-1", Some("The answer is 42."), None, None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, Some("stop"), None))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val comp = completion.toOption.get
    comp.thinking shouldBe Some("Let me calculate...")
    comp.content shouldBe "The answer is 42."
    comp.hasThinking shouldBe true
  }

  test("should add thinking delta directly") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addThinkingDelta("First part. ")
    accumulator.addThinkingDelta("Second part.")

    accumulator.getCurrentThinking shouldBe Some("First part. Second part.")
  }

  test("should track thinking tokens") {
    val accumulator = StreamingAccumulator.create()

    accumulator.updateTokensWithThinking(100, 50, 200)
    accumulator.addChunk(StreamedChunk("msg-1", Some("response"), None, None, Some("thinking")))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val usage = completion.toOption.get.usage
    usage.isDefined shouldBe true
    usage.get.promptTokens shouldBe 100
    usage.get.completionTokens shouldBe 50
    usage.get.thinkingTokens shouldBe Some(200)
    usage.get.totalOutputTokens shouldBe 250
    usage.get.totalTokens shouldBe 350
  }

  test("should include thinking tokens in snapshot") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, Some("thinking")))
    accumulator.updateTokensWithThinking(10, 5, 20)

    val snapshot = accumulator.snapshot()

    snapshot.content shouldBe "content"
    snapshot.thinking shouldBe Some("thinking")
    snapshot.thinkingTokens shouldBe 20
  }

  test("should clear thinking content on clear") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, Some("thinking")))
    accumulator.updateTokensWithThinking(10, 5, 20)

    accumulator.getCurrentThinking shouldBe Some("thinking")
    accumulator.hasThinking shouldBe true

    accumulator.clear()

    accumulator.getCurrentThinking shouldBe None
    accumulator.hasThinking shouldBe false
  }
}
