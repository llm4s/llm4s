package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models.{ ChatCompletions, ChatCompletionsOptions }
import com.azure.json.JsonProviders
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Using

final class OpenAIToolCallExtractionSpec extends AnyFlatSpec with Matchers {

  private def completionsFromJson(json: String): ChatCompletions =
    Using.resource(JsonProviders.createReader(json))(ChatCompletions.fromJson)

  "OpenAIClient.extractToolCalls" should "extract multiple tool calls with complex arguments" in {
    val model = "gpt-4"

    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    // Test with multiple tool calls with different argument structures
    val completions = completionsFromJson(
      """{
        |"id":"chatcmpl-2",
        |"created":0,
        |"choices":[{
        |  "index":0,
        |  "message":{
        |    "role":"assistant",
        |    "content":"calling functions",
        |    "tool_calls":[
        |      {
        |        "id":"call-1",
        |        "type":"function",
        |        "function":{
        |          "name":"search",
        |          "arguments":"{\"query\":\"test\",\"limit\":10}"
        |        }
        |      },
        |      {
        |        "id":"call-2",
        |        "type":"function",
        |        "function":{
        |          "name":"calculate",
        |          "arguments":"{\"a\":5,\"b\":3,\"operation\":\"add\"}"
        |        }
        |      }
        |    ]
        |  }
        |}],
        |"usage":{"completion_tokens":2,"prompt_tokens":1,"total_tokens":3}
        |}""".stripMargin
    )

    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions = completions

      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): com.azure.core.util.IterableStream[ChatCompletions] =
        throw new UnsupportedOperationException("not used in this test")
    }

    val client = OpenAIClient.forTest(model, transport, config)

    val result = client.complete(Conversation(Seq(UserMessage("test"))), CompletionOptions())

    val completion = result.getOrElse(fail("Expected successful completion"))
    completion.toolCalls should have size 2

    // Validate first tool call
    completion.toolCalls(0).id shouldBe "call-1"
    completion.toolCalls(0).name shouldBe "search"
    completion.toolCalls(0).arguments("query").str shouldBe "test"
    completion.toolCalls(0).arguments("limit").num shouldBe 10.0

    // Validate second tool call
    completion.toolCalls(1).id shouldBe "call-2"
    completion.toolCalls(1).name shouldBe "calculate"
    completion.toolCalls(1).arguments("a").num shouldBe 5.0
    completion.toolCalls(1).arguments("b").num shouldBe 3.0
    completion.toolCalls(1).arguments("operation").str shouldBe "add"
  }

  "OpenAIClient.extractToolCalls" should "ignore tool calls with invalid JSON arguments" in {
    val model = "gpt-4"

    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    // Mix of valid and invalid tool calls
    val completions = completionsFromJson(
      """{
        |"id":"chatcmpl-3",
        |"created":0,
        |"choices":[{
        |  "index":0,
        |  "message":{
        |    "role":"assistant",
        |    "content":"mixed calls",
        |    "tool_calls":[
        |      {
        |        "id":"call-valid",
        |        "type":"function",
        |        "function":{
        |          "name":"valid_func",
        |          "arguments":"{\"x\":1}"
        |        }
        |      },
        |      {
        |        "id":"call-invalid",
        |        "type":"function",
        |        "function":{
        |          "name":"invalid_func",
        |          "arguments":"not valid json {"
        |        }
        |      }
        |    ]
        |  }
        |}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}
        |}""".stripMargin
    )

    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions = completions

      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): com.azure.core.util.IterableStream[ChatCompletions] =
        throw new UnsupportedOperationException("not used in this test")
    }

    val client = OpenAIClient.forTest(model, transport, config)

    val result = client.complete(Conversation(Seq(UserMessage("test"))), CompletionOptions())

    val completion = result.getOrElse(fail("Expected successful completion"))
    // Only valid tool call should be extracted
    completion.toolCalls should have size 1
    completion.toolCalls(0).id shouldBe "call-valid"
    completion.toolCalls(0).name shouldBe "valid_func"
  }
}
