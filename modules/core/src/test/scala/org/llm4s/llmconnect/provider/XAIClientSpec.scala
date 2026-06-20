package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.XAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * Unit tests for [[XAIClient]] using a local ephemeral HTTP server.
 *
 * All tests are self-contained — no real xAI API key required. Covers happy-path
 * completion, streaming, error mapping, exchange logging, and edge cases.
 */
class XAIClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def localConfig(baseUrl: String): XAIConfig =
    XAIConfig(
      apiKey = "xai-test-key",
      model = "grok-beta",
      baseUrl = baseUrl,
      contextWindow = 131072,
      reserveCompletion = 4096,
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  // ==========================================================================
  // complete() — happy path
  // ==========================================================================

  "XAIClient.complete" should "parse a successful OpenAI-compatible response" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Hello from Grok!", "grok-beta"))
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content shouldBe "Hello from Grok!"
      completion.model shouldBe "grok-beta"
      completion.id shouldBe "chatcmpl-test"
      completion.usage shouldBe defined
      completion.usage.get.promptTokens shouldBe 10
      completion.usage.get.completionTokens shouldBe 5
      completion.usage.get.totalTokens shouldBe 15
    }

  it should "return the correct context window" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("OK", "grok-beta"))
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      client.getContextWindow() shouldBe 131072
    }

  it should "return the correct reserve completion" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("OK", "grok-beta"))
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      client.getReserveCompletion() shouldBe 4096
    }

  // ==========================================================================
  // complete() — response parsing edge cases
  // ==========================================================================

  it should "handle completion with missing usage field" in
    withServer("/chat/completions") { exchange =>
      val body =
        """{
          |  "id": "chatcmpl-no-usage",
          |  "created": 1700000000,
          |  "model": "grok-beta",
          |  "choices": [{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]
          |}""".stripMargin
      sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isRight shouldBe true
      result.toOption.get.usage shouldBe None
    }

  it should "forward maxTokens when specified" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Short answer", "grok-beta"))
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions(maxTokens = Some(50)))
      result.isRight shouldBe true
      result.toOption.get.content shouldBe "Short answer"
    }

  // ==========================================================================
  // complete() — HTTP error mapping
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 401, """{"error":{"message":"Unauthorized"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 403 to AuthenticationError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 403, """{"error":{"message":"Forbidden"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 429 to RateLimitError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"error":{"message":"Rate limit exceeded"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[RateLimitError]
    }

  it should "map HTTP 500 to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":{"message":"Internal server error"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "sanitize non-JSON error body and not leak sensitive data" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 502, "Internal error: token=xai-secret-123")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.complete(conversation, CompletionOptions())
      result.isLeft shouldBe true
      val err = result.swap.toOption.get
      err shouldBe a[ServiceError]
      (err.message should not).include("xai-secret-123")
      err.message should include("xai API error")
    }

  // ==========================================================================
  // complete() — exchange logging
  // ==========================================================================

  it should "record a provider exchange when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("Logged response", "grok-beta"))
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new XAIClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink),
      )
      val result = client.complete(conversation, CompletionOptions())

      result.isRight shouldBe true
      recorded should have size 1

      val ex = recorded.head
      ex.provider shouldBe "xai"
      ex.model shouldBe Some("grok-beta")
      ex.requestBody should include("\"messages\"")
      ex.requestBody should include("hello")
      ex.responseBody.value should include("Logged response")
    }

  it should "record a failed exchange when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 401, """{"error":{"message":"Bad key"}}""")
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new XAIClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink),
      )
      client.complete(conversation, CompletionOptions())

      recorded should have size 1
      recorded.head.provider shouldBe "xai"
    }

  // ==========================================================================
  // streamComplete()
  // ==========================================================================

  "XAIClient.streamComplete" should "collect streamed chunks and return a completion" in
    withServer("/chat/completions") { exchange =>
      val sseBody = openAISseBody(Seq("Hello, ", "world!", " How are you?"), "grok-beta")
      sendSseResponse(exchange, sseBody)
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

      result.isRight shouldBe true
      result.toOption.get.content should include("Hello")
      chunks should not be empty
    }

  it should "map HTTP 401 error during streaming to AuthenticationError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 401, """{"error":{"message":"Unauthorized"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[AuthenticationError]
    }

  it should "map HTTP 500 error during streaming to ServiceError" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 500, """{"error":{"message":"Internal error"}}""")
    } { baseUrl =>
      val client = new XAIClient(localConfig(baseUrl))
      val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ServiceError]
    }

  it should "record streaming exchange when logging is enabled" in
    withServer("/chat/completions") { exchange =>
      val sseBody = openAISseBody(Seq("Stream chunk"), "grok-beta")
      sendSseResponse(exchange, sseBody)
    } { baseUrl =>
      val recorded = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit =
          recorded += exchange

      val client = new XAIClient(
        localConfig(baseUrl),
        exchangeLogging = ProviderExchangeLogging.enabled(sink),
      )
      client.streamComplete(conversation, CompletionOptions(), _ => ())

      recorded should have size 1
      recorded.head.provider shouldBe "xai"
    }

  // ==========================================================================
  // request body construction
  // ==========================================================================

  "XAIClient.createRequestBody" should "include model and messages" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("OK", "grok-beta"))
    } { baseUrl =>
      val client  = new XAIClient(localConfig(baseUrl))
      val options = CompletionOptions()
      val body    = client.createRequestBody(conversation, options)
      body("model").str shouldBe "grok-beta"
      body("messages").arr should not be empty
    }

  it should "include max_tokens when specified" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("OK", "grok-beta"))
    } { baseUrl =>
      val client  = new XAIClient(localConfig(baseUrl))
      val options = CompletionOptions(maxTokens = Some(256))
      val body    = client.createRequestBody(conversation, options)
      body.obj.get("max_tokens").flatMap(_.numOpt).map(_.toInt) shouldBe Some(256)
    }

  // ==========================================================================
  // companion object
  // ==========================================================================

  "XAIClient companion" should "create a client successfully via apply(config)" in {
    val cfg = XAIConfig(
      apiKey = "xai-key",
      model = "grok-2-latest",
      baseUrl = "https://api.x.ai/v1",
      contextWindow = 131072,
      reserveCompletion = 4096,
    )
    val result = XAIClient(cfg)
    result.isRight shouldBe true
  }

  it should "create a client successfully via apply(config, metrics)" in {
    val cfg = XAIConfig(
      apiKey = "xai-key",
      model = "grok-beta",
      baseUrl = "https://api.x.ai/v1",
      contextWindow = 131072,
      reserveCompletion = 4096,
    )
    val result = XAIClient(cfg, org.llm4s.metrics.MetricsCollector.noop)
    result.isRight shouldBe true
  }

  it should "create a client successfully via apply(config, metrics, exchangeLogging)" in {
    val cfg = XAIConfig(
      apiKey = "xai-key",
      model = "grok-2-vision-latest",
      baseUrl = "https://api.x.ai/v1",
      contextWindow = 131072,
      reserveCompletion = 4096,
    )
    val result = XAIClient(cfg, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled)
    result.isRight shouldBe true
  }
}
