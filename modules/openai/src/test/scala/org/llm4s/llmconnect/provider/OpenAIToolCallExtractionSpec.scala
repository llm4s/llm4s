package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ToolCallId, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ completion => completionOf, transport }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class OpenAIToolCallExtractionSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  "OpenAIClient.extractToolCalls" should "extract multiple tool calls with complex arguments" in {
    val model = "gpt-4"

    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName(model),
        apiKey = Secret("test-api-key"),
        organization = None,
        baseUrl = BaseUrl("https://example.invalid/v1")
      )
      .value

    // Test with multiple tool calls with different argument structures
    val completions = completionOf(
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

    val client = OpenAIClient.forTest(model, transport(complete = _ => completions), config)

    val result = client.complete(Conversation(Seq(UserMessage("test"))), CompletionOptions())

    val completion = result.getOrElse(fail("Expected successful completion"))
    completion.toolCalls should have size 2

    // Validate first tool call
    completion.toolCalls(0).id shouldBe ToolCallId("call-1")
    completion.toolCalls(0).name shouldBe "search"
    completion.toolCalls(0).arguments("query").str shouldBe "test"
    completion.toolCalls(0).arguments("limit").num shouldBe 10.0

    // Validate second tool call
    completion.toolCalls(1).id shouldBe ToolCallId("call-2")
    completion.toolCalls(1).name shouldBe "calculate"
    completion.toolCalls(1).arguments("a").num shouldBe 5.0
    completion.toolCalls(1).arguments("b").num shouldBe 3.0
    completion.toolCalls(1).arguments("operation").str shouldBe "add"
  }

  "OpenAIClient.extractToolCalls" should "fail a reply with a tool call whose arguments are invalid JSON, never dropping it (RFC 0009)" in {
    val model = "gpt-4"

    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName(model),
        apiKey = Secret("test-api-key"),
        organization = None,
        baseUrl = BaseUrl("https://example.invalid/v1")
      )
      .value

    // Mix of valid and invalid tool calls
    val completions = completionOf(
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

    val client = OpenAIClient.forTest(model, transport(complete = _ => completions), config)

    val result = client.complete(Conversation(Seq(UserMessage("test"))), CompletionOptions())

    // dropped, the call left a reply that looked whole; the caller could not see why the call was missing
    result.left.map(_.message) should matchPattern {
      case Left(message: String) if message.contains("malformed tool call 'invalid_func'") =>
    }
  }

  // The arguments string is model output: nested too deeply it fails the reply like any other unparseable
  // arguments, before it becomes a value that overflows the stack of whatever renders it next (#1562).
  // On a 1 MB stack, so deterministic; the ids are extracted inside it so no deep value is rendered.
  it should "fail a tool call whose arguments are nested too deeply, without parsing them" in {
    val model = "gpt-4"

    val config = OpenAIConfig
      .fromValues(
        modelName = ModelName(model),
        apiKey = Secret("test-api-key"),
        organization = None,
        baseUrl = BaseUrl("https://example.invalid/v1")
      )
      .value

    val completions = completionOf(
      s"""{
         |"id":"chatcmpl-4",
         |"created":0,
         |"choices":[{
         |  "index":0,
         |  "message":{
         |    "role":"assistant",
         |    "content":"deep call",
         |    "tool_calls":[
         |      {"id":"call-valid","type":"function","function":{"name":"valid_func","arguments":"{\\"x\\":1}"}},
         |      {"id":"call-deep","type":"function","function":{"name":"deep_func","arguments":"${"[" * 100000 + "]" * 100000}"}}
         |    ]
         |  }
         |}],
         |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}
         |}""".stripMargin
    )

    val client = OpenAIClient.forTest(model, transport(complete = _ => completions), config)

    val failed = SmallStack.run(
      client
        .complete(Conversation(Seq(UserMessage("test"))), CompletionOptions())
        .left
        .map(_.message.contains("'deep_func': the arguments are nested more than 512 levels deep"))
    )
    failed shouldBe Right(Left(true))
  }
}
