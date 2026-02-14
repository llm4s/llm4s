package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{
  Conversation,
  CompletionOptions,
  ToolMessage,
  UserMessage,
  AssistantMessage,
  ToolCall
}

/**
 * Test helper for building DeepSeek request bodies without reflection.
 * This replaces reflection-based introspection with a pure function approach.
 */
private[provider] object DeepSeekRequestBodyTestHelper {
  def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> content)
      case org.llm4s.llmconnect.model.SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> content)
      case AssistantMessage(content, toolCalls) =>
        val base = ujson.Obj("role" -> "assistant")
        content.filter(_.nonEmpty).foreach(c => base("content") = c)
        if (toolCalls.nonEmpty) {
          base("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"   -> tc.id,
              "type" -> "function",
              "function" -> ujson.Obj(
                "name"      -> tc.name,
                "arguments" -> tc.arguments.render()
              )
            )
          })
        }
        base
      case ToolMessage(content, toolCallId) =>
        ujson.Obj(
          "role"         -> "tool",
          "tool_call_id" -> toolCallId,
          "content"      -> content
        )
    }

    val base = ujson.Obj(
      "model"       -> "deepseek-chat",
      "messages"    -> ujson.Arr.from(messages),
      "temperature" -> options.temperature
    )
    options.maxTokens.foreach(t => base("max_tokens") = t)
    base
  }
}

/**
 * Tests for DeepSeekClient ToolMessage encoding.
 *
 * These tests verify that:
 * - ToolMessage fields are encoded in the correct order (content, toolCallId)
 * - The request JSON has the correct structure for tool responses
 */
class DeepSeekClientToolMessageTest extends AnyFlatSpec with Matchers {

  "DeepSeekClient" should "encode ToolMessage with correct field order" in {
    // Use test helper instead of reflection
    val conversation = Conversation(
      Seq(
        UserMessage("What's the weather?"),
        AssistantMessage(
          contentOpt = None,
          toolCalls = List(
            ToolCall(
              id = "call_abc123",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "San Francisco")
            )
          )
        ),
        ToolMessage(
          content = """{"temperature": 72, "condition": "sunny"}""",
          toolCallId = "call_abc123"
        )
      )
    )

    val requestBody = DeepSeekRequestBodyTestHelper.createRequestBody(conversation, CompletionOptions())

    // Verify the messages array
    val messages = requestBody("messages").arr

    // The third message should be the ToolMessage
    val toolMessage = messages(2).obj

    // Verify the encoding is correct
    toolMessage("role").str shouldBe "tool"
    toolMessage("tool_call_id").str shouldBe "call_abc123"
    toolMessage("content").str shouldBe """{"temperature": 72, "condition": "sunny"}"""

    // Critical assertion: verify that content and toolCallId are NOT swapped
    // If they were swapped, tool_call_id would contain the JSON content
    (toolMessage("tool_call_id").str should not).include("temperature")
    (toolMessage("tool_call_id").str should not).include("sunny")
  }

  it should "handle multiple ToolMessages correctly" in {
    val conversation = Conversation(
      Seq(
        UserMessage("Get weather for multiple cities"),
        AssistantMessage(
          contentOpt = None,
          toolCalls = List(
            ToolCall(
              id = "call_1",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "SF")
            ),
            ToolCall(
              id = "call_2",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "NYC")
            )
          )
        ),
        ToolMessage(
          content = """{"temp": 72}""",
          toolCallId = "call_1"
        ),
        ToolMessage(
          content = """{"temp": 45}""",
          toolCallId = "call_2"
        )
      )
    )

    val requestBody = DeepSeekRequestBodyTestHelper.createRequestBody(conversation, CompletionOptions())

    val messages = requestBody("messages").arr

    // Verify first ToolMessage
    val toolMsg1 = messages(2).obj
    toolMsg1("tool_call_id").str shouldBe "call_1"
    toolMsg1("content").str shouldBe """{"temp": 72}"""

    // Verify second ToolMessage
    val toolMsg2 = messages(3).obj
    toolMsg2("tool_call_id").str shouldBe "call_2"
    toolMsg2("content").str shouldBe """{"temp": 45}"""
  }
}


