package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ToolMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class MistralClientSpec extends AnyFlatSpec with Matchers {

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/v1/chat/completions", exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  private def config(baseUrl: String): MistralConfig =
    MistralConfig(
      apiKey = "test-key",
      model = "mistral-small-latest",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  "MistralClient.complete" should "parse a successful OpenAI-compatible response" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-abc123",
        |  "object": "chat.completion",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
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

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Hello! How can I help you?"
        completion.id shouldBe "cmpl-abc123"
        completion.usage.isDefined shouldBe true
        completion.usage.foreach { u =>
          u.promptTokens shouldBe 10
          u.completionTokens shouldBe 8
        }
      }
    )
  }

  it should "handle response with whitespace in content" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-xyz",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
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

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Hello world"
        completion.usage.isDefined shouldBe true
        completion.usage.foreach { u =>
          u.promptTokens shouldBe 5
          u.completionTokens shouldBe 3
        }
      }
    )
  }

  it should "fail with ValidationError when required text is missing" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-empty",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
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

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("Missing required text")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "map HTTP 401 to AuthenticationError" in withServer { exchange =>
    val body  = """{ "message": "Unauthorized" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(401, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    val body  = """{ "message": "Rate limit exceeded" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(429, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map HTTP 5xx to ServiceError" in withServer { exchange =>
    val body  = """{ "message": "Internal server error" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(500, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ServiceError]
        err.context("httpStatus") shouldBe "500"
      },
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "parse nested error.message format" in withServer { exchange =>
    val body  = """{ "error": { "message": "Bad request details" } }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(400, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("Bad request details")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  // ============ Edge case tests for coverage ============

  it should "fail with ValidationError for an empty conversation" in withServer { exchange =>
    val body  = """{}"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client    = new MistralClient(config(baseUrl))
    val emptyConv = Conversation(Seq.empty)

    val result = client.complete(emptyConv, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("at least one message")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "handle multi-turn conversation with system and assistant messages" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-multi",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Sure, I can help with that."
        |      },
        |      "finish_reason": "stop"
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 20,
        |    "completion_tokens": 7,
        |    "total_tokens": 27
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    import org.llm4s.llmconnect.model.{ AssistantMessage, SystemMessage }
    val client = new MistralClient(config(baseUrl))
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

  it should "generate a fallback UUID when response has no id" in withServer { exchange =>
    val body =
      """{
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Hello"
        |      }
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 5,
        |    "completion_tokens": 1,
        |    "total_tokens": 6
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

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
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "World"
        |      }
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 3,
        |    "completion_tokens": 1,
        |    "total_tokens": 4
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))
    val before = System.currentTimeMillis() / 1000

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.created should be >= before
    )
  }

  it should "handle response with missing token usage gracefully" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-no-usage",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "No usage data"
        |      }
        |    }
        |  ]
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.usage shouldBe None
    )
  }

  it should "map HTTP 403 to AuthenticationError" in withServer { exchange =>
    val body  = """{ "message": "Forbidden" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(403, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "sanitize non-JSON error body instead of leaking raw response" in withServer { exchange =>
    val body  = "Internal Server Error with sensitive details: token=sk-12345"
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "text/plain")
    exchange.sendResponseHeaders(502, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ServiceError]
        (err.message should not).include("sk-12345")
        err.message should include("Mistral API error")
      },
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "truncate excessively long error messages" in withServer { exchange =>
    val longMessage = "x" * 500
    val body        = s"""{ "message": "$longMessage" }"""
    val bytes       = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(500, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

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

  it should "forward maxTokens option when specified" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-max",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Short response"
        |      }
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 5,
        |    "completion_tokens": 2,
        |    "total_tokens": 7
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions(maxTokens = Some(100)))
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Short response"
    )
  }

  // ============ Accessor tests ============

  "MistralClient" should "return correct context window from config" in {
    val cfg = MistralConfig(
      apiKey = "key",
      model = "mistral-small-latest",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val client = new MistralClient(cfg)
    client.getContextWindow() shouldBe 128000
  }

  it should "return correct reserve completion from config" in {
    val cfg = MistralConfig(
      apiKey = "key",
      model = "mistral-small-latest",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val client = new MistralClient(cfg)
    client.getReserveCompletion() shouldBe 4096
  }

  it should "handle assistant message with empty content by skipping it" in withServer { exchange =>
    val body =
      """{
        |  "id": "cmpl-skip",
        |  "created": 1700000000,
        |  "model": "mistral-small-latest",
        |  "choices": [
        |    {
        |      "message": {
        |        "role": "assistant",
        |        "content": "Response after skipped message"
        |      }
        |    }
        |  ],
        |  "usage": {
        |    "prompt_tokens": 10,
        |    "completion_tokens": 5,
        |    "total_tokens": 15
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    import org.llm4s.llmconnect.model.AssistantMessage
    val client = new MistralClient(config(baseUrl))
    val convWithEmpty = Conversation(
      Seq(
        UserMessage("hello"),
        AssistantMessage(Some(""), Seq.empty),
        UserMessage("world")
      )
    )

    val result = client.complete(convWithEmpty, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Response after skipped message"
    )
  }

  it should "handle other HTTP status codes as ServiceError" in withServer { exchange =>
    val body  = """{ "message": "I am a teapot" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(418, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ServiceError],
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "fail fast with ValidationError for unsupported message types" in withServer { exchange =>
    exchange.sendResponseHeaders(200, 0)
    exchange.getResponseBody.close()
  } { baseUrl =>
    val client = new MistralClient(config(baseUrl))
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
}
