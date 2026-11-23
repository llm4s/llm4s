package org.llm4s.trace

import org.llm4s.security.Secret
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, UsageSummary, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * Who can read a trace, and whose it is (#2828): `public` on every `trace-create` is false unless
 * `llm4s.tracing.langfuse.public` says otherwise - Langfuse serves a public trace to anyone holding its URL, signed in
 * or not - and `userId` is the run's own, absent when the run has none.
 */
class LangfuseTracePrivacySpec extends AnyFlatSpec with Matchers {

  private val messages = Seq(UserMessage("my account number is 1234"), AssistantMessage("noted"))

  private def run(userId: Option[String], span: Option[TraceSpan]) =
    TraceEvent.AgentRunEnded(
      "thread-1",
      "run-1",
      "assistant",
      "completed",
      messages,
      UsageSummary(),
      span = span,
      userId = userId
    )

  private val placed = Some(TraceSpan(TraceContext.rootFor("run:run-1"), None, Instant.parse("2026-10-21T10:00:00Z")))

  /** The bodies of the `trace-create` events `config`'s backend sends for `event`. */
  private def traces(config: LangfuseConfig, event: TraceEvent): Seq[ujson.Value] = {
    val http    = new MockHttpClient(HttpResponse(200, """{"successes":1}"""))
    val tracing = LangfuseTracing.from(config, http)
    tracing.traceEvent(event) shouldBe Right(())
    val batch = ujson.read(http.lastBody.getOrElse(fail("nothing was sent")))("batch").arr.toSeq
    batch.filter(_("type").str == "trace-create").map(_("body"))
  }

  private val keys = LangfuseConfig(publicKey = Some(Secret("pk-lf-test")), secretKey = Some(Secret("sk-lf-test")))
  private val sharedByLink = LangfuseConfig.fromExtras(
    Map("publicKey" -> "pk-lf-test", "secretKey" -> "sk-lf-test", "public" -> "true")
  )

  private val everyTrace: Seq[(String, Option[String] => TraceEvent)] = Seq(
    "a placed agent run"        -> (user => run(user, placed)),
    "an unplaced agent run"     -> (user => run(user, None)),
    "an agent's initialisation" -> (_ => TraceEvent.AgentInitialized("what is my balance?", Vector("lookup")))
  )

  everyTrace.foreach { case (name, event) =>
    s"The trace of $name" should "be private by default" in {
      val bodies = traces(keys, event(None))
      bodies should have size 1
      bodies.head("public").bool shouldBe false
    }

    it should "be public when llm4s.tracing.langfuse.public is true" in {
      val bodies = traces(sharedByLink, event(None))
      bodies should have size 1
      bodies.head("public").bool shouldBe true
    }

    it should "name no user when the run has none" in {
      traces(keys, event(None)).head.obj.contains("userId") shouldBe false
      traces(sharedByLink, event(None)).head.obj.contains("userId") shouldBe false
    }
  }

  "The trace of an agent run" should "carry the run's user id, placed or not" in {
    traces(keys, run(Some("alice"), placed)).head("userId").str shouldBe "alice"
    traces(keys, run(Some("alice"), None)).head("userId").str shouldBe "alice"
  }

  "LangfuseConfig" should "read public only from an explicit true" in {
    LangfuseConfig().publicTraces shouldBe false
    LangfuseConfig.fromExtras(Map.empty).publicTraces shouldBe false
    LangfuseConfig.fromExtras(Map("public" -> " TRUE ")).publicTraces shouldBe true
    LangfuseConfig.fromExtras(Map("public" -> "false")).publicTraces shouldBe false
    LangfuseConfig.fromExtras(Map("public" -> "yes")).publicTraces shouldBe false
  }
}
