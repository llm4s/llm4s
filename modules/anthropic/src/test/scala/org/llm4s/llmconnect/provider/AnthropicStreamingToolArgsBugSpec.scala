package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import org.llm4s.llmconnect.model.{ StreamedChunk, ToolCall }
import org.llm4s.llmconnect.streaming.StreamingAccumulator

/**
 * Regression tests for issue #820: AnthropicClient.streamComplete() was
 * silently dropping all tool-call arguments because input_json_delta events
 * were never forwarded to the StreamingAccumulator.
 *
 * These tests exercise the accumulator layer directly to verify that
 * tool-call argument fragments are correctly assembled.
 */
class AnthropicStreamingToolArgsBugSpec extends AnyFlatSpec with Matchers {

  private def toolStartChunk(msgId: String, toolId: String, toolName: String): StreamedChunk =
    StreamedChunk(
      id = msgId,
      content = None,
      toolCall = Some(ToolCall(id = toolId, name = toolName, arguments = ujson.Obj())),
      finishReason = None
    )

  private def argFragmentChunk(msgId: String, toolId: String, fragment: String): StreamedChunk =
    StreamedChunk(
      id = msgId,
      content = None,
      toolCall = Some(ToolCall(id = toolId, name = "", arguments = ujson.Str(fragment))),
      finishReason = None
    )

  private def stopChunk(msgId: String): StreamedChunk =
    StreamedChunk(
      id = msgId,
      content = None,
      toolCall = None,
      finishReason = Some("end_turn")
    )

  "StreamingAccumulator" should "assemble tool-call arguments from fragments" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(toolStartChunk("msg_1", "toolu_01A", "get_weather"))
    acc.addChunk(argFragmentChunk("msg_1", "toolu_01A", "{\"location\""))
    acc.addChunk(argFragmentChunk("msg_1", "toolu_01A", ": \"Paris\"}"))
    acc.addChunk(stopChunk("msg_1"))

    val calls = acc.getCurrentToolCalls
    calls should have size 1
    calls.head.name shouldBe "get_weather"
    calls.head.arguments shouldBe ujson.Obj("location" -> "Paris")
  }

  it should "handle multiple tool calls with separate argument streams" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(toolStartChunk("msg_1", "toolu_01A", "get_weather"))
    acc.addChunk(argFragmentChunk("msg_1", "toolu_01A", "{\"city\":\"London\"}"))
    acc.addChunk(toolStartChunk("msg_1", "toolu_01B", "get_time"))
    acc.addChunk(argFragmentChunk("msg_1", "toolu_01B", "{\"tz\":\"UTC\"}"))
    acc.addChunk(stopChunk("msg_1"))

    val calls = acc.getCurrentToolCalls
    calls should have size 2
    val byName = calls.map(c => c.name -> c).toMap
    byName("get_weather").arguments shouldBe ujson.Obj("city" -> "London")
    byName("get_time").arguments shouldBe ujson.Obj("tz" -> "UTC")
  }

  it should "handle many small argument fragments" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(toolStartChunk("msg_1", "toolu_01A", "search"))
    // Simulate character-level streaming
    val fullJson = "{\"query\":\"hello world\"}"
    fullJson.foreach(ch => acc.addChunk(argFragmentChunk("msg_1", "toolu_01A", ch.toString)))
    acc.addChunk(stopChunk("msg_1"))

    val calls = acc.getCurrentToolCalls
    calls should have size 1
    calls.head.arguments shouldBe ujson.Obj("query" -> "hello world")
  }

  it should "return empty arguments when no fragments are provided" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(toolStartChunk("msg_1", "toolu_01A", "no_args_tool"))
    acc.addChunk(stopChunk("msg_1"))

    val calls = acc.getCurrentToolCalls
    calls should have size 1
    calls.head.arguments shouldBe ujson.Obj()
  }

  it should "preserve tool call alongside text content" in {
    val acc = StreamingAccumulator.create()
    // Text content first
    acc.addChunk(StreamedChunk("msg_1", content = Some("Let me check "), toolCall = None, finishReason = None))
    acc.addChunk(StreamedChunk("msg_1", content = Some("the weather."), toolCall = None, finishReason = None))
    // Then tool call
    acc.addChunk(toolStartChunk("msg_1", "toolu_01A", "get_weather"))
    acc.addChunk(argFragmentChunk("msg_1", "toolu_01A", "{\"city\":\"NYC\"}"))
    acc.addChunk(stopChunk("msg_1"))

    acc.getCurrentContent shouldBe "Let me check the weather."
    val calls = acc.getCurrentToolCalls
    calls should have size 1
    calls.head.arguments shouldBe ujson.Obj("city" -> "NYC")
  }
}
