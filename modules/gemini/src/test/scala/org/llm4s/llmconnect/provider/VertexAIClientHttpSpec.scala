package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.http.{ HttpResponse, StreamingHttpResponse, StubbedHttpCalls }
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
    model = ModelName("gemini-2.0-flash"),
    credentialFilePath = None,
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private val tokenBody = """{"access_token":"ya29.test-token","expires_in":3600}"""
  private val successBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""".stripMargin

  // the shape Gemini returns when thinking spent the whole maxOutputTokens: content with no `parts` (seen from
  // gemini-3.5-flash on 2026-11-05; Vertex AI serves the same response format)
  private val noPartsMaxTokensBody =
    """|{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS","index":0}],
       | "usageMetadata":{"promptTokenCount":8,"totalTokenCount":539,"thoughtsTokenCount":531}}""".stripMargin

  private val noPartsUsage =
    TokenUsage(promptTokens = 8, completionTokens = 0, totalTokens = 539, thinkingTokens = Some(531))

  // the shapes of a candidate that holds no parts and did not finish normally, and the error each must be
  private val noContentShapes: Seq[(String, String, Boolean)] = Seq(
    // no `content` at all, as Gemini sends for a safety block
    (
      """{"candidates":[{"finishReason":"SAFETY","index":0,"safetyRatings":[{"category":"HARM_CATEGORY_HARASSMENT","probability":"HIGH"}]}],"usageMetadata":{"promptTokenCount":8,"totalTokenCount":8}}""",
      "returned no content: finishReason SAFETY",
      true
    ),
    // `content` with a role and no `parts`
    ("""{"candidates":[{"content":{"role":"model"},"finishReason":"SAFETY"}]}""", "finishReason SAFETY", true),
    // `content` with an empty `parts`
    (
      """{"candidates":[{"content":{"parts":[]},"finishReason":"PROHIBITED_CONTENT"}]}""",
      "finishReason PROHIBITED_CONTENT",
      true
    ),
    // not a filter: still no answer, named after its reason, but a ValidationError
    (
      """{"candidates":[{"content":{"role":"model"},"finishReason":"OTHER"}]}""",
      "returned no content: finishReason OTHER",
      false
    )
  )

  // a blocked prompt has no candidate, only promptFeedback
  private val promptBlockedBody =
    """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":8,"totalTokenCount":8}}"""

  /** The message of a ContentFilteredError (`filtered`) or of a ValidationError. */
  private def noContentMessage(result: org.llm4s.types.Result[Completion], filtered: Boolean = true): String =
    result match {
      case Left(e: org.llm4s.error.ContentFilteredError) if filtered => e.message
      case Left(e: org.llm4s.error.ValidationError) if !filtered     => e.message
      case other                                                     => fail(s"unexpected $other")
    }

  private def streamingClient(sseData: String): VertexAIClient = {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(200, new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8)))))
    new VertexAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
  }

  /**
   * Creates a VertexAIClient backed by a mock HTTP client that:
   *  - routes POST to the oauth2 token endpoint → returns a valid token
   *  - routes all other POST calls (generateContent) → returns `completionResponse`
   *  - stubs GET (metadata server fallback) → returns a valid token
   *
   * This covers all three auth-provider code paths, making tests environment-agnostic.
   */
  private def stubHttpWithAuth(mockHttp: StubbedHttpCalls, completionResponse: HttpResponse): VertexAIClient = {
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, _: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else completionResponse
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
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
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpOk(successBody))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello!"
    // RFC 0009: STOP with no function call
    result.toOption.get.finishReason shouldBe FinishReason.Stop
  }

  it should "report the sources of a grounded answer as citations (#1216)" in {
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://src/a","title":"A"}},{"web":{"uri":"https://src/b","title":"B"}}],"groundingSupports":[{"segment":{"startIndex":0,"endIndex":5},"groundingChunkIndices":[0]}]}}]}"""
    val client = stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(body))

    client.complete(conversation("Hi"), CompletionOptions()).toOption.get.citations shouldBe List(
      Citation(url = "https://src/a", title = Some("A")),
      Citation(url = "https://src/b", title = Some("B"))
    )
  }

  it should "parse token usage from the response" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpOk(successBody))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 10
    usage.completionTokens shouldBe 5
    usage.totalTokens shouldBe 15
  }

  it should "report thinking and cached tokens from usageMetadata (#2348)" in {
    val body =
      """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
         | "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":40,
         | "cachedContentTokenCount":64,"totalTokenCount":145}}""".stripMargin
    val client = stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(body))

    client.complete(conversation("Hi"), CompletionOptions()).toOption.get.usage shouldBe Some(
      TokenUsage(
        promptTokens = 100,
        completionTokens = 5,
        totalTokens = 145,
        thinkingTokens = Some(40),
        cachedTokens = Some(64)
      )
    )
  }

  it should "return an empty answer with its usage when thinking spent the whole token budget (no parts)" in {
    val client = stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(noPartsMaxTokensBody))

    val completion = client.complete(conversation("Hi"), CompletionOptions()) match {
      case Right(c) => c
      case Left(e)  => fail(s"expected an empty answer, got $e")
    }
    completion.content shouldBe ""
    completion.message.contentOpt shouldBe None
    completion.toolCalls shouldBe empty
    completion.usage shouldBe Some(noPartsUsage)
  }

  noContentShapes.foreach { case (body, expected, filtered) =>
    it should s"fail naming the reason for a candidate with no parts: $expected (${body.take(40)})" in {
      noContentMessage(
        stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(body)).complete(conversation("Hi"), CompletionOptions()),
        filtered
      ) should include(expected)
    }
  }

  it should "fail with a ContentFilteredError naming the block reason when Gemini blocked the prompt" in {
    noContentMessage(
      stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(promptBlockedBody))
        .complete(conversation("Hi"), CompletionOptions())
    ) should include("blocked the prompt: blockReason PROHIBITED_CONTENT")
  }

  it should "parse a tool call response" in {
    val toolCallBody =
      """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"London"}}}],"role":"model"}}]}"""
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpOk(toolCallBody))

    val result = client.complete(conversation("What's the weather?"), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "return AuthenticationError on HTTP 401" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpErr(401))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpErr(403))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpErr(429))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpErr(400))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpErr(500))

    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  it should "send Authorization Bearer header to the Vertex AI endpoint" in {
    var capturedHeaders: Map[String, String] = Map.empty
    val mockHttp                             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, headers: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else {
            capturedHeaders = headers
            httpOk(successBody)
          }
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))

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
    val mockHttp    = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, _: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else {
            capturedUrl = url
            httpOk(successBody)
          }
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))

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
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, _: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else httpOk(successBody)
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))

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
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

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

  it should "report a requested reasoning effort as dropped, from complete and from a stream (RFC 0008)" in {
    val mockHttp = stub[StubbedHttpCalls]
    val client   = stubHttpWithAuth(mockHttp, httpOk(successBody))
    (mockHttp.onPostStream _)
      .when(*, *, *, *)
      .onCall((_: String, _: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right(
          StreamingHttpResponse(
            200,
            new ByteArrayInputStream(
              "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]},\"finishReason\":\"STOP\"}]}\n"
                .getBytes(StandardCharsets.UTF_8)
            )
          )
        )
      )
    val reasoning = CompletionOptions().withReasoning(ReasoningEffort.High)
    val dropped = List(
      RequestAdjustment
        .Dropped(RequestOption.Reasoning, s"reasoning effort is not sent to ${testConfig.model} by this client")
    )

    client.complete(conversation("Hi"), reasoning).toOption.value.adjustments shouldBe dropped
    client.streamComplete(conversation("Hi"), reasoning, _ => ()).toOption.value.adjustments shouldBe dropped
    client.complete(conversation("Hi"), CompletionOptions()).toOption.value.adjustments shouldBe empty
  }

  it should "report grounding sources from the stream as citations" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}," +
        "\"groundingMetadata\":" + ujson.write(
          ujson.read(
            """{"groundingChunks":[{"web":{"uri":"https://src/a","title":"A"}},{"web":{"uri":"https://src/b","title":"B"}}],"groundingSupports":[{"segment":{"startIndex":0,"endIndex":5},"groundingChunkIndices":[0]}]}"""
          )
        ) + ",\"finishReason\":\"STOP\"}]}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))
    val mockHttp    = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client =
      new VertexAIClient(
        testConfig,
        org.llm4s.metrics.MetricsCollector.noop,
        ProviderExchangeLogging.Disabled,
        mockHttp
      )
    client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ()).toOption.get.citations shouldBe List(
      Citation(url = "https://src/a", title = Some("A")),
      Citation(url = "https://src/b", title = Some("B"))
    )
  }

  it should "parse token usage from the final SSE chunk" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

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

  it should "report thinking and cached tokens from the stream's usageMetadata (#2348)" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":40," +
        "\"cachedContentTokenCount\":64,\"totalTokenCount\":145}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    client.streamComplete(conversation("Hi"), CompletionOptions(), _ => ()).toOption.get.usage shouldBe Some(
      TokenUsage(
        promptTokens = 100,
        completionTokens = 5,
        totalTokens = 145,
        thinkingTokens = Some(40),
        cachedTokens = Some(64)
      )
    )
  }

  it should "end with the MAX_TOKENS finish reason and the usage when the last chunk has no parts" in {
    val sseData     = "data: " + ujson.read(noPartsMaxTokensBody).render() + "\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    val chunks = ListBuffer.empty[StreamedChunk]
    val completion = client.streamComplete(conversation("Hi"), CompletionOptions(), chunks += _) match {
      case Right(c) => c
      case Left(e)  => fail(s"expected an empty answer, got $e")
    }
    completion.content shouldBe ""
    completion.usage shouldBe Some(noPartsUsage)
    chunks.flatMap(_.finishReason) shouldBe List(FinishReason.Length)
    chunks.flatMap(_.content) shouldBe empty
  }

  noContentShapes.foreach { case (body, expected, filtered) =>
    it should s"end the stream naming the reason when its only chunk has no parts: $expected (${body.take(40)})" in {
      val chunks = ListBuffer.empty[StreamedChunk]
      noContentMessage(
        streamingClient(s"data: ${ujson.read(body).render()}\n\n")
          .streamComplete(conversation("Hi"), CompletionOptions(), chunks += _),
        filtered
      ) should include(expected)
      chunks.flatMap(_.content) shouldBe empty
    }
  }

  it should "end the stream with a ContentFilteredError naming the block reason when Gemini blocks the prompt" in {
    noContentMessage(
      streamingClient(s"data: $promptBlockedBody\n\n").streamComplete(conversation("Hi"), CompletionOptions(), _ => ())
    ) should include("blockReason PROHIBITED_CONTENT")
  }

  it should "return an error for non-200 stream status" in {
    val errorBody   = """{"error":{"message":"UNAUTHENTICATED","status":"UNAUTHENTICATED"}}"""
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(401, inputStream)))

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

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
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

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
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

  // text, then a finish that is not a normal one: `complete` (one candidate with the text and the finish) and a
  // stream (the text, then a chunk with the finish and no parts) must end the same way
  private def helThen: Seq[(String, String)] = Seq("SAFETY", "OTHER").map { reason =>
    (
      s"""{"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"finishReason":"$reason"}]}""",
      s"""data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}\n\n""" +
        s"""data: {"candidates":[{"content":{"role":"model"},"finishReason":"$reason"}]}\n\n"""
    )
  }

  "A reply a content filter cut short after text" should "be a ContentFilteredError from complete and from a stream" in {
    val (body, sse) = helThen.head
    val chunks      = ListBuffer.empty[StreamedChunk]
    noContentMessage(
      completeOf(body)
    ) shouldBe "Vertex AI stopped the reply after 3 characters of text: finishReason SAFETY"
    noContentMessage(streamOf(sse, chunks += _)) shouldBe
      "Vertex AI stopped the reply after 3 characters of text: finishReason SAFETY"
    chunks.flatMap(_.content) shouldBe List("Hel") // what was delivered stays delivered
  }

  "A filtered reply" should "keep the token usage it was billed, from complete and from a stream (#2676)" in {
    val usage = ""","usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3,"totalTokenCount":11}"""
    val body =
      s"""{"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"finishReason":"SAFETY"}]$usage}"""
    val sse = s"""data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]$usage}\n\n""" +
      s"""data: {"candidates":[{"content":{"role":"model"},"finishReason":"SAFETY"}]}\n\n"""
    Seq(completeOf(body), streamOf(sse, _ => ())).foreach {
      case Left(e: org.llm4s.error.ContentFilteredError) => e.usage.map(_.totalTokens) shouldBe Some(11)
      case other                                         => fail(s"expected a ContentFilteredError, got $other")
    }
  }

  "A reply that produced text before an abnormal finish" should "be the text, from complete and from a stream" in {
    val (body, sse) = helThen(1)
    completeOf(body).map(_.content) shouldBe Right("Hel")
    streamOf(sse, _ => ()).map(_.content) shouldBe Right("Hel")
  }

  // RFC 0009 PR 4: a stream ends on a finishReason, and an error object mid-stream fails it with that error
  private def helChunk = """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}""" + "\n\n"

  "A stream that ends before a finishReason" should "be a NetworkError, the text delivered staying delivered" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    streamOf(helChunk, chunks += _) match {
      case Left(e: org.llm4s.error.NetworkError) => e.message should include("finishReason")
      case other                                 => fail(s"expected a NetworkError, got $other")
    }
    chunks.flatMap(_.content) shouldBe List("Hel")
  }

  "An error object in a stream" should "fail it with the error its code stands for, unread past it" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    val sse = helChunk +
      """data: {"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""" + "\n\n" +
      """data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    streamOf(sse, chunks += _) match {
      case Left(e: org.llm4s.error.ServiceError) =>
        e.httpStatus shouldBe 503
        e.message should include("The model is overloaded.")
      case other => fail(s"expected a ServiceError, got $other")
    }
    chunks.flatMap(_.content) shouldBe List("Hel")
    val limited     = """data: {"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}"""
    val rateLimited = streamOf(limited + "\n\n", _ => ()).left.toOption.value
    rateLimited shouldBe a[org.llm4s.error.RateLimitError]
    rateLimited.message should include("Resource exhausted")
  }

  "A data line that is not JSON" should "fail the stream, a 502, unread past it (RFC 0009, open question 5)" in {
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val sse =
      """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}""" + "\n\n" +
        "data: not json\n\n" +
        """data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    streamOf(sse, chunks += _) match {
      case Left(e: org.llm4s.error.ServiceError) =>
        e.httpStatus shouldBe 502
        e.message should include("not JSON")
      case other => fail(s"expected a ServiceError, got $other")
    }
    // the text already delivered stays delivered; the chunk after the unreadable line is never read
    chunks.flatMap(_.content) shouldBe List("Hel")
  }

  it should "not be confused with a blank line or an SSE comment, which a keep-alive is: both are ignored" in {
    val sse = ": keep-alive\n\n" + "\n" + ":\n" +
      """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    streamOf(sse, _ => ()).map(_.content) shouldBe Right("Hel")
  }

  private def completeOf(body: String): org.llm4s.types.Result[Completion] =
    stubHttpWithAuth(stub[StubbedHttpCalls], httpOk(body)).complete(conversation("Hi"), CompletionOptions())

  private def streamOf(sse: String, onChunk: StreamedChunk => Unit): org.llm4s.types.Result[Completion] =
    streamingClient(sse).streamComplete(conversation("Hi"), CompletionOptions(), onChunk)

  // ---- service tier (#2726) ----

  "VertexAIClient" should "send Flex and Priority as the shared request type header, and Auto and Default as none" in {
    var capturedHeaders: Map[String, String] = Map.empty
    val mockHttp                             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, headers: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else {
            capturedHeaders = headers
            httpOk(successBody)
          }
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    Seq(ServiceTier.Flex -> "flex", ServiceTier.Priority -> "priority").foreach { (tier, value) =>
      client.complete(conversation("Hi"), CompletionOptions().withServiceTier(tier)).toOption.value.adjustments shouldBe
        empty
      capturedHeaders.get("X-Vertex-AI-LLM-Shared-Request-Type") shouldBe Some(value)
    }
    Seq(ServiceTier.Auto, ServiceTier.Default).foreach { tier =>
      val completion = client.complete(conversation("Hi"), CompletionOptions().withServiceTier(tier)).toOption.value
      capturedHeaders.keySet should not contain "X-Vertex-AI-LLM-Shared-Request-Type"
      completion.adjustments shouldBe List(
        RequestAdjustment.NotNeeded(
          RequestOption.ServiceTier,
          "Vertex AI serves a request that names no tier at its standard tier"
        )
      )
    }
  }

  "servedTier" should "be the tier usageMetadata.trafficType names, on complete and on a stream (#2746)" in {
    def body(trafficType: String) =
      """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}],""" +
        s""""usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"trafficType":"$trafficType"}}"""
    def served(trafficType: String) = completeOf(body(trafficType)).toOption.value.servedTier
    served("ON_DEMAND") shouldBe Some(ServiceTier.Default)
    served("ON_DEMAND_FLEX") shouldBe Some(ServiceTier.Flex)
    served("ON_DEMAND_PRIORITY") shouldBe Some(ServiceTier.Priority)
    served("PROVISIONED_THROUGHPUT") shouldBe Some(ServiceTier.Other("PROVISIONED_THROUGHPUT"))
    served("TRAFFIC_TYPE_UNSPECIFIED") shouldBe None
    completeOf(successBody).toOption.value.servedTier shouldBe None

    streamOf(s"data: ${body("ON_DEMAND_FLEX")}\n\n", _ => ()).toOption.value.servedTier shouldBe
      Some(ServiceTier.Flex)
  }

  it should "send ServiceTier.Other as the shared request type header, as written" in {
    var capturedHeaders: Map[String, String] = Map.empty
    val mockHttp                             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (url: String, headers: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else {
            capturedHeaders = headers
            httpOk(successBody)
          }
        }
    }
    (mockHttp.onGet _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
    client.complete(conversation("Hi"), CompletionOptions().withServiceTier(ServiceTier.Other("dedicated")))
    capturedHeaders.get("X-Vertex-AI-LLM-Shared-Request-Type") shouldBe Some("dedicated")
  }
}
