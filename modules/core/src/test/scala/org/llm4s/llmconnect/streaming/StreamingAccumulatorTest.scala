package org.llm4s.llmconnect.streaming

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.SmallStack
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class StreamingAccumulatorTest extends AnyFunSuite with Matchers {

  test("should accumulate content from multiple chunks") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("Hello "), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("world"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("!"), None, None))

    accumulator.currentContent shouldBe "Hello world!"
  }

  test("should handle chunks with no content") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("test"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None))

    accumulator.currentContent shouldBe "test"
  }

  test("should track message ID") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-123", Some("content"), None, None))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true
    completion.toOption.get.id shouldBe "msg-123"
  }

  test("should accumulate tool calls") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    val toolCall1 = ToolCall(ToolCallId("tool-1"), "function1", ujson.Obj("arg" -> "value1"))
    val toolCall2 = ToolCall(ToolCallId("tool-2"), "function2", ujson.Obj("arg" -> "value2"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(toolCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", Some("text"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(toolCall2), None))

    val toolCalls = accumulator.currentToolCalls
    (toolCalls should have).length(2)
    toolCalls.head.name shouldBe "function1"
    toolCalls(1).name shouldBe "function2"
  }

  test("the completion's toolCalls and content are its message's (#2807)") {
    // the completion used to hold a second, empty `toolCalls` beside the message's, which each client copied over
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    val call        = ToolCall(ToolCallId("tool-1"), "function1", ujson.Obj("arg" -> "value1"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("text"), None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(call), Some(FinishReason.ToolCalls)))

    val completion = accumulator.toCompletion.fold(e => fail(e.message), identity)
    completion.toolCalls.map(_.id) shouldBe Seq("tool-1")
    completion.toolCalls shouldBe completion.message.toolCalls
    completion.content shouldBe "text"
    completion.hasToolCalls shouldBe true
  }

  test("should track finish reason") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.isComplete shouldBe false

    accumulator.addChunk(StreamedChunk("msg-1", None, None, Some(FinishReason.Stop)))
    accumulator.isComplete shouldBe true
  }

  test("should update token counts") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.updateUsage(TokenUsage(100, 50, 150))
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
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-456", Some("Hello "), None, None))
    accumulator.addChunk(StreamedChunk("msg-456", Some("world!"), None, None))
    accumulator.updateUsage(TokenUsage(10, 5, 15))
    accumulator.addChunk(StreamedChunk("msg-456", None, None, Some(FinishReason.Stop)))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val comp = completion.toOption.get
    comp.id shouldBe "msg-456"
    comp.message.text shouldBe Some("Hello world!")
    comp.usage.get.totalTokens shouldBe 15
  }

  test("should clear accumulator state") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.updateUsage(TokenUsage(10, 5, 15))

    accumulator.currentContent shouldBe "content"

    accumulator.clear()

    accumulator.currentContent shouldBe ""
    accumulator.isComplete shouldBe false
  }

  test("should carry message id, content and tokens into the completion") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.updateUsage(TokenUsage(10, 5, 15))

    val completion = accumulator.toCompletion(Instant.EPOCH).toOption.get

    completion.content shouldBe "content"
    completion.id shouldBe "msg-1"
    completion.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((10, 5))
  }

  test("should handle partial tool call accumulation") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    // Simulate receiving tool call in parts — empty sentinel signals "no args yet"
    val partialCall1 = ToolCall(ToolCallId("tool-1"), "get_weather", ujson.Obj())
    val partialCall2 = ToolCall(ToolCallId("tool-1"), "", ujson.Obj("location" -> "SF"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall2), None))

    val toolCalls = accumulator.currentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.id shouldBe ToolCallId("tool-1")
    toolCalls.head.name shouldBe "get_weather"
  }

  test("should filter empty-object sentinel from tool call arguments") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    // Empty Obj() is the sentinel for "no arguments yet" — should not be appended
    val sentinelCall = ToolCall(ToolCallId("tool-1"), "get_weather", ujson.Obj())
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(sentinelCall), None))

    val toolCalls = accumulator.currentToolCalls
    (toolCalls should have).length(1)
    // Arguments should parse back to empty Obj since nothing was appended
    toolCalls.head.arguments shouldBe ujson.Obj()
  }

  test("should preserve raw string fragments for later assembly") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    // Partial JSON arrives as ujson.Str fragments
    val partial1 = ToolCall(ToolCallId("tool-1"), "search", ujson.Str("{\"query\":"))
    val partial2 = ToolCall(ToolCallId("tool-1"), "", ujson.Str("\"hello world\"}"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partial1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partial2), None))

    val toolCalls = accumulator.currentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.arguments("query").str shouldBe "hello world"
  }

  test("should assemble tool call arguments from raw JSON fragments") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    val partialCall1 = ToolCall(ToolCallId("tool-1"), "get_weather", ujson.Str("{\"location\":"))
    val partialCall2 = ToolCall(ToolCallId("tool-1"), "", ujson.Str("\"SF\"}"))

    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall1), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(partialCall2), None))

    val toolCalls = accumulator.currentToolCalls
    (toolCalls should have).length(1)
    toolCalls.head.arguments("location").str shouldBe "SF"
  }

  // ===========================================
  // Thinking content tests (Phase 4.1)
  // ===========================================

  test("should accumulate thinking content from chunks") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Let me ")))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("think about ")))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("this...")))

    accumulator.currentThinking shouldBe Some("Let me think about this...")
    accumulator.hasThinking shouldBe true
  }

  test("should return None for thinking when no thinking content") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, None))

    accumulator.currentThinking shouldBe None
    accumulator.hasThinking shouldBe false
  }

  test("should handle mixed content and thinking chunks") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    // Thinking comes first
    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Thinking...")))
    // Then content
    accumulator.addChunk(StreamedChunk("msg-1", Some("The answer is 42."), None, None, None))

    accumulator.currentThinking shouldBe Some("Thinking...")
    accumulator.currentContent shouldBe "The answer is 42."
  }

  test("should include thinking in completion") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", None, None, None, Some("Let me calculate...")))
    accumulator.addChunk(StreamedChunk("msg-1", Some("The answer is 42."), None, None, None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, Some(FinishReason.Stop), None))

    val completion = accumulator.toCompletion
    completion.isRight shouldBe true

    val comp = completion.toOption.get
    comp.thinking shouldBe Some("Let me calculate...")
    comp.content shouldBe "The answer is 42."
    comp.hasThinking shouldBe true
    // on the message, so it stays in the conversation history (#1381)
    comp.message.thinking shouldBe Seq(ThinkingBlock.Text("Let me calculate..."))
  }

  test("a stream without thinking leaves the message's thinking empty") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("Hi"), None, Some(FinishReason.Stop), None))
    accumulator.toCompletion.map(_.message.thinking) shouldBe Right(Seq.empty)
  }

  test("should track thinking tokens") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.updateUsage(TokenUsage(100, 50, 350, thinkingTokens = Some(200)))
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

  test("should include thinking tokens in the completion") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, Some("thinking")))
    accumulator.updateUsage(TokenUsage(10, 5, 35, thinkingTokens = Some(20)))

    val completion = accumulator.toCompletion(Instant.EPOCH).toOption.get

    completion.content shouldBe "content"
    completion.thinking shouldBe Some("thinking")
    completion.usage.flatMap(_.thinkingTokens) shouldBe Some(20)
  }

  test("should clear thinking content on clear") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None, Some("thinking")))
    accumulator.updateUsage(TokenUsage(10, 5, 35, thinkingTokens = Some(20)))

    accumulator.currentThinking shouldBe Some("thinking")
    accumulator.hasThinking shouldBe true

    accumulator.clear()

    accumulator.currentThinking shouldBe None
    accumulator.hasThinking shouldBe false
  }

  test("updateUsage carries every TokenUsage field onto the completion") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))
    accumulator.updateUsage(
      TokenUsage(
        promptTokens = 120,
        completionTokens = 40,
        totalTokens = 160,
        thinkingTokens = Some(24),
        cachedTokens = Some(80),
        cacheCreationTokens = Some(16)
      )
    )

    accumulator.toCompletion(Instant.EPOCH).toOption.get.usage shouldBe Some(
      TokenUsage(
        promptTokens = 120,
        completionTokens = 40,
        totalTokens = 160,
        thinkingTokens = Some(24),
        cachedTokens = Some(80),
        cacheCreationTokens = Some(16)
      )
    )
  }

  test("updateUsage merges partial reports, each field following the latest report that states it") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    // input and cache counts first, output last, as some providers split them
    accumulator.updateUsage(
      TokenUsage(promptTokens = 100, completionTokens = 0, totalTokens = 0, cachedTokens = Some(60))
    )
    accumulator.updateUsage(TokenUsage(promptTokens = 0, completionTokens = 30, totalTokens = 0))
    accumulator.updateUsage(
      TokenUsage(promptTokens = 0, completionTokens = 45, totalTokens = 0, cacheCreationTokens = Some(10))
    )

    val usage = accumulator.toCompletion(Instant.EPOCH).toOption.get.usage.get
    usage.promptTokens shouldBe 100
    usage.completionTokens shouldBe 45
    usage.cachedTokens shouldBe Some(60)
    usage.cacheCreationTokens shouldBe Some(10)
    usage.thinkingTokens shouldBe None
    usage.totalTokens shouldBe 145 // no report stated a total: prompt + completion + thinking
  }

  test("updateUsage reports usage that only has cache counts") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.updateUsage(TokenUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0, cachedTokens = Some(5)))

    accumulator.toCompletion(Instant.EPOCH).toOption.get.usage.flatMap(_.cachedTokens) shouldBe Some(5)
  }

  test("the completion reports the model the accumulator was created for") {
    val accumulator = StreamingAccumulator(ModelName("gpt-test"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("hi"), None, Some(FinishReason.Stop)))
    accumulator.toCompletion(Instant.EPOCH).map(_.model) shouldBe Right(ModelName("gpt-test"))
  }

  test("a report without a total after one with a total keeps the counts it does not state") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.updateUsage(
      TokenUsage(promptTokens = 100, completionTokens = 0, totalTokens = 0, cachedTokens = Some(40))
    )
    accumulator.updateUsage(TokenUsage(promptTokens = 0, completionTokens = 5, totalTokens = 0))

    val usage = accumulator.toCompletion(Instant.EPOCH).toOption.get.usage.get
    (usage.promptTokens, usage.completionTokens, usage.totalTokens, usage.cachedTokens) shouldBe ((
      100,
      5,
      105,
      Some(40)
    ))
  }

  test("clear resets the usage merged by updateUsage") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.updateUsage(
      TokenUsage(
        promptTokens = 100,
        completionTokens = 50,
        totalTokens = 150,
        thinkingTokens = Some(7),
        cachedTokens = Some(40),
        cacheCreationTokens = Some(3)
      )
    )
    accumulator.clear()

    accumulator.toCompletion(Instant.EPOCH).toOption.get.usage shouldBe None
  }

  test("should stamp the completion with the given creation time") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))

    val completion = accumulator.toCompletion(created = Instant.ofEpochSecond(1_700_000_000L))
    completion.toOption.get.created shouldBe Instant.ofEpochSecond(1_700_000_000L)
  }

  test("should stamp the completion with the current time when no creation time is given") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("content"), None, None))

    val before  = Instant.now()
    val created = accumulator.toCompletion.toOption.get.created
    val after   = Instant.now()

    created.isBefore(before) shouldBe false
    created.isAfter(after) shouldBe false
  }

  test("should return tool calls in the order the stream first named them, on the message too") {
    // Ids chosen so hash order and stream order disagree: before #1132 the partial calls sat in
    // an unordered map and came back in hash order.
    val ids         = Seq("call_zeta", "call_alpha", "call_mu", "call_beta", "call_omega", "call_c", "call_q", "call_k")
    val accumulator = StreamingAccumulator(ModelName("test-model"))

    // Each call opens with its id, name and first fragment, then all continue interleaved.
    ids.zipWithIndex.foreach { (id, i) =>
      accumulator.addChunk(
        StreamedChunk("msg-1", None, Some(ToolCall(ToolCallId(id), s"tool_$i", ujson.Str("{\"n\":"))), None)
      )
    }
    ids.reverse.zipWithIndex.foreach { (id, i) =>
      accumulator.addChunk(
        StreamedChunk("msg-1", None, Some(ToolCall(ToolCallId(id), "", ujson.Str(s"${ids.size - 1 - i}}"))), None)
      )
    }

    accumulator.currentToolCalls.map(_.id) shouldBe ids
    accumulator.currentToolCalls.map(_.name) shouldBe ids.indices.map(i => s"tool_$i")
    accumulator.currentToolCalls.map(_.arguments("n").num.toInt) shouldBe ids.indices

    val completion = accumulator.toCompletion.toOption.get
    completion.message.toolCalls.map(_.id) shouldBe ids
    accumulator.toCompletion(Instant.EPOCH).toOption.get.message.toolCalls.map(_.id) shouldBe ids
  }

  // Streamed arguments are model output: nested too deeply they are never parsed into a value that
  // overflows the stack of whatever renders it next (#1562). The live view keeps them as the raw Str;
  // the finished stream is a Left (RFC 0009, Part 3). On a 1 MB stack, so deterministic; the
  // comparisons happen inside it so no failure message renders a deep value.
  test("tool-call arguments nested too deeply are never parsed: a raw Str live, a Left at the end") {
    val deep        = "[" * 100000 + "]" * 100000
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", None, Some(ToolCall(ToolCallId("call-1"), "f", ujson.Str(deep))), None))
    accumulator.addChunk(StreamedChunk("msg-1", None, None, Some(FinishReason.ToolCalls)))

    SmallStack.run(accumulator.currentToolCalls.map(_.arguments == ujson.Str(deep))) shouldBe Right(Seq(true))
    SmallStack.run(accumulator.toCompletion.left.map(_.message.contains("more than 512 levels deep"))) shouldBe
      Right(Left(true))
  }

  // ===========================================
  // RFC 0009, Part 3: tool-call arguments
  // ===========================================

  private def streamed(arguments: Seq[String], reason: Option[FinishReason]): Result[Completion] = {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(
      StreamedChunk("msg-1", None, Some(ToolCall(ToolCallId("call-1"), "lookup", ujson.Obj())), None)
    )
    arguments.foreach { fragment =>
      accumulator.addChunk(
        StreamedChunk("msg-1", None, Some(ToolCall(ToolCallId("call-1"), "", ujson.Str(fragment))), None)
      )
    }
    reason.foreach(r => accumulator.addChunk(StreamedChunk("msg-1", None, None, Some(r))))
    accumulator.toCompletion(Instant.EPOCH)
  }

  private def malformedCall(result: Result[Completion]): Boolean = result match {
    case Left(e: ProcessingError) => e.message.contains("malformed tool call") && e.message.contains("lookup")
    case _                        => false
  }

  test("a call whose streamed arguments never close is a Left, never {} (rule 1)") {
    malformedCall(streamed(Seq("{\"q\":", "\"x"), Some(FinishReason.ToolCalls))) shouldBe true
  }

  test("a call whose streamed arguments are JSON but not an object is a Left (rule 1)") {
    malformedCall(streamed(Seq("[1,", "2]"), Some(FinishReason.ToolCalls))) shouldBe true
  }

  test("a call with no arguments is {} after a ToolCalls or Stop end (rule 2)") {
    Seq(FinishReason.ToolCalls, FinishReason.Stop).foreach { reason =>
      streamed(Nil, Some(reason)).map(_.message.toolCalls.map(_.arguments)) shouldBe Right(Seq(ujson.Obj()))
    }
  }

  test("a call with no arguments is a Left when the stream did not end with ToolCalls or Stop (rule 2)") {
    malformedCall(streamed(Nil, None)) shouldBe true
    malformedCall(streamed(Nil, Some(FinishReason.Other("function_call_filter")))) shouldBe true
  }

  test("a tool call in a stream the output limit cut is a Left, even with whole arguments (rule 3)") {
    val cut = streamed(Seq("{\"q\":\"x\"}"), Some(FinishReason.Length))
    malformedCall(cut) shouldBe true
    cut.left.map(_.message.contains("FinishReason.Length")) shouldBe Left(true)
  }

  test("a stream cut by the output limit with no tool call is still a Right") {
    val accumulator = StreamingAccumulator(ModelName("test-model"))
    accumulator.addChunk(StreamedChunk("msg-1", Some("partial"), None, Some(FinishReason.Length)))
    accumulator.toCompletion.map(_.finishReason) shouldBe Right(FinishReason.Length)
  }
}
