package org.llm4s.trace

import org.llm4s.security.Secret
import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.Base64

class LangfuseBatchSenderSpec extends AnyFlatSpec with Matchers {

  private def testConfig = LangfuseHttpApiCaller(
    langfuseUrl = "https://cloud.langfuse.com",
    publicKey = Secret("pk-lf-test-key"),
    secretKey = Secret("sk-lf-test-secret")
  )

  private val t0 = Instant.parse("2026-11-24T12:00:00Z")

  private def request(names: String*) =
    LangfuseOtlp.request(
      names.map(n =>
        LangfuseOtlp.Observation(TraceContext.random(), None, n, LangfuseOtlp.ObservationType.Span, t0, t0)
      ),
      LangfuseOtlp.Deployment("test", "r", "v"),
      "spec"
    )

  private def testScores = Seq(ujson.Obj("traceId" -> "a" * 32, "name" -> "faithfulness", "value" -> 0.9))

  private def withLangfuseBatchSenderLoggerSilenced[A](body: => A): A = {
    val logger   = LoggerFactory.getLogger(classOf[DefaultLangfuseBatchSender]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try body
    finally logger.setLevel(previous)
  }

  private def sender(mock: org.llm4s.http.Llm4sHttpClient, restore: () => Unit = () => ()) =
    new DefaultLangfuseBatchSender(httpClient = mock, restoreInterrupt = restore)

  "DefaultLangfuseBatchSender.sendTraces" should "post the export to the OTel endpoint with Langfuse's headers" in {
    val mockClient = new MockHttpClient(HttpResponse(200, "{}"))

    sender(mockClient).sendTraces(request("one", "two"), testConfig)

    mockClient.lastUrl shouldBe Some("https://cloud.langfuse.com/api/public/otel/v1/traces")
    mockClient.lastTimeout shouldBe Some(scala.concurrent.duration.Duration(30, "seconds"))
    val expectedCredentials = Base64.getEncoder.encodeToString("pk-lf-test-key:sk-lf-test-secret".getBytes)
    val headers             = mockClient.lastHeaders.getOrElse(fail("no headers"))
    headers.get("Authorization") shouldBe Some(s"Basic $expectedCredentials")
    headers.get("Content-Type") shouldBe Some("application/json")
    headers.get("x-langfuse-ingestion-version") shouldBe Some("4")

    SentSpan.all(mockClient.lastBody.getOrElse(fail("Expected body"))).map(_.name) shouldBe Seq("one", "two")
  }

  it should "post to the host of a URL that names the legacy ingestion endpoint" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    sender(mockClient).sendTraces(
      request("x"),
      testConfig.copy(langfuseUrl = "https://langfuse.example.com/api/public/ingestion")
    )
    mockClient.lastUrl shouldBe Some("https://langfuse.example.com/api/public/otel/v1/traces")
  }

  it should "handle a partial success, an error response and an unreadable body without throwing" in {
    Seq(
      HttpResponse(200, """{"partialSuccess":{"rejectedSpans":"1","errorMessage":"bad"}}"""),
      HttpResponse(500, """{"error":"Internal Server Error"}"""),
      HttpResponse(401, """{"error":"Unauthorized"}"""),
      HttpResponse(200, "not json")
    ).foreach { response =>
      val mockClient = new MockHttpClient(response)
      noException should be thrownBy withLangfuseBatchSenderLoggerSilenced(
        sender(mockClient).sendTraces(request("x"), testConfig)
      )
      mockClient.postCallCount shouldBe 1
    }
  }

  it should "skip export when either key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    withLangfuseBatchSenderLoggerSilenced {
      sender(mockClient).sendTraces(request("x"), testConfig.copy(publicKey = Secret("")))
      sender(mockClient).sendScores(testScores, testConfig.copy(secretKey = Secret("")))
    }
    mockClient.postCallCount shouldBe 0
  }

  it should "call restoreInterrupt on an interrupted request" in {
    var interruptRestored = false
    val failingClient = new FailingHttpClient(org.llm4s.error.ExecutionError("POST request interrupted", "http.POST"))

    noException should be thrownBy withLangfuseBatchSenderLoggerSilenced(
      sender(failingClient, () => interruptRestored = true).sendTraces(request("x"), testConfig)
    )
    interruptRestored shouldBe true
  }

  it should "handle a transport failure without throwing" in {
    val failingClient = new FailingHttpClient(new RuntimeException("connection reset"))
    noException should be thrownBy withLangfuseBatchSenderLoggerSilenced(
      sender(failingClient).sendTraces(request("x"), testConfig)
    )
  }

  "DefaultLangfuseBatchSender.sendScores" should "post the scores as one JSON array to the scores endpoint" in {
    val mockClient = new MockHttpClient(HttpResponse(202, """{"message":"Accepted"}"""))
    val scores     = testScores :+ ujson.Obj("traceId" -> "a" * 32, "name" -> "ragas_score", "value" -> 0.8)

    sender(mockClient).sendScores(scores, testConfig)

    mockClient.lastUrl shouldBe Some("https://cloud.langfuse.com/api/public/scores")
    mockClient.lastHeaders.flatMap(_.get("Content-Type")) shouldBe Some("application/json")
    ujson.read(mockClient.lastBody.getOrElse(fail("Expected body"))) shouldBe ujson.Arr.from(scores)
  }

  it should "send nothing for no scores" in {
    val mockClient = new MockHttpClient(HttpResponse(202, ""))
    sender(mockClient).sendScores(Seq.empty, testConfig)
    mockClient.postCallCount shouldBe 0
  }

  it should "handle a 207 that rejects scores without throwing" in {
    val body       = """{"accepted":0,"rejected":1,"errors":[{"message":"value must be a number"}]}"""
    val mockClient = new MockHttpClient(HttpResponse(207, body))
    noException should be thrownBy withLangfuseBatchSenderLoggerSilenced(
      sender(mockClient).sendScores(testScores, testConfig)
    )
    mockClient.postCallCount shouldBe 1
  }
}
