package org.llm4s.llmconnect.streaming

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Regression test for the SSE-based AnthropicStreamingHandler path (issue #820).
 * Verifies that content_block_start events register tool calls and
 * input_json_delta events accumulate their arguments.
 */
class AnthropicStreamingHandlerToolArgsSpec extends AnyFlatSpec with Matchers {

  private def sseEvent(eventType: String, data: String): String =
    s"event: $eventType\ndata: $data\n\n"

  "AnthropicStreamingHandler" should "accumulate tool-call arguments from SSE events" in {
    val handler = new AnthropicStreamingHandler

    handler.processChunk(
      sseEvent(
        "message_start",
        """{"message":{"id":"msg_01","type":"message","role":"assistant"}}"""
      )
    )

    handler.processChunk(
      sseEvent(
        "content_block_start",
        """{"index":0,"content_block":{"type":"tool_use","id":"toolu_01A","name":"get_weather","input":{}}}"""
      )
    )

    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"location\""}}"""
      )
    )
    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":0,"delta":{"type":"input_json_delta","partial_json":": \"Paris\"}"}}"""
      )
    )

    handler.processChunk(sseEvent("message_stop", "{}"))

    val completion = handler.getCompletion
    completion.isRight shouldBe true
    val calls = completion.toOption.get.message.toolCalls
    calls should have size 1
    calls.head.id shouldBe "toolu_01A"
    calls.head.name shouldBe "get_weather"
    calls.head.arguments shouldBe ujson.Obj("location" -> "Paris")
  }

  it should "handle multiple tool calls at different block indices" in {
    val handler = new AnthropicStreamingHandler

    handler.processChunk(
      sseEvent("message_start", """{"message":{"id":"msg_02"}}""")
    )

    handler.processChunk(
      sseEvent(
        "content_block_start",
        """{"index":0,"content_block":{"type":"tool_use","id":"toolu_A","name":"tool_a","input":{}}}"""
      )
    )
    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}"""
      )
    )

    handler.processChunk(
      sseEvent(
        "content_block_start",
        """{"index":1,"content_block":{"type":"tool_use","id":"toolu_B","name":"tool_b","input":{}}}"""
      )
    )
    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"y\":2}"}}"""
      )
    )

    handler.processChunk(sseEvent("message_stop", "{}"))

    val completion = handler.getCompletion
    completion.isRight shouldBe true
    val calls = completion.toOption.get.message.toolCalls
    calls should have size 2
    val byName = calls.map(c => c.name -> c).toMap
    byName("tool_a").arguments shouldBe ujson.Obj("x" -> 1)
    byName("tool_b").arguments shouldBe ujson.Obj("y" -> 2)
  }

  it should "interleave text and tool-call content" in {
    val handler = new AnthropicStreamingHandler

    handler.processChunk(
      sseEvent("message_start", """{"message":{"id":"msg_03"}}""")
    )

    // Text block
    handler.processChunk(
      sseEvent(
        "content_block_start",
        """{"index":0,"content_block":{"type":"text","text":""}}"""
      )
    )
    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":0,"delta":{"type":"text_delta","text":"Checking weather..."}}"""
      )
    )

    // Tool block
    handler.processChunk(
      sseEvent(
        "content_block_start",
        """{"index":1,"content_block":{"type":"tool_use","id":"toolu_C","name":"weather","input":{}}}"""
      )
    )
    handler.processChunk(
      sseEvent(
        "content_block_delta",
        """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"NYC\"}"}}"""
      )
    )

    handler.processChunk(sseEvent("message_stop", "{}"))

    val completion = handler.getCompletion
    completion.isRight shouldBe true
    val result = completion.toOption.get
    result.content shouldBe "Checking weather..."
    result.message.toolCalls should have size 1
    result.message.toolCalls.head.arguments shouldBe ujson.Obj("city" -> "NYC")
  }
}
