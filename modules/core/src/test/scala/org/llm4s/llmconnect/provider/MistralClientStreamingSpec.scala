package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * HTTP-level tests for MistralClient.streamComplete() (PR #925).
 *
 * Verifies that the new SSE streaming implementation correctly handles
 * the OpenAI-compatible streaming format used by Mistral's v1/chat/completions
 * endpoint.  All tests run against a local in-process HTTP server — no API
 * keys or external services are required.
 */
class MistralClientStreamingSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def localConfig(baseUrl: String): MistralConfig =
    MistralConfig(
      apiKey = "test-key",
      model = "mistral-small-latest",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  // ===========================================================================
  // streamComplete() — happy path
  // ===========================================================================

  "MistralClient.streamComplete" should "parse SSE events and accumulate content" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " world"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello world"
      completion.model shouldBe "mistral-small-latest"
      chunks should not be empty
    }

  it should "deliver each SSE chunk to the onChunk callback" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("A", "B", "C"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      chunks.flatMap(_.content).mkString shouldBe "ABC"
      chunks.size shouldBe 3
    }

  it should "accumulate token usage from the final SSE chunk" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hi"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      val usage = result.toOption.get.usage
      usage shouldBe defined
      usage.get.promptTokens shouldBe 10
      usage.get.completionTokens shouldBe 5
    }

  it should "return a completion even when the SSE stream has no content chunks (only [DONE])" in
    withServer("/v1/chat/completions") { exchange =>
      val onlyDone = "data: [DONE]\n\n"
      sendSseResponse(exchange, onlyDone)
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe ""
    }

  it should "handle a chunk with an empty choices array (usage-only chunk)" in
    withServer("/v1/chat/completions") { exchange =>
      val emptyChoices =
        """data: {"id":"chatcmpl-test","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
      sendSseResponse(exchange, s"$emptyChoices\n\ndata: [DONE]\n\n")
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      chunks shouldBe empty
      result.toOption.get.content shouldBe ""
      result.toOption.get.usage.get.promptTokens shouldBe 3
    }

  it should "tolerate a chunk whose usage field has no recognised token counts" in
    withServer("/v1/chat/completions") { exchange =>
      val noTokens =
        """data: {"id":"chatcmpl-test","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}],"usage":{}}"""
      sendSseResponse(exchange, s"$noTokens\n\ndata: [DONE]\n\n")
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.content shouldBe "hi"
      result.toOption.get.usage shouldBe None
    }

  it should "populate the returned completion id from SSE chunk ids" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hi"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      result.toOption.get.id shouldBe "chatcmpl-test"
    }

  it should "set stream:true in the outgoing request body" in
    withServer("/v1/chat/completions") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("stream").bool shouldBe true
      sendSseResponse(exchange, openAISseBody(Seq("ok"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(), _ => ())
    }

  it should "include the model in the request body" in
    withServer("/v1/chat/completions") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("model").str shouldBe "mistral-small-latest"
      sendSseResponse(exchange, openAISseBody(Seq("ok"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(), _ => ())
    }

  it should "pass maxTokens option in the streaming request" in
    withServer("/v1/chat/completions") { exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val json = ujson.read(body)
      json("max_tokens").num.toInt shouldBe 256
      sendSseResponse(exchange, openAISseBody(Seq("ok"), "mistral-small-latest"))
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      client.streamComplete(conversation, CompletionOptions(maxTokens = Some(256)), _ => ())
    }

  // ===========================================================================
  // streamComplete() — error handling
  // ===========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/v1/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val client = new MistralClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/v1/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/v1/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new MistralClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "not invoke onChunk when the server returns an error status" in
    withServer("/v1/chat/completions")(exchange => sendJsonResponse(exchange, 429, """{"error":"Rate limited"}""")) {
      baseUrl =>
        val client       = new MistralClient(localConfig(baseUrl))
        var chunksCalled = 0
        val result       = client.streamComplete(conversation, CompletionOptions(), _ => chunksCalled += 1)

        result.isLeft shouldBe true
        chunksCalled shouldBe 0
    }

  // ===========================================================================
  // streamComplete() — exchange logging
  // ===========================================================================

  it should "record a provider exchange with streaming request and SSE response body" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " Mistral"), "mistral-small-latest"))
    } { baseUrl =>
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(e: ProviderExchange): Unit = exchanges += e

      val client = new MistralClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      exchanges should have size 1
      val ex = exchanges.head
      ex.provider shouldBe "mistral"
      ex.model shouldBe Some("mistral-small-latest")
      ex.requestBody should include("\"stream\":true")
      ex.requestBody should include("hello")
      ex.responseBody.value should include("data:")
      ex.responseBody.value should include("Hello")
      ex.responseBody.value should include("[DONE]")
      ex.errorMessage shouldBe empty
    }

  it should "record a provider exchange with errorMessage when streaming returns an error" in
    withServer("/v1/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val exchanges = ListBuffer.empty[ProviderExchange]
        val sink = new ProviderExchangeSink:
          override def record(e: ProviderExchange): Unit = exchanges += e

        val client = new MistralClient(
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
    val client = new MistralClient(
      MistralConfig("k", "mistral-small-latest", "https://example.invalid", 128000, 4096)
    )
    client.close()
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("already closed")
  }
}
