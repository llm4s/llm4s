package org.llm4s.trace

import org.llm4s.security.Secret
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.testutil.EchoedCredentials
import org.llm4s.testutil.EchoedCredentials.{ leaked, logged }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A Langfuse error body, or a partial-success message, can echo the request's credentials. Neither may be logged
 * or put into an error in the clear (#1674).
 */
class LangfuseErrorBodyRedactionSpec extends AnyFlatSpec with Matchers {

  private def config = LangfuseHttpApiCaller("https://cloud.langfuse.com", Secret("pk-lf-test"), Secret("sk-lf-test"))

  "DefaultLangfuseBatchSender" should "redact credentials echoed in an error body before logging it" in {
    val sender = new DefaultLangfuseBatchSender(new MockHttpClient(HttpResponse(500, EchoedCredentials.Text)), () => ())
    val (_, lines) =
      logged(sender.sendTraces(LangfuseOtlp.request(Seq.empty, LangfuseOtlp.Deployment("e", "r", "v"), "s"), config))
    lines.exists(_.contains("[REDACTED]")) shouldBe true
    lines.flatMap(leaked) shouldBe empty
  }

  it should "redact credentials echoed in a score rejection before logging it" in {
    val body =
      ujson.Obj("accepted" -> 0, "rejected" -> 1, "errors" -> ujson.Arr(ujson.Obj("message" -> EchoedCredentials.Text)))
    val sender     = new DefaultLangfuseBatchSender(new MockHttpClient(HttpResponse(207, body.render())), () => ())
    val (_, lines) = logged(sender.sendScores(Seq(ujson.Obj("traceId" -> "t", "name" -> "n", "value" -> 1)), config))
    lines.exists(_.contains("rejected 1 of 1 scores")) shouldBe true
    lines.flatMap(leaked) shouldBe empty
  }

  "LangfuseTracing" should "redact credentials echoed in an error body before logging it" in {
    val tracing = new LangfuseTracing(
      langfuseUrl = "https://cloud.langfuse.com",
      publicKey = Secret("pk-test"),
      secretKey = Secret("sk-test"),
      environment = "test",
      release = "v1",
      version = "1",
      httpClient = new MockHttpClient(HttpResponse(500, EchoedCredentials.Text)),
      restoreInterrupt = () => ()
    )
    val (result, lines) = logged(tracing.traceEvent(TraceEvent.CustomEvent("query", ujson.Obj())))
    result.isLeft shouldBe true
    lines.exists(_.contains("[REDACTED]")) shouldBe true
    lines.flatMap(leaked) shouldBe empty
  }

  "LangfuseExport.otlpRejection" should "redact credentials echoed in a partial-success message" in {
    val body =
      ujson.Obj("partialSuccess" -> ujson.Obj("rejectedSpans" -> "1", "errorMessage" -> EchoedCredentials.Text))
    val summary = LangfuseExport.otlpRejection(body.render(), 1).toOption.flatten.getOrElse(fail("no rejection"))
    summary should include("[REDACTED]")
    leaked(summary) shouldBe empty
  }
}
