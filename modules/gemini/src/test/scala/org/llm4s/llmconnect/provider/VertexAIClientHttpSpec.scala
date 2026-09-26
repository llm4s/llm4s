package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * HTTP-level tests for [[VertexAIClient]].
 *
 * Both `httpClient.post` (used for generateContent AND oauth2 token endpoint)
 * and `httpClient.get` (GCE metadata-server fallback) must be stubbed in every
 * test to prevent NPEs when VertexAIAuthProvider falls through all credential paths.
 */
class VertexAIClientHttpSpec extends AnyFlatSpec with Matchers with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = VertexAIConfig(
    projectId = "my-gcp-project",
    location = "us-central1",
    model = "gemini-2.0-flash",
    credentialFilePath = None,
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private val tokenBody = """{"access_token":"ya29.test-token","expires_in":3600}"""
  private val successBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""".stripMargin

  /**
   * Creates a VertexAIClient backed by a mock HTTP client that:
   *  - routes POST to the oauth2 token endpoint → returns a valid token
   *  - routes all other POST calls (generateContent) → returns `completionResponse`
   *  - stubs GET (metadata server fallback) → returns a valid token
   *
   * This covers all three auth-provider code paths, making tests environment-agnostic.
   */
  private def stubHttpWithAuth(mockHttp: Llm4sHttpClient, completionResponse: HttpResponse): VertexAIClient = {
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], _: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else completionResponse
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    new VertexAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
  }

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int): HttpResponse = HttpResponse(status, s"Error $status", Map.empty)
  private def conversation(text: String): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  // ============================================================
  // complete() tests
  // ============================================================

  "VertexAIClient.complete()" should "parse text content from a 200 response" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpOk(successBody))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello!"
  }

  it should "parse token usage from the response" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpOk(successBody))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 10
    usage.completionTokens shouldBe 5
    usage.totalTokens shouldBe 15
  }

  it should "parse a tool call response" in {
    val toolCallBody =
      """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"London"}}}],"role":"model"}}]}"""
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpOk(toolCallBody))

    val result = client.complete(conversation("What's the weather?"), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "return AuthenticationError on HTTP 401" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpErr(401))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpErr(403))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpErr(429))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpErr(400))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = stubHttpWithAuth(mockHttp, httpErr(500))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  it should "send Authorization Bearer header to the Vertex AI endpoint" in {
    var capturedHeaders: Map[String, String] = Map.empty
    val mockHttp                             = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, headers: Map[String, String], _: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else {
        capturedHeaders = headers
        httpOk(successBody)
      }
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    client.complete(conversation("Hi"), CompletionOptions())

    capturedHeaders.get("Authorization").value should startWith("Bearer ")
  }

  it should "build the correct Vertex AI generateContent URL" in {
    var capturedUrl = ""
    val mockHttp    = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], _: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else {
        capturedUrl = url
        httpOk(successBody)
      }
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    client.complete(conversation("Hi"), CompletionOptions())

    capturedUrl should include("us-central1-aiplatform.googleapis.com")
    capturedUrl should include("my-gcp-project")
    capturedUrl should include("gemini-2.0-flash")
    capturedUrl should include("generateContent")
  }

  it should "record provider exchanges when logging is enabled" in {
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    }
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], _: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else httpOk(successBody)
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink),
      mockHttp
    )
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "vertexai"
    exchanges.head.model shouldBe Some("gemini-2.0-flash")
    exchanges.head.requestBody should include("Hi")
    exchanges.head.responseBody.value should include("Hello!")
  }

  // ============================================================
  // streamComplete() tests
  // ============================================================

  "VertexAIClient.streamComplete()" should "parse SSE lines and accumulate into a Completion" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val chunks = scala.collection.mutable.Buffer[StreamedChunk]()
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), chunk => chunks += chunk)

    result.isRight shouldBe true
    result.toOption.get.content should include("Hello")
    result.toOption.get.content should include("world")
    chunks should have size 2
  }

  it should "parse token usage from the final SSE chunk" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 5
    usage.completionTokens shouldBe 2
  }

  it should "return an error for non-200 stream status" in {
    val errorBody   = """{"error":{"message":"UNAUTHENTICATED","status":"UNAUTHENTICATED"}}"""
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(401, inputStream))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  // ============================================================
  // Schema conversion tests (convertToolToVertexFormat)
  // ============================================================

  "VertexAIClient.convertToolToVertexFormat()" should "strip 'strict' and 'additionalProperties' from schema" in {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }

    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("q", Schema.string("query")))

    val toolResult = ToolBuilder[Map[String, Any], String]("search", "Search tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    toolResult match {
      case Right(tool) =>
        val vertexTool = client.convertToolToVertexFormat(tool)
        val rendered   = vertexTool.render()
        (rendered should not).include("\"strict\"")
        (rendered should not).include("\"additionalProperties\"")
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }

  it should "include name, description and parameters in the result" in {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }

    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("x", Schema.integer("x")))

    val toolResult = ToolBuilder[Map[String, Any], String]("my_tool", "My tool description", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    toolResult match {
      case Right(tool) =>
        val vertexTool = client.convertToolToVertexFormat(tool)
        vertexTool("name").str shouldBe "my_tool"
        vertexTool("description").str shouldBe "My tool description"
        vertexTool.obj.contains("parameters") shouldBe true
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }
}
