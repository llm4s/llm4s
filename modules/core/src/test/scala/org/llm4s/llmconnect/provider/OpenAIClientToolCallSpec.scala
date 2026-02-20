package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models.{ ChatCompletions, ChatCompletionsOptions }
import com.azure.json.JsonProviders
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Using

final class OpenAIClientToolCallSpec extends AnyFlatSpec with Matchers {

  private def completionsFromJson(json: String): ChatCompletions =
    Using.resource(JsonProviders.createReader(json))(ChatCompletions.fromJson)

  "OpenAIClient.complete" should "parse tool call arguments into JSON objects" in {
    val model = "gpt-4"

    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    val completions = completionsFromJson(
      """{
        |"id":"chatcmpl-1",
        |"created":0,
        |"choices":[{
        |  "index":0,
        |  "message":{
        |    "role":"assistant",
        |    "content":"ok",
        |    "tool_calls":[{
        |      "id":"call-1",
        |      "type":"function",
        |      "function":{
        |        "name":"test",
        |        "arguments":"{\"x\":1}"
        |      }
        |    }]
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

    val result = client.complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())

    val completion = result.getOrElse(fail("Expected successful completion"))
    completion.toolCalls should have size 1
    completion.toolCalls(0).arguments("x").num shouldBe 1
  }
}
