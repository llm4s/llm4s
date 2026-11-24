package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.http.{ FailingHttpClient, HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.model.{ TokenUsage, ToolCallId }
import org.slf4j.LoggerFactory

import java.util.Base64
import scala.concurrent.duration.*

/** The export itself: where `LangfuseTracing` posts, with which headers, and what it makes of each response (#2883). */
class LangfuseTracingSpec extends AnyFlatSpec with Matchers with LoneElement {

  private def simpleEvent =
    TraceEvent.ToolExecuted("test-tool", ToolCallId("""{"q":"hello"}""").asString, "result", 100.millis, true)

  private def withLangfuseLoggerSilenced[A](body: => A): A = {
    val logger   = LoggerFactory.getLogger(classOf[LangfuseTracing]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try body
    finally logger.setLevel(previous)
  }

  private def makeTracing(
    mockClient: Llm4sHttpClient,
    langfuseUrl: String = "https://cloud.langfuse.com",
    publicKey: String = "pk-lf-test",
    secretKey: String = "sk-lf-secret",
    restoreInterrupt: () => Unit = () => ()
  ) = new LangfuseTracing(
    langfuseUrl = langfuseUrl,
    publicKey = Secret(publicKey),
    secretKey = Secret(secretKey),
    environment = "test",
    release = "v0.1.0",
    version = "1.0.0",
    httpClient = mockClient,
    restoreInterrupt = restoreInterrupt
  )

  private def sent(mock: MockHttpClient): Seq[SentSpan] = SentSpan.all(mock.lastBody.getOrElse(fail("Expected body")))

  "LangfuseTracing" should "post an OTLP/JSON export to Langfuse's OpenTelemetry endpoint with its headers" in {
    val mockClient = new MockHttpClient(HttpResponse(200, "{}"))
    val tracing    = makeTracing(mockClient)

    val result = tracing.traceEvent(simpleEvent)

    result.isRight shouldBe true
    mockClient.postCallCount shouldBe 1
    mockClient.lastUrl shouldBe Some("https://cloud.langfuse.com/api/public/otel/v1/traces")
    mockClient.lastTimeout shouldBe Some(30.seconds)

    val expectedCredentials = Base64.getEncoder.encodeToString("pk-lf-test:sk-lf-secret".getBytes)
    val headers             = mockClient.lastHeaders.getOrElse(fail("no headers"))
    headers.get("Authorization") shouldBe Some(s"Basic $expectedCredentials")
    headers.get("Content-Type") shouldBe Some("application/json")
    headers.get("x-langfuse-ingestion-version") shouldBe Some("4")
    headers.get("User-Agent") shouldBe Some("llm4s-scala/1.0.0")

    val json  = ujson.read(mockClient.lastBody.getOrElse(fail("Expected body")))
    val scope = json("resourceSpans")(0)("scopeSpans")(0)
    json("resourceSpans")(0)("resource")("attributes")(0)("key").str shouldBe "service.name"
    scope("scope")("name").str shouldBe "org.llm4s.trace.LangfuseTracing"
    val span = scope("spans")(0)
    (span("traceId").str should fullyMatch).regex("[0-9a-f]{32}")
    (span("spanId").str should fullyMatch).regex("[0-9a-f]{16}")
    span("kind").num shouldBe 1
    // OTLP JSON writes a fixed64 as a decimal string
    (span("startTimeUnixNano").str should fullyMatch).regex("[0-9]+")
    (span("endTimeUnixNano").str should fullyMatch).regex("[0-9]+")
    span.obj.contains("parentSpanId") shouldBe false

    val only = sent(mockClient).loneElement
    only.kind shouldBe "span"
    only.str("langfuse.environment") shouldBe "test"
    only.str("langfuse.release") shouldBe "v0.1.0"
    only.str("langfuse.version") shouldBe "1.0.0"
    // a tool call spans its duration
    java.time.Duration.between(only.startTime, only.endTime).toMillis shouldBe 100
  }

  it should "post to the host of a URL that still names the legacy ingestion endpoint" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient, langfuseUrl = "https://langfuse.example.com/api/public/ingestion").traceEvent(simpleEvent)
    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/otel/v1/traces")
  }

  it should "strip a trailing slash, and keep a URL that already names the OTel endpoint" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient, langfuseUrl = "https://langfuse.example.com/").traceEvent(simpleEvent)
    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/otel/v1/traces")

    makeTracing(mockClient, langfuseUrl = "https://langfuse.example.com/api/public/otel").traceEvent(simpleEvent)
    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/otel/v1/traces")

    makeTracing(mockClient, langfuseUrl = "https://h.example/lf/api/public/otel/v1/traces/").traceEvent(simpleEvent)
    mockClient.lastUrl shouldBe Some("https://h.example/lf/api/public/otel/v1/traces")
  }

  it should "return Right on a 200 whose partial success rejects nothing" in {
    val body = """{"partialSuccess":{"rejectedSpans":"0","errorMessage":""}}"""
    makeTracing(new MockHttpClient(HttpResponse(200, body))).traceEvent(simpleEvent) shouldBe Right(())
    makeTracing(new MockHttpClient(HttpResponse(200, """{"partialSuccess":{}}"""))).traceEvent(simpleEvent) shouldBe
      Right(())
  }

  it should "return Left naming the count when a partial success rejects spans" in {
    val body = """{"partialSuccess":{"rejectedSpans":"1","errorMessage":"span 2: invalid start time"}}"""
    val result =
      withLangfuseLoggerSilenced(makeTracing(new MockHttpClient(HttpResponse(200, body))).traceEvent(simpleEvent))

    val message = result.left.toOption.getOrElse(fail("expected Left")).message
    message should include("rejected 1 of 1 spans")
    message should include("span 2: invalid start time")
    // a number rather than OTLP's string is read too
    val numeric = """{"partialSuccess":{"rejectedSpans":2}}"""
    withLangfuseLoggerSilenced(
      makeTracing(new MockHttpClient(HttpResponse(200, numeric))).traceEvent(simpleEvent)
    ).isLeft shouldBe true
  }

  it should "treat a 2xx whose body cannot be read as accepted, rather than fail tracing" in {
    makeTracing(new MockHttpClient(HttpResponse(200, "not json"))).traceEvent(simpleEvent) shouldBe Right(())
    makeTracing(new MockHttpClient(HttpResponse(202, "[1]"))).traceEvent(simpleEvent) shouldBe Right(())
  }

  it should "return Left on a 5xx, without retrying it" in {
    val mockClient = new MockHttpClient(HttpResponse(500, """{"error":"Internal Server Error"}"""))
    val result     = withLangfuseLoggerSilenced(makeTracing(mockClient).traceEvent(simpleEvent))

    result.swap.getOrElse(fail("Expected Left")).message should include("500")
    mockClient.postCallCount shouldBe 1
  }

  it should "return Left on 401 unauthorized" in {
    val mockClient = new MockHttpClient(HttpResponse(401, """{"error":"Unauthorized"}"""))
    val result     = withLangfuseLoggerSilenced(makeTracing(mockClient).traceEvent(simpleEvent))
    result.swap.getOrElse(fail("Expected Left")).message should include("401")
  }

  it should "skip export and return Right when public key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    withLangfuseLoggerSilenced(makeTracing(mockClient, publicKey = "").traceEvent(simpleEvent)) shouldBe Right(())
    mockClient.postCallCount shouldBe 0
  }

  it should "skip export and return Right when secret key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    withLangfuseLoggerSilenced(makeTracing(mockClient, secretKey = "").traceEvent(simpleEvent)) shouldBe Right(())
    mockClient.postCallCount shouldBe 0
  }

  it should "call restoreInterrupt and return Left on InterruptedException" in {
    var interruptRestored = false
    val failingClient = new FailingHttpClient(org.llm4s.error.ExecutionError("POST request interrupted", "http.POST"))
    val tracing       = makeTracing(failingClient, restoreInterrupt = () => interruptRestored = true)

    val result = withLangfuseLoggerSilenced(tracing.traceEvent(simpleEvent))

    result.swap.getOrElse(fail("Expected Left")).message should include("interrupted")
    interruptRestored shouldBe true
  }

  it should "return Left on generic exception" in {
    val tracing = makeTracing(new FailingHttpClient(new RuntimeException("connection reset")))
    val result  = withLangfuseLoggerSilenced(tracing.traceEvent(simpleEvent))
    result.swap.getOrElse(fail("Expected Left")).message should include("connection reset")
  }

  it should "encode credentials correctly in Base64" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient, publicKey = "pk-test", secretKey = "sk-test").traceEvent(simpleEvent)

    val authHeader = mockClient.lastHeaders.flatMap(_.get("Authorization")).getOrElse(fail("Missing Authorization"))
    new String(Base64.getDecoder.decode(authHeader.stripPrefix("Basic "))) shouldBe "pk-test:sk-test"
  }

  it should "send traceTokenUsage as an event" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient).traceTokenUsage(TokenUsage(100, 50, 150), ModelName("gpt-4o"), "completion") shouldBe Right(
      ()
    )
    val span = sent(mockClient).loneElement
    span.kind shouldBe "event"
    span.name should include("Token Usage")
  }

  it should "send traceToolCall as a span named after the tool" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient).traceToolCall(
      "calculator",
      """{"expr":"2+2"}""",
      "4",
      Duration.Zero,
      success = true
    ) shouldBe
      Right(())
    val span = sent(mockClient).loneElement
    span.kind shouldBe "span"
    span.name should include("calculator")
    span.input("arguments").str shouldBe """{"expr":"2+2"}"""
    span.output("result").str shouldBe "4"
  }

  it should "send traceError as an ERROR event with an error status" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    makeTracing(mockClient).traceError(new RuntimeException("something broke"), "test-context") shouldBe Right(())
    val span = sent(mockClient).loneElement
    span.kind shouldBe "event"
    span.level shouldBe Some("ERROR")
    span.statusMessage.getOrElse("") should include("something broke")
    span.status.map(_("code").num) shouldBe Some(2)
  }
}
