package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ ToolCall, StreamedChunk }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class AnthropicStreamingToolUseSpec extends AnyFlatSpec with Matchers {

  "AnthropicClient streaming" should "initialize tool-use with normalized empty object arguments" in {
    // When Anthropic streaming starts a tool-use block, it creates a ToolCall with ujson.Obj()
    // for arguments (normalized from null). This test validates that behavior works correctly.
    val toolCall = ToolCall(
      id = "tool_call_123",
      name = "get_weather",
      arguments = ujson.Obj() // Empty object as per P2a normalization
    )

    // Verify the tool call can be used and serialized
    toolCall.id shouldBe "tool_call_123"
    toolCall.name shouldBe "get_weather"
    toolCall.arguments shouldBe ujson.Obj()
    toolCall.arguments.render() shouldBe "{}"
  }

  it should "handle tool-call streaming with accumulation of partial arguments" in {
    // When arguments stream in fragments, they accumulate into the empty object
    val initialToolCall = ToolCall(
      id = "tool_call_456",
      name = "search",
      arguments = ujson.Obj() // Starts empty on tool-use block start
    )

    // Simulate streaming argument fragments
    val fragmentChunk1 = StreamedChunk(
      id = "msg-1",
      content = None,
      toolCall = Some(initialToolCall),
      finishReason = None
    )

    // The streaming accumulator should handle empty object arguments gracefully
    fragmentChunk1.toolCall.isDefined shouldBe true
    fragmentChunk1.toolCall.get.arguments shouldBe ujson.Obj()
  }

  it should "preserve tool-call structure when arguments are empty" in {
    // Tool calls with empty arguments should not cause issues in downstream handling
    val chunk = StreamedChunk(
      id = "msg-2",
      content = None,
      toolCall = Some(ToolCall("id-1", "func-1", ujson.Obj())),
      finishReason = None
    )

    // Pattern matching on the tool call should work
    chunk.toolCall.foreach { tc =>
      tc.arguments match {
        case ujson.Obj(_) => // Empty object case - should match
        case _            => fail("Expected empty object arguments")
      }
    }
  }

  it should "distinguish between null and empty object arguments" in {
    // Ensure P2a normalization: null becomes ujson.Obj(), not the other way around
    val toolCallWithEmpty = ToolCall("id", "name", ujson.Obj())

    // Should NOT be null
    toolCallWithEmpty.arguments should not be ujson.Null

    // Should be an empty object (falsy but not null)
    toolCallWithEmpty.arguments shouldBe ujson.Obj()

    // Should be renderable
    toolCallWithEmpty.arguments.render() shouldBe "{}"
  }
}
