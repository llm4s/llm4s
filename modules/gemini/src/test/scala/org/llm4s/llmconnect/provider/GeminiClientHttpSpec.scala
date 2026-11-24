package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse, StubbedHttpCalls }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

class GeminiClientHttpSpec extends AnyFlatSpec with Matchers with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = GeminiConfig(
    apiKey = Secret("test-key"),
    model = ModelName("gemini-2.0-flash"),
    baseUrl = BaseUrl("https://generativelanguage.googleapis.com/v1beta"),
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private def mkClient(mockHttp: Llm4sHttpClient): GeminiClient =
    new GeminiClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

  private def httpOk(body: String): HttpResponse = HttpResponse(200, body, Map.empty)
  private def httpErr(status: Int): HttpResponse = HttpResponse(status, s"Error $status", Map.empty)
  private def conversation(text: String): Conversation =
    Conversation(messages = Seq(UserMessage(text)))

  private val successBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""".stripMargin

  private val thinkingCachedBody =
    """|{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],
       | "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":40,
       | "cachedContentTokenCount":64,"totalTokenCount":145}}""".stripMargin

  // the shape real gemini-3.5-flash returned (2026-11-05) when thinking spent the whole maxOutputTokens: the
  // candidate's content has a role and no `parts`. The model stopped, so it is an empty answer, not an error,
  // and the thinking tokens it was billed for are still reported.
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

  // ============================================================
  // complete() tests
  // ============================================================

  "GeminiClient.complete()" should "parse text content from a 200 response" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(successBody)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello!"
    // RFC 0009: STOP with no function call
    result.toOption.get.finishReason shouldBe FinishReason.Stop
  }

  it should "parse token usage from the response" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(successBody)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 10
    usage.completionTokens shouldBe 5
    usage.totalTokens shouldBe 15
  }

  it should "report thinking and cached tokens from usageMetadata (#2348)" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(thinkingCachedBody)))

    mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions()).toOption.value.usage shouldBe Some(
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
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(noPartsMaxTokensBody)))

    val completion = mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions()) match {
      case Right(c) => c
      case Left(e)  => fail(s"expected an empty answer, got $e")
    }
    completion.content shouldBe ""
    completion.message.text shouldBe None
    completion.toolCalls shouldBe empty
    completion.usage shouldBe Some(noPartsUsage)
  }

  noContentShapes.foreach { case (body, expected, filtered) =>
    it should s"fail naming the reason for a candidate with no parts: $expected (${body.take(40)})" in {
      noContentMessage(
        {
          val mockHttp = stub[StubbedHttpCalls]
          (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(body)))
          mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions())
        },
        filtered
      ) should include(expected)
    }
  }

  it should "fail with a ContentFilteredError naming the block reason when Gemini blocked the prompt" in {
    noContentMessage {
      val mockHttp = stub[StubbedHttpCalls]
      (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(promptBlockedBody)))
      mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions())
    } should include("blocked the prompt: blockReason PROHIBITED_CONTENT")
  }

  it should "parse a tool call response" in {
    val toolCallBody =
      """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"London"}}}],"role":"model"}}]}"""
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(toolCallBody)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("What's the weather?"), CompletionOptions())

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.toolCalls should have size 1
    completion.toolCalls.head.name shouldBe "get_weather"
  }

  it should "report the sources of a grounded answer as citations (#1216)" in {
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://src/a","title":"A"}},{"web":{"uri":"https://src/b","title":"B"}}],"groundingSupports":[{"segment":{"startIndex":0,"endIndex":5},"groundingChunkIndices":[0]}]}}]}"""
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(body)))

    val completion = mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions()).toOption.value
    completion.citations shouldBe List(
      Citation(url = "https://src/a", title = Some("A")),
      Citation(url = "https://src/b", title = Some("B"))
    )
  }

  it should "report no citations for an answer that was not grounded" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(successBody)))

    mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions()).toOption.value.citations shouldBe empty
  }

  private def requestBody(config: GeminiConfig, options: CompletionOptions): ujson.Value = {
    var body     = ""
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], sent: String, _: scala.concurrent.duration.FiniteDuration) =>
        body = sent
        Right(httpOk(successBody))
    }
    new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .complete(conversation("Hi"), options)
      .isRight shouldBe true
    ujson.read(body)
  }

  private lazy val searchTool = {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }
    val schema = Schema.`object`[Map[String, Any]]("In").withProperty(Schema.property("q", Schema.string("q")))
    ToolBuilder[String]("lookup", "Lookup tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()
      .fold(e => fail(e.message), identity)
  }

  it should "send no tools unless the section enables Google Search or the request has functions" in {
    requestBody(testConfig, CompletionOptions()).obj.contains("tools") shouldBe false
  }

  it should "send the google_search tool when googleSearch is on, beside any function declarations" in {
    val grounded = testConfig.withGoogleSearch(true)
    requestBody(grounded, CompletionOptions())("tools") shouldBe ujson.Arr(ujson.Obj("google_search" -> ujson.Obj()))

    val both = requestBody(grounded, CompletionOptions().withTools(Seq(searchTool)))("tools").arr
    both.map(_.obj.keySet) shouldBe Seq(Set("functionDeclarations"), Set("google_search"))
    both.head("functionDeclarations")(0)("name").str shouldBe "lookup"
  }

  it should "return AuthenticationError on HTTP 401" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpErr(401)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return AuthenticationError on HTTP 403" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpErr(403)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "return RateLimitError on HTTP 429" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpErr(429)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.RateLimitError]
  }

  it should "return ValidationError on HTTP 400" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpErr(400)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return AuthenticationError when Gemini reports an invalid API key with HTTP 400" in {
    val invalidKeyBody =
      """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(400, invalidKeyBody, Map.empty)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.AuthenticationError]
  }

  it should "keep non-auth Gemini HTTP 400 responses as ValidationError" in {
    val invalidRequestBody =
      """{"error":{"code":400,"message":"Invalid value at 'contents[0].parts[0].text'","status":"INVALID_ARGUMENT"}}"""
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(400, invalidRequestBody, Map.empty)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "return ServiceError on HTTP 500" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpErr(500)))

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[org.llm4s.error.ServiceError]
  }

  it should "record provider exchanges when logging is enabled" in {
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit =
        exchanges += exchange
    }
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(successBody)))

    val client = new GeminiClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink),
      mockHttp
    )
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider.asString shouldBe "gemini"
    exchanges.head.model shouldBe Some("gemini-2.0-flash")
    exchanges.head.requestBody should include("Hi")
    exchanges.head.responseBody.value should include("Hello!")
  }

  it should "include responseMimeType and no responseSchema when ResponseFormat.Json is set" in {
    var capturedBody: String = ""
    val mockHttp             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], body: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          capturedBody = body
          httpOk(successBody)
        }
    }

    val client  = mkClient(mockHttp)
    val options = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val result  = client.complete(conversation("Hi"), options)

    result.isRight shouldBe true
    val request = ujson.read(capturedBody)
    request("generationConfig")("responseMimeType").str shouldBe "application/json"
    request("generationConfig").obj.contains("responseSchema") shouldBe false
  }

  it should "include responseMimeType and responseSchema when ResponseFormat.JsonSchema is set" in {
    var capturedBody: String = ""
    val mockHttp             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], body: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          capturedBody = body
          httpOk(successBody)
        }
    }

    val schema  = ujson.Obj("type" -> "object", "properties" -> ujson.Obj("name" -> ujson.Obj("type" -> "string")))
    val client  = mkClient(mockHttp)
    val options = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    val result  = client.complete(conversation("Hi"), options)

    result.isRight shouldBe true
    val request = ujson.read(capturedBody)
    request("generationConfig")("responseMimeType").str shouldBe "application/json"
    request("generationConfig")("responseSchema") shouldBe schema
  }

  it should "omit responseMimeType and responseSchema when responseFormat is not set" in {
    var capturedBody: String = ""
    val mockHttp             = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], body: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right {
          capturedBody = body
          httpOk(successBody)
        }
    }

    val client = mkClient(mockHttp)
    val result = client.complete(conversation("Hi"), CompletionOptions())

    result.isRight shouldBe true
    val request = ujson.read(capturedBody)
    request("generationConfig").obj.contains("responseMimeType") shouldBe false
    request("generationConfig").obj.contains("responseSchema") shouldBe false
  }

  // ============================================================
  // streamComplete() tests
  // ============================================================

  "GeminiClient.streamComplete()" should "parse SSE lines and accumulate into a Completion" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val chunks = scala.collection.mutable.Buffer[StreamedChunk]()
    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions())(chunk => chunks += chunk)

    result.isRight shouldBe true
    result.toOption.get.content should include("Hello")
    result.toOption.get.content should include("world")
    chunks should have size 2
  }

  // RFC 0008 (#2115): Gemini's request carries no reasoning setting, so a requested one is reported, not dropped silently
  it should "report a requested reasoning effort as dropped, from complete and from a stream" in {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(successBody)))
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
    val client    = mkClient(mockHttp)
    val reasoning = CompletionOptions().withReasoning(ReasoningEffort.High)
    val dropped = List(
      RequestAdjustment.Dropped(
        RequestOption.Reasoning,
        "reasoning effort is not sent to gemini-2.0-flash by this client"
      )
    )

    client.complete(conversation("Hi"), reasoning).toOption.value.adjustments shouldBe dropped
    client.streamComplete(conversation("Hi"), reasoning)(_ => ()).toOption.value.adjustments shouldBe dropped
    client.complete(conversation("Hi"), CompletionOptions()).toOption.value.adjustments shouldBe empty
  }

  it should "send thinking as generationConfig.thinkingConfig: a budget on 2.5, a level on 3 and later" in {
    requestBody(testConfig.withModel(ModelName("gemini-2.5-flash")), CompletionOptions().withThinking(Thinking.Off))(
      "generationConfig"
    )("thinkingConfig") shouldBe ujson.Obj("thinkingBudget" -> 0)
    requestBody(
      testConfig.withModel(ModelName("gemini-3-pro-preview")),
      CompletionOptions().withReasoning(ReasoningEffort.High)
    )(
      "generationConfig"
    )("thinkingConfig") shouldBe ujson.Obj("thinkingLevel" -> "HIGH")
    requestBody(testConfig, CompletionOptions().withReasoning(ReasoningEffort.High))(
      "generationConfig"
    ).obj.keySet should
      not contain "thinkingConfig"
  }

  it should "report grounding sources from the stream as citations, however the chunks carry them" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]}," +
        "\"groundingMetadata\":" + ujson.write(
          ujson.read(
            """{"groundingChunks":[{"web":{"uri":"https://src/a","title":"A"}},{"web":{"uri":"https://src/b","title":"B"}}],"groundingSupports":[{"segment":{"startIndex":0,"endIndex":5},"groundingChunkIndices":[0]}]}"""
          )
        ) + ",\"finishReason\":\"STOP\"}]}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))
    val mockHttp    = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val completion = mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(_ => ()).toOption.value
    completion.content shouldBe "Hello world"
    completion.citations shouldBe List(
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
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions())(_ => ())

    result.isRight shouldBe true
    val usage = result.toOption.get.usage.get
    usage.promptTokens shouldBe 5
    usage.completionTokens shouldBe 2
  }

  it should "report thinking and cached tokens from the stream's usageMetadata (#2348)" in {
    // each chunk reports the usage so far; the last report is the stream's usage
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Do\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":100,\"cachedContentTokenCount\":64,\"totalTokenCount\":100}}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ne\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":40," +
        "\"cachedContentTokenCount\":64,\"totalTokenCount\":145}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val completion = mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(_ => ()).toOption.value
    completion.content shouldBe "Done"
    completion.usage shouldBe Some(
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
    // the same reply as complete's, streamed: the chunk's finish reason must still reach onChunk
    val sseData =
      "data: " + ujson.read(noPartsMaxTokensBody).render() + "\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val chunks = ListBuffer.empty[StreamedChunk]
    val completion = mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(chunks += _) match {
      case Right(c) => c
      case Left(e)  => fail(s"expected an empty answer, got $e")
    }
    completion.content shouldBe ""
    completion.usage shouldBe Some(noPartsUsage)
    chunks.flatMap(_.finishReason) shouldBe List(FinishReason.Length)
    chunks.flatMap(_.contentDelta) shouldBe empty
  }

  noContentShapes.foreach { case (body, expected, filtered) =>
    it should s"end the stream naming the reason when its only chunk has no parts: $expected (${body.take(40)})" in {
      val chunks = ListBuffer.empty[StreamedChunk]
      noContentMessage(
        {
          val sseData  = s"data: ${ujson.read(body).render()}\n\n"
          val mockHttp = stub[StubbedHttpCalls]
          (mockHttp.onPostStream _)
            .when(*, *, *, *)
            .returns(
              Right(StreamingHttpResponse(200, new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))))
            )
          mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(chunks += _)
        },
        filtered
      ) should include(expected)
      chunks.flatMap(_.contentDelta) shouldBe empty
    }
  }

  it should "end the stream with a ContentFilteredError naming the block reason when Gemini blocks the prompt" in {
    noContentMessage {
      val sseData  = s"data: $promptBlockedBody\n\n"
      val mockHttp = stub[StubbedHttpCalls]
      (mockHttp.onPostStream _)
        .when(*, *, *, *)
        .returns(Right(StreamingHttpResponse(200, new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8)))))
      mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(_ => ())
    } should include("blockReason PROHIBITED_CONTENT")
  }

  it should "record provider exchanges for streaming responses when logging is enabled" in {
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        exchanges += exchange

    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}\n"
    val inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8))
    val mockHttp    = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, inputStream)))

    val client = new GeminiClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink),
      mockHttp
    )
    val result = client.streamComplete(conversation("Hi"), CompletionOptions())(_ => ())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider.asString shouldBe "gemini"
    exchanges.head.requestBody should include("Hi")
    exchanges.head.responseBody.value should include("data:")
    exchanges.head.responseBody.value should include("Hello")
    exchanges.head.errorMessage shouldBe empty
  }

  it should "return an error for non-200 status before reading the body" in {
    val errorBody   = """{"error":{"message":"API key missing","status":"UNAUTHENTICATED"}}"""
    val inputStream = new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))

    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(401, inputStream)))

    val client = mkClient(mockHttp)
    val result = client.streamComplete(conversation("Hi"), CompletionOptions())(_ => ())

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

    val toolResult = ToolBuilder[String]("search", "Search tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val client = mkClient(stub[StubbedHttpCalls])
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

    val toolResult = ToolBuilder[String]("my_tool", "My tool description", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()

    val client = mkClient(stub[StubbedHttpCalls])
    toolResult match {
      case Right(tool) =>
        val geminiTool = client.convertToolToGeminiFormat(tool)
        geminiTool("name").str shouldBe "my_tool"
        geminiTool("description").str shouldBe "My tool description"
        geminiTool.obj.contains("parameters") shouldBe true
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }

  "GeminiClient.streamComplete()" should "return a recoverable NetworkError when the stream fails mid-read" in {
    val resetting = new java.io.InputStream {
      override def read(): Int = throw new java.io.IOException("Connection reset")
    }
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, resetting)))

    mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(_ => ()) match {
      case Left(e: org.llm4s.error.NetworkError) =>
        e.isRetryable shouldBe true
        (e.message should not).include("key=")
      case other => fail(s"expected a NetworkError, got $other")
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
    ) shouldBe "Gemini stopped the reply after 3 characters of text: finishReason SAFETY"
    noContentMessage(streamOf(sse, chunks += _)) shouldBe
      "Gemini stopped the reply after 3 characters of text: finishReason SAFETY"
    chunks.flatMap(_.contentDelta) shouldBe List("Hel") // what was delivered stays delivered
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
    chunks.flatMap(_.contentDelta) shouldBe List("Hel")
  }

  "An error object in a stream" should "fail it with the error its code stands for, unread past it" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    val sse = helChunk +
      """data: {"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""" + "\n\n" +
      """data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    streamOf(sse, chunks += _) match {
      case Left(e: org.llm4s.error.ServiceError) =>
        e.status shouldBe 503
        e.message should include("The model is overloaded.")
      case other => fail(s"expected a ServiceError, got $other")
    }
    chunks.flatMap(_.contentDelta) shouldBe List("Hel")
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
        e.status shouldBe 502
        e.message should include("not JSON")
      case other => fail(s"expected a ServiceError, got $other")
    }
    // the text already delivered stays delivered; the chunk after the unreadable line is never read
    chunks.flatMap(_.contentDelta) shouldBe List("Hel")
  }

  it should "not be confused with a blank line or an SSE comment, which a keep-alive is: both are ignored" in {
    val sse = ": keep-alive\n\n" + "\n" + ":\n" +
      """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    streamOf(sse, _ => ()).map(_.content) shouldBe Right("Hel")
  }

  private def completeOf(body: String): org.llm4s.types.Result[Completion] = {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).returns(Right(httpOk(body)))
    mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions())
  }

  private def streamOf(sse: String, onChunk: StreamedChunk => Unit): org.llm4s.types.Result[Completion] = {
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(200, new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))))
    mkClient(mockHttp).streamComplete(conversation("Hi"), CompletionOptions())(onChunk)
  }

  // ---- service tier (#2726) ----

  "GeminiClient" should "send a service tier as serviceTier, and report Auto as not needed" in {
    var body: ujson.Value = ujson.Null
    val mockHttp          = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], sent: String, _: scala.concurrent.duration.FiniteDuration) =>
        body = ujson.read(sent)
        Right(httpOk(successBody))
    }
    val client = mkClient(mockHttp)
    Seq(ServiceTier.Default -> "standard", ServiceTier.Flex -> "flex", ServiceTier.Priority -> "priority").foreach {
      (tier, wire) =>
        client
          .complete(conversation("Hi"), CompletionOptions().withServiceTier(tier))
          .toOption
          .value
          .adjustments shouldBe
          empty
        body("serviceTier").str shouldBe wire
    }
    val auto = client.complete(conversation("Hi"), CompletionOptions().withServiceTier(ServiceTier.Auto))
    body.obj.contains("serviceTier") shouldBe false
    auto.toOption.value.adjustments shouldBe List(
      RequestAdjustment.NotNeeded(
        RequestOption.ServiceTier,
        "Gemini serves a request that names no tier at its standard tier"
      )
    )
  }

  it should "send ServiceTier.Other as written" in {
    var body: ujson.Value = ujson.Null
    val mockHttp          = stub[StubbedHttpCalls]
    (mockHttp.onPost _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], sent: String, _: scala.concurrent.duration.FiniteDuration) =>
        body = ujson.read(sent)
        Right(httpOk(successBody))
    }
    mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions().withServiceTier(ServiceTier.Other("batch")))
    body("serviceTier").str shouldBe "batch"
  }

  "servedTier" should "be the tier the x-gemini-service-tier header names, on complete and on a stream (#2746)" in {
    def served(headers: Map[String, Seq[String]]): Option[ServiceTier] = {
      val mockHttp = stub[StubbedHttpCalls]
      (mockHttp.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, successBody, headers)))
      mkClient(mockHttp).complete(conversation("Hi"), CompletionOptions()).toOption.value.servedTier
    }
    served(Map("x-gemini-service-tier" -> Seq("standard"))) shouldBe Some(ServiceTier.Default)
    served(Map("X-Gemini-Service-Tier" -> Seq("priority"))) shouldBe Some(ServiceTier.Priority)
    served(Map("x-gemini-service-tier" -> Seq("flex"))) shouldBe Some(ServiceTier.Flex)
    served(Map("x-gemini-service-tier" -> Seq("batch"))) shouldBe Some(ServiceTier.Other("batch"))
    served(Map("x-gemini-service-tier" -> Seq(""))) shouldBe None
    served(Map.empty) shouldBe None

    val sse =
      """data: {"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}]}""" + "\n\n"
    val mockHttp = stub[StubbedHttpCalls]
    (mockHttp.onPostStream _)
      .when(*, *, *, *)
      .returns(
        Right(
          StreamingHttpResponse(
            200,
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            Map("x-gemini-service-tier" -> Seq("flex"))
          )
        )
      )
    mkClient(mockHttp)
      .streamComplete(conversation("Hi"), CompletionOptions())(_ => ())
      .toOption
      .value
      .servedTier shouldBe Some(ServiceTier.Flex)
  }

  it should "fall back to usageMetadata.trafficType, and be none without either" in {
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}],""" +
        """"usageMetadata":{"promptTokenCount":1,"trafficType":"ON_DEMAND_PRIORITY"}}"""
    completeOf(body).toOption.value.servedTier shouldBe Some(ServiceTier.Priority)
    completeOf(successBody).toOption.value.servedTier shouldBe None
  }
}
