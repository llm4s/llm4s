package org.llm4s.llmconnect.provider

import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  NetworkError,
  RateLimitError,
  ServiceError,
  ValidationError
}
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.BedrockConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Mock-based integration tests for [[BedrockClient]].
 *
 * All tests run under `sbt test` with no AWS credentials or network access.
 * HTTP responses are stubbed using [[MockHttpClient]] or [[FailingHttpClient]].
 */
class BedrockClientSpec extends AnyFlatSpec with Matchers {

  private given org.llm4s.model.ModelRegistryService = ModelRegistryTestSupport.defaultService()

  private val testConfig = BedrockConfig(
    model = "amazon.titan-text-express-v1",
    region = "us-east-1",
    baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
    contextWindow = 32000,
    reserveCompletion = 4096
  )

  private val claudeConfig = BedrockConfig(
    model = "anthropic.claude-3-5-sonnet-20241022-v2:0",
    region = "us-east-1",
    baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi")))

  private val successResponse =
    """{
      |  "ResponseMetadata": { "RequestId": "req-abc-123" },
      |  "output": {
      |    "message": {
      |      "role": "assistant",
      |      "content": [{ "text": "Hello! How can I help you?" }]
      |    }
      |  },
      |  "stopReason": "end_turn",
      |  "usage": {
      |    "inputTokens": 10,
      |    "outputTokens": 8,
      |    "totalTokens": 18
      |  }
      |}""".stripMargin

  // ===========================================================================
  // Happy path — complete()
  // ===========================================================================

  "BedrockClient.complete" should "parse a successful Converse API response" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Hello! How can I help you?"
        completion.id shouldBe "req-abc-123"
        completion.model shouldBe testConfig.model
        completion.usage.isDefined shouldBe true
        completion.usage.foreach { u =>
          u.promptTokens shouldBe 10
          u.completionTokens shouldBe 8
          u.totalTokens shouldBe 18
        }
      }
    )
  }

  it should "populate usage tokens from response" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    result.toOption.get.usage.isDefined shouldBe true
  }

  it should "generate a fallback UUID when RequestId is absent" in {
    val responseNoId =
      """{
        |  "output": {
        |    "message": {
        |      "role": "assistant",
        |      "content": [{ "text": "Hello" }]
        |    }
        |  },
        |  "usage": { "inputTokens": 5, "outputTokens": 2, "totalTokens": 7 }
        |}""".stripMargin

    val mock   = new MockHttpClient(HttpResponse(200, responseNoId))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.id should not be empty
    )
  }

  it should "handle missing usage gracefully" in {
    val responseNoUsage =
      """{
        |  "ResponseMetadata": { "RequestId": "req-no-usage" },
        |  "output": {
        |    "message": {
        |      "role": "assistant",
        |      "content": [{ "text": "No usage" }]
        |    }
        |  }
        |}""".stripMargin

    val mock   = new MockHttpClient(HttpResponse(200, responseNoUsage))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.usage shouldBe None
    )
  }

  it should "pass system message to Bedrock system field" in {
    val mock = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    val convWithSystem = Conversation(Seq(SystemMessage("You are helpful."), UserMessage("Hello")))

    val result = client.complete(convWithSystem, CompletionOptions())
    result.isRight shouldBe true
    mock.lastBody.foreach { body =>
      body should include("system")
      body should include("You are helpful.")
    }
  }

  it should "work with a Claude-on-Bedrock model ID" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(claudeConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    mock.lastUrl.foreach { url =>
      url should include("anthropic.claude-3-5-sonnet-20241022-v2:0")
      url should include("converse")
    }
  }

  it should "include temperature in inferenceConfig" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions(temperature = 0.3))
    result.isRight shouldBe true
    mock.lastBody.foreach { body =>
      body should include("inferenceConfig")
      body should include("temperature")
    }
  }

  it should "include maxTokens in inferenceConfig when specified" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions(maxTokens = Some(512)))
    result.isRight shouldBe true
    mock.lastBody.foreach { body =>
      body should include("maxTokens")
    }
  }

  // ===========================================================================
  // Validation errors
  // ===========================================================================

  it should "return ValidationError for empty conversation" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    val emptyConversation = Conversation(Seq.empty)

    val result = client.complete(emptyConversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("at least one")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "return ValidationError when response has no text content" in {
    val emptyOutput =
      """{
        |  "ResponseMetadata": { "RequestId": "req-empty" },
        |  "output": {
        |    "message": {
        |      "role": "assistant",
        |      "content": []
        |    }
        |  }
        |}""".stripMargin

    val mock   = new MockHttpClient(HttpResponse(200, emptyOutput))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ValidationError],
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "return ValidationError for conversation with only system message" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    val systemOnlyConversation = Conversation(Seq(SystemMessage("Only system")))

    val result = client.complete(systemOnlyConversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ValidationError]
        err.message should include("at least one")
      },
      _ => fail("Expected Left(ValidationError)")
    )
  }

  // ===========================================================================
  // Error mapping — HTTP errors
  // ===========================================================================

  it should "map HTTP 401 to AuthenticationError" in {
    val body   = """{"message": "Unauthorized"}"""
    val mock   = new MockHttpClient(HttpResponse(401, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe an[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 403 to AuthenticationError" in {
    val body   = """{"message": "Forbidden"}"""
    val mock   = new MockHttpClient(HttpResponse(403, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe an[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 429 to RateLimitError" in {
    val body   = """{"message": "Too Many Requests"}"""
    val mock   = new MockHttpClient(HttpResponse(429, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map ThrottlingException to RateLimitError" in {
    val body   = """{"__type": "ThrottlingException", "message": "Rate exceeded"}"""
    val mock   = new MockHttpClient(HttpResponse(400, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map ValidationException to ValidationError" in {
    val body   = """{"__type": "ValidationException", "message": "Invalid model ID"}"""
    val mock   = new MockHttpClient(HttpResponse(400, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ValidationError],
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "map HTTP 400 (generic) to ValidationError" in {
    val body   = """{"message": "Bad request"}"""
    val mock   = new MockHttpClient(HttpResponse(400, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ValidationError],
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "map HTTP 500 to ServiceError" in {
    val body   = """{"message": "Internal server error"}"""
    val mock   = new MockHttpClient(HttpResponse(500, body))
    val client = BedrockClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ServiceError]
        err.context.get("httpStatus") shouldBe Some("500")
      },
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "map network I/O failure to NetworkError" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = BedrockClient.forTest(testConfig, failing)

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[NetworkError],
      _ => fail("Expected Left(NetworkError)")
    )
  }

  // ===========================================================================
  // Missing credentials
  // ===========================================================================

  it should "return ConfigurationError when client is already closed" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    client.close()

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ConfigurationError],
      _ => fail("Expected Left(ConfigurationError) for closed client")
    )
  }

  // ===========================================================================
  // Model ID routing
  // ===========================================================================

  it should "route model ID amazon.titan-text-express-v1 to the correct URL path" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions())
    mock.lastUrl.foreach { url =>
      url should include("amazon.titan-text-express-v1")
      url should include("/converse")
    }
  }

  it should "route claude model ID to the correct URL path" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(claudeConfig, mock)

    client.complete(conversation, CompletionOptions())
    mock.lastUrl.foreach { url =>
      url should include("anthropic.claude-3-5-sonnet-20241022-v2:0")
      url should include("/converse")
    }
  }

  // ===========================================================================
  // Accessor methods
  // ===========================================================================

  "BedrockClient" should "return correct context window from config" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    client.getContextWindow() shouldBe 32000
  }

  it should "return correct reserve completion from config" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = BedrockClient.forTest(testConfig, mock)
    client.getReserveCompletion() shouldBe 4096
  }

  // ===========================================================================
  // Streaming
  // ===========================================================================

  "BedrockClient.streamComplete" should "assemble streaming chunks into a completion" in {
    val streamBody =
      """{  "contentBlockDelta": { "delta": { "text": "Hello" } } }
        |{  "contentBlockDelta": { "delta": { "text": " world" } } }
        |{  "messageStop": { "stopReason": "end_turn" } }""".stripMargin

    val streamResponse = org.llm4s.http.StreamingHttpResponse(
      200,
      new java.io.ByteArrayInputStream(streamBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )

    val mockStreamClient = new MockStreamHttpClient(streamResponse)
    val client           = BedrockClient.forTest(testConfig, mockStreamClient)

    val chunks = scala.collection.mutable.ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content should include("Hello")
        completion.content should include("world")
        chunks should not be empty
      }
    )
  }

  it should "return NetworkError when stream HTTP call fails" in {
    val failing = new FailingHttpClient(new java.io.IOException("stream connection failed"))
    val client  = BedrockClient.forTest(testConfig, failing)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
    result.fold(
      err => err shouldBe a[NetworkError],
      _ => fail("Expected Left(NetworkError)")
    )
  }

  it should "return ValidationError when stream returns empty content" in {
    val emptyStreamBody = ""

    val streamResponse = org.llm4s.http.StreamingHttpResponse(
      200,
      new java.io.ByteArrayInputStream(emptyStreamBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )

    val mockStreamClient = new MockStreamHttpClient(streamResponse)
    val client           = BedrockClient.forTest(testConfig, mockStreamClient)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
    result.fold(
      err => err shouldBe a[ValidationError],
      _ => fail("Expected Left(ValidationError) for empty stream")
    )
  }

  // ===========================================================================
  // BedrockConfig companion object
  // ===========================================================================

  "BedrockConfig.fromValues" should "build config with default base URL when none provided" in {
    given org.llm4s.llmconnect.config.ContextWindowResolver =
      org.llm4s.llmconnect.config.ContextWindowResolver(ModelRegistryTestSupport.defaultService())

    val cfg = BedrockConfig.fromValues("amazon.titan-text-express-v1", "us-west-2")
    cfg.region shouldBe "us-west-2"
    cfg.baseUrl should include("us-west-2")
    cfg.baseUrl should include("bedrock-runtime")
    cfg.model shouldBe "amazon.titan-text-express-v1"
  }

  it should "use custom base URL when provided" in {
    given org.llm4s.llmconnect.config.ContextWindowResolver =
      org.llm4s.llmconnect.config.ContextWindowResolver(ModelRegistryTestSupport.defaultService())

    val customUrl = "https://my-custom-endpoint.example.com"
    val cfg       = BedrockConfig.fromValues("amazon.titan-text-express-v1", "us-east-1", Some(customUrl))
    cfg.baseUrl shouldBe customUrl
  }

  it should "derive context window from model name for Claude models" in {
    given org.llm4s.llmconnect.config.ContextWindowResolver =
      org.llm4s.llmconnect.config.ContextWindowResolver(ModelRegistryTestSupport.defaultService())

    val cfg = BedrockConfig.fromValues("anthropic.claude-3-5-sonnet-20241022-v2:0", "us-east-1")
    cfg.contextWindow should be > 0
  }

  it should "require non-empty region" in {
    given org.llm4s.llmconnect.config.ContextWindowResolver =
      org.llm4s.llmconnect.config.ContextWindowResolver(ModelRegistryTestSupport.defaultService())

    an[IllegalArgumentException] should be thrownBy {
      BedrockConfig.fromValues("amazon.titan-text-express-v1", "")
    }
  }

  "BedrockConfig.defaultBaseUrl" should "construct the correct regional endpoint" in {
    BedrockConfig.defaultBaseUrl("us-east-1") shouldBe "https://bedrock-runtime.us-east-1.amazonaws.com"
    BedrockConfig.defaultBaseUrl("eu-west-1") shouldBe "https://bedrock-runtime.eu-west-1.amazonaws.com"
  }
}

/**
 * A MockHttpClient that returns a pre-built [[org.llm4s.http.StreamingHttpResponse]] for postStream calls.
 */
private class MockStreamHttpClient(streamResponse: org.llm4s.http.StreamingHttpResponse)
    extends org.llm4s.http.MockHttpClient(HttpResponse(200, "")) {

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): org.llm4s.http.StreamingHttpResponse = streamResponse
}
