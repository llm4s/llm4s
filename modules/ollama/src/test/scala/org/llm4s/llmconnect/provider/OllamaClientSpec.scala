package org.llm4s.llmconnect.provider

import org.llm4s.error.{ NetworkError, RateLimitError, TimeoutError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MockMetricsCollector
import org.scalamock.scalatest.MockFactory
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * Test helper for building Ollama request bodies.
 * Delegates to the real production implementation to ensure tests validate actual behavior.
 */
private[provider] object OllamaRequestBodyTestHelper {
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private val testConfig = OllamaConfig(
    model = "llama3.1",
    baseUrl = "http://localhost:11434",
    contextWindow = 4096,
    reserveCompletion = 512
  )

  private val client = new OllamaClient(testConfig)

  def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): ujson.Obj =
    client.createRequestBody(conversation, options, stream)
}

class OllamaClientSpec extends AnyFunSuite {
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  test("ollama chat request sends assistant content as a plain string") {

    val conversation = Conversation(
      messages = Seq(
        SystemMessage("You are a helpful assistant"),
        UserMessage("Say hello"),
        // This reproduces the bug
        AssistantMessage(None, Seq.empty)
      )
    )

    // Use test helper instead of reflection
    val body = OllamaRequestBodyTestHelper.createRequestBody(conversation, CompletionOptions(), stream = false)

    val messages = body("messages").arr

    val assistantMessage =
      messages.find(_("role").str == "assistant").get

    assert(
      assistantMessage("content").isInstanceOf[ujson.Str],
      "Expected assistant message content to be a string for Ollama"
    )
    assert(assistantMessage("content").str == "", "Assistant content should default to empty string when missing")
  }

  test("ollama client accepts custom metrics collector") {
    val config = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    val mockMetrics = new MockMetricsCollector()
    val client      = new OllamaClient(config, mockMetrics)

    // Verify client was created with custom metrics
    assert(client != null)
    assert(mockMetrics.totalRequests == 0) // No requests yet
  }

  test("ollama client uses noop metrics by default") {
    val config = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    // Default constructor should use noop metrics
    val client = new OllamaClient(config)

    // Verify it compiles and doesn't throw (noop metrics should never fail)
    assert(client != null)
  }
}

// ============================================================
// HTTP stub-based tests for OllamaClient
// ============================================================
class OllamaClientHttpSpec extends AnyFunSuite with MockFactory {
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = OllamaConfig(
    model = "llama3.1",
    baseUrl = "http://localhost:11434",
    contextWindow = 4096,
    reserveCompletion = 512
  )

  private def mkClient(mockHttp: Llm4sHttpClient): OllamaClient =
    new OllamaClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

  private def mkClient(
    mockHttp: Llm4sHttpClient,
    exchangeLogging: ProviderExchangeLogging
  ): OllamaClient =
    new OllamaClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, exchangeLogging, mockHttp)

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def conversation(text: String): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  // ── complete() happy path ────────────────────────────────────────────────

  test("complete() parses message.content from a 200 response") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"Hi there!"},"prompt_eval_count":10,"eval_count":5}"""
    (mockHttp.post _).when(*, *, *, *).returns(Right(httpOk(body)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    assert(result.toOption.get.content == "Hi there!")
  }

  test("complete() parses token usage from the response") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"OK"},"prompt_eval_count":12,"eval_count":8}"""
    (mockHttp.post _).when(*, *, *, *).returns(Right(httpOk(body)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    val usage = result.toOption.get.usage.get
    assert(usage.promptTokens == 12)
    assert(usage.completionTokens == 8)
    assert(usage.totalTokens == 20)
  }

  test("complete() records a provider exchange when logging is enabled") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"Logged!"},"prompt_eval_count":10,"eval_count":5}"""
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    (mockHttp.post _).when(*, *, *, *).returns(Right(httpOk(body)))

    val client = mkClient(mockHttp, ProviderExchangeLogging.enabled(sink))
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    assert(recorded.size == 1)

    val exchange = recorded.head
    assert(exchange.provider == "ollama")
    assert(exchange.model.contains("llama3.1"))
    assert(exchange.requestBody.contains("\"model\":\"llama3.1\""))
    assert(exchange.requestBody.contains("\"stream\":false"))
    assert(exchange.responseBody.contains(body))
    assert(exchange.errorMessage.isEmpty)
    assert(exchange.duration >= Duration.Zero)
  }

  test("complete() returns None usage when token counts are absent") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"OK"}}"""
    (mockHttp.post _).when(*, *, *, *).returns(Right(httpOk(body)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    assert(result.toOption.get.usage.isEmpty)
  }

  // ── complete() error cases ───────────────────────────────────────────────

  test("complete() returns AuthenticationError on HTTP 401") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(401, "Unauthorized", Map.empty)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.AuthenticationError])
  }

  test("complete() returns RateLimitError on HTTP 429") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(429, "Too Many Requests", Map.empty)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.RateLimitError])
  }

  test("complete() returns ServiceError on HTTP 500") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(500, "Internal Server Error", Map.empty)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.ServiceError])
  }

  test("complete() passes a transport failure through and records the exchange") {
    val mockHttp = stub[Llm4sHttpClient]
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange
    val failure = NetworkError("connection reset", None, "http://localhost:11434/api/chat")
    (mockHttp.post _).when(*, *, *, *).returns(Left(failure))

    val client = mkClient(mockHttp, ProviderExchangeLogging.enabled(sink))
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result == Left(failure))
    assert(recorded.size == 1)
    assert(recorded.head.errorMessage.isDefined)
  }

  test("complete() passes a transport timeout through unchanged") {
    val mockHttp = stub[Llm4sHttpClient]
    val failure  = TimeoutError("timed out", 120.seconds, "http.POST")
    (mockHttp.post _).when(*, *, *, *).returns(Left(failure))

    val result = mkClient(mockHttp).complete(conversation("Hello"), CompletionOptions())

    assert(result == Left(failure))
  }

  test("complete() asks for a 120 second timeout") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(httpOk("""{"message":{"content":"OK"}}""")))

    mkClient(mockHttp).complete(conversation("Hello"), CompletionOptions())

    (mockHttp.post _).verify(*, *, *, 120.seconds).once()
  }

  test("complete() carries a 429's Retry-After into the RateLimitError") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _)
      .when(*, *, *, *)
      .returns(Right(HttpResponse(429, "slow down", Map("retry-after" -> Seq("5")))))

    mkClient(mockHttp).complete(conversation("Hello"), CompletionOptions()) match {
      case Left(err: RateLimitError) => assert(err.retryDelay.contains(5.seconds))
      case other                     => fail(s"Expected RateLimitError, got: $other")
    }
  }

  // ── request body tests via OllamaRequestBodyTestHelper ──────────────────

  test("request body includes system, user and assistant messages with correct roles") {
    val conv = Conversation(messages =
      Seq(
        SystemMessage("Be helpful"),
        UserMessage("Hello"),
        AssistantMessage(Some("Hi"), Seq.empty)
      )
    )
    val body = OllamaRequestBodyTestHelper.createRequestBody(conv, CompletionOptions(), stream = false)
    val msgs = body("messages").arr
    assert(msgs.exists(_("role").str == "system"))
    assert(msgs.exists(_("role").str == "user"))
    assert(msgs.exists(_("role").str == "assistant"))
  }

  test("request body forwards ToolMessages as role tool") {
    val conv = Conversation(messages =
      Seq(
        UserMessage("Hello"),
        ToolMessage("tool result", "call-1")
      )
    )
    val body = OllamaRequestBodyTestHelper.createRequestBody(conv, CompletionOptions(), stream = false)
    val msgs = body("messages").arr
    assert(msgs.size == 2)
    assert(msgs(1)("role").str == "tool")
    assert(msgs(1)("content").str == "tool result")
  }

  test("request body maps maxTokens to num_predict in options") {
    val options = CompletionOptions(maxTokens = Some(256))
    val conv    = Conversation(messages = Seq(UserMessage("Hello")))
    val body    = OllamaRequestBodyTestHelper.createRequestBody(conv, options, stream = false)
    val opts    = body("options")
    assert(opts("num_predict").num.toInt == 256)
  }

  test("request body includes temperature and topP in options") {
    val options = CompletionOptions(temperature = 0.7, topP = 0.9)
    val conv    = Conversation(messages = Seq(UserMessage("Hello")))
    val body    = OllamaRequestBodyTestHelper.createRequestBody(conv, options, stream = false)
    val opts    = body("options")
    assert(opts("temperature").num == 0.7)
    assert(opts("top_p").num == 0.9)
  }

  // ── streamComplete() tests ───────────────────────────────────────────────

  test("streamComplete() parses JSON lines and accumulates content") {
    val jsonLines =
      "{\"message\":{\"content\":\"Hello\"},\"done\":false}\n" +
        "{\"message\":{\"content\":\" world\"},\"done\":false}\n" +
        "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":10,\"eval_count\":5}\n"
    val inputStream = new ByteArrayInputStream(jsonLines.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val chunks = scala.collection.mutable.Buffer[StreamedChunk]()
    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), chunk => chunks += chunk)

    assert(result.isRight)
    val completion = result.toOption.get
    assert(completion.content.contains("Hello"))
    assert(completion.content.contains("world"))
  }

  test("a stream that fails mid-read is a recoverable NetworkError, so streamCompleteWithRetry retries it") {
    // The connection resets after the 200 arrives, before the first chunk.
    val resetting = new java.io.InputStream {
      override def read(): Int = throw new java.io.IOException("Connection reset")
    }
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, resetting)))

    val result = mkClient(mockHttp).streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    result match {
      case Left(e: NetworkError) =>
        assert(e.message.contains("Connection reset"))
        assert(org.llm4s.error.LLMError.isRecoverable(e))
      case other => fail(s"expected a NetworkError, got $other")
    }
  }

  test("streamComplete() parses token counts from the done=true line") {
    val jsonLines =
      "{\"message\":{\"content\":\"Done\"},\"done\":true,\"prompt_eval_count\":15,\"eval_count\":7}\n"
    val inputStream = new ByteArrayInputStream(jsonLines.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isRight)
    val usage = result.toOption.get.usage.get
    assert(usage.promptTokens == 15)
    assert(usage.completionTokens == 7)
  }

  test("streamComplete() records a provider exchange when logging is enabled") {
    val jsonLines =
      "{\"message\":{\"content\":\"Hello\"},\"done\":false}\n" +
        "{\"message\":{\"content\":\" world\"},\"done\":false}\n" +
        "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":10,\"eval_count\":5}\n"
    val inputStream = new ByteArrayInputStream(jsonLines.getBytes(StandardCharsets.UTF_8))
    val mockHttp    = stub[Llm4sHttpClient]
    val recorded    = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = mkClient(mockHttp, ProviderExchangeLogging.enabled(sink))
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isRight)
    assert(recorded.size == 1)

    val exchange = recorded.head
    assert(exchange.provider == "ollama")
    assert(exchange.model.contains("llama3.1"))
    assert(exchange.requestBody.contains("\"stream\":true"))
    assert(exchange.responseBody.exists(_.contains(""""done":true""")))
    assert(exchange.errorMessage.isEmpty)
    assert(exchange.duration >= Duration.Zero)
  }

  test("streamComplete() returns error on non-200 status") {
    val errorBody   = "Unauthorized"
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(401, inputStream)))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.AuthenticationError])
  }

  test("streamComplete() passes a transport failure through and records the exchange") {
    val mockHttp = stub[Llm4sHttpClient]
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange
    val failure = NetworkError("connection reset", None, "http://localhost:11434/api/chat")
    (mockHttp.postStream _).when(*, *, *, *).returns(Left(failure))

    val client = mkClient(mockHttp, ProviderExchangeLogging.enabled(sink))
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result == Left(failure))
    assert(recorded.size == 1)
    assert(recorded.head.errorMessage.isDefined)
  }

  test("streamComplete() carries a 429's Retry-After into the RateLimitError") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = new ByteArrayInputStream("slow down".getBytes(StandardCharsets.UTF_8))
    (mockHttp.postStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(429, body, Map("retry-after" -> Seq("9")))))

    mkClient(mockHttp).streamComplete(conversation("Hello"), CompletionOptions(), _ => ()) match {
      case Left(err: RateLimitError) => assert(err.retryDelay.contains(9.seconds))
      case other                     => fail(s"Expected RateLimitError, got: $other")
    }
  }

  test("streamComplete() returns an error for a malformed JSON line, and records what it read") {
    val mockHttp = stub[Llm4sHttpClient]
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange
    val body =
      new ByteArrayInputStream("{\"message\":{\"content\":\"a\"}}\nnot-json\n".getBytes(StandardCharsets.UTF_8))
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, body)))

    val result = mkClient(mockHttp, ProviderExchangeLogging.enabled(sink))
      .streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isLeft)
    assert(recorded.head.responseBody.exists(_.contains("not-json")))
  }

}

// ============================================================
// End to end over real HTTP: a 429's Retry-After reaches the caller
// ============================================================
class OllamaClientRateLimitSpec extends AnyFunSuite with org.scalatest.BeforeAndAfterAll {
  import com.sun.net.httpserver.HttpServer
  import java.net.InetSocketAddress

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
  server.createContext(
    "/api/chat",
    exchange => {
      exchange.getRequestBody.readAllBytes()
      val body = """{"error":"rate limited"}""".getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Retry-After", "5")
      exchange.sendResponseHeaders(429, body.length.toLong)
      exchange.getResponseBody.write(body)
      exchange.close()
    }
  )

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit  = server.stop(0)

  private def client: OllamaClient =
    new OllamaClient(
      OllamaConfig(
        model = "llama3.1",
        baseUrl = s"http://localhost:${server.getAddress.getPort}",
        contextWindow = 4096,
        reserveCompletion = 512
      )
    )

  test("complete() turns a 429 with Retry-After: 5 into a RateLimitError with a 5 second delay") {
    client.complete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions()) match {
      case Left(err: RateLimitError) =>
        assert(err.retryAfter.contains(5.seconds))
        assert(err.retryDelay.contains(5.seconds))
      case other => fail(s"Expected RateLimitError, got: $other")
    }
  }

  test("streamComplete() turns a 429 with Retry-After: 5 into a RateLimitError with a 5 second delay") {
    client.streamComplete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions(), _ => ()) match {
      case Left(err: RateLimitError) => assert(err.retryDelay.contains(5.seconds))
      case other                     => fail(s"Expected RateLimitError, got: $other")
    }
  }

  test("complete() returns a NetworkError, not an exception, when nothing is listening") {
    val port = scala.util.Using.resource(new java.net.ServerSocket(0))(_.getLocalPort)
    val unreachable = new OllamaClient(
      OllamaConfig(
        model = "llama3.1",
        baseUrl = s"http://localhost:$port",
        contextWindow = 4096,
        reserveCompletion = 512
      )
    )
    unreachable.complete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions()) match {
      case Left(_: NetworkError) => succeed
      case other                 => fail(s"Expected NetworkError, got: $other")
    }
  }
}
