package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models.{
  ChatCompletions,
  ChatCompletionsOptions,
  ChatCompletionsJsonResponseFormat,
  ChatCompletionsJsonSchemaResponseFormat
}
import com.azure.json.JsonProviders
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ResponseFormat, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.util.Using

final class OpenAIClientToolCallSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

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

  it should "set ChatCompletionsJsonResponseFormat when ResponseFormat.Json is used" in {
    val model = "gpt-4"
    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    val completions = completionsFromJson(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    var capturedOptions: ChatCompletionsOptions = null
    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions = {
        capturedOptions = options
        completions
      }
      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): com.azure.core.util.IterableStream[ChatCompletions] =
        throw new UnsupportedOperationException("not used")
    }

    val client  = OpenAIClient.forTest(model, transport, config)
    val options = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    client.complete(Conversation(Seq(UserMessage("hello"))), options)

    capturedOptions should not be null
    capturedOptions.getResponseFormat shouldBe a[ChatCompletionsJsonResponseFormat]
  }

  it should "set ChatCompletionsJsonSchemaResponseFormat when ResponseFormat.JsonSchema is used" in {
    val model = "gpt-4"
    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    val completions = completionsFromJson(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    var capturedOptions: ChatCompletionsOptions = null
    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions = {
        capturedOptions = options
        completions
      }
      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): com.azure.core.util.IterableStream[ChatCompletions] =
        throw new UnsupportedOperationException("not used")
    }

    val client  = OpenAIClient.forTest(model, transport, config)
    val schema  = ujson.Obj("type" -> "object")
    val options = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    val result  = client.complete(Conversation(Seq(UserMessage("hello"))), options)

    result.isRight shouldBe true
    capturedOptions should not be null
    capturedOptions.getResponseFormat match {
      case fmt: ChatCompletionsJsonSchemaResponseFormat =>
        fmt.getJsonSchema.getName shouldBe "response"
        fmt.getJsonSchema.isStrict shouldBe true
      case other =>
        fail(s"Expected ChatCompletionsJsonSchemaResponseFormat but got ${other.getClass.getSimpleName}")
    }
  }

  it should "record a provider exchange when logging is enabled" in {
    val model = "gpt-4"
    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    val completions = completionsFromJson(
      """{"id":"chatcmpl-1","created":0,"model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"logged ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions =
        completions

      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): com.azure.core.util.IterableStream[ChatCompletions] =
        throw new UnsupportedOperationException("not used")
    }

    val client = OpenAIClient.forTest(
      model,
      transport,
      config,
      exchangeLogging = ProviderExchangeLogging.enabled(sink)
    )

    val result = client.complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())

    result.isRight shouldBe true
    recorded should have size 1

    val exchange = recorded.head
    exchange.provider shouldBe "openai"
    exchange.model shouldBe Some("gpt-4")
    exchange.requestBody should include("messages")
    exchange.requestBody should include("hello")
    exchange.responseBody shouldBe defined
    exchange.responseBody.get should include("chatcmpl-1")
    exchange.responseBody.get should include("logged ok")
    exchange.errorMessage shouldBe empty
    exchange.durationMs should be >= 0L
  }
}
