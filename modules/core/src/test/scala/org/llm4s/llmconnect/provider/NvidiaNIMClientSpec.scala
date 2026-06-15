package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.NvidiaNIMConfig
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  SystemMessage,
  ToolMessage,
  UserMessage
}
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

class NvidiaNIMClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/v1/chat/completions", exchange => handler(exchange))
    server.start()
    val baseUrl = s"http://localhost:${server.getAddress.getPort}/v1"
    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  private def cloudConfig(baseUrl: String): NvidiaNIMConfig =
    NvidiaNIMConfig(
      apiKey = "nvapi-test-key",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private def onPremiseConfig(baseUrl: String): NvidiaNIMConfig =
    NvidiaNIMConfig(
      apiKey = "",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private val successBody =
    """{
      |  "id": "cmpl-nim123",
      |  "object": "chat.completion",
      |  "created": 1700000000,
      |  "model": "meta/llama-3.1-8b-instruct",
      |  "choices": [
      |    {
      |      "index": 0,
      |      "message": {
      |        "role": "assistant",
      |        "content": "Hello! How can I help you?"
      |      },
      |      "finish_reason": "stop"
      |    }
      |  ],
      |  "usage": {
      |    "prompt_tokens": 10,
      |    "completion_tokens": 8,
      |    "total_tokens": 18
      |  }
      |}""".stripMargin

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  // =========================================================================
  // Happy-path tests
  // =========================================================================

  "NvidiaNIMClient.complete" should "parse a successful OpenAI-compatible response (cloud mode)" in withServer {
    exchange => respond(exchange, 200, successBody)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Hello! How can I help you?"
        completion.id shouldBe "cmpl-nim123"
        completion.usage.isDefined shouldBe true
        completion.usage.foreach { u =>
          u.promptTokens shouldBe 10
          u.completionTokens shouldBe 8
          u.totalTokens shouldBe 18
        }
      }
    )
  }

  it should "parse a successful response in on-premise mode (no API key)" in withServer { exchange =>
    respond(exchange, 200, successBody)
  } { baseUrl =>
    val client = new NvidiaNIMClient(onPremiseConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello! How can I help you?"
  }

  it should "handle response with whitespace in content" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-ws",
        |  "created": 1700000000,
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "  Hello world  "
        |      },
        |      "finish_reason": "stop"
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 5,
        |    "completion_tokens": 3,
        |    "total_tokens": 8
        |  }
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Hello world"
    )
  }

  it should "handle response with missing token usage gracefully" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-no-usage",
        |  "created": 1700000000,
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "No usage data"
        |      }
        |    }
        |  ]
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.usage shouldBe None
    )
  }

  it should "generate a fallback UUID when response has no id" in withServer { exchange =>
    val body =
      """{
        |  "created": 1700000000,
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Hello"
        |      }
        |    }
        |  ]
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.id should not be empty
    )
  }

  it should "use current time when response has no created field" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-no-created",
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "World"
        |      }
        |    }
        |  ]
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val before = System.currentTimeMillis() / 1000
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.created should be >= before
    )
  }

  // =========================================================================
  // Error-path tests
  // =========================================================================

  it should "fail with ValidationError when required text is missing" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-empty",
        |  "created": 1700000000,
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": ""
        |      },
        |      "finish_reason": "stop"
        |    }
        |  ]
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("Missing required text")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "fail with ValidationError for an empty conversation" in withServer { exchange =>
    respond(exchange, 200, "{}")
  } { baseUrl =>
    val client    = new NvidiaNIMClient(cloudConfig(baseUrl))
    val emptyConv = Conversation(Seq.empty)
    val result    = client.complete(emptyConv, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("at least one message")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "fail fast with ValidationError for unsupported message types" in withServer { exchange =>
    exchange.sendResponseHeaders(200, 0)
    exchange.getResponseBody.close()
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val unsupportedConversation = Conversation(
      Seq(UserMessage("Hello"), ToolMessage("tool result", "call-123"))
    )
    val result = client.complete(unsupportedConversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("does not support message type")
        err.message should include("ToolMessage")
      },
      _ => fail("Expected Left(ValidationError) for unsupported message type")
    )
  }

  it should "map HTTP 401 to AuthenticationError" in withServer { exchange =>
    respond(exchange, 401, """{ "message": "Unauthorized" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 403 to AuthenticationError" in withServer { exchange =>
    respond(exchange, 403, """{ "message": "Forbidden" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    respond(exchange, 429, """{ "message": "Rate limit exceeded" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map HTTP 500 to ServiceError" in withServer { exchange =>
    respond(exchange, 500, """{ "message": "Internal server error" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ServiceError]
        err.context("httpStatus") shouldBe "500"
      },
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "map arbitrary HTTP 4xx/5xx to ServiceError" in withServer { exchange =>
    respond(exchange, 418, """{ "message": "I am a teapot" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ServiceError],
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "parse nested error.message format" in withServer { exchange =>
    respond(exchange, 400, """{ "error": { "message": "Bad request details" } }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("Bad request details")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "truncate excessively long error messages" in withServer { exchange =>
    val longMessage = "x" * 500
    respond(exchange, 500, s"""{ "message": "$longMessage" }""")
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ServiceError]
        err.message.length should be <= 350
        err.message should include("[truncated]")
      },
      _ => fail("Expected Left(ServiceError)")
    )
  }

  // =========================================================================
  // Multi-turn conversation tests
  // =========================================================================

  it should "handle multi-turn conversation with system and assistant messages" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-multi",
        |  "created": 1700000000,
        |  "model": "meta/llama-3.1-8b-instruct",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Sure, I can help with that."
        |      },
        |      "finish_reason": "stop"
        |    }
        |  ]
        |}""".stripMargin
    respond(exchange, 200, body)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val multiConv = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Hello"),
        AssistantMessage(Some("Hi! How can I help?"), Seq.empty),
        UserMessage("Tell me about Scala")
      )
    )
    val result = client.complete(multiConv, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Sure, I can help with that."
    )
  }

  it should "skip empty assistant messages in conversation" in withServer { exchange =>
    respond(exchange, 200, successBody)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val convWithEmpty = Conversation(
      Seq(
        UserMessage("hello"),
        AssistantMessage(Some(""), Seq.empty),
        UserMessage("world")
      )
    )
    val result = client.complete(convWithEmpty, CompletionOptions())
    result.isRight shouldBe true
  }

  it should "forward maxTokens option when specified" in withServer { exchange =>
    respond(exchange, 200, successBody)
  } { baseUrl =>
    val client = new NvidiaNIMClient(cloudConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions(maxTokens = Some(100)))
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Hello! How can I help you?"
    )
  }

  // =========================================================================
  // streamComplete (not supported)
  // =========================================================================

  "NvidiaNIMClient.streamComplete" should "return ConfigurationError (not supported)" in {
    val client = new NvidiaNIMClient(
      NvidiaNIMConfig(
        apiKey = "key",
        model = "meta/llama-3.1-8b-instruct",
        baseUrl = "https://example.invalid",
        contextWindow = 128000,
        reserveCompletion = 4096
      )
    )
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
  }

  // =========================================================================
  // Accessor tests
  // =========================================================================

  "NvidiaNIMClient" should "return correct context window from config" in {
    val cfg = NvidiaNIMConfig(
      apiKey = "key",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val client = new NvidiaNIMClient(cfg)
    client.getContextWindow() shouldBe 128000
  }

  it should "return correct reserve completion from config" in {
    val cfg = NvidiaNIMConfig(
      apiKey = "key",
      model = "meta/llama-3.1-8b-instruct",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val client = new NvidiaNIMClient(cfg)
    client.getReserveCompletion() shouldBe 4096
  }

  // =========================================================================
  // Exchange logging test
  // =========================================================================

  it should "record provider exchanges when logging is enabled" in withServer { exchange =>
    respond(exchange, 200, successBody)
  } { baseUrl =>
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit =
        exchanges += exchange
    }
    val client = new NvidiaNIMClient(
      cloudConfig(baseUrl),
      exchangeLogging = ProviderExchangeLogging.Enabled(sink)
    )
    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "nvidia-nim"
    exchanges.head.model shouldBe Some("meta/llama-3.1-8b-instruct")
    exchanges.head.requestBody should include("hello")
    exchanges.head.responseBody.value should include("Hello! How can I help you?")
  }
}
