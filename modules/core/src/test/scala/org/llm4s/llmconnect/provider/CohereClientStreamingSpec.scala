package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * HTTP-level tests for CohereClient.streamComplete() (PR #925).
 *
 * Verifies that the new SSE streaming implementation correctly handles
 * the Cohere v2 streaming event format (message-start / content-delta /
 * message-end).  All tests run against a local in-process HTTP server —
 * no API keys or external services are required.
 */
class CohereClientStreamingSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def localConfig(baseUrl: String): CohereConfig =
    CohereConfig(
      apiKey = "test-key",
      model = "command-r",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  /**
   * Builds a Cohere v2 SSE body from a sequence of text chunks.
   * Format: message-start → content-start → content-delta* → content-end → message-end
   */
  private def cohereSseBody(
    chunks: Seq[String],
    id: String = "msg-cohere-test",
    inputTokens: Int = 10,
    outputTokens: Int = 5
  ): String = {
    val messageStart =
      s"""data: {"type":"message-start","id":"$id","delta":{"message":{"role":"assistant","content":[],"tool_plan":"","tool_calls":[],"citations":[]}}}"""

    val contentStart =
      """data: {"type":"content-start","index":0,"delta":{"message":{"content":{"type":"text","text":""}}}}"""

    val contentDeltas = chunks.map { text =>
      s"""data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":${ujson
          .Str(text)
          .render()}}}}}"""
    }

    val contentEnd = """data: {"type":"content-end","index":0}"""

    val messageEnd =
      s"""data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"billed_units":{"input_tokens":$inputTokens,"output_tokens":$outputTokens},"tokens":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}}}"""

    (Seq(messageStart, contentStart) ++ contentDeltas ++ Seq(contentEnd, messageEnd))
      .mkString("\n\n") + "\n\n"
  }

  // ===========================================================================
  // streamComplete() — happy path
  // ===========================================================================

  "CohereClient.streamComplete" should "parse Cohere v2 SSE events and accumulate content" in
    withServer("/v2/chat")(exchange => sendSseResponse(exchange, cohereSseBody(Seq("Hello", " world")))) { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello world"
      completion.model shouldBe "command-r"
      chunks should not be empty
    }

  it should "deliver each content-delta to the onChunk callback" in
    withServer("/v2/chat")(exchange => sendSseResponse(exchange, cohereSseBody(Seq("A", "B", "C")))) { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      chunks.flatMap(_.content).mkString shouldBe "ABC"
      chunks.size shouldBe 3
    }

  it should "accumulate token usage from the message-end event" in
    withServer("/v2/chat") { exchange =>
      sendSseResponse(exchange, cohereSseBody(Seq("Hi"), inputTokens = 12, outputTokens = 7))
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      val usage = result.toOption.get.usage
      usage shouldBe defined
      usage.get.promptTokens shouldBe 12
      usage.get.completionTokens shouldBe 7
    }

  it should "capture the message ID from the message-start event" in
    withServer("/v2/chat")(exchange => sendSseResponse(exchange, cohereSseBody(Seq("Hi"), id = "cohere-id-42"))) {
      baseUrl =>
        val client = new CohereClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isRight shouldBe true
        result.toOption.get.id shouldBe "cohere-id-42"
    }

  it should "handle a single-word streaming response" in
    withServer("/v2/chat")(exchange => sendSseResponse(exchange, cohereSseBody(Seq("Bonjour")))) { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe "Bonjour"
    }

  it should "return an empty completion when no content-delta events are present" in
    withServer("/v2/chat") { exchange =>
      val noContent =
        "data: {\"type\":\"message-start\",\"id\":\"x\"}\n\n" +
          "data: {\"type\":\"message-end\",\"delta\":{\"finish_reason\":\"COMPLETE\",\"usage\":{\"tokens\":{\"input_tokens\":1,\"output_tokens\":0}}}}\n\n"
      sendSseResponse(exchange, noContent)
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe ""
    }

  it should "handle message-end with missing or incomplete token counts gracefully" in
    withServer("/v2/chat") { exchange =>
      val missingTokens =
        "data: {\"type\":\"message-start\",\"id\":\"x\"}\n\n" +
          "data: {\"type\":\"content-delta\",\"index\":0,\"delta\":{\"message\":{\"content\":{\"text\":\"hi\"}}}}\n\n" +
          "data: {\"type\":\"message-end\",\"delta\":{\"finish_reason\":\"COMPLETE\",\"usage\":{\"tokens\":{}}}}\n\n"
      sendSseResponse(exchange, missingTokens)
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe "hi"
      result.toOption.get.usage shouldBe None
    }

  it should "set stream:true in the outgoing request body" in
    withServer("/v2/chat") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("stream").bool shouldBe true
      sendSseResponse(exchange, cohereSseBody(Seq("ok")))
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(), _ => ())
    }

  it should "include the model in the streaming request body" in
    withServer("/v2/chat") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("model").str shouldBe "command-r"
      sendSseResponse(exchange, cohereSseBody(Seq("ok")))
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(), _ => ())
    }

  it should "pass maxTokens option in the streaming request" in
    withServer("/v2/chat") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("max_tokens").num.toInt shouldBe 512
      sendSseResponse(exchange, cohereSseBody(Seq("ok")))
    } { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(maxTokens = Some(512)), _ => ())
    }

  // ===========================================================================
  // streamComplete() — error handling
  // ===========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/v2/chat")(exchange => sendJsonResponse(exchange, 401, """{"message":"Unauthorized"}""")) { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/v2/chat")(exchange => sendJsonResponse(exchange, 429, """{"message":"Rate limit exceeded"}""")) {
      baseUrl =>
        val client = new CohereClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/v2/chat")(exchange => sendJsonResponse(exchange, 500, """{"message":"Internal error"}""")) { baseUrl =>
      val client = new CohereClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "not invoke onChunk when the server returns an error status" in
    withServer("/v2/chat")(exchange => sendJsonResponse(exchange, 429, """{"message":"Too many requests"}""")) {
      baseUrl =>
        val client       = new CohereClient(localConfig(baseUrl))
        var chunksCalled = 0
        val result       = client.streamComplete(conversation, CompletionOptions(), _ => chunksCalled += 1)

        result.isLeft shouldBe true
        chunksCalled shouldBe 0
    }

  // ===========================================================================
  // streamComplete() — exchange logging
  // ===========================================================================

  it should "record a provider exchange with streaming request and SSE response body" in
    withServer("/v2/chat")(exchange => sendSseResponse(exchange, cohereSseBody(Seq("Hello", " Cohere")))) { baseUrl =>
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(e: ProviderExchange): Unit = exchanges += e

      val client = new CohereClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      exchanges should have size 1
      val ex = exchanges.head
      ex.provider shouldBe "cohere"
      ex.model shouldBe Some("command-r")
      ex.requestBody should include("\"stream\":true")
      ex.requestBody should include("hello")
      ex.responseBody.value should include("data:")
      ex.responseBody.value should include("content-delta")
      ex.errorMessage shouldBe empty
    }

  it should "record a provider exchange with errorMessage when streaming returns an error" in
    withServer("/v2/chat")(exchange => sendJsonResponse(exchange, 401, """{"message":"Unauthorized"}""")) { baseUrl =>
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(e: ProviderExchange): Unit = exchanges += e

      val client = new CohereClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      client.streamComplete(conversation, CompletionOptions(), _ => ())

      exchanges should have size 1
      exchanges.head.errorMessage shouldBe defined
    }

  // ===========================================================================
  // closed-state — streamComplete after close
  // ===========================================================================

  it should "return ConfigurationError when called after close()" in {
    val client = new CohereClient(
      CohereConfig("k", "command-r", "https://example.invalid", 128000, 4096)
    )
    client.close()
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("already closed")
  }
}
