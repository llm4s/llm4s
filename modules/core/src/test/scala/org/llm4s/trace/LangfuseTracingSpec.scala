package org.llm4s.trace

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.http.{ FailingHttpClient, HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.model.TokenUsage

import java.util.Base64

class LangfuseTracingSpec extends AnyFlatSpec with Matchers {

  private def simpleEvent = TraceEvent.ToolExecuted("test-tool", """{"q":"hello"}""", "result", 100, true)

  private def makeTracing(
    mockClient: Llm4sHttpClient,
    langfuseUrl: String = "https://cloud.langfuse.com",
    publicKey: String = "pk-lf-test",
    secretKey: String = "sk-lf-secret",
    restoreInterrupt: () => Unit = () => ()
  ) = new LangfuseTracing(
    langfuseUrl = langfuseUrl,
    publicKey = publicKey,
    secretKey = secretKey,
    environment = "test",
    release = "v0.1.0",
    version = "1.0.0",
    httpClient = mockClient,
    restoreInterrupt = restoreInterrupt
  )

  "LangfuseTracing" should "send correct URL, headers, and batch body on 200" in {
    val mockClient = new MockHttpClient(HttpResponse(200, """{"successes":1}"""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
    mockClient.lastUrl shouldBe Some("https://cloud.langfuse.com/api/public/ingestion")
    mockClient.lastTimeout shouldBe Some(30000)

    // Verify Basic auth header
    val expectedCredentials = Base64.getEncoder.encodeToString("pk-lf-test:sk-lf-secret".getBytes)
    mockClient.lastHeaders.flatMap(_.get("Authorization")) shouldBe Some(s"Basic $expectedCredentials")
    mockClient.lastHeaders.flatMap(_.get("Content-Type")) shouldBe Some("application/json")
    mockClient.lastHeaders.flatMap(_.get("User-Agent")) shouldBe Some("llm4s-scala/1.0.0")

    // Verify body is a batch payload
    val body = mockClient.lastBody.getOrElse(fail("Expected body"))
    val json = ujson.read(body)
    json.obj("batch").arr should have size 1
    json.obj("batch").arr.head.obj("type").str shouldBe "span-create"
  }

  it should "not append /api/public/ingestion if URL already has it" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient, langfuseUrl = "https://langfuse.example.com/api/public/ingestion")

    tracing.traceEvent(simpleEvent)

    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/ingestion")
  }

  it should "strip trailing slash before appending ingestion path" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient, langfuseUrl = "https://langfuse.example.com/")

    tracing.traceEvent(simpleEvent)

    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/ingestion")
  }

  it should "return Right on 207 partial success" in {
    val mockClient = new MockHttpClient(HttpResponse(207, """{"successes":1,"errors":0}"""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
  }

  it should "return Left on non-2xx response" in {
    val mockClient = new MockHttpClient(HttpResponse(500, """{"error":"Internal Server Error"}"""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isLeft shouldBe true
    val error = result.swap.getOrElse(fail("Expected Left"))
    error.message should include("500")
  }

  it should "return Left on 401 unauthorized" in {
    val mockClient = new MockHttpClient(HttpResponse(401, """{"error":"Unauthorized"}"""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isLeft shouldBe true
    val error = result.swap.getOrElse(fail("Expected Left"))
    error.message should include("401")
  }

  it should "skip export and return Right when public key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient, publicKey = "")

    val result = tracing.traceEvent(simpleEvent)

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 0
  }

  it should "skip export and return Right when secret key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient, secretKey = "")

    val result = tracing.traceEvent(simpleEvent)

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 0
  }

  it should "call restoreInterrupt and return Left on InterruptedException" in {
    var interruptRestored = false
    val mockRestore       = () => interruptRestored = true
    val failingClient     = new FailingHttpClient(new InterruptedException("interrupted"))
    val tracing           = makeTracing(failingClient, restoreInterrupt = mockRestore)

    val result = tracing.traceEvent(simpleEvent)

    result.isLeft shouldBe true
    val error = result.swap.getOrElse(fail("Expected Left"))
    error.message should include("interrupted")
    interruptRestored shouldBe true
  }

  it should "return Left on generic exception" in {
    val failingClient = new FailingHttpClient(new RuntimeException("connection reset"))
    val tracing       = makeTracing(failingClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isLeft shouldBe true
    val error = result.swap.getOrElse(fail("Expected Left"))
    error.message should include("connection reset")
  }

  it should "encode credentials correctly in Base64" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient, publicKey = "pk-test", secretKey = "sk-test")

    tracing.traceEvent(simpleEvent)

    val authHeader = mockClient.lastHeaders.flatMap(_.get("Authorization")).getOrElse(fail("Missing Authorization"))
    val encoded    = authHeader.stripPrefix("Basic ")
    val decoded    = new String(Base64.getDecoder.decode(encoded))
    decoded shouldBe "pk-test:sk-test"
  }

  it should "handle TokenUsageRecorded event correctly" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient)
    val usage      = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)

    val result = tracing.traceTokenUsage(usage, "gpt-4o", "completion")

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
    val body  = ujson.read(mockClient.lastBody.getOrElse(fail("Expected body")))
    val event = body.obj("batch").arr.head
    event.obj("type").str shouldBe "event-create"
    event.obj("body").obj("name").str should include("Token Usage")
  }

  it should "handle traceToolCall correctly" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceToolCall("calculator", """{"expr":"2+2"}""", "4")

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
    val body  = ujson.read(mockClient.lastBody.getOrElse(fail("Expected body")))
    val event = body.obj("batch").arr.head
    event.obj("type").str shouldBe "span-create"
    event.obj("body").obj("name").str should include("calculator")
  }

  it should "handle traceError correctly" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceError(new RuntimeException("something broke"), "test-context")

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
    val body  = ujson.read(mockClient.lastBody.getOrElse(fail("Expected body")))
    val event = body.obj("batch").arr.head
    event.obj("type").str shouldBe "event-create"
    event.obj("body").obj("level").str shouldBe "ERROR"
    event.obj("body").obj("statusMessage").str should include("something broke")
  }
}
