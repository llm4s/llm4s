package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.FireworksConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._
import org.llm4s.model.ModelRegistryService

import scala.collection.mutable.ListBuffer

/**
 * HTTP-level unit tests for FireworksClient.
 *
 * Spins up a local HTTP server to verify request/response handling without
 * requiring a real Fireworks AI API key. No external services needed.
 */
class FireworksClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val defaultModel = "accounts/fireworks/models/llama-v3p1-8b-instruct"

  private def localConfig(baseUrl: String, model: String = defaultModel): FireworksConfig =
    FireworksConfig(
      apiKey = "fw-test-key",
      model = model,
      baseUrl = baseUrl,
      contextWindow = 131072,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  // ==========================================================================
  // complete() — success
  // ==========================================================================

  "FireworksClient.complete" should "parse a successful OpenAI-compatible response" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Hello from Fireworks!", defaultModel))
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello from Fireworks!"
      completion.model shouldBe defaultModel
      completion.id shouldBe "chatcmpl-test"
      completion.usage shouldBe defined
      completion.usage.get.promptTokens shouldBe 10
      completion.usage.get.completionTokens shouldBe 5
      completion.usage.get.totalTokens shouldBe 15
    }

  it should "return the correct context window and reserve completion" in {
    val cfg = FireworksConfig(
      apiKey = "key",
      model = defaultModel,
      baseUrl = "https://api.fireworks.ai/inference/v1",
      contextWindow = 131072,
      reserveCompletion = 4096
    )
    val client = new FireworksClient(cfg)
    client.getContextWindow() shouldBe 131072
    client.getReserveCompletion() shouldBe 4096
  }

  it should "record a provider exchange when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Logged response", defaultModel))
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new FireworksClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      recorded should have size 1
      val exchange = recorded.head
      exchange.provider shouldBe "fireworks"
      exchange.model shouldBe Some(defaultModel)
      exchange.requestBody should include("\"messages\"")
      exchange.requestBody should include("hello")
      exchange.responseBody shouldBe defined
      exchange.responseBody.get should include("chatcmpl-test")
      exchange.responseBody.get should include("Logged response")
      exchange.errorMessage shouldBe empty
      exchange.durationMs should be >= 0L
    }

  it should "handle response with missing token usage gracefully" in
    withServer("/chat/completions") { exchange =>
      val body =
        """{
          |  "id": "chatcmpl-no-usage",
          |  "object": "chat.completion",
          |  "created": 1700000000,
          |  "model": "accounts/fireworks/models/llama-v3p1-8b-instruct",
          |  "choices": [{
          |    "index": 0,
          |    "message": { "role": "assistant", "content": "No usage" },
          |    "finish_reason": "stop"
          |  }]
          |}""".stripMargin
      sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      result.toOption.get.usage shouldBe None
    }

  it should "handle multi-turn conversation with system and assistant messages" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Multi-turn response", defaultModel))
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val multiConv = Conversation(
        Seq(
          SystemMessage("You are a helpful assistant."),
          UserMessage("Hello"),
          AssistantMessage(Some("Hi! How can I help?"), Seq.empty),
          UserMessage("Tell me about Scala")
        )
      )
      val result = client.complete(multiConv, CompletionOptions())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe "Multi-turn response"
    }

  it should "handle response with empty assistant message content" in
    withServer("/chat/completions") { exchange =>
      val body =
        """{
          |  "id": "chatcmpl-empty",
          |  "object": "chat.completion",
          |  "created": 1700000000,
          |  "model": "accounts/fireworks/models/llama-v3p1-8b-instruct",
          |  "choices": [{
          |    "index": 0,
          |    "message": { "role": "assistant", "content": "" },
          |    "finish_reason": "stop"
          |  }],
          |  "usage": { "prompt_tokens": 5, "completion_tokens": 0, "total_tokens": 5 }
          |}""".stripMargin
      sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe ""
    }

  it should "include maxTokens in the request body when specified" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Short", defaultModel))
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions(maxTokens = Some(100)))

      result.isRight shouldBe true
    }

  it should "handle tool calls in the response" in
    withServer("/chat/completions") { exchange =>
      val body =
        """{
          |  "id": "chatcmpl-tools",
          |  "object": "chat.completion",
          |  "created": 1700000000,
          |  "model": "accounts/fireworks/models/firefunction-v2",
          |  "choices": [{
          |    "index": 0,
          |    "message": {
          |      "role": "assistant",
          |      "content": null,
          |      "tool_calls": [{
          |        "id": "call-1",
          |        "type": "function",
          |        "function": {
          |          "name": "get_weather",
          |          "arguments": "{\"city\": \"London\"}"
          |        }
          |      }]
          |    },
          |    "finish_reason": "tool_calls"
          |  }],
          |  "usage": { "prompt_tokens": 20, "completion_tokens": 15, "total_tokens": 35 }
          |}""".stripMargin
      sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val ffModel = "accounts/fireworks/models/firefunction-v2"
      val client  = new FireworksClient(localConfig(baseUrl, ffModel))
      val result  = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.toolCalls should have size 1
      completion.toolCalls.head.name shouldBe "get_weather"
      completion.toolCalls.head.id shouldBe "call-1"
    }

  it should "handle assistant message with tool calls in conversation" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Done", defaultModel))
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val convWithToolCall = Conversation(
        Seq(
          UserMessage("What is the weather in London?"),
          AssistantMessage(
            None,
            Seq(
              ToolCall(
                id = "call-1",
                name = "get_weather",
                arguments = ujson.Obj("city" -> "London")
              )
            )
          ),
          ToolMessage("{\"temp\": 15, \"condition\": \"cloudy\"}", "call-1"),
          UserMessage("Thank you")
        )
      )
      val result = client.complete(convWithToolCall, CompletionOptions())
      result.isRight shouldBe true
    }

  // ==========================================================================
  // complete() — error handling
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val client = new FireworksClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 403 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 403, """{"error":"Forbidden"}""")) {
      baseUrl =>
        val client = new FireworksClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "map HTTP 502 to ServiceError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 502, "Bad Gateway")) { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  // ==========================================================================
  // streamComplete() — success
  // ==========================================================================

  "FireworksClient.streamComplete" should "parse SSE events and accumulate content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " world"), defaultModel))
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello world"
      chunks should not be empty
    }

  it should "record provider exchanges for streaming when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " world"), defaultModel))
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new FireworksClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      recorded should have size 1
      recorded.head.provider shouldBe "fireworks"
      recorded.head.requestBody should include("\"stream\":true")
      recorded.head.responseBody.value should include("data:")
      recorded.head.responseBody.value should include("Hello")
      recorded.head.responseBody.value should include("[DONE]")
      recorded.head.errorMessage shouldBe empty
    }

  it should "handle [DONE] termination signal without error" in
    withServer("/chat/completions")(exchange => sendSseResponse(exchange, "data: [DONE]\n\n")) { baseUrl =>
      val client     = new FireworksClient(localConfig(baseUrl))
      var chunkCount = 0
      val result     = client.streamComplete(conversation, CompletionOptions(), _ => chunkCount += 1)

      result.isRight shouldBe true
      chunkCount shouldBe 0
    }

  // ==========================================================================
  // streamComplete() — error handling
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Invalid API key"}""")) {
      baseUrl =>
        val client = new FireworksClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError during streaming" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError during streaming" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new FireworksClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  // ==========================================================================
  // createRequestBody — serialization tests (package-private seam)
  // ==========================================================================

  "FireworksClient.createRequestBody" should "include model and messages" in {
    val cfg    = FireworksConfig("key", defaultModel, "https://example.invalid", 8192, 4096)
    val client = new FireworksClient(cfg)
    val body   = client.createRequestBody(conversation, CompletionOptions())

    body("model").str shouldBe defaultModel
    body("messages").arr should have size 1
    body("messages").arr(0)("role").str shouldBe "user"
    body("messages").arr(0)("content").str shouldBe "hello"
  }

  it should "include max_tokens when specified" in {
    val cfg    = FireworksConfig("key", defaultModel, "https://example.invalid", 8192, 4096)
    val client = new FireworksClient(cfg)
    val body   = client.createRequestBody(conversation, CompletionOptions(maxTokens = Some(256)))

    body("max_tokens").num.toInt shouldBe 256
  }

  it should "not include max_tokens when not specified" in {
    val cfg    = FireworksConfig("key", defaultModel, "https://example.invalid", 8192, 4096)
    val client = new FireworksClient(cfg)
    val body   = client.createRequestBody(conversation, CompletionOptions())

    body.obj.contains("max_tokens") shouldBe false
  }

  it should "serialize system messages correctly" in {
    val cfg    = FireworksConfig("key", defaultModel, "https://example.invalid", 8192, 4096)
    val client = new FireworksClient(cfg)
    val conv = Conversation(
      Seq(
        SystemMessage("You are helpful"),
        UserMessage("hi")
      )
    )
    val body = client.createRequestBody(conv, CompletionOptions())

    body("messages").arr(0)("role").str shouldBe "system"
    body("messages").arr(0)("content").str shouldBe "You are helpful"
    body("messages").arr(1)("role").str shouldBe "user"
  }

  it should "serialize tool messages correctly" in {
    val cfg    = FireworksConfig("key", defaultModel, "https://example.invalid", 8192, 4096)
    val client = new FireworksClient(cfg)
    val conv = Conversation(
      Seq(
        UserMessage("hi"),
        AssistantMessage(
          None,
          Seq(ToolCall("call-1", "get_data", ujson.Obj("q" -> "test")))
        ),
        ToolMessage("{\"result\": 42}", "call-1")
      )
    )
    val body = client.createRequestBody(conv, CompletionOptions())

    val msgs = body("messages").arr
    msgs(1)("role").str shouldBe "assistant"
    msgs(1).obj.contains("tool_calls") shouldBe true
    msgs(2)("role").str shouldBe "tool"
    msgs(2)("tool_call_id").str shouldBe "call-1"
    msgs(2)("content").str shouldBe "{\"result\": 42}"
  }
}
