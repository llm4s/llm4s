package org.llm4s.trace

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.testutil.EchoedCredentials
import org.llm4s.testutil.EchoedCredentials.{ leaked, logged }
import org.llm4s.trace.LangfuseIngestionResponse.Rejection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A Langfuse error body, or a per-event rejection message, can echo the request's credentials. Neither may be logged
 * or put into an error in the clear (#1674).
 */
class LangfuseErrorBodyRedactionSpec extends AnyFlatSpec with Matchers {

  private def events = Seq(ujson.Obj("type" -> "trace-create", "body" -> ujson.Obj("id" -> "trace-1")))

  "DefaultLangfuseBatchSender" should "redact credentials echoed in an error body before logging it" in {
    val sender = new DefaultLangfuseBatchSender(new MockHttpClient(HttpResponse(500, EchoedCredentials.Text)), () => ())
    val (_, lines) = logged(
      sender.sendBatch(events, LangfuseHttpApiCaller("https://cloud.langfuse.com", "pk-lf-test", "sk-lf-test"))
    )
    lines.exists(_.contains("[REDACTED]")) shouldBe true
    lines.flatMap(leaked) shouldBe empty
  }

  "LangfuseTracing" should "redact credentials echoed in an error body before logging it" in {
    val tracing = new LangfuseTracing(
      langfuseUrl = "https://cloud.langfuse.com",
      publicKey = "pk-test",
      secretKey = "sk-test",
      environment = "test",
      release = "v1",
      version = "1",
      httpClient = new MockHttpClient(HttpResponse(500, EchoedCredentials.Text)),
      restoreInterrupt = () => ()
    )
    val (result, lines) = logged(tracing.traceEvent(TraceEvent.AgentInitialized("query", Vector.empty)))
    result.isLeft shouldBe true
    lines.exists(_.contains("[REDACTED]")) shouldBe true
    lines.flatMap(leaked) shouldBe empty
  }

  "LangfuseIngestionResponse.summary" should "redact credentials echoed in a rejection message" in {
    val summary = LangfuseIngestionResponse.summary(Seq(Rejection("bad-1", Some(400), EchoedCredentials.Text)), 1)
    summary should include("bad-1")
    summary should include("[REDACTED]")
    leaked(summary) shouldBe empty
  }
}
