package org.llm4s.llmconnect.provider

import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import scala.collection.mutable.ListBuffer

/**
 * Mock-based integration tests for [[VertexAIClient]].
 *
 * Uses [[MockHttpClient]] / [[FailingHttpClient]] — no GCP credentials needed.
 *
 * Covers:
 *  - Gemini-on-Vertex response format parsing
 *  - Claude-on-Vertex response format parsing
 *  - streamComplete() chunks assembled correctly
 *  - ADC auth failure (empty access token) → ConfigurationError
 *  - 401/403 responses → AuthenticationError
 *  - Quota exceeded (429) → RateLimitError
 *  - Regional endpoint routing (us-central1, europe-west1)
 *  - Network failure → wrapped error
 *  - Provider exchange logging
 */
class VertexAIClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private def geminiConfig(
    project: String = "my-project",
    location: String = "us-central1",
    model: String = "gemini-1.5-flash",
    token: String = "test-token",
    baseUrl: String = "https://us-central1-aiplatform.googleapis.com/v1",
  ): VertexAIConfig =
    VertexAIConfig(
      project = project,
      location = location,
      model = model,
      accessToken = token,
      baseUrl = baseUrl,
      contextWindow = 1048576,
      reserveCompletion = 8192,
    )

  private def claudeConfig(
    project: String = "my-project",
    location: String = "us-central1",
    model: String = "claude-3-haiku@20240307",
    token: String = "test-token",
    baseUrl: String = "https://us-central1-aiplatform.googleapis.com/v1",
  ): VertexAIConfig =
    VertexAIConfig(
      project = project,
      location = location,
      model = model,
      accessToken = token,
      baseUrl = baseUrl,
      contextWindow = 200000,
      reserveCompletion = 8192,
    )

  private def mkGeminiClient(mockHttp: MockHttpClient, config: VertexAIConfig = geminiConfig()): VertexAIClient =
    VertexAIClient.forTest(config, mockHttp)

  private def conversation(text: String = "Hello"): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  private def geminiSuccessBody: String =
    """|{
       |  "candidates": [{
       |    "content": {
       |      "parts": [{"text": "Hello from Vertex!"}],
       |      "role": "model"
       |    },
       |    "finishReason": "STOP"
       |  }],
       |  "usageMetadata": {
       |    "promptTokenCount": 10,
       |    "candidatesTokenCount": 5,
       |    "totalTokenCount": 15
       |  }
       |}""".stripMargin

  private def claudeSuccessBody: String =
    """|{
       |  "id": "msg-vertex-001",
       |  "type": "message",
       |  "role": "assistant",
       |  "content": [{"type": "text", "text": "Hello from Claude on Vertex!"}],
       |  "usage": {"input_tokens": 12, "output_tokens": 7}
       |}""".stripMargin

  private def ok(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def err(status: Int): HttpResponse = HttpResponse(status, s"""{"error":{"message":"Error $status"}}""", Map.empty)

  // -----------------------------------------------------------------------
  // complete() — Gemini-on-Vertex response format
  // -----------------------------------------------------------------------

  "VertexAIClient.complete() with Gemini model" should "parse text content from a 200 response" in {
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello from Vertex!"
  }

  it should "parse token usage from the Gemini response" in {
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.value
    usage.promptTokens shouldBe 10
    usage.completionTokens shouldBe 5
    usage.totalTokens shouldBe 15
  }

  it should "set the model name in the Completion from config" in {
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.model shouldBe "gemini-1.5-flash"
  }

  it should "parse a Gemini tool-call response" in {
    val toolCallBody =
      """{
        |  "candidates": [{
        |    "content": {
        |      "parts": [{"functionCall": {"name": "get_weather", "args": {"city": "London"}}}],
        |      "role": "model"
        |    }
        |  }]
        |}""".stripMargin
    val mock   = new MockHttpClient(ok(toolCallBody))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "return an error when candidates array is empty" in {
    val emptyBody = """{"candidates": []}"""
    val mock      = new MockHttpClient(ok(emptyBody))
    val client    = mkGeminiClient(mock)
    val result    = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  // -----------------------------------------------------------------------
  // complete() — Claude-on-Vertex response format
  // -----------------------------------------------------------------------

  "VertexAIClient.complete() with Claude model" should "parse text content from an Anthropic-format response" in {
    val mock   = new MockHttpClient(ok(claudeSuccessBody))
    val client = VertexAIClient.forTest(claudeConfig(), mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello from Claude on Vertex!"
  }

  it should "parse token usage from the Claude response" in {
    val mock   = new MockHttpClient(ok(claudeSuccessBody))
    val client = VertexAIClient.forTest(claudeConfig(), mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.value
    usage.promptTokens shouldBe 12
    usage.completionTokens shouldBe 7
  }

  it should "preserve the response id from the Claude response" in {
    val mock   = new MockHttpClient(ok(claudeSuccessBody))
    val client = VertexAIClient.forTest(claudeConfig(), mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.id shouldBe "msg-vertex-001"
  }

  it should "set the model name in the Completion from config" in {
    val mock   = new MockHttpClient(ok(claudeSuccessBody))
    val client = VertexAIClient.forTest(claudeConfig(), mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.model shouldBe "claude-3-haiku@20240307"
  }

  // -----------------------------------------------------------------------
  // Error handling — HTTP status codes
  // -----------------------------------------------------------------------

  "VertexAIClient.complete() error handling" should "return AuthenticationError on HTTP 401" in {
    val mock   = new MockHttpClient(err(401))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mock   = new MockHttpClient(err(403))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429 (quota exceeded)" in {
    val quotaBody = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
    val mock      = new MockHttpClient(HttpResponse(429, quotaBody, Map.empty))
    val client    = mkGeminiClient(mock)
    val result    = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mock   = new MockHttpClient(err(400))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mock   = new MockHttpClient(err(500))
    val client = mkGeminiClient(mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  it should "return ConfigurationError when accessToken is empty (ADC auth failure)" in {
    val config = geminiConfig(token = "")
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
  }

  it should "return ConfigurationError when accessToken is blank (ADC auth failure)" in {
    val config = geminiConfig(token = "   ")
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val result = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
  }

  it should "return a wrapped error on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = VertexAIClient.forTest(geminiConfig(), failing)
    val result  = client.complete(conversation(), CompletionOptions())

    result.isLeft shouldBe true
  }

  // -----------------------------------------------------------------------
  // Regional endpoint routing
  // -----------------------------------------------------------------------

  "VertexAIClient endpoint routing" should "use us-central1 regional endpoint" in {
    val config = geminiConfig(
      location = "us-central1",
      baseUrl = "https://us-central1-aiplatform.googleapis.com/v1",
    )
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastUrl.value should include("us-central1-aiplatform.googleapis.com")
    mock.lastUrl.value should include("us-central1")
  }

  it should "use europe-west1 regional endpoint" in {
    val config = geminiConfig(
      location = "europe-west1",
      baseUrl = "https://europe-west1-aiplatform.googleapis.com/v1",
    )
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastUrl.value should include("europe-west1-aiplatform.googleapis.com")
    mock.lastUrl.value should include("europe-west1")
  }

  it should "include project and location in Gemini endpoint URL" in {
    val config = geminiConfig(project = "test-project", location = "us-central1")
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastUrl.value should include("test-project")
    mock.lastUrl.value should include("us-central1")
    mock.lastUrl.value should include("generateContent")
  }

  it should "use rawPredict endpoint for Claude models" in {
    val config = claudeConfig()
    val mock   = new MockHttpClient(ok(claudeSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastUrl.value should include("rawPredict")
    mock.lastUrl.value should include("anthropic")
  }

  it should "use generateContent endpoint for Gemini models" in {
    val config = geminiConfig()
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastUrl.value should include("generateContent")
    mock.lastUrl.value should include("google")
  }

  it should "include Authorization header with Bearer token" in {
    val config = geminiConfig(token = "my-access-token")
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = VertexAIClient.forTest(config, mock)
    val _      = client.complete(conversation(), CompletionOptions())

    mock.lastHeaders.value.get("Authorization") shouldBe Some("Bearer my-access-token")
  }

  // -----------------------------------------------------------------------
  // streamComplete() — Gemini SSE chunks
  // -----------------------------------------------------------------------

  "VertexAIClient.streamComplete()" should "parse SSE lines and accumulate into a Completion" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"

    val mock   = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val chunks = ListBuffer.empty[StreamedChunk]
    val client = VertexAIClient.forTest(geminiConfig(), mock)
    val result = client.streamComplete(conversation(), CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    result.toOption.get.content should include("Hello")
    result.toOption.get.content should include("world")
    chunks should have size 2
  }

  it should "parse token usage from the final SSE chunk" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"

    val mock   = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val client = VertexAIClient.forTest(geminiConfig(), mock)
    val result = client.streamComplete(conversation(), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.value
    usage.promptTokens shouldBe 5
    usage.completionTokens shouldBe 2
  }

  it should "return AuthenticationError on non-200 streaming status" in {
    val errorBody = """{"error":{"message":"API key missing","status":"UNAUTHENTICATED"}}"""
    val mock      = new MockHttpClient(HttpResponse(401, errorBody, Map.empty))
    val client    = VertexAIClient.forTest(geminiConfig(), mock)
    val result    = client.streamComplete(conversation(), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return ConfigurationError when accessToken is empty during streaming" in {
    val config = geminiConfig(token = "")
    val mock   = new MockHttpClient(HttpResponse(200, "", Map.empty))
    val client = VertexAIClient.forTest(config, mock)
    val result = client.streamComplete(conversation(), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
  }

  it should "use streamGenerateContent endpoint for Gemini streaming" in {
    val sseData = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n"
    val mock    = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val client  = VertexAIClient.forTest(geminiConfig(), mock)
    val _       = client.streamComplete(conversation(), CompletionOptions(), _ => ())

    mock.lastUrl.value should include("streamGenerateContent")
  }

  // -----------------------------------------------------------------------
  // Provider exchange logging
  // -----------------------------------------------------------------------

  "VertexAIClient exchange logging" should "record exchanges when logging is enabled" in {
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    }

    val mock = new MockHttpClient(ok(geminiSuccessBody))
    val client = new VertexAIClient(
      geminiConfig(),
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink),
      mock,
    )
    val result = client.complete(conversation("test message"), CompletionOptions())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "vertex"
    exchanges.head.model shouldBe Some("gemini-1.5-flash")
    exchanges.head.requestBody should include("test message")
    exchanges.head.responseBody.value should include("Hello from Vertex!")
  }

  // -----------------------------------------------------------------------
  // Config accessors
  // -----------------------------------------------------------------------

  "VertexAIClient config accessors" should "return correct context window" in {
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = mkGeminiClient(mock)
    client.getContextWindow() shouldBe 1048576
  }

  it should "return correct reserve completion" in {
    val mock   = new MockHttpClient(ok(geminiSuccessBody))
    val client = mkGeminiClient(mock)
    client.getReserveCompletion() shouldBe 8192
  }

  // -----------------------------------------------------------------------
  // VertexAIConfig tests
  // -----------------------------------------------------------------------

  "VertexAIConfig" should "redact the access token in toString" in {
    val config = geminiConfig(token = "super-secret-token")
    config.toString should not include "super-secret-token"
    config.toString should include("project=my-project")
    config.toString should include("location=us-central1")
  }

  it should "expose the correct ProviderKind" in {
    val config = geminiConfig()
    config.provider shouldBe org.llm4s.types.ProviderModelTypes.ProviderKind.VertexAI
  }

  it should "derive the correct regional base URL from location using fromValues" in {
    import org.llm4s.llmconnect.config.ContextWindowResolver
    given ContextWindowResolver = ContextWindowResolver(
      org.llm4s.model.ModelRegistryTestSupport.defaultService()
    )
    val config = VertexAIConfig.fromValues(
      project = "my-project",
      location = "europe-west1",
      modelName = "gemini-1.5-flash",
      accessToken = "tok",
    )
    config.baseUrl should include("europe-west1")
    config.baseUrl should include("aiplatform.googleapis.com")
  }

  it should "use custom baseUrl when provided via fromValues" in {
    import org.llm4s.llmconnect.config.ContextWindowResolver
    given ContextWindowResolver = ContextWindowResolver(
      org.llm4s.model.ModelRegistryTestSupport.defaultService()
    )
    val config = VertexAIConfig.fromValues(
      project = "my-project",
      location = "us-central1",
      modelName = "gemini-1.5-flash",
      accessToken = "tok",
      baseUrl = "http://localhost:9999",
    )
    config.baseUrl shouldBe "http://localhost:9999"
  }

  // -----------------------------------------------------------------------
  // LLMConnect routing
  // -----------------------------------------------------------------------

  "LLMConnect" should "route VertexAIConfig to VertexAIClient" in {
    import org.llm4s.llmconnect.LLMConnect
    val config = geminiConfig()
    val result = LLMConnect.getClient(config)
    result.isRight shouldBe true
    result.toOption.get shouldBe a[VertexAIClient]
  }

  it should "route ProviderKind.VertexAI with VertexAIConfig correctly" in {
    import org.llm4s.llmconnect.LLMConnect
    import org.llm4s.types.ProviderModelTypes.ProviderKind
    val config = geminiConfig()
    val result = LLMConnect.getClient(ProviderKind.VertexAI, config)
    result.isRight shouldBe true
    result.toOption.get shouldBe a[VertexAIClient]
  }
}
