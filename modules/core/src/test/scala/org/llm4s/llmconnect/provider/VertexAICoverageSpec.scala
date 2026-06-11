package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart, StreamingHttpResponse }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.util.Base64
import org.llm4s.model.ModelRegistryService

/** Concrete AutoCloseable HTTP client used in lifecycle tests. */
private class TrackingAutoCloseableHttpClient extends Llm4sHttpClient with AutoCloseable {
  var closeCalled: Boolean = false

  override def get(url: String, headers: Map[String, String], params: Map[String, String], timeout: Int): HttpResponse =
    HttpResponse(200, """{"access_token":"tok","expires_in":3600}""", Map.empty)
  override def post(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse =
    HttpResponse(200, """{"access_token":"tok","expires_in":3600}""", Map.empty)
  override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): HttpResponse =
    HttpResponse(200, "", Map.empty)
  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse = HttpResponse(200, "", Map.empty)
  override def put(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse =
    HttpResponse(200, "", Map.empty)
  override def delete(url: String, headers: Map[String, String], timeout: Int): HttpResponse =
    HttpResponse(200, "", Map.empty)
  override def postRaw(url: String, headers: Map[String, String], body: String, timeout: Int): HttpRawResponse =
    HttpRawResponse(200, Array.emptyByteArray)
  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): StreamingHttpResponse = StreamingHttpResponse(200, new ByteArrayInputStream(Array.emptyByteArray))
  override def close(): Unit = closeCalled = true
}

/**
 * Additional coverage tests for [[VertexAIClient]] and [[VertexAIAuthProvider]].
 *
 * Covers service-account auth, request-body message types, response edge cases,
 * streaming edge cases, lifecycle management, and the apply factory methods.
 */
class VertexAICoverageSpec extends AnyFlatSpec with Matchers with MockFactory {

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

  private def captureBodyClient(onCompletion: String => Unit): VertexAIClient = {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], body: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else {
        onCompletion(body)
        HttpResponse(200, successBody, Map.empty)
      }
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    new VertexAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
  }

  // ============================================================
  // VertexAIAuthProvider — additional auth-path coverage
  // ============================================================

  "VertexAIAuthProvider" should "resolve credential file from GOOGLE_APPLICATION_CREDENTIALS env var" in {
    val credFileContent =
      """{
        |  "type": "authorized_user",
        |  "client_id": "test-client-id",
        |  "client_secret": "test-secret",
        |  "refresh_token": "test-refresh-token"
        |}""".stripMargin

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = {
        case "GOOGLE_ACCESS_TOKEN"            => None
        case "GOOGLE_APPLICATION_CREDENTIALS" => Some("/env/application_default_credentials.json")
        case _                                => None
      },
      fileReader = _ => Right(credFileContent)
    )

    val result = provider.getAccessToken()
    result.isRight shouldBe true
    result.toOption.get shouldBe "ya29.test-token"
  }

  it should "return error when credential file JSON is missing the 'type' field" in {
    val mockHttp = stub[Llm4sHttpClient]

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right("""{"client_id": "some-id"}""")
    )

    val result = provider.getAccessToken()
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("missing 'type' field")
  }

  it should "fetch a token via service_account JWT-assertion flow" in {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp = kpg.generateKeyPair()
    val pem = "-----BEGIN PRIVATE KEY-----\n" +
      Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate.getEncoded) +
      "\n-----END PRIVATE KEY-----\n"
    val credJson = ujson
      .Obj(
        "type"         -> "service_account",
        "client_email" -> "test@my-project.iam.gserviceaccount.com",
        "private_key"  -> pem,
        "token_uri"    -> "https://oauth2.googleapis.com/token"
      )
      .render()

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/sa_creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credJson)
    )

    val result = provider.getAccessToken()
    result.isRight shouldBe true
    result.toOption.get shouldBe "ya29.test-token"
  }

  it should "return AuthenticationError when JWT exchange returns non-200" in {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp = kpg.generateKeyPair()
    val pem = "-----BEGIN PRIVATE KEY-----\n" +
      Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate.getEncoded) +
      "\n-----END PRIVATE KEY-----\n"
    val credJson = ujson
      .Obj(
        "type"         -> "service_account",
        "client_email" -> "test@my-project.iam.gserviceaccount.com",
        "private_key"  -> pem,
        "token_uri"    -> "https://oauth2.googleapis.com/token"
      )
      .render()

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(401, "Unauthorized", Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/sa_creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credJson)
    )

    val result = provider.getAccessToken()
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("JWT token exchange failed")
  }

  it should "return AuthenticationError for an invalid RSA private key in service_account credentials" in {
    val credJson = ujson
      .Obj(
        "type"         -> "service_account",
        "client_email" -> "test@my-project.iam.gserviceaccount.com",
        "private_key"  -> "-----BEGIN PRIVATE KEY-----\nYWFh\n-----END PRIVATE KEY-----\n",
        "token_uri"    -> "https://oauth2.googleapis.com/token"
      )
      .render()

    val mockHttp = stub[Llm4sHttpClient]

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/sa_creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credJson)
    )

    val result = provider.getAccessToken()
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to parse private key")
  }

  // ============================================================
  // VertexAIClient — request body: message type coverage
  // ============================================================

  "VertexAIClient request body" should "include systemInstruction when a SystemMessage is present" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)
    val conv         = Conversation(Seq(SystemMessage("You are a helpful assistant."), UserMessage("Hi")))

    val result = client.complete(conv, CompletionOptions())

    result.isRight shouldBe true
    val req = ujson.read(capturedBody)
    req.obj.contains("systemInstruction") shouldBe true
    req("systemInstruction")("parts")(0)("text").str should include("helpful assistant")
  }

  it should "include functionCall parts for AssistantMessage with tool calls" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)
    val conv = Conversation(
      Seq(
        UserMessage("What is the weather?"),
        AssistantMessage(None, Seq(ToolCall("tc-1", "get_weather", ujson.Obj("location" -> "London"))))
      )
    )

    val result = client.complete(conv, CompletionOptions())

    result.isRight shouldBe true
    capturedBody should include("functionCall")
    capturedBody should include("get_weather")
  }

  it should "include functionResponse parts for ToolMessage after a tool call" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)
    val conv = Conversation(
      Seq(
        UserMessage("What is the weather?"),
        AssistantMessage(None, Seq(ToolCall("tc-1", "get_weather", ujson.Obj("location" -> "London")))),
        ToolMessage("Sunny, 22 C", "tc-1")
      )
    )

    val result = client.complete(conv, CompletionOptions())

    result.isRight shouldBe true
    capturedBody should include("functionResponse")
    capturedBody should include("get_weather")
    capturedBody should include("Sunny")
  }

  it should "set maxOutputTokens when maxTokens is provided" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)

    val result = client.complete(
      Conversation(Seq(UserMessage("Hi"))),
      CompletionOptions().copy(maxTokens = Some(512))
    )

    result.isRight shouldBe true
    ujson.read(capturedBody)("generationConfig")("maxOutputTokens").num.toInt shouldBe 512
  }

  it should "set responseMimeType for ResponseFormat.Json" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)

    val result = client.complete(
      Conversation(Seq(UserMessage("Hi"))),
      CompletionOptions().withResponseFormat(ResponseFormat.Json)
    )

    result.isRight shouldBe true
    ujson.read(capturedBody)("generationConfig")("responseMimeType").str shouldBe "application/json"
  }

  it should "set responseMimeType and responseSchema for ResponseFormat.JsonSchema" in {
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)
    val schema =
      ujson.Obj("type" -> "object", "properties" -> ujson.Obj("name" -> ujson.Obj("type" -> "string")))

    val result = client.complete(
      Conversation(Seq(UserMessage("Hi"))),
      CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    )

    result.isRight shouldBe true
    val genCfg = ujson.read(capturedBody)("generationConfig")
    genCfg("responseMimeType").str shouldBe "application/json"
    genCfg.obj.contains("responseSchema") shouldBe true
  }

  it should "include functionDeclarations when tools are passed in CompletionOptions" in {
    import org.llm4s.toolapi.{ Schema, ToolBuilder }
    var capturedBody = ""
    val client       = captureBodyClient(capturedBody = _)
    val schema       = Schema.`object`[Map[String, Any]]("In").withProperty(Schema.property("q", Schema.string("q")))

    ToolBuilder[Map[String, Any], String]("search", "Search tool", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe() match {
      case Right(tool) =>
        val result = client.complete(
          Conversation(Seq(UserMessage("Hi"))),
          CompletionOptions().copy(tools = Seq(tool))
        )
        result.isRight shouldBe true
        ujson.read(capturedBody)("tools")(0)("functionDeclarations")(0)("name").str shouldBe "search"
      case Left(err) => fail(s"Tool build failed: ${err.message}")
    }
  }

  // ============================================================
  // VertexAIClient.stripAdditionalProperties
  // ============================================================

  "VertexAIClient.stripAdditionalProperties" should "remove additionalProperties from anyOf sub-schemas" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    val schema = ujson.Obj(
      "type" -> "object",
      "anyOf" -> ujson.Arr(
        ujson.Obj("type" -> "string", "additionalProperties"  -> false),
        ujson.Obj("type" -> "integer", "additionalProperties" -> false)
      )
    )
    client.stripAdditionalProperties(schema)

    schema("anyOf")(0).obj.contains("additionalProperties") shouldBe false
    schema("anyOf")(1).obj.contains("additionalProperties") shouldBe false
  }

  it should "remove additionalProperties from oneOf sub-schemas" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    val schema = ujson.Obj(
      "oneOf" -> ujson.Arr(
        ujson.Obj("type" -> "string", "additionalProperties" -> ujson.Bool(true))
      )
    )
    client.stripAdditionalProperties(schema)

    schema("oneOf")(0).obj.contains("additionalProperties") shouldBe false
  }

  it should "remove additionalProperties from allOf sub-schemas" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    val schema = ujson.Obj(
      "allOf" -> ujson.Arr(
        ujson.Obj("type" -> "object", "additionalProperties" -> ujson.Obj())
      )
    )
    client.stripAdditionalProperties(schema)

    schema("allOf")(0).obj.contains("additionalProperties") shouldBe false
  }

  it should "be a no-op for non-object JSON values" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    noException should be thrownBy {
      client.stripAdditionalProperties(ujson.Str("a string value"))
      client.stripAdditionalProperties(ujson.Num(42))
      client.stripAdditionalProperties(ujson.True)
    }
  }

  // ============================================================
  // VertexAIClient — response parsing edge cases
  // ============================================================

  "VertexAIClient.complete()" should "return a ValidationError when candidates array is empty" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], _: String, _: Int) =>
      if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
      else HttpResponse(200, """{"candidates":[]}""", Map.empty)
    }
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenBody, Map.empty))
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )

    val result = client.complete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("No candidates")
  }

  // ============================================================
  // VertexAIClient — streaming edge cases
  // ============================================================

  "VertexAIClient.streamComplete()" should "emit a tool-call chunk for functionCall SSE data" in {
    val sseData =
      "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":" +
        "{\"name\":\"get_weather\",\"args\":{\"location\":\"London\"}}}]}}]}\n"
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
    val result = client.streamComplete(
      Conversation(Seq(UserMessage("What is the weather?"))),
      CompletionOptions(),
      chunk => chunks += chunk
    )

    result.isRight shouldBe true
    chunks should have size 1
    chunks.head.toolCall should not be empty
    chunks.head.toolCall.get.name shouldBe "get_weather"
  }

  it should "skip non-data SSE lines (comment and event lines)" in {
    val sseData =
      ": keep-alive\n" +
        "event: content\n" +
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]," +
        "\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":1,\"totalTokenCount\":3}}\n"
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
    val result = client.streamComplete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    result.toOption.get.content shouldBe "Hello"
  }

  it should "handle a data line with empty candidates gracefully" in {
    val sseData =
      "data: {\"candidates\":[]," +
        "\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":0,\"totalTokenCount\":2}}\n"
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
    val result = client.streamComplete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions(), _ => ())

    result.isRight shouldBe true
  }

  // ============================================================
  // VertexAIClient — lifecycle: releaseResources
  // ============================================================

  "VertexAIClient" should "call close() on httpClient when it is AutoCloseable" in {
    val trackingHttp = new TrackingAutoCloseableHttpClient()
    val client = new VertexAIClient(
      testConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      trackingHttp
    )
    client.close()

    trackingHttp.closeCalled shouldBe true
  }

  // ============================================================
  // VertexAIClient.apply — factory method coverage
  // ============================================================

  "VertexAIClient.apply" should "construct a client using the single-argument overload" in {
    val result = VertexAIClient(testConfig)
    result.isRight shouldBe true
  }

  it should "construct a client using the two-argument (config + metrics) overload" in {
    val result = VertexAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop)
    result.isRight shouldBe true
  }

  it should "construct a client using the three-argument (config + metrics + logging) overload" in {
    val result = VertexAIClient(testConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled)
    result.isRight shouldBe true
  }
}
