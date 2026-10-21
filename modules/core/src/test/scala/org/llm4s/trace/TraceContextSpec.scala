package org.llm4s.trace

import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class TraceContextSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val span  = "00f067aa0ba902b7"

  "TraceContext" should "write and read a W3C traceparent value" in {
    val context = TraceContext(trace, span)
    context.traceParent shouldBe s"00-$trace-$span-01"
    TraceContext.parse(context.traceParent).value shouldBe context
    TraceContext.parse(s"00-$trace-$span-00").value.sampled shouldBe false
    TraceContext(trace, span, sampled = false).traceParent shouldBe s"00-$trace-$span-00"
  }

  it should "lower-case upper-case hex, and read a later version's extra fields" in {
    TraceContext.parse(s"00-${trace.toUpperCase}-$span-01").value.traceId shouldBe trace
    TraceContext.parse(s"01-$trace-$span-03-extra").value shouldBe TraceContext(trace, span)
  }

  it should "refuse an invalid value" in {
    val invalid = Seq(
      "",
      "garbage",
      s"00-$trace-$span",
      s"00-$trace-$span-01-extra",
      s"ff-$trace-$span-01",
      s"00-${"0" * 32}-$span-01",
      s"00-$trace-${"0" * 16}-01",
      s"00-${trace.take(31)}-$span-01",
      s"00-$trace-${span}0-01",
      s"00-${trace.dropRight(1)}g-$span-01",
      null
    )
    invalid.foreach(value => TraceContext.parse(value).isLeft shouldBe true)
    an[IllegalArgumentException] should be thrownBy TraceContext("abc", span)
    TraceContext.of(trace, "xyz").left.value.message should include("spanId")
  }

  it should "derive the same span for the same key in the same trace, and another for another key or trace" in {
    val context = TraceContext(trace, span)
    val a       = context.spanFor("tool:call-1")
    a.traceId shouldBe trace
    (a.spanId should have).length(16)
    a shouldBe TraceContext(trace, "1111111111111111").spanFor("tool:call-1")
    a should not be context.spanFor("tool:call-2")
    a.spanId should not be TraceContext("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", span).spanFor("tool:call-1").spanId
    context.withSampled(false).spanFor("k").sampled shouldBe false
  }

  it should "derive a root context from a key, and draw a random one" in {
    TraceContext.rootFor("run-1") shouldBe TraceContext.rootFor("run-1")
    TraceContext.rootFor("run-1").traceId should not be TraceContext.rootFor("run-2").traceId
    TraceContext.parse(TraceContext.rootFor("run-1").traceParent).isRight shouldBe true
    val r = TraceContext.random()
    TraceContext.parse(r.traceParent).value shouldBe r
    r should not be TraceContext.random()
  }

  it should "encode as its traceparent string in JSON" in {
    val context = TraceContext(trace, span)
    upickle.default.write(context) shouldBe s"\"00-$trace-$span-01\""
    upickle.default.read[TraceContext](upickle.default.write(context)) shouldBe context
    an[Exception] should be thrownBy upickle.default.read[TraceContext]("\"nope\"")
  }

  "TraceEvent" should "carry no span by default, and name a placed event's ids in its JSON" in {
    val context = TraceContext(trace, span)
    val placed  = TraceSpan(context.spanFor("e"), Some(context), Instant.EPOCH)
    TraceEvent.CustomEvent("x", ujson.Obj()).span shouldBe None
    TraceEvent.AgentInitialized("q", Vector.empty).span shouldBe None

    val custom = TraceEvent.CustomEvent("x", ujson.Obj(), span = Some(placed)).toJson
    custom("trace_id").str shouldBe trace
    custom("span_id").str shouldBe placed.context.spanId
    custom("parent_span_id").str shouldBe span

    val run =
      TraceEvent.AgentRunEnded("t", "r", "a", "completed", Nil, UsageSummary(), span = Some(placed.withParent(None)))
    run.toJson.obj.contains("parent_span_id") shouldBe false
    run.toJson("trace_id").str shouldBe trace
    TraceEvent.TokenUsageRecorded(TokenUsage(1, 1, 2), "m", "op", span = Some(placed)).toJson("span_id").str shouldBe
      placed.context.spanId
    TraceEvent.ErrorOccurred(new RuntimeException("x"), "c", span = Some(placed)).span shouldBe Some(placed)
  }
