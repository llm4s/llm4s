package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

class WatsonXClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def testConfig: WatsonXConfig = WatsonXConfig(
    apiKey = "test-ibm-api-key",
    projectId = "test-project-id",
    spaceId = None,
    model = "ibm/granite-13b-instruct-v2",
    baseUrl = "https://us-south.ml.cloud.ibm.com",
    apiVersion = "2024-05-31",
    contextWindow = 8192,
    reserveCompletion = 4096
  )

  private def iamSuccessResponse: HttpResponse = HttpResponse(
    statusCode = 200,
    body = """{"access_token":"test-bearer-token","expires_in":3600,"token_type":"Bearer"}"""
  )

  private def watsonxSuccessResponse(text: String, id: String = "gen-123"): HttpResponse = HttpResponse(
    statusCode = 200,
    body =
      s"""|{
          |  "id": "$id",
          |  "model_id": "ibm/granite-13b-instruct-v2",
          |  "results": [
          |    {
          |      "generated_text": "$text",
          |      "generated_token_count": 10,
          |      "input_token_count": 5,
          |      "stop_reason": "eos_token"
          |    }
          |  ]
          |}""".stripMargin
  )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Hello WatsonX")))

  // ==========================================================================
  // IAM token exchange tests
  // ==========================================================================

  "WatsonXClient IAM token exchange" should "obtain a token and cache it" in {
    // Two responses: IAM token, then generation
    val iamResponse  = iamSuccessResponse
    val genResponse  = watsonxSuccessResponse("Hello from WatsonX!")
    val iamMock      = new MockHttpClient(Seq(iamResponse))
    val apiMock      = new MockHttpClient(Seq(genResponse))
    val client       = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello from WatsonX!"
    iamMock.postCallCount shouldBe 1
    apiMock.postCallCount shouldBe 1
  }

  it should "use cached token on subsequent calls" in {
    // 1st call: IAM + generation; 2nd call: only generation (token still valid)
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("First"), watsonxSuccessResponse("Second")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val r1 = client.complete(conversation, CompletionOptions())
    val r2 = client.complete(conversation, CompletionOptions())

    r1.isRight shouldBe true
    r2.isRight shouldBe true
    // IAM token fetched only once
    iamMock.postCallCount shouldBe 1
    apiMock.postCallCount shouldBe 2
  }

  it should "return AuthenticationError when IAM exchange returns 401" in {
    val iamMock = new MockHttpClient(HttpResponse(401, """{"message":"Invalid API key"}"""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "return AuthenticationError when IAM response is missing access_token" in {
    val iamMock = new MockHttpClient(HttpResponse(200, """{"expires_in":3600}"""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
    result.swap.toOption.get.message should include("access_token")
  }

  it should "return error when IAM HTTP call fails with network exception" in {
    val iamMock = new FailingHttpClient(new java.io.IOException("IAM unreachable"))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }

  // ==========================================================================
  // complete() — happy path
  // ==========================================================================

  "WatsonXClient.complete" should "parse a successful WatsonX generation response" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Generated response from Granite")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Generated response from Granite"
    completion.model shouldBe "ibm/granite-13b-instruct-v2"
    completion.id shouldBe "gen-123"
  }

  it should "parse token usage from the response" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Hello")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.usage shouldBe defined
    completion.usage.get.promptTokens shouldBe 5
    completion.usage.get.completionTokens shouldBe 10
    completion.usage.get.totalTokens shouldBe 15
  }

  it should "handle empty generated_text in response" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val genResponse = HttpResponse(
      statusCode = 200,
      body =
        """|{
           |  "id": "gen-empty",
           |  "results": [
           |    {
           |      "generated_text": "",
           |      "generated_token_count": 0,
           |      "input_token_count": 5
           |    }
           |  ]
           |}""".stripMargin
    )
    val apiMock = new MockHttpClient(Seq(genResponse))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe ""
  }

  it should "generate a UUID id when response has no id field" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val genResponse = HttpResponse(
      statusCode = 200,
      body =
        """|{
           |  "results": [
           |    {
           |      "generated_text": "Hello",
           |      "generated_token_count": 1,
           |      "input_token_count": 2
           |    }
           |  ]
           |}""".stripMargin
    )
    val apiMock = new MockHttpClient(Seq(genResponse))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.id should not be empty
  }

  it should "handle missing token usage gracefully (return None)" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val genResponse = HttpResponse(
      statusCode = 200,
      body =
        """|{
           |  "id": "gen-nousage",
           |  "results": [
           |    {
           |      "generated_text": "Response without usage"
           |    }
           |  ]
           |}""".stripMargin
    )
    val apiMock = new MockHttpClient(Seq(genResponse))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.usage shouldBe None
  }

  // ==========================================================================
  // complete() — error cases
  // ==========================================================================

  it should "map HTTP 401 to AuthenticationError" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(401, """{"error":"Unauthorized"}"""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 403 to AuthenticationError" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(403, """{"error":"Forbidden"}"""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(429, """{"error":"Rate limit exceeded"}"""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[RateLimitError]
  }

  it should "map HTTP 500 to ServiceError" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(500, """{"error":"Internal server error"}"""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ServiceError]
  }

  it should "return error when API HTTP call fails with network exception" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new FailingHttpClient(new java.io.IOException("Connection refused"))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }

  it should "return error when response body is malformed JSON" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(200, "not valid json {{{"))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }

  it should "return error when response missing results array" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(HttpResponse(200, """{"id":"gen-1","model_id":"ibm/granite-13b-instruct-v2"}"""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("results")
  }

  // ==========================================================================
  // complete() — request body building
  // ==========================================================================

  it should "include project_id in request body when spaceId is None" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.complete(conversation, CompletionOptions())

    val requestBody = apiMock.lastBody.value
    requestBody should include("project_id")
    requestBody should include("test-project-id")
    requestBody should not include "space_id"
  }

  it should "include space_id in request body when spaceId is set" in {
    val configWithSpace = testConfig.copy(spaceId = Some("my-space-123"))
    val iamMock         = new MockHttpClient(Seq(iamSuccessResponse))
    val genResponse     = watsonxSuccessResponse("Response with space")
    val apiMock         = new MockHttpClient(Seq(genResponse))
    val client          = WatsonXClient.forTest(configWithSpace, apiMock, iamMock)

    client.complete(conversation, CompletionOptions())

    val requestBody = apiMock.lastBody.value
    requestBody should include("space_id")
    requestBody should include("my-space-123")
    requestBody should not include "project_id"
  }

  it should "include model_id in request body" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.complete(conversation, CompletionOptions())

    val requestBody = apiMock.lastBody.value
    requestBody should include("model_id")
    requestBody should include("ibm/granite-13b-instruct-v2")
  }

  it should "include max_new_tokens when maxTokens option is set" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.complete(conversation, CompletionOptions(maxTokens = Some(512)))

    val requestBody = apiMock.lastBody.value
    requestBody should include("max_new_tokens")
    requestBody should include("512")
  }

  it should "include Bearer token in Authorization header" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.complete(conversation, CompletionOptions())

    val headers = apiMock.lastHeaders.value
    headers.get("Authorization") shouldBe Some("Bearer test-bearer-token")
  }

  it should "include api version in request URL" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.complete(conversation, CompletionOptions())

    val url = apiMock.lastUrl.value
    url should include("version=2024-05-31")
    url should include("/ml/v1/text/generation")
  }

  // ==========================================================================
  // Conversation formatting tests
  // ==========================================================================

  it should "format multi-turn conversation with system, user, and assistant messages" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new MockHttpClient(Seq(watsonxSuccessResponse("Response")))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val multiConv = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Hello"),
        AssistantMessage(Some("Hi! How can I help?"), Seq.empty),
        UserMessage("Tell me about Scala")
      )
    )

    client.complete(multiConv, CompletionOptions())

    val requestBody = apiMock.lastBody.value
    requestBody should include("[SYSTEM]: You are a helpful assistant.")
    requestBody should include("[USER]: Hello")
    requestBody should include("[ASSISTANT]: Hi! How can I help?")
    requestBody should include("[USER]: Tell me about Scala")
    requestBody should include("[ASSISTANT]: ")
  }

  // ==========================================================================
  // buildGenerationRequest tests (unit)
  // ==========================================================================

  "WatsonXClient.buildGenerationRequest" should "include input field" in {
    val iamMock = new MockHttpClient(HttpResponse(200, ""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val conv    = Conversation(Seq(UserMessage("test input")))

    val req = client.buildGenerationRequest(conv, CompletionOptions())

    req.obj.keys.toSeq should contain("input")
    req.obj.keys.toSeq should contain("model_id")
    req.obj.keys.toSeq should contain("parameters")
    req.obj("model_id").str shouldBe "ibm/granite-13b-instruct-v2"
  }

  it should "add top_p to parameters when not default (1.0)" in {
    val iamMock = new MockHttpClient(HttpResponse(200, ""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val conv    = Conversation(Seq(UserMessage("test")))

    val req = client.buildGenerationRequest(conv, CompletionOptions(topP = 0.9))

    val params = req.obj("parameters")
    params.obj.keys.toSeq should contain("top_p")
  }

  it should "not add top_p to parameters when topP is default (1.0)" in {
    val iamMock = new MockHttpClient(HttpResponse(200, ""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val conv    = Conversation(Seq(UserMessage("test")))

    val req = client.buildGenerationRequest(conv, CompletionOptions())

    val params = req.obj("parameters")
    params.obj.keys.toSeq should not contain "top_p"
  }

  // ==========================================================================
  // Accessor tests
  // ==========================================================================

  "WatsonXClient" should "return correct context window from config" in {
    val iamMock = new MockHttpClient(HttpResponse(200, ""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.getContextWindow() shouldBe 8192
  }

  it should "return correct reserve completion from config" in {
    val iamMock = new MockHttpClient(HttpResponse(200, ""))
    val apiMock = new MockHttpClient(HttpResponse(200, ""))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.getReserveCompletion() shouldBe 4096
  }

  // ==========================================================================
  // Streaming tests
  // ==========================================================================

  "WatsonXClient.streamComplete" should "parse streaming SSE events and accumulate content" in {
    val sseBody =
      "data: {\"results\":[{\"generated_text\":\"Hello\",\"generated_token_count\":1,\"input_token_count\":2}]}\n\n" +
        "data: {\"results\":[{\"generated_text\":\" world\",\"generated_token_count\":1,\"input_token_count\":2}]}\n\n" +
        "data: [DONE]\n\n"

    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val streamingResponse = StreamingHttpResponse(
      statusCode = 200,
      body = new java.io.ByteArrayInputStream(sseBody.getBytes("UTF-8"))
    )

    val apiMock = new MockHttpClient(HttpResponse(200, sseBody)) {
      override def postStream(
        url: String,
        headers: Map[String, String],
        body: String,
        timeout: Int
      ): StreamingHttpResponse = streamingResponse
    }
    val client = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val chunks = scala.collection.mutable.ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]

    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Hello world"
    chunks should have size 2
  }

  it should "handle streaming error status" in {
    val errorBody = """{"error":"Unauthorized"}"""
    val iamMock   = new MockHttpClient(Seq(iamSuccessResponse))
    val streamingResponse = StreamingHttpResponse(
      statusCode = 401,
      body = new java.io.ByteArrayInputStream(errorBody.getBytes("UTF-8"))
    )
    val apiMock = new MockHttpClient(HttpResponse(401, errorBody)) {
      override def postStream(
        url: String,
        headers: Map[String, String],
        body: String,
        timeout: Int
      ): StreamingHttpResponse = streamingResponse
    }
    val client = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  it should "accumulate multiple text chunks in streaming" in {
    val sseBody = Seq(
      "data: {\"id\":\"gen-s1\",\"results\":[{\"generated_text\":\"chunk1\"}]}\n\n",
      "data: {\"id\":\"gen-s1\",\"results\":[{\"generated_text\":\" chunk2\"}]}\n\n",
      "data: {\"id\":\"gen-s1\",\"results\":[{\"generated_text\":\" chunk3\"}]}\n\n"
    ).mkString

    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val streamingResponse = StreamingHttpResponse(
      statusCode = 200,
      body = new java.io.ByteArrayInputStream(sseBody.getBytes("UTF-8"))
    )
    val apiMock = new MockHttpClient(HttpResponse(200, sseBody)) {
      override def postStream(
        url: String,
        headers: Map[String, String],
        body: String,
        timeout: Int
      ): StreamingHttpResponse = streamingResponse
    }
    val client = WatsonXClient.forTest(testConfig, apiMock, iamMock)
    val chunks = scala.collection.mutable.ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]

    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "chunk1 chunk2 chunk3"
    chunks should have size 3
  }

  it should "fail when stream HTTP call throws network exception" in {
    val iamMock = new MockHttpClient(Seq(iamSuccessResponse))
    val apiMock = new FailingHttpClient(new java.io.IOException("Stream connection refused"))
    val client  = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())

    result.isLeft shouldBe true
  }

  it should "mark stream request body with stream=true" in {
    val sseBody = "data: {\"results\":[{\"generated_text\":\"ok\"}]}\n\n"
    val iamMock            = new MockHttpClient(Seq(iamSuccessResponse))
    var capturedStreamBody = Option.empty[String]
    val streamingResponse = StreamingHttpResponse(
      statusCode = 200,
      body = new java.io.ByteArrayInputStream(sseBody.getBytes("UTF-8"))
    )
    val apiMock = new MockHttpClient(HttpResponse(200, sseBody)) {
      override def postStream(
        url: String,
        headers: Map[String, String],
        body: String,
        timeout: Int
      ): StreamingHttpResponse = {
        capturedStreamBody = Some(body)
        streamingResponse
      }
    }
    val client = WatsonXClient.forTest(testConfig, apiMock, iamMock)

    client.streamComplete(conversation, CompletionOptions(), _ => ())

    capturedStreamBody.value should include("\"stream\":true")
  }
}
