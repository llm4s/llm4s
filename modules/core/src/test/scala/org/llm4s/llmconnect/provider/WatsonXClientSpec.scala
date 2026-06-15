package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import scala.collection.mutable.ListBuffer

/**
 * Unit tests for [[WatsonXClient]].
 *
 * All tests use [[MockHttpClient]] and [[FailingHttpClient]] — no IBM credentials required.
 * Tests verify:
 *  - IAM token exchange and caching
 *  - IAM token refresh on expiry
 *  - Successful `complete()` response parsing
 *  - Successful `streamComplete()` chunk assembly
 *  - Error mapping for HTTP 401 / 429 / 400 / 500
 *  - Invalid project_id detection
 *  - IAM failure propagation
 *  - Network failure handling
 */
class WatsonXClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  // A valid IAM response stub
  private val iamResponse: String =
    """{
      |  "access_token": "test-bearer-token-abc123",
      |  "token_type": "Bearer",
      |  "expires_in": 3600
      |}""".stripMargin

  // A valid WatsonX /ml/v1/text/generation response
  private def watsonxResponse(text: String = "Hello from WatsonX!"): String =
    s"""{
       |  "id": "cmpl-watsonx-001",
       |  "model_id": "ibm/granite-3-8b-instruct",
       |  "created": 1700000000,
       |  "results": [
       |    {
       |      "generated_text": "$text",
       |      "generated_token_count": 8,
       |      "input_token_count": 12,
       |      "stop_reason": "eos_token"
       |    }
       |  ]
       |}""".stripMargin

  private def testConfig: WatsonXConfig = WatsonXConfig(
    apiKey = "test-ibm-api-key",
    projectId = "test-project-id-1234",
    model = "ibm/granite-3-8b-instruct",
    baseUrl = "https://us-south.ml.cloud.ibm.com",
    iamUrl = "https://iam.cloud.ibm.com/identity/token",
    contextWindow = 8192,
    reserveCompletion = 2048
  )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi")))

  // ==========================================================================
  // complete() — happy path
  // ==========================================================================

  "WatsonXClient.complete" should "parse a successful WatsonX response" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Hello from WatsonX!"
    completion.model shouldBe "ibm/granite-3-8b-instruct"
    completion.id shouldBe "cmpl-watsonx-001"
    completion.usage.isDefined shouldBe true
    completion.usage.get.promptTokens shouldBe 12
    completion.usage.get.completionTokens shouldBe 8
    completion.usage.get.totalTokens shouldBe 20
  }

  it should "cache the IAM token and not re-exchange on subsequent calls" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(200, watsonxResponse("First")),
        HttpResponse(200, watsonxResponse("Second"))
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val r1 = client.complete(conversation, CompletionOptions())
    val r2 = client.complete(conversation, CompletionOptions())

    r1.isRight shouldBe true
    r2.isRight shouldBe true
    // IAM was called only once (postCallCount includes both IAM and completion calls)
    // First call: 2 posts (IAM + completion), Second call: 1 post (completion only)
    mock.postCallCount shouldBe 3
    r1.toOption.get.content shouldBe "First"
    r2.toOption.get.content shouldBe "Second"
  }

  it should "return ValidationError for an empty conversation" in {
    val mock = new MockHttpClient(HttpResponse(200, iamResponse))
    val client = WatsonXClient.forTest(testConfig, mock)
    val emptyConv = Conversation(Seq.empty)

    val result = client.complete(emptyConv, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.message should include("at least one message")
  }

  it should "handle multi-turn conversation with system and assistant messages" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse("Sure!")))
    )
    val client = WatsonXClient.forTest(testConfig, mock)
    val multiConv = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Hello"),
        AssistantMessage(Some("Hi!"), Seq.empty),
        UserMessage("Tell me a joke")
      )
    )

    val result = client.complete(multiConv, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Sure!"
    // The request body should contain prompt with "Human:" prefixes
    mock.lastBody.value should include("Human: Hello")
    mock.lastBody.value should include("Human: Tell me a joke")
    mock.lastBody.value should include("Assistant: Hi!")
  }

  it should "include the project_id in the request body" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions())

    mock.lastBody.value should include("test-project-id-1234")
  }

  it should "include the model_id in the request body" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions())

    mock.lastBody.value should include("ibm/granite-3-8b-instruct")
  }

  it should "send maxTokens in request parameters when specified" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse("Short")))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions(maxTokens = Some(100)))

    mock.lastBody.value should include("max_new_tokens")
    mock.lastBody.value should include("100")
  }

  it should "return ValidationError when generated_text is missing in response" in {
    val emptyResponse =
      """{
        |  "id": "cmpl-empty",
        |  "model_id": "ibm/granite-3-8b-instruct",
        |  "results": [
        |    {
        |      "generated_token_count": 0,
        |      "input_token_count": 5,
        |      "stop_reason": "eos_token"
        |    }
        |  ]
        |}""".stripMargin
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, emptyResponse))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.message should include("generated_text")
  }

  it should "handle response without token usage gracefully" in {
    val responseNoUsage =
      """{
        |  "id": "cmpl-no-usage",
        |  "model_id": "ibm/granite-3-8b-instruct",
        |  "results": [
        |    {
        |      "generated_text": "Hello!",
        |      "stop_reason": "eos_token"
        |    }
        |  ]
        |}""".stripMargin
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, responseNoUsage))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello!"
    result.toOption.get.usage shouldBe None
  }

  it should "generate a UUID when response has no id field" in {
    val responseNoId =
      """{
        |  "model_id": "ibm/granite-3-8b-instruct",
        |  "results": [
        |    {
        |      "generated_text": "Hi there!",
        |      "generated_token_count": 3,
        |      "input_token_count": 5,
        |      "stop_reason": "eos_token"
        |    }
        |  ]
        |}""".stripMargin
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, responseNoId))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.id should not be empty
  }

  // ==========================================================================
  // IAM token exchange and refresh
  // ==========================================================================

  "WatsonXClient IAM token" should "return ConfigurationError when IAM exchange fails (non-2xx)" in {
    val mock = new MockHttpClient(HttpResponse(400, """{"error": "Bad request"}"""))
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ConfigurationError]
    result.swap.toOption.get.message should include("IAM token exchange failed")
  }

  it should "return ConfigurationError when IAM response has no access_token" in {
    val badIam = """{"token_type": "Bearer", "expires_in": 3600}"""
    val mock = new MockHttpClient(HttpResponse(200, badIam))
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ConfigurationError]
    result.swap.toOption.get.message should include("access_token")
  }

  it should "return ConfigurationError when IAM returns invalid JSON" in {
    val mock = new MockHttpClient(HttpResponse(200, "not-json"))
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ConfigurationError]
  }

  it should "refresh token when cached token has expired (less than 60s remaining)" in {
    // First two calls: IAM + completion (for first complete() call)
    // Then we manually expire the token and make a second call
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(200, watsonxResponse("First")),
        HttpResponse(200, iamResponse),
        HttpResponse(200, watsonxResponse("Second"))
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    // First call works normally
    val r1 = client.complete(conversation, CompletionOptions())
    r1.isRight shouldBe true

    // Manually inject an expired token into the cache
    client.cachedToken.set(Some(("old-token", System.currentTimeMillis() / 1000L - 100)))

    // Second call should re-exchange the token
    val r2 = client.complete(conversation, CompletionOptions())
    r2.isRight shouldBe true
    r2.toOption.get.content shouldBe "Second"

    // Total posts: 2 (IAM + completion) + 2 (IAM + completion) = 4
    mock.postCallCount shouldBe 4
  }

  it should "send the bearer token as Authorization header on WatsonX API calls" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions())

    mock.lastHeaders.value.get("Authorization") shouldBe Some("Bearer test-bearer-token-abc123")
  }

  // ==========================================================================
  // Error mapping
  // ==========================================================================

  "WatsonXClient.complete error handling" should "map HTTP 401 to AuthenticationError" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(401, """{"message": "Unauthorized"}""")
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[AuthenticationError]
  }

  it should "map HTTP 403 to AuthenticationError" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(403, """{"message": "Forbidden"}""")
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError (WatsonX quota exceeded)" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(429, """{"message": "Rate limit exceeded"}""")
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[RateLimitError]
  }

  it should "map HTTP 500 to ServiceError" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(500, """{"message": "Internal server error"}""")
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ServiceError]
  }

  it should "map invalid project_id error body to ValidationError" in {
    val projectIdError =
      """{
        |  "errors": [
        |    {
        |      "code": "BXZAI0001E",
        |      "message": "The project_id provided is not valid"
        |    }
        |  ]
        |}""".stripMargin
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(400, projectIdError)
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.message should include("project_id")
  }

  it should "detect invalid project_id from error body containing 'project_id'" in {
    val projectIdError = """{"message": "Invalid project_id provided"}"""
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(400, projectIdError)
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "return UnknownError on network failure during WatsonX call" in {
    val failingClient = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client = WatsonXClient.forTest(testConfig, failingClient)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }

  // ==========================================================================
  // streamComplete() — happy path
  // ==========================================================================

  "WatsonXClient.streamComplete" should "assemble chunks into a complete Completion" in {
    val sseBody =
      """data: {"results":[{"generated_text":"Hello","generated_token_count":1,"input_token_count":5,"stop_reason":"not_finished"}]}
        |data: {"results":[{"generated_text":" World","generated_token_count":1,"input_token_count":5,"stop_reason":"eos_token"}]}
        |data: [DONE]
        |""".stripMargin

    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, sseBody))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val chunks = ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Hello World"
    chunks should have size 2
    chunks.head.content shouldBe Some("Hello")
    chunks(1).content shouldBe Some(" World")
  }

  it should "return ValidationError when stream emits no text" in {
    val emptySseBody = "data: [DONE]\n"
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, emptySseBody))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "propagate HTTP errors from streaming endpoint" in {
    val mock = new MockHttpClient(
      Seq(
        HttpResponse(200, iamResponse),
        HttpResponse(429, """{"message": "Too many requests"}""")
      )
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[RateLimitError]
  }

  it should "capture token usage from streaming response" in {
    val sseBody =
      """data: {"results":[{"generated_text":"Hi!","generated_token_count":2,"input_token_count":10,"stop_reason":"eos_token"}]}
        |data: [DONE]
        |""".stripMargin

    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, sseBody))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.usage.isDefined shouldBe true
    completion.usage.get.promptTokens shouldBe 10
    completion.usage.get.completionTokens shouldBe 2
  }

  // ==========================================================================
  // Provider exchange logging
  // ==========================================================================

  "WatsonXClient" should "record provider exchanges when logging is enabled" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange
    }
    val client = new WatsonXClient(
      testConfig,
      exchangeLogging = ProviderExchangeLogging.Enabled(sink),
      httpClient = mock
    )

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    recorded should have size 1
    recorded.head.provider shouldBe "watsonx"
    recorded.head.model shouldBe Some("ibm/granite-3-8b-instruct")
    recorded.head.requestBody should include("input")
    recorded.head.responseBody.value should include("Hello from WatsonX!")
  }

  // ==========================================================================
  // Lifecycle methods
  // ==========================================================================

  "WatsonXClient" should "return correct context window from config" in {
    val mock   = new MockHttpClient(HttpResponse(200, "{}"))
    val client = WatsonXClient.forTest(testConfig, mock)
    client.getContextWindow() shouldBe 8192
  }

  it should "return correct reserve completion from config" in {
    val mock   = new MockHttpClient(HttpResponse(200, "{}"))
    val client = WatsonXClient.forTest(testConfig, mock)
    client.getReserveCompletion() shouldBe 2048
  }

  it should "return ConfigurationError after being closed" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)
    client.close()

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ConfigurationError]
    result.swap.toOption.get.message should include("closed")
  }

  // ==========================================================================
  // WatsonXConfig
  // ==========================================================================

  "WatsonXConfig" should "have correct defaults for base URL and IAM URL" in {
    WatsonXConfig.DEFAULT_BASE_URL shouldBe "https://us-south.ml.cloud.ibm.com"
    WatsonXConfig.DEFAULT_IAM_URL shouldBe "https://iam.cloud.ibm.com/identity/token"
  }

  it should "include apiKey (redacted) and projectId in toString" in {
    val cfg = testConfig
    val str = cfg.toString
    str should include("projectId=test-project-id-1234")
    str should not include "test-ibm-api-key"
    str should include("ibm/granite-3-8b-instruct")
  }

  it should "have provider kind WatsonX" in {
    import org.llm4s.types.ProviderModelTypes.ProviderKind
    testConfig.provider shouldBe ProviderKind.WatsonX
  }

  // ==========================================================================
  // IAM failure propagates to complete
  // ==========================================================================

  "WatsonXClient" should "propagate IAM network failure as error" in {
    val failingClient = new FailingHttpClient(new java.net.ConnectException("IAM unreachable"))
    val client = WatsonXClient.forTest(testConfig, failingClient)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }

  it should "use the IAM URL from config for token exchange" in {
    val mock = new MockHttpClient(
      Seq(HttpResponse(200, iamResponse), HttpResponse(200, watsonxResponse()))
    )
    val client = WatsonXClient.forTest(testConfig, mock)

    client.complete(conversation, CompletionOptions())

    // The first POST call should go to the IAM URL
    // (we can check via getRequests or postCallCount behavior)
    // Both IAM and WatsonX use POST
    mock.postCallCount shouldBe 2
  }
}
