package org.llm4s.trace

import org.llm4s.llmconnect.model.{ EmbeddingUsage, TokenUsage, UsageSummary, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/** The growth-safe shape of every [[TraceEvent]] case: `apply` defaults and one `with*` setter per field (#2377). */
class TraceEventSettersSpec extends AnyFlatSpec with Matchers {

  private val t0   = Instant.parse("2024-01-15T10:30:00Z")
  private val t1   = Instant.parse("2025-02-16T11:31:01Z")
  private val root = TraceContext.rootFor("run")
  private val span = TraceSpan(root.spanFor("e"), Some(root), t0)

  /** `apply` stamps the current time when no timestamp is given. */
  private def stampedNow(ts: Instant): Unit = {
    val now = Instant.now()
    ts.isAfter(now) shouldBe false
    ts.isBefore(now.minusSeconds(60)) shouldBe false
  }

  "AgentInitialized" should "default its timestamp and set every field" in {
    stampedNow(TraceEvent.AgentInitialized("q", Vector("a")).timestamp)
    val e = TraceEvent.AgentInitialized("q", Vector("a"), t0)
    val c = e.withQuery("q2").withTools(Vector("b")).withTimestamp(t1)
    c shouldBe TraceEvent.AgentInitialized("q2", Vector("b"), t1)
    e.query shouldBe "q"
  }

  "CompletionReceived" should "default its timestamp and set every field" in {
    stampedNow(TraceEvent.CompletionReceived("id", "m", 0, "c").timestamp)
    val e = TraceEvent.CompletionReceived("id", "m", 0, "c", t0)
    e.withId("id2").withModel("m2").withToolCalls(2).withContent("c2").withTimestamp(t1) shouldBe
      TraceEvent.CompletionReceived("id2", "m2", 2, "c2", t1)
  }

  "ToolExecuted" should "default its timestamp and set every field" in {
    stampedNow(TraceEvent.ToolExecuted("n", "i", "o", 1.milli, true).timestamp)
    val e = TraceEvent.ToolExecuted("n", "i", "o", 1.milli, true, t0)
    e.withName("n2")
      .withInput("i2")
      .withOutput("o2")
      .withDuration(2.millis)
      .withSuccess(false)
      .withTimestamp(t1) shouldBe TraceEvent.ToolExecuted("n2", "i2", "o2", 2.millis, false, t1)
  }

  "ErrorOccurred" should "default its timestamp and span and set every field" in {
    val boom = new RuntimeException("boom")
    val d    = TraceEvent.ErrorOccurred(boom, "ctx")
    stampedNow(d.timestamp)
    d.span shouldBe None
    val other = new IllegalStateException("other")
    val e     = TraceEvent.ErrorOccurred(boom, "ctx", t0)
    val c     = e.withError(other).withContext("ctx2").withTimestamp(t1).withSpan(span)
    c shouldBe TraceEvent.ErrorOccurred(other, "ctx2", t1, Some(span))
    c.withSpan(None).span shouldBe None
    c.withSpan(Some(span)).span shouldBe Some(span)
  }

  "TokenUsageRecorded" should "default its timestamp and span and set every field" in {
    val d = TraceEvent.TokenUsageRecorded(TokenUsage(1, 2, 3), "m", "op")
    stampedNow(d.timestamp)
    d.span shouldBe None
    val c = TraceEvent
      .TokenUsageRecorded(TokenUsage(1, 2, 3), "m", "op", t0)
      .withUsage(TokenUsage(4, 5, 9))
      .withModel("m2")
      .withOperation("op2")
      .withTimestamp(t1)
      .withSpan(span)
    c shouldBe TraceEvent.TokenUsageRecorded(TokenUsage(4, 5, 9), "m2", "op2", t1, Some(span))
    c.withSpan(None).span shouldBe None
    c.withSpan(Some(span)).span shouldBe Some(span)
  }

  "AgentRunEnded" should "default its timestamp, parents and span and set every field" in {
    val d = TraceEvent.AgentRunEnded("th", "r", "a", "completed", Seq.empty, UsageSummary())
    stampedNow(d.timestamp)
    d.parentThreadId shouldBe None
    d.parentRunId shouldBe None
    d.span shouldBe None
    val usage = UsageSummary().add("m", TokenUsage(1, 2, 3), None)
    val msgs  = Seq(UserMessage("hi"))
    val c = d
      .withThreadId("th2")
      .withRunId("r2")
      .withAgent("a2")
      .withStatus("failed")
      .withMessages(msgs)
      .withUsage(usage)
      .withTimestamp(t1)
      .withParentThreadId("pt")
      .withParentRunId("pr")
      .withSpan(span)
    c shouldBe TraceEvent.AgentRunEnded(
      "th2",
      "r2",
      "a2",
      "failed",
      msgs,
      usage,
      t1,
      Some("pt"),
      Some("pr"),
      Some(span)
    )
    val cleared = c.withParentThreadId(None).withParentRunId(None).withSpan(None)
    (cleared.parentThreadId, cleared.parentRunId, cleared.span) shouldBe ((None, None, None))
    val reset = cleared.withParentThreadId(Some("pt")).withParentRunId(Some("pr")).withSpan(Some(span))
    reset shouldBe c
  }

  "CustomEvent" should "default its timestamp and span and set every field" in {
    val d = TraceEvent.CustomEvent("n", ujson.Obj())
    stampedNow(d.timestamp)
    d.span shouldBe None
    val c = d.withName("n2").withData(ujson.Num(1)).withTimestamp(t1).withSpan(span)
    c shouldBe TraceEvent.CustomEvent("n2", ujson.Num(1), t1, Some(span))
    c.withSpan(None).span shouldBe None
    c.withSpan(Some(span)).span shouldBe Some(span)
  }

  "EmbeddingUsageRecorded" should "default its timestamp and set every field" in {
    val d = TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(1, 1), "m", "query", 1)
    stampedNow(d.timestamp)
    d.withUsage(EmbeddingUsage(2, 2))
      .withModel("m2")
      .withOperation("indexing")
      .withInputCount(3)
      .withTimestamp(t1) shouldBe TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(2, 2), "m2", "indexing", 3, t1)
  }

  "CostRecorded" should "default its timestamp and set every field" in {
    val d = TraceEvent.CostRecorded(0.1, "m", "op", 1, "total")
    stampedNow(d.timestamp)
    d.withCostUsd(0.2)
      .withModel("m2")
      .withOperation("op2")
      .withTokenCount(2)
      .withCostType("completion")
      .withTimestamp(t1) shouldBe TraceEvent.CostRecorded(0.2, "m2", "op2", 2, "completion", t1)
  }

  "RAGOperationCompleted" should "default its optional metrics and timestamp and set every field" in {
    val d = TraceEvent.RAGOperationCompleted("search", 1.milli)
    stampedNow(d.timestamp)
    (d.embeddingTokens, d.llmPromptTokens, d.llmCompletionTokens, d.totalCostUsd) shouldBe ((None, None, None, None))
    val c = d
      .withOperation("answer")
      .withDuration(2.millis)
      .withEmbeddingTokens(1)
      .withLlmPromptTokens(2)
      .withLlmCompletionTokens(3)
      .withTotalCostUsd(0.5)
      .withTimestamp(t1)
    c shouldBe TraceEvent.RAGOperationCompleted("answer", 2.millis, Some(1), Some(2), Some(3), Some(0.5), t1)
    val cleared =
      c.withEmbeddingTokens(None).withLlmPromptTokens(None).withLlmCompletionTokens(None).withTotalCostUsd(None)
    cleared shouldBe TraceEvent.RAGOperationCompleted("answer", 2.millis, timestamp = t1)
    cleared
      .withEmbeddingTokens(Some(1))
      .withLlmPromptTokens(Some(2))
      .withLlmCompletionTokens(Some(3))
      .withTotalCostUsd(Some(0.5)) shouldBe c
  }

  "ImageGenerationCompleted" should "default cost, success, error and timestamp and set every field" in {
    val d = TraceEvent.ImageGenerationCompleted("m", "p", "generate", 1, "1024x1024", "hd", 1.milli)
    stampedNow(d.timestamp)
    (d.costUsd, d.success, d.errorMessage) shouldBe ((None, true, None))
    val c = d
      .withModel("m2")
      .withProvider("p2")
      .withOperation("edit")
      .withImageCount(2)
      .withSize("512x512")
      .withQuality("standard")
      .withDuration(2.millis)
      .withCostUsd(0.04)
      .withSuccess(false)
      .withErrorMessage("bad")
      .withTimestamp(t1)
    c shouldBe TraceEvent.ImageGenerationCompleted(
      "m2",
      "p2",
      "edit",
      2,
      "512x512",
      "standard",
      2.millis,
      Some(0.04),
      false,
      Some("bad"),
      t1
    )
    val cleared = c.withCostUsd(None).withErrorMessage(None)
    (cleared.costUsd, cleared.errorMessage) shouldBe ((None, None))
    cleared.withCostUsd(Some(0.04)).withErrorMessage(Some("bad")) shouldBe c
  }

  "CacheHit" should "default its timestamp and set every field" in {
    val d = TraceEvent.CacheHit(0.9, 0.8)
    stampedNow(d.timestamp)
    d.withSimilarity(0.95).withThreshold(0.85).withTimestamp(t1) shouldBe TraceEvent.CacheHit(0.95, 0.85, t1)
  }

  "CacheMiss" should "default its timestamp and set every field" in {
    val d = TraceEvent.CacheMiss(TraceEvent.CacheMissReason.LowSimilarity)
    stampedNow(d.timestamp)
    d.withReason(TraceEvent.CacheMissReason.TtlExpired).withTimestamp(t1) shouldBe
      TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired, t1)
  }

  "TraceEvent cases" should "still match positionally" in {
    TraceEvent.CacheHit(0.9, 0.8, t0) match {
      case TraceEvent.CacheHit(s, th, ts) => (s, th, ts) shouldBe ((0.9, 0.8, t0))
    }
  }
}
