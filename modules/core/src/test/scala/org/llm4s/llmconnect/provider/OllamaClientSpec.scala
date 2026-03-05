package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MockMetricsCollector
import org.scalamock.scalatest.MockFactory
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Test helper for building Ollama request bodies.
 * Delegates to the real production implementation to ensure tests validate actual behavior.
 */
private[provider] object OllamaRequestBodyTestHelper {
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

  private val testConfig = OllamaConfig(
    model = "llama3.1",
    baseUrl = "http://localhost:11434",
    contextWindow = 4096,
    reserveCompletion = 512
  )

  private def mkClient(mockHttp: Llm4sHttpClient): OllamaClient =
    new OllamaClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, mockHttp)

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def conversation(text: String): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  // ── complete() happy path ────────────────────────────────────────────────

  test("complete() parses message.content from a 200 response") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"Hi there!"},"prompt_eval_count":10,"eval_count":5}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    assert(result.toOption.get.content == "Hi there!")
  }

  test("complete() parses token usage from the response") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"OK"},"prompt_eval_count":12,"eval_count":8}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    val usage = result.toOption.get.usage.get
    assert(usage.promptTokens == 12)
    assert(usage.completionTokens == 8)
    assert(usage.totalTokens == 20)
  }

  test("complete() returns None usage when token counts are absent") {
    val mockHttp = stub[Llm4sHttpClient]
    val body     = """{"message":{"content":"OK"}}"""
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(body))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isRight)
    assert(result.toOption.get.usage.isEmpty)
  }

  // ── complete() error cases ───────────────────────────────────────────────

  test("complete() returns AuthenticationError on HTTP 401") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(401, "Unauthorized", Map.empty))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.AuthenticationError])
  }

  test("complete() returns RateLimitError on HTTP 429") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(429, "Too Many Requests", Map.empty))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.RateLimitError])
  }

  test("complete() returns ServiceError on HTTP 500") {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(500, "Internal Server Error", Map.empty))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hello"), CompletionOptions())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.ServiceError])
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

  test("request body drops ToolMessages silently") {
    val conv = Conversation(messages =
      Seq(
        UserMessage("Hello"),
        ToolMessage("tool result", "call-1")
      )
    )
    val body = OllamaRequestBodyTestHelper.createRequestBody(conv, CompletionOptions(), stream = false)
    val msgs = body("messages").arr
    // Only the UserMessage should appear; ToolMessage is dropped
    assert(msgs.size == 1)
    assert(msgs.head("role").str == "user")
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
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val chunks = scala.collection.mutable.Buffer[StreamedChunk]()
    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), chunk => chunks += chunk)

    assert(result.isRight)
    val completion = result.toOption.get
    assert(completion.content.contains("Hello"))
    assert(completion.content.contains("world"))
  }

  test("streamComplete() parses token counts from the done=true line") {
    val jsonLines =
      "{\"message\":{\"content\":\"Done\"},\"done\":true,\"prompt_eval_count\":15,\"eval_count\":7}\n"
    val inputStream = new ByteArrayInputStream(jsonLines.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isRight)
    val usage = result.toOption.get.usage.get
    assert(usage.promptTokens == 15)
    assert(usage.completionTokens == 7)
  }

  test("streamComplete() returns error on non-200 status") {
    val errorBody   = "Unauthorized"
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(401, inputStream))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hello"), CompletionOptions(), _ => ())

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[org.llm4s.error.AuthenticationError])
  }
}
