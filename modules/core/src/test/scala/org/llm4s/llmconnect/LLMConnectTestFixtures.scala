package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.llm4s.llmconnect.model._

/**
 * Shared test fixtures for llmconnect package tests.
 *
 * Provides sample data for:
 *  - Conversations (simple, with tools, multi-turn)
 *  - Tool calls and definitions
 *  - Streaming chunks
 *  - Provider configurations
 *  - Sample JSON responses
 */
object LLMConnectTestFixtures {

  // ================================= MESSAGES =================================

  val simpleSystemMessage: SystemMessage       = SystemMessage("You are a helpful assistant.")
  val simpleUserMessage: UserMessage           = UserMessage("What is 2 + 2?")
  val simpleAssistantMessage: AssistantMessage = AssistantMessage("The answer is 4.")

  // ================================= TOOL CALLS =================================

  val sampleToolCall: ToolCall = ToolCall(
    id = "call_abc123",
    name = "get_weather",
    arguments = ujson.Obj("location" -> "San Francisco", "unit" -> "celsius")
  )

  val sampleToolCall2: ToolCall = ToolCall(
    id = "call_def456",
    name = "search_web",
    arguments = ujson.Obj("query" -> "Scala programming")
  )

  val assistantWithToolCalls: AssistantMessage = AssistantMessage(
    contentOpt = Some("I'll check the weather for you."),
    toolCalls = Seq(sampleToolCall)
  )

  val toolResponse: ToolMessage = ToolMessage(
    content = """{"temperature": 18, "condition": "sunny"}""",
    toolCallId = "call_abc123"
  )

  // ================================= CONVERSATIONS =================================

  val simpleConversation: Conversation = Conversation(
    Seq(
      simpleSystemMessage,
      simpleUserMessage
    )
  )

  val completedConversation: Conversation = Conversation(
    Seq(
      simpleSystemMessage,
      simpleUserMessage,
      simpleAssistantMessage
    )
  )

  val toolConversation: Conversation = Conversation(
    Seq(
      simpleSystemMessage,
      UserMessage("What's the weather in San Francisco?"),
      assistantWithToolCalls,
      toolResponse,
      AssistantMessage("The weather in San Francisco is 18°C and sunny.")
    )
  )

  val multiTurnConversation: Conversation = Conversation(
    Seq(
      simpleSystemMessage,
      UserMessage("Tell me a joke."),
      AssistantMessage("Why don't scientists trust atoms? Because they make up everything!"),
      UserMessage("That's funny! Tell me another."),
      AssistantMessage("Why did the scarecrow win an award? Because he was outstanding in his field!")
    )
  )

  // ================================= STREAMING =================================

  val streamChunk1: StreamedChunk = StreamedChunk(
    id = "msg-123",
    content = Some("Hello"),
    toolCall = None,
    finishReason = None
  )

  val streamChunk2: StreamedChunk = StreamedChunk(
    id = "msg-123",
    content = Some(" world"),
    toolCall = None,
    finishReason = None
  )

  val streamChunkFinal: StreamedChunk = StreamedChunk(
    id = "msg-123",
    content = Some("!"),
    toolCall = None,
    finishReason = Some("stop")
  )

  val streamChunkWithThinking: StreamedChunk = StreamedChunk(
    id = "msg-456",
    content = None,
    toolCall = None,
    finishReason = None,
    thinkingDelta = Some("Let me think about this...")
  )

  // ================================= LOCAL MODELS =================================

  // Use model names from ModelDimensionRegistry
  val testLocalModels: LocalEmbeddingModels = LocalEmbeddingModels(
    imageModel = "openclip-vit-b32",
    audioModel = "wav2vec2-base",
    videoModel = "timesformer-base"
  )

  // ================================= JSON RESPONSES =================================

  object SampleResponses {

    val openAICompletion: String =
      """{
        |  "id": "chatcmpl-abc123",
        |  "object": "chat.completion",
        |  "created": 1677858242,
        |  "model": "gpt-4o",
        |  "choices": [
        |    {
        |      "index": 0,
        |      "message": {
        |        "role": "assistant",
        |        "content": "The answer is 4."
        |      },
        |      "finish_reason": "stop"
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 12,
        |    "completion_tokens": 5,
        |    "total_tokens": 17
        |  }
        |}""".stripMargin

    val openAICompletionWithToolCalls: String =
      """{
        |  "id": "chatcmpl-def456",
        |  "object": "chat.completion",
        |  "created": 1677858243,
        |  "model": "gpt-4o",
        |  "choices": [
        |    {
        |      "index": 0,
        |      "message": {
        |        "role": "assistant",
        |        "content": null,
        |        "tool_calls": [
        |          {
        |            "id": "call_abc123",
        |            "type": "function",
        |            "function": {
        |              "name": "get_weather",
        |              "arguments": "{\"location\":\"San Francisco\"}"
        |            }
        |          }
        |        ]
        |      },
        |      "finish_reason": "tool_calls"
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 20,
        |    "completion_tokens": 15,
        |    "total_tokens": 35
        |  }
        |}""".stripMargin

    val ollamaCompletion: String =
      """{
        |  "model": "llama2",
        |  "created_at": "2023-08-04T19:22:45.499127Z",
        |  "message": {
        |    "role": "assistant",
        |    "content": "The answer is 4."
        |  },
        |  "done": true,
        |  "total_duration": 5023456789,
        |  "prompt_eval_count": 12,
        |  "eval_count": 5
        |}""".stripMargin

    val anthropicCompletion: String =
      """{
        |  "id": "msg_01abc",
        |  "type": "message",
        |  "role": "assistant",
        |  "content": [
        |    {
        |      "type": "text",
        |      "text": "The answer is 4."
        |    }
        |  ],
        |  "model": "claude-3-sonnet-20240229",
        |  "stop_reason": "end_turn",
        |  "usage": {
        |    "input_tokens": 12,
        |    "output_tokens": 5
        |  }
        |}""".stripMargin

    val anthropicCompletionWithThinking: String =
      """{
        |  "id": "msg_02def",
        |  "type": "message",
        |  "role": "assistant",
        |  "content": [
        |    {
        |      "type": "thinking",
        |      "thinking": "Let me calculate 2 + 2..."
        |    },
        |    {
        |      "type": "text",
        |      "text": "The answer is 4."
        |    }
        |  ],
        |  "model": "claude-3-sonnet-20240229",
        |  "stop_reason": "end_turn",
        |  "usage": {
        |    "input_tokens": 12,
        |    "output_tokens": 5
        |  }
        |}""".stripMargin

    val sseStreamChunk: String =
      """data: {"id":"chatcmpl-xyz","choices":[{"index":0,"delta":{"content":"Hello"}}]}"""

    val sseStreamDone: String = "data: [DONE]"

    val errorResponse401: String =
      """{
        |  "error": {
        |    "message": "Incorrect API key provided",
        |    "type": "invalid_request_error",
        |    "code": "invalid_api_key"
        |  }
        |}""".stripMargin

    val errorResponse429: String =
      """{
        |  "error": {
        |    "message": "Rate limit exceeded",
        |    "type": "rate_limit_error",
        |    "code": "rate_limit_exceeded"
        |  }
        |}""".stripMargin
  }

  // ================================= TOOL CALL JSON =================================

  object ToolCallJson {

    val standardFormat: String =
      """[
        |  {
        |    "id": "call_abc123",
        |    "type": "function",
        |    "function": {
        |      "name": "get_weather",
        |      "arguments": "{\"location\":\"San Francisco\"}"
        |    }
        |  }
        |]""".stripMargin

    val openRouterFormat: String =
      """[
        |  [
        |    {
        |      "id": "call_abc123",
        |      "type": "function",
        |      "function": {
        |        "name": "get_weather",
        |        "arguments": "{\"location\":\"San Francisco\"}"
        |      }
        |    }
        |  ]
        |]""".stripMargin

    val multipleToolCalls: String =
      """[
        |  {
        |    "id": "call_1",
        |    "type": "function",
        |    "function": {
        |      "name": "get_weather",
        |      "arguments": "{\"location\":\"NYC\"}"
        |    }
        |  },
        |  {
        |    "id": "call_2",
        |    "type": "function",
        |    "function": {
        |      "name": "search_web",
        |      "arguments": "{\"query\":\"news\"}"
        |    }
        |  }
        |]""".stripMargin
  }
}
