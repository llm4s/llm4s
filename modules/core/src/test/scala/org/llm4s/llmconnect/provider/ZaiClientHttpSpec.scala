package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * Local HTTP server tests for ZaiClient (Tier 1).
 *
 * Verifies the complete HTTP request→response cycle against a deterministic
 * local server, including Z.ai's array-format content wrapping.
 * No API keys or external services required — runs unconditionally as part of `sbt test`.
 */
class ZaiClientHttpSpec extends AnyFlatSpec with Matchers {

  private def localConfig(baseUrl: String): ZaiConfig =
    ZaiConfig(
      apiKey = "test-key",
      model = "GLM-4.7",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  /** Z.ai returns content in array format: [{"type":"text","text":"..."}] */
  private def zaiCompletion(content: String, model: String = "GLM-4.7"): String =
    s"""{
       |  "id": "chatcmpl-zai-test",
       |  "created": 1700000000,
       |  "model": "$model",
       |  "choices": [{
       |    "index": 0,
       |    "message": {
       |      "role": "assistant",
       |      "content": [{"type": "text", "text": ${ujson.Str(content).render()}}]
       |    },
       |    "finish_reason": "stop"
       |  }],
       |  "usage": {
       |    "prompt_tokens": 8,
       |    "completion_tokens": 12,
       |    "total_tokens": 20
       |  }
       |}""".stripMargin

  // ==========================================================================
  // complete() — success with array-format content
  // ==========================================================================

  "ZaiClient.complete" should "parse response with array-format content" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, zaiCompletion("Hello from Z.ai!"))) {
      baseUrl =>
        val client = new ZaiClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isRight shouldBe true
        val completion = result.toOption.get
        completion.content shouldBe "Hello from Z.ai!"
        completion.model shouldBe "GLM-4.7"
        completion.id shouldBe "chatcmpl-zai-test"
        completion.usage shouldBe defined
        completion.usage.get.promptTokens shouldBe 8
        completion.usage.get.completionTokens shouldBe 12
    }

  // ==========================================================================
  // complete() — error handling
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val client = new ZaiClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new ZaiClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new ZaiClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "record provider exchanges when logging is enabled" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, zaiCompletion("Logged response"))) {
      baseUrl =>
        val exchanges = ListBuffer.empty[ProviderExchange]
        val sink = new ProviderExchangeSink {
          override def record(exchange: ProviderExchange): Unit =
            exchanges += exchange
        }
        val client = new ZaiClient(
          localConfig(baseUrl),
          exchangeLogging = ProviderExchangeLogging.Enabled(sink)
        )
        val result = client.complete(conversation, CompletionOptions())

        result.isRight shouldBe true
        exchanges should have size 1
        exchanges.head.provider shouldBe "zai"
        exchanges.head.model shouldBe Some("GLM-4.7")
        exchanges.head.requestBody should include("hello")
        exchanges.head.responseBody.value should include("Logged response")
    }

  // ==========================================================================
  // streamComplete() — success
  // ==========================================================================

  "ZaiClient.streamComplete" should "parse SSE events and accumulate content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " from Z.ai"), "GLM-4.7"))
    } { baseUrl =>
      val client = new ZaiClient(localConfig(baseUrl))
      val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello from Z.ai"
      chunks should not be empty
    }

  it should "record provider exchanges for streaming responses when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " from Z.ai"), "GLM-4.7"))
    } { baseUrl =>
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          exchanges += exchange

      val client = new ZaiClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      exchanges should have size 1
      exchanges.head.provider shouldBe "zai"
      exchanges.head.requestBody should include("\"stream\":true")
      exchanges.head.responseBody.value should include("data:")
      exchanges.head.responseBody.value should include("Hello")
      exchanges.head.responseBody.value should include("[DONE]")
      exchanges.head.errorMessage shouldBe empty
    }

  it should "map error status codes to typed errors" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 429, """{"error":"Rate limited"}""")) {
      baseUrl =>
        val client = new ZaiClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[RateLimitError]
    }

  // ==========================================================================
  // Request body verification — array-format content
  // ==========================================================================

  "ZaiClient request" should "wrap user message content in array format" in
    withServer("/chat/completions") { exchange =>
      val requestBody = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json        = ujson.read(requestBody)
      val userMsg     = json("messages")(0)

      // Verify array format wrapping
      userMsg("role").str shouldBe "user"
      userMsg("content")(0)("type").str shouldBe "text"
      userMsg("content")(0)("text").str shouldBe "hello"

      // Send valid response so the test doesn't fail on parsing
      sendJsonResponse(exchange, 200, zaiCompletion("ok"))
    } { baseUrl =>
      val client = new ZaiClient(localConfig(baseUrl))
      client.complete(conversation, CompletionOptions())
    }

  it should "wrap tool message content in array format" in
    withServer("/chat/completions") { exchange =>
      val requestBody = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json        = ujson.read(requestBody)
      val toolMsg     = json("messages")(0)

      toolMsg("role").str shouldBe "tool"
      toolMsg("tool_call_id").str shouldBe "call-42"
      toolMsg("content")(0)("type").str shouldBe "text"
      toolMsg("content")(0)("text").str shouldBe "result"

      sendJsonResponse(exchange, 200, zaiCompletion("ok"))
    } { baseUrl =>
      val client   = new ZaiClient(localConfig(baseUrl))
      val toolConv = Conversation(Seq(ToolMessage("result", "call-42")))
      client.complete(toolConv, CompletionOptions())
    }
}
