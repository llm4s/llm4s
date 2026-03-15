package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{
  CompletionOptions,
  Conversation,
  ResponseFormat,
  StreamedChunk,
  ToolMessage,
  UserMessage
}
import org.llm4s.metrics.MockMetricsCollector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class OpenRouterClientSpec extends AnyFlatSpec with Matchers {

  private val testConfig = OpenAIConfig(
    apiKey = "test-key",
    model = "anthropic/claude-3.5-sonnet",
    organization = None,
    baseUrl = "https://openrouter.ai/api/v1",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private def localConfig(baseUrl: String): OpenAIConfig =
    OpenAIConfig(
      apiKey = "test-key",
      model = "anthropic/claude-3.5-sonnet",
      organization = None,
      baseUrl = baseUrl,
      contextWindow = 200000,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/chat/completions", exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  private def sendResponse(exchange: HttpExchange, statusCode: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  // ==========================================================================
  // Existing tests
  // ==========================================================================

  "OpenRouterClient" should "accept custom metrics collector" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new OpenRouterClient(testConfig, mockMetrics)

    client should not be null
    mockMetrics.totalRequests shouldBe 0
  }

  it should "use noop metrics by default" in {
    val client = new OpenRouterClient(testConfig)

    client should not be null
  }

  it should "return correct context window" in {
    val client = new OpenRouterClient(testConfig)

    client.getContextWindow() shouldBe 200000
  }

  it should "return correct reserve completion" in {
    val client = new OpenRouterClient(testConfig)

    client.getReserveCompletion() shouldBe 4096
  }

  it should "serialize tool message with correct fields" in {
    val client      = new OpenRouterClientTestHelper(testConfig)
    val conv        = Conversation(Seq(ToolMessage("tool-output", "call-42")))
    val requestBody = client.exposedCreateRequestBody(conv, CompletionOptions())
    val toolMsg     = requestBody("messages")(0)

    toolMsg("role").str shouldBe "tool"
    toolMsg("tool_call_id").str shouldBe "call-42"
    toolMsg("content").str shouldBe "tool-output"
  }

  it should "add response_format when ResponseFormat.Json is set" in {
    val client       = new OpenRouterClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val options      = CompletionOptions().withResponseFormat(ResponseFormat.Json)

    val requestBody = client.exposedCreateRequestBody(conversation, options)

    (requestBody.obj should contain).key("response_format")
    requestBody("response_format")("type").str shouldBe "json_object"
  }

  it should "add response_format with json_schema when ResponseFormat.JsonSchema is set" in {
    val client       = new OpenRouterClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val schema       = ujson.Obj("type" -> "object", "properties" -> ujson.Obj("name" -> ujson.Obj("type" -> "string")))
    val options      = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))

    val requestBody = client.exposedCreateRequestBody(conversation, options)

    (requestBody.obj should contain).key("response_format")
    requestBody("response_format")("type").str shouldBe "json_schema"
    (requestBody("response_format")("json_schema").obj should contain).key("schema")
  }

  it should "not add response_format when not set" in {
    val client       = new OpenRouterClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Hello")))

    val requestBody = client.exposedCreateRequestBody(conversation, CompletionOptions())

    requestBody.obj should not contain key("response_format")
  }

  // ==========================================================================
  // complete() error handling
  // ==========================================================================

  "OpenRouterClient.complete" should "map HTTP 401 to AuthenticationError" in withServer { exchange =>
    sendResponse(exchange, 401, """{"error":"Unauthorized"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    sendResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[RateLimitError]
  }

  it should "map HTTP 500 to ServiceError" in withServer { exchange =>
    sendResponse(exchange, 500, """{"error":"Internal server error"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ServiceError]
  }

  // ==========================================================================
  // streamComplete() error handling — previously threw exceptions (issue 2.1)
  // ==========================================================================

  "OpenRouterClient.streamComplete" should "return Left(AuthenticationError) for HTTP 401" in withServer { exchange =>
    sendResponse(exchange, 401, """{"error":"Invalid API key"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "return Left(RateLimitError) for HTTP 429" in withServer { exchange =>
    sendResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[RateLimitError]
  }

  it should "return Left(ServiceError) for HTTP 500" in withServer { exchange =>
    sendResponse(exchange, 500, """{"error":"Internal server error"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ServiceError]
  }

  it should "include error body in ServiceError for unknown status codes" in withServer { exchange =>
    sendResponse(exchange, 503, """{"error":"Service unavailable"}""")
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err shouldBe a[ServiceError]
    err.message should include("Service unavailable")
  }

  it should "not invoke onChunk callback on error responses" in withServer { exchange =>
    sendResponse(exchange, 401, """{"error":"Unauthorized"}""")
  } { baseUrl =>
    val client     = new OpenRouterClient(localConfig(baseUrl))
    var chunkCount = 0
    val result     = client.streamComplete(conversation, CompletionOptions(), _ => chunkCount += 1)

    result.isLeft shouldBe true
    chunkCount shouldBe 0
  }

  it should "stream a successful SSE response" in withServer { exchange =>
    val sseBody =
      """data: {"id":"chatcmpl-1","created":0,"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}""" + "\n\n" +
        """data: {"id":"chatcmpl-1","created":0,"choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""" + "\n\n" +
        "data: [DONE]\n\n"

    val bytes = sseBody.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new OpenRouterClient(localConfig(baseUrl))
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Hi there"
    chunks should not be empty
  }
}

final private class OpenRouterClientTestHelper(cfg: OpenAIConfig) extends OpenRouterClient(cfg) {
  def exposedCreateRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj =
    createRequestBody(conversation, options)
}
