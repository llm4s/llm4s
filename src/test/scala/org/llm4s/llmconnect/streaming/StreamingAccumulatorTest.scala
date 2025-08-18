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

    accumulator.getCurrentContent() shouldBe "Hello world!"
  }

  test("should handle chunks with no content") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("test"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))

    accumulator.getCurrentContent() shouldBe "test"
  }

  test("should track message ID") {
    val accumulator = StreamingAccumulator.create()

    accumulator.addChunk(StreamedChunk("msg-123", Some("content"), None, None))

    val completion = accumulator.toCompletion()
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

    val toolCalls = accumulator.getCurrentToolCalls()
    (toolCalls should have).length(2)
    toolCalls(0).name shouldBe "function1"
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

    val completion = accumulator.toCompletion()
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

    val completion = accumulator.toCompletion()
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

    accumulator.getCurrentContent() shouldBe "content"

    accumulator.clear()

    accumulator.getCurrentContent() shouldBe ""
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

    // Simulate receiving tool call in parts
    val partialCall1 = ToolCall("tool-1", "get_weather", ujson.Null)
    val partialCall2 = ToolCall("tool-1", "", ujson.Obj("location" -> "SF"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall2), None))

    val toolCalls = accumulator.getCurrentToolCalls()
    (toolCalls should have).length(1)
    toolCalls(0).id shouldBe "tool-1"
    toolCalls(0).name shouldBe "get_weather"
  }
}
