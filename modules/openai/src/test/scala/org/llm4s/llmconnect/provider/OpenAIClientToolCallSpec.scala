package org.llm4s.llmconnect.provider

import org.scalatest.EitherValues
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ResponseFormat, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ completion => completionOf, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

final class OpenAIClientToolCallSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  "OpenAIClient.complete" should "parse tool call arguments into JSON objects" in {
    val model = "gpt-4"

    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value

    val completions = completionOf(
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

    val client = OpenAIClient.forTest(model, transport(complete = _ => completions), config)

    val result = client.complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())

    val completion = result.getOrElse(fail("Expected successful completion"))
    completion.toolCalls should have size 1
    completion.toolCalls(0).arguments("x").num shouldBe 1
  }

  it should "send a json_object response format when ResponseFormat.Json is used" in {
    val model = "gpt-4"
    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value

    val completions = completionOf(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    var capturedOptions: ChatCompletionCreateParams = null
    val capturing = transport(complete = { params =>
      capturedOptions = params
      completions
    })

    val client  = OpenAIClient.forTest(model, capturing, config)
    val options = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    client.complete(Conversation(Seq(UserMessage("hello"))), options)

    capturedOptions should not be null
    capturedOptions.responseFormat().get().isJsonObject shouldBe true
  }

  it should "send a json_schema response format when ResponseFormat.JsonSchema is used" in {
    val model = "gpt-4"
    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value

    val completions = completionOf(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    var capturedOptions: ChatCompletionCreateParams = null
    val capturing = transport(complete = { params =>
      capturedOptions = params
      completions
    })

    val client  = OpenAIClient.forTest(model, capturing, config)
    val schema  = ujson.Obj("type" -> "object")
    val options = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    val result  = client.complete(Conversation(Seq(UserMessage("hello"))), options)

    result.isRight shouldBe true
    capturedOptions should not be null
    val format = capturedOptions.responseFormat().get()
    format.isJsonSchema shouldBe true
    format.asJsonSchema().jsonSchema().name() shouldBe "response"
    format.asJsonSchema().jsonSchema().strict().get() shouldBe true
    format.asJsonSchema().jsonSchema().schema().get()._additionalProperties().get("type").toString shouldBe "object"
  }

  it should "record a provider exchange when logging is enabled" in {
    val model = "gpt-4"
    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value

    val completions = completionOf(
      """{"id":"chatcmpl-1","created":0,"model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"logged ok"}}],
        |"usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""".stripMargin
    )

    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    val client = OpenAIClient.forTest(
      model,
      transport(complete = _ => completions),
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
    exchange.duration should be >= Duration.Zero
  }
}
