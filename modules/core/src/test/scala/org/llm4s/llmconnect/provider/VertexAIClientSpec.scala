// scalafix:off DisableSyntax.NoKeywordTry
package org.llm4s.llmconnect.provider

import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.model.ModelRegistryService

import scala.collection.mutable.ListBuffer

class VertexAIClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val geminiConfig = VertexAIConfig(
    projectId = "my-project",
    location = "us-central1",
    model = "gemini-2.0-flash",
    accessToken = "ya29.test-token",
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private val claudeConfig = VertexAIConfig(
    projectId = "my-project",
    location = "us-central1",
    model = "claude-3-5-sonnet@20241022",
    accessToken = "ya29.test-token",
    contextWindow = 200000,
    reserveCompletion = 8192
  )

  private def mkClient(config: VertexAIConfig, mock: MockHttpClient): VertexAIClient =
    new VertexAIClient(
      config,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mock
    )

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int): HttpResponse =
    HttpResponse(status, s"""{"error":{"message":"err $status"}}""", Map.empty)
  private def conversation(text: String): Conversation = Conversation(messages = Seq(UserMessage(text)))

  private val successBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello from Vertex!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":6,"totalTokenCount":18}}""".stripMargin

  // ===========================================================================
  // Config and URL routing
  // ===========================================================================

  "VertexAIClient" should "return correct context window for Gemini model" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.getContextWindow() shouldBe 1048576
  }

  it should "return correct reserve completion" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.getReserveCompletion() shouldBe 8192
  }

  it should "identify Gemini model as non-Claude" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.isClaudeModel shouldBe false
  }

  it should "identify Claude model correctly" in {
    val client = VertexAIClient.forTest(claudeConfig, new MockHttpClient(httpOk(successBody)))
    client.isClaudeModel shouldBe true
  }

  it should "route Gemini model to generateContent endpoint" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.completionUrl should include("publishers/google/models/gemini-2.0-flash:generateContent")
  }

  it should "route Claude model to rawPredict endpoint" in {
    val client = VertexAIClient.forTest(claudeConfig, new MockHttpClient(httpOk(successBody)))
    client.completionUrl should include("publishers/anthropic/models/claude-3-5-sonnet@20241022:rawPredict")
  }

  it should "route Gemini streaming to streamGenerateContent" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.streamingUrl should include("streamGenerateContent")
    client.streamingUrl should include("alt=sse")
  }

  it should "route Claude streaming to streamRawPredict" in {
    val client = VertexAIClient.forTest(claudeConfig, new MockHttpClient(httpOk(successBody)))
    client.streamingUrl should include("streamRawPredict")
  }

  it should "include project and location in base URL" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    client.vertexBaseUrl should include("my-project")
    client.vertexBaseUrl should include("us-central1")
  }

  // ===========================================================================
  // complete() — happy path
  // ===========================================================================

  "VertexAIClient.complete()" should "parse text content from a 200 response" in {
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello from Vertex!"
  }

  it should "parse token usage from the response" in {
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 12
    usage.completionTokens shouldBe 6
    usage.totalTokens shouldBe 18
  }

  it should "parse a tool call response" in {
    val toolCallBody =
      """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"London"}}}],"role":"model"}}]}"""
    val mock   = new MockHttpClient(httpOk(toolCallBody))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Weather?"), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "send Authorization header with Bearer token" in {
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    client.complete(conversation("Hi"), CompletionOptions())

    val headers = mock.lastHeaders.getOrElse(Map.empty)
    headers.get("Authorization") shouldBe Some(s"Bearer ${geminiConfig.accessToken}")
  }

  it should "send Content-Type application/json header" in {
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    client.complete(conversation("Hi"), CompletionOptions())

    val headers = mock.lastHeaders.getOrElse(Map.empty)
    headers.get("Content-Type") shouldBe Some("application/json")
  }

  it should "include system instruction in request body when SystemMessage is present" in {
    val conv   = Conversation(messages = Seq(SystemMessage("You are helpful."), UserMessage("Hi")))
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    client.complete(conv, CompletionOptions())

    val body = ujson.read(mock.lastBody.getOrElse("{}"))
    body.obj.contains("systemInstruction") shouldBe true
    body("systemInstruction")("parts")(0)("text").str shouldBe "You are helpful."
  }

  it should "include responseMimeType when Json response format is set" in {
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    val opts   = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    client.complete(conversation("Hi"), opts)

    val body = ujson.read(mock.lastBody.getOrElse("{}"))
    body("generationConfig")("responseMimeType").str shouldBe "application/json"
  }

  it should "include responseSchema when JsonSchema response format is set" in {
    val schema = ujson.Obj("type" -> "object")
    val mock   = new MockHttpClient(httpOk(successBody))
    val client = mkClient(geminiConfig, mock)
    val opts   = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    client.complete(conversation("Hi"), opts)

    val body = ujson.read(mock.lastBody.getOrElse("{}"))
    body("generationConfig")("responseMimeType").str shouldBe "application/json"
    body("generationConfig")("responseSchema") shouldBe schema
  }

  // ===========================================================================
  // complete() — error mapping
  // ===========================================================================

  it should "return AuthenticationError on HTTP 401" in {
    val mock   = new MockHttpClient(httpErr(401))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mock   = new MockHttpClient(httpErr(403))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429" in {
    val mock   = new MockHttpClient(httpErr(429))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mock   = new MockHttpClient(httpErr(400))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mock   = new MockHttpClient(httpErr(500))
    val client = mkClient(geminiConfig, mock)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  it should "return an error on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client = new VertexAIClient(
      geminiConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      failing
    )
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
  }

  // ===========================================================================
  // complete() — exchange logging
  // ===========================================================================

  it should "record provider exchanges when logging is enabled" in {
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    }
    val mock = new MockHttpClient(httpOk(successBody))
    val client = new VertexAIClient(
      geminiConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink),
      mock
    )
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "vertex"
    exchanges.head.model shouldBe Some("gemini-2.0-flash")
    exchanges.head.requestBody should include("Hi")
    exchanges.head.responseBody.get should include("Hello from Vertex!")
  }

  // ===========================================================================
  // streamComplete() — MockHttpClient already handles postStream via body bytes
  // ===========================================================================

  "VertexAIClient.streamComplete()" should "parse SSE lines and accumulate into a Completion" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" Vertex\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"

    // MockHttpClient.postStream returns ByteArrayInputStream wrapping response.body bytes
    val mock   = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val client = mkClient(geminiConfig, mock)
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), chunk => chunks += chunk)

    result.isRight shouldBe true
    result.toOption.get.content should include("Hello")
    result.toOption.get.content should include("Vertex")
    chunks should have size 2
  }

  it should "parse token usage from the final SSE chunk" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3,\"totalTokenCount\":8}}\n"

    val mock   = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val client = mkClient(geminiConfig, mock)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 5
    usage.completionTokens shouldBe 3
  }

  it should "return AuthenticationError for 401 on streaming response" in {
    val errBody = """{"error":{"message":"unauthorized"}}"""
    val mock    = new MockHttpClient(HttpResponse(401, errBody, Map.empty))
    val client  = mkClient(geminiConfig, mock)
    val result  = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "skip [DONE] SSE lines without error" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n" +
        "data: [DONE]\n"

    val mock   = new MockHttpClient(HttpResponse(200, sseData, Map.empty))
    val client = mkClient(geminiConfig, mock)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hi"
  }

  it should "return an error on network failure during streaming" in {
    val failing = new FailingHttpClient(new java.io.IOException("stream failure"))
    val client = new VertexAIClient(
      geminiConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      failing
    )
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
  }

  // ===========================================================================
  // buildRequestBody
  // ===========================================================================

  "VertexAIClient.buildRequestBody()" should "produce valid Gemini JSON for a user message" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    val conv   = conversation("Hello")
    val body   = client.buildRequestBody(conv, CompletionOptions())

    val contents = body("contents").arr
    contents should have size 1
    contents.head("role").str shouldBe "user"
    contents.head("parts")(0)("text").str shouldBe "Hello"
  }

  it should "map AssistantMessage to model role" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    val conv = Conversation(messages =
      Seq(
        UserMessage("Hi"),
        AssistantMessage(contentOpt = Some("Hey"), toolCalls = Seq.empty)
      )
    )
    val body     = client.buildRequestBody(conv, CompletionOptions())
    val contents = body("contents").arr

    contents should have size 2
    contents(1)("role").str shouldBe "model"
    contents(1)("parts")(0)("text").str shouldBe "Hey"
  }

  it should "produce functionCall parts for tool-calling AssistantMessages" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    val tc     = ToolCall(id = "id-1", name = "search", arguments = ujson.Obj("q" -> "Scala"))
    val conv = Conversation(messages =
      Seq(
        UserMessage("Search"),
        AssistantMessage(contentOpt = None, toolCalls = Seq(tc))
      )
    )
    val body     = client.buildRequestBody(conv, CompletionOptions())
    val contents = body("contents").arr

    contents(1)("parts")(0)("functionCall")("name").str shouldBe "search"
  }

  it should "produce functionResponse parts for ToolMessages" in {
    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    val tc     = ToolCall(id = "id-1", name = "search", arguments = ujson.Obj("q" -> "Scala"))
    val conv = Conversation(messages =
      Seq(
        UserMessage("Search"),
        AssistantMessage(contentOpt = None, toolCalls = Seq(tc)),
        ToolMessage(content = "result data", toolCallId = "id-1")
      )
    )
    val body     = client.buildRequestBody(conv, CompletionOptions())
    val contents = body("contents").arr

    val toolResponsePart = contents(2)("parts")(0)
    toolResponsePart("functionResponse")("name").str shouldBe "search"
    toolResponsePart("functionResponse")("response")("result").str shouldBe "result data"
  }

  it should "set maxOutputTokens in generationConfig when maxTokens is provided" in {
    val client  = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    val options = CompletionOptions().copy(maxTokens = Some(512))
    val body    = client.buildRequestBody(conversation("Hi"), options)

    body("generationConfig")("maxOutputTokens").num.toInt shouldBe 512
  }

  // ===========================================================================
  // convertToolToVertexFormat / stripAdditionalProperties
  // ===========================================================================

  "VertexAIClient.convertToolToVertexFormat()" should "strip strict and additionalProperties from schema" in {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }

    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("q", Schema.string("query")))

    val toolResult = ToolBuilder[Map[String, Any], String]("search", "Search tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    toolResult match {
      case Right(tool) =>
        val rendered = client.convertToolToVertexFormat(tool).render()
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

    val toolResult = ToolBuilder[Map[String, Any], String]("vertex_tool", "My tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val client = VertexAIClient.forTest(geminiConfig, new MockHttpClient(httpOk(successBody)))
    toolResult match {
      case Right(tool) =>
        val result = client.convertToolToVertexFormat(tool)
        result("name").str shouldBe "vertex_tool"
        result("description").str shouldBe "My tool"
        result.obj.contains("parameters") shouldBe true
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }

  // ===========================================================================
  // VertexAIConfig
  // ===========================================================================

  "VertexAIConfig" should "redact access token in toString" in {
    val cfg = geminiConfig
    (cfg.toString should not).include("ya29.test-token")
  }

  it should "have vertex as provider kind" in {
    geminiConfig.provider.name shouldBe "vertex"
  }

  // ===========================================================================
  // VertexAIClient.apply factory
  // ===========================================================================

  "VertexAIClient.apply" should "succeed and return a Right for a valid config" in {
    val result = VertexAIClient(geminiConfig)
    result.isRight shouldBe true
  }

  it should "accept a custom MetricsCollector" in {
    val result = VertexAIClient(geminiConfig, org.llm4s.metrics.MetricsCollector.noop)
    result.isRight shouldBe true
  }

  it should "accept exchange logging" in {
    val result = VertexAIClient(geminiConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled)
    result.isRight shouldBe true
  }
}
