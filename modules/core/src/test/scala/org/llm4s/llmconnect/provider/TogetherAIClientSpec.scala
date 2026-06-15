package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ServiceError }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, TogetherAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for [[TogetherAIClient]] using [[MockHttpClient]].
 *
 * No real API key required — all HTTP interactions are intercepted by the mock.
 */
class TogetherAIClientSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService =
    ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val testConfig = TogetherAIConfig(
    apiKey = "test-api-key",
    model = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
    baseUrl = "https://api.together.xyz/v1",
    contextWindow = 131072,
    reserveCompletion = 4096
  )

  private val validCompletionResponse =
    """{
      |  "id": "chat-abc123",
      |  "object": "chat.completion",
      |  "created": 1700000000,
      |  "model": "meta-llama/Llama-3.3-70B-Instruct-Turbo",
      |  "choices": [
      |    {
      |      "index": 0,
      |      "message": {
      |        "role": "assistant",
      |        "content": "Hello! How can I help you today?"
      |      },
      |      "finish_reason": "stop"
      |    }
      |  ],
      |  "usage": {
      |    "prompt_tokens": 10,
      |    "completion_tokens": 9,
      |    "total_tokens": 19
      |  }
      |}""".stripMargin

  private val conversationWithUser = Conversation(Seq(UserMessage("Say hi in one word")))

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  "TogetherAIClient.complete" should "return a successful Completion on 200 OK" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    val result = client.complete(conversationWithUser, CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content should not be empty
    completion.content should include("Hello")
    completion.model shouldBe testConfig.model
  }

  it should "populate token usage from response" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    val result = client.complete(conversationWithUser, CompletionOptions())
    val usage  = result.toOption.get.usage

    usage.isDefined shouldBe true
    usage.get.promptTokens shouldBe 10
    usage.get.completionTokens shouldBe 9
    usage.get.totalTokens shouldBe 19
  }

  it should "use the correct API endpoint URL" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.complete(conversationWithUser, CompletionOptions())

    mock.lastUrl shouldBe Some("https://api.together.xyz/v1/chat/completions")
  }

  it should "include Authorization Bearer header in request" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.complete(conversationWithUser, CompletionOptions())

    mock.lastHeaders.flatMap(_.get("Authorization")) shouldBe Some("Bearer test-api-key")
  }

  it should "include Content-Type application/json header" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.complete(conversationWithUser, CompletionOptions())

    mock.lastHeaders.flatMap(_.get("Content-Type")) shouldBe Some("application/json")
  }

  it should "send the correct model in request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.complete(conversationWithUser, CompletionOptions())

    val body = mock.lastBody.getOrElse("")
    body should include("meta-llama/Llama-3.3-70B-Instruct-Turbo")
  }

  it should "include user message in request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.complete(conversationWithUser, CompletionOptions())

    val body = mock.lastBody.getOrElse("")
    body should include("Say hi in one word")
  }

  // ---------------------------------------------------------------------------
  // Error responses
  // ---------------------------------------------------------------------------

  it should "return AuthenticationError on 401 response" in {
    val errorBody = """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""
    val mock      = new MockHttpClient(HttpResponse(401, errorBody))
    val client    = TogetherAIClient.forTest(testConfig, mock)
    val result    = client.complete(conversationWithUser, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "return a Left on 500 server error" in {
    val errorBody = """{"error":{"message":"Internal server error"}}"""
    val mock      = new MockHttpClient(HttpResponse(500, errorBody))
    val client    = TogetherAIClient.forTest(testConfig, mock)
    val result    = client.complete(conversationWithUser, CompletionOptions())

    result.isLeft shouldBe true
  }

  it should "return a Left on 429 rate limit error" in {
    val errorBody = """{"error":{"message":"Rate limit exceeded"}}"""
    val mock      = new MockHttpClient(HttpResponse(429, errorBody))
    val client    = TogetherAIClient.forTest(testConfig, mock)
    val result    = client.complete(conversationWithUser, CompletionOptions())

    result.isLeft shouldBe true
  }

  it should "return a Left on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = TogetherAIClient.forTest(testConfig, failing)
    val result  = client.complete(conversationWithUser, CompletionOptions())

    result.isLeft shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Streaming
  // ---------------------------------------------------------------------------

  "TogetherAIClient.streamComplete" should "emit streaming chunks and return a Completion" in {
    val sseBody =
      """data: {"id":"stream-1","object":"chat.completion.chunk","model":"meta-llama/Llama-3.3-70B-Instruct-Turbo","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}
        |
        |data: {"id":"stream-1","object":"chat.completion.chunk","model":"meta-llama/Llama-3.3-70B-Instruct-Turbo","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}
        |
        |data: [DONE]
        |""".stripMargin

    val mock   = new MockHttpClient(HttpResponse(200, sseBody))
    val client = TogetherAIClient.forTest(testConfig, mock)
    val chunks = scala.collection.mutable.ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]
    val result = client.streamComplete(conversationWithUser, CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    chunks should not be empty
    result.toOption.get.content should not be empty
  }

  it should "set stream=true in the request body" in {
    val sseBody = "data: [DONE]\n"
    val mock    = new MockHttpClient(HttpResponse(200, sseBody))
    val client  = TogetherAIClient.forTest(testConfig, mock)
    client.streamComplete(conversationWithUser, CompletionOptions(), _ => ())

    val body = mock.lastBody.getOrElse("")
    body should include("\"stream\"")
  }

  it should "return a Left on network failure during streaming" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = TogetherAIClient.forTest(testConfig, failing)
    val result  = client.streamComplete(conversationWithUser, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Config and factory
  // ---------------------------------------------------------------------------

  "TogetherAIConfig" should "have correct default base URL" in {
    TogetherAIConfig.DEFAULT_BASE_URL shouldBe "https://api.together.xyz/v1"
  }

  it should "be constructable via fromValues" in {
    val cfg = TogetherAIConfig.fromValues(
      modelName = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
      apiKey = "key-123",
      baseUrl = "https://api.together.xyz/v1"
    )
    cfg.model shouldBe "meta-llama/Llama-3.3-70B-Instruct-Turbo"
    cfg.apiKey shouldBe "key-123"
    cfg.baseUrl shouldBe "https://api.together.xyz/v1"
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "throw on empty apiKey" in {
    an[IllegalArgumentException] should be thrownBy {
      TogetherAIConfig.fromValues(
        modelName = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        apiKey = "",
        baseUrl = "https://api.together.xyz/v1"
      )
    }
  }

  it should "throw on empty baseUrl" in {
    an[IllegalArgumentException] should be thrownBy {
      TogetherAIConfig.fromValues(
        modelName = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        apiKey = "key-123",
        baseUrl = ""
      )
    }
  }

  it should "reflect ProviderKind.TogetherAI" in {
    import org.llm4s.types.ProviderModelTypes.ProviderKind
    testConfig.provider shouldBe ProviderKind.TogetherAI
  }

  it should "redact apiKey in toString" in {
    (testConfig.toString should not).include("test-api-key")
    testConfig.toString should include("***")
  }

  "TogetherAIClient" should "expose correct contextWindow" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.getContextWindow() shouldBe testConfig.contextWindow
  }

  it should "expose correct reserveCompletion" in {
    val mock   = new MockHttpClient(HttpResponse(200, validCompletionResponse))
    val client = TogetherAIClient.forTest(testConfig, mock)
    client.getReserveCompletion() shouldBe testConfig.reserveCompletion
  }

  it should "be successfully created via apply factory" in {
    val result = TogetherAIClient(testConfig)
    result.isRight shouldBe true
  }

  it should "be successfully created via apply with metrics" in {
    val result = TogetherAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop)
    result.isRight shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Variant model names + context window resolution
  // ---------------------------------------------------------------------------

  "TogetherAIConfig context window resolution" should "return larger context for Llama 3.3" in {
    val cfg = TogetherAIConfig.fromValues(
      modelName = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
      apiKey = "k",
      baseUrl = "https://api.together.xyz/v1"
    )
    cfg.contextWindow should be >= 8192
  }

  it should "return a context window for Mixtral models" in {
    val cfg = TogetherAIConfig.fromValues(
      modelName = "mistralai/Mixtral-8x7B-Instruct-v0.1",
      apiKey = "k",
      baseUrl = "https://api.together.xyz/v1"
    )
    cfg.contextWindow should be > 0
  }

  it should "return a context window for Qwen models" in {
    val cfg = TogetherAIConfig.fromValues(
      modelName = "Qwen/Qwen2.5-72B-Instruct-Turbo",
      apiKey = "k",
      baseUrl = "https://api.together.xyz/v1"
    )
    cfg.contextWindow should be > 0
  }

  it should "use fallback for unknown model names" in {
    val cfg = TogetherAIConfig.fromValues(
      modelName = "unknown/model-v1",
      apiKey = "k",
      baseUrl = "https://api.together.xyz/v1"
    )
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }
}
