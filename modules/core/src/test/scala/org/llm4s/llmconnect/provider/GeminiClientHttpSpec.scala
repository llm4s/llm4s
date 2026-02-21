package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class GeminiClientHttpSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val testConfig = GeminiConfig(
    apiKey = "test-key",
    model = "gemini-2.0-flash",
    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private def mkClient(mockHttp: Llm4sHttpClient): GeminiClient =
    new GeminiClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, mockHttp)

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int): HttpResponse = HttpResponse(status, s"Error $status", Map.empty)
  private def conversation(text: String): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  private val successBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""".stripMargin

  // ============================================================
  // complete() tests
  // ============================================================

  "GeminiClient.complete()" should "parse text content from a 200 response" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(successBody))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello!"
  }

  it should "parse token usage from the response" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(successBody))

    val client = mkClient(mockHttp)
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
    (mockHttp.post _).when(*, *, *, *).returns(httpOk(toolCallBody))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("What's the weather?"), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "return AuthenticationError on HTTP 401" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(401))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(403))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(429))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(400))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(httpErr(500))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  // ============================================================
  // streamComplete() tests
  // ============================================================

  "GeminiClient.streamComplete()" should "parse SSE lines and accumulate into a Completion" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val chunks = scala.collection.mutable.Buffer[StreamedChunk]()
    val client = mkClient(mockHttp)
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
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(200, inputStream))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 5
    usage.completionTokens shouldBe 2
  }

  it should "return an error for non-200 status before reading the body" in {
    val errorBody   = """{"error":{"message":"API key missing","status":"UNAUTHENTICATED"}}"""
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(StreamingHttpResponse(401, inputStream))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  // ============================================================
  // Schema conversion tests (convertToolToGeminiFormat)
  // ============================================================

  "GeminiClient.convertToolToGeminiFormat()" should "strip 'strict' and 'additionalProperties' from schema" in {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }

    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("q", Schema.string("query")))

    val toolResult = ToolBuilder[Map[String, Any], String]("search", "Search tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val client = mkClient(stub[Llm4sHttpClient])
    toolResult match {
      case Right(tool) =>
        val geminiTool = client.convertToolToGeminiFormat(tool)
        val rendered   = geminiTool.render()
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

    val client = mkClient(stub[Llm4sHttpClient])
    toolResult match {
      case Right(tool) =>
        val geminiTool = client.convertToolToGeminiFormat(tool)
        geminiTool("name").str shouldBe "my_tool"
        geminiTool("description").str shouldBe "My tool description"
        geminiTool.obj.contains("parameters") shouldBe true
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }
}
