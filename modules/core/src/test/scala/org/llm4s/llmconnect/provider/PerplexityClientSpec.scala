package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * Local HTTP server tests for PerplexityClient.
 *
 * Verifies the complete HTTP request->response cycle against a deterministic
 * local server. No API keys or external services required.
 */
class PerplexityClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def localConfig(baseUrl: String): PerplexityConfig =
    PerplexityConfig(
      apiKey = "test-perplexity-key",
      model = "sonar",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("What is the latest news?")))

  // ==========================================================================
  // complete() — success
  // ==========================================================================

  "PerplexityClient.complete" should "parse a successful response" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Today's top news: Scala 3.6 released!", "sonar"))
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Today's top news: Scala 3.6 released!"
      completion.model shouldBe "sonar"
      completion.id shouldBe "chatcmpl-test"
      completion.usage shouldBe defined
      completion.usage.get.promptTokens shouldBe 10
      completion.usage.get.completionTokens shouldBe 5
      completion.usage.get.totalTokens shouldBe 15
    }

  it should "record a provider exchange when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Perplexity logged response", "sonar"))
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new PerplexityClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      recorded should have size 1

      val exchange = recorded.head
      exchange.provider shouldBe "perplexity"
      exchange.model shouldBe Some("sonar")
      exchange.requestBody should include("\"messages\"")
      exchange.requestBody should include("What is the latest news?")
      exchange.responseBody shouldBe defined
      exchange.responseBody.get should include("chatcmpl-test")
      exchange.responseBody.get should include("Perplexity logged response")
      exchange.errorMessage shouldBe empty
      exchange.durationMs should be >= 0L
    }

  it should "return model identifier from config" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("response", "sonar"))
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      client.getContextWindow() shouldBe 128000
      client.getReserveCompletion() shouldBe 4096
    }

  it should "include maxTokens in request when specified" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Short answer", "sonar"))
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions(maxTokens = Some(100)))

      result.isRight shouldBe true
      result.toOption.get.content shouldBe "Short answer"
    }

  // ==========================================================================
  // complete() — error handling
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val client = new PerplexityClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 403 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 403, """{"error":"Forbidden"}""")) {
      baseUrl =>
        val client = new PerplexityClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "map HTTP 502 to ServiceError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 502, "Bad Gateway")) { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  // ==========================================================================
  // streamComplete() — success
  // ==========================================================================

  "PerplexityClient.streamComplete" should "parse SSE events and accumulate content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " world"), "sonar"))
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello world"
      chunks should not be empty
    }

  it should "complete streaming successfully and produce correct content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Perplexity test content"), "sonar"))
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Perplexity test content"
      completion.model shouldBe "sonar"
    }

  it should "record provider exchanges for streaming responses when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Real-time", " answer"), "sonar"))
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new PerplexityClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      )
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      recorded should have size 1
      recorded.head.provider shouldBe "perplexity"
      recorded.head.requestBody should include("\"stream\":true")
      recorded.head.responseBody.value should include("data:")
      recorded.head.responseBody.value should include("Real-time")
      recorded.head.responseBody.value should include("[DONE]")
      recorded.head.errorMessage shouldBe empty
    }

  it should "handle [DONE] termination signal without error" in
    withServer("/chat/completions") { exchange =>
      val body = "data: [DONE]\n\n"
      sendSseResponse(exchange, body)
    } { baseUrl =>
      val client     = new PerplexityClient(localConfig(baseUrl))
      var chunkCount = 0
      val result     = client.streamComplete(conversation, CompletionOptions(), _ => chunkCount += 1)

      result.isRight shouldBe true
      chunkCount shouldBe 0
    }

  // ==========================================================================
  // streamComplete() — error handling
  // ==========================================================================

  it should "map error status codes to typed errors" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Invalid API key"}""")) {
      baseUrl =>
        val client = new PerplexityClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 500 streaming error to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Service unavailable"}""")
    } { baseUrl =>
      val client = new PerplexityClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  // ==========================================================================
  // createRequestBody() — internal test seam
  // ==========================================================================

  "PerplexityClient.createRequestBody" should "include model and messages in request" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, openAICompletion("ok", "sonar"))) {
      baseUrl =>
        val client      = new PerplexityClient(localConfig(baseUrl))
        val requestBody = client.createRequestBody(conversation, CompletionOptions())

        requestBody("model").str shouldBe "sonar"
        requestBody("messages").arr should not be empty
        requestBody("messages").arr.head("content").str shouldBe "What is the latest news?"
    }

  it should "include stream flag when streaming" in
    withServer("/chat/completions")(exchange => sendSseResponse(exchange, openAISseBody(Seq("test"), "sonar"))) {
      baseUrl =>
        val client      = new PerplexityClient(localConfig(baseUrl))
        val requestBody = client.createRequestBody(conversation, CompletionOptions())
        requestBody("stream") = true

        requestBody("stream").bool shouldBe true
    }
}
