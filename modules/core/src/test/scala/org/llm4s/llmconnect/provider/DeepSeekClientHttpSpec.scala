package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Local HTTP server tests for DeepSeekClient (Tier 1).
 *
 * Verifies the complete HTTP request→response cycle against a deterministic
 * local server. Catches serialization, parsing, streaming, and error mapping bugs.
 * No API keys or external services required — runs unconditionally as part of `sbt test`.
 */
class DeepSeekClientHttpSpec extends AnyFlatSpec with Matchers {

  private def localConfig(baseUrl: String): DeepSeekConfig =
    DeepSeekConfig(
      apiKey = "test-key",
      model = "deepseek-chat",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 8192
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  // ==========================================================================
  // complete() — success
  // ==========================================================================

  "DeepSeekClient.complete" should "parse a successful response" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Hello from DeepSeek!", "deepseek-chat"))
    } { baseUrl =>
      val client = new DeepSeekClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello from DeepSeek!"
      completion.model shouldBe "deepseek-chat"
      completion.id shouldBe "chatcmpl-test"
      completion.usage shouldBe defined
      completion.usage.get.promptTokens shouldBe 10
      completion.usage.get.completionTokens shouldBe 5
      completion.usage.get.totalTokens shouldBe 15
    }

  // ==========================================================================
  // complete() — error handling
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Unauthorized"}""")) {
      baseUrl =>
        val client = new DeepSeekClient(localConfig(baseUrl))
        val result = client.complete(conversation, CompletionOptions())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":"Rate limit exceeded"}""")
    } { baseUrl =>
      val client = new DeepSeekClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":"Internal server error"}""")
    } { baseUrl =>
      val client = new DeepSeekClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  // ==========================================================================
  // streamComplete() — success
  // ==========================================================================

  "DeepSeekClient.streamComplete" should "parse SSE events and accumulate content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " world"), "deepseek-chat"))
    } { baseUrl =>
      val client = new DeepSeekClient(localConfig(baseUrl))
      val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello world"
      chunks should not be empty
    }

  it should "complete streaming successfully and produce correct content" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, openAISseBody(Seq("test"), "deepseek-chat"))
    } { baseUrl =>
      val client = new DeepSeekClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "test"
      completion.model shouldBe "deepseek-chat"
    }

  // ==========================================================================
  // streamComplete() — error handling
  // ==========================================================================

  it should "map error status codes to typed errors" in
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"Invalid API key"}""")) {
      baseUrl =>
        val client = new DeepSeekClient(localConfig(baseUrl))
        val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "handle [DONE] termination signal without error" in
    withServer("/chat/completions") { exchange =>
      // Only [DONE] with no content chunks — edge case
      val body = "data: [DONE]\n\n"
      sendSseResponse(exchange, body)
    } { baseUrl =>
      val client     = new DeepSeekClient(localConfig(baseUrl))
      var chunkCount = 0
      val result     = client.streamComplete(conversation, CompletionOptions(), _ => chunkCount += 1)

      result.isRight shouldBe true
      chunkCount shouldBe 0
    }
}
