package org.llm4s.trace

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The OTLP/JSON encoding, the endpoint URLs, and what a Langfuse response says was rejected (#2883). */
class LangfuseExportSpec extends AnyFlatSpec with Matchers with EitherValues {

  "LangfuseOtlp.baseUrl" should "read the host from a URL with or without one of Langfuse's API paths" in {
    Seq(
      "https://cloud.langfuse.com",
      "https://cloud.langfuse.com/",
      " https://cloud.langfuse.com ",
      "https://cloud.langfuse.com/api/public/ingestion",
      "https://cloud.langfuse.com/api/public/otel",
      "https://cloud.langfuse.com/api/public/otel/v1/traces",
      "https://cloud.langfuse.com/api/public/scores/"
    ).foreach(url => LangfuseOtlp.baseUrl(url) shouldBe "https://cloud.langfuse.com")
    LangfuseOtlp.baseUrl("http://localhost:3000/langfuse") shouldBe "http://localhost:3000/langfuse"
    LangfuseOtlp.scoresUrl("https://h.example") shouldBe "https://h.example/api/public/scores"
  }

  "LangfuseOtlp.anyValue" should "encode each JSON value as an OTLP AnyValue" in {
    LangfuseOtlp.anyValue(ujson.Str("s")) shouldBe ujson.Obj("stringValue" -> "s")
    LangfuseOtlp.anyValue(ujson.Bool(true)) shouldBe ujson.Obj("boolValue" -> true)
    // a 64-bit integer is a decimal string
    LangfuseOtlp.anyValue(ujson.Num(42)) shouldBe ujson.Obj("intValue" -> "42")
    LangfuseOtlp.anyValue(ujson.Num(0.5)) shouldBe ujson.Obj("doubleValue" -> 0.5)
    LangfuseOtlp.anyValue(ujson.Num(Double.NaN)) shouldBe ujson.Obj("stringValue" -> "NaN")
    LangfuseOtlp.anyValue(ujson.Num(1e300)) shouldBe ujson.Obj("doubleValue" -> 1e300)
    LangfuseOtlp.anyValue(ujson.Arr("a", 1)) shouldBe
      ujson.Obj(
        "arrayValue" -> ujson.Obj("values" -> ujson.Arr(ujson.Obj("stringValue" -> "a"), ujson.Obj("intValue" -> "1")))
      )
    LangfuseOtlp.anyValue(ujson.Obj("k" -> 1)) shouldBe ujson.Obj("stringValue" -> """{"k":1}""")
    LangfuseOtlp.anyValue(ujson.Null) shouldBe ujson.Obj("stringValue" -> "null")
  }

  "LangfuseOtlp.unixNanos" should "write nanoseconds since the epoch as a decimal string" in {
    LangfuseOtlp.unixNanos(Instant.parse("2026-11-24T12:00:00.000000123Z")) shouldBe "1795521600000000123"
    LangfuseOtlp.unixNanos(Instant.EPOCH) shouldBe "0"
  }

  "LangfuseOtlp.span" should "flatten metadata, skip nulls, and never end before it starts" in {
    val start = Instant.parse("2026-11-24T12:00:05Z")
    val span = LangfuseOtlp.span(
      LangfuseOtlp.Observation(
        TraceContext.rootFor("x"),
        Some("00f067aa0ba902b7"),
        "n",
        LangfuseOtlp.ObservationType.Event,
        start,
        start.minusSeconds(1),
        metadata = ujson.Obj("s" -> "v", "n" -> 1, "b" -> false, "o" -> ujson.Obj("k" -> "v"), "z" -> ujson.Null)
      ),
      LangfuseOtlp.Deployment("e", "r", "v")
    )
    val decoded = SentSpan
      .all(
        ujson
          .Obj(
            "resourceSpans" -> ujson.Arr(ujson.Obj("scopeSpans" -> ujson.Arr(ujson.Obj("spans" -> ujson.Arr(span)))))
          )
          .render()
      )
      .head
    decoded.parentSpanId shouldBe Some("00f067aa0ba902b7")
    decoded.endTime shouldBe start
    decoded.meta("s") shouldBe ujson.Str("v")
    decoded.meta("n") shouldBe ujson.Num(1)
    decoded.meta("b") shouldBe ujson.Bool(false)
    decoded.meta("o") shouldBe ujson.Str("""{"k":"v"}""")
    decoded.hasMeta("z") shouldBe false
    decoded.has("langfuse.observation.input") shouldBe false
    decoded.status shouldBe None
  }

  "LangfuseExport.traces" should "count the spans of a request, and tolerate a malformed one" in {
    LangfuseExport.traces("https://h", ujson.Obj()).items shouldBe 0
    LangfuseExport.traces("https://h", ujson.Obj("resourceSpans" -> ujson.Arr(1, ujson.Obj()))).items shouldBe 0
  }

  "LangfuseExport.otlpRejection" should "read a partial success" in {
    LangfuseExport.otlpRejection("", 3).value shouldBe None
    LangfuseExport.otlpRejection("{}", 3).value shouldBe None
    LangfuseExport.otlpRejection("""{"partialSuccess":{"rejectedSpans":"0"}}""", 3).value shouldBe None
    LangfuseExport.otlpRejection("""{"partialSuccess":{"rejectedSpans":"2","errorMessage":"bad"}}""", 3).value shouldBe
      Some("Langfuse rejected 2 of 3 spans: bad")
    LangfuseExport.otlpRejection("""{"partialSuccess":{"rejectedSpans":1}}""", 3).value shouldBe
      Some("Langfuse rejected 1 of 3 spans: no message")
    LangfuseExport.otlpRejection("not json", 3).left.value should include("unreadable body")
    LangfuseExport.otlpRejection("[1]", 3).left.value should include("not a JSON object")
  }

  "LangfuseExport.scoreRejection" should "read the scores endpoint's 207" in {
    LangfuseExport.scoreRejection("""{"accepted":2,"rejected":0,"errors":[]}""", 2).value shouldBe None
    LangfuseExport
      .scoreRejection("""{"accepted":1,"rejected":1,"errors":[{"message":"bad value"}]}""", 2)
      .value shouldBe Some("Langfuse rejected 1 of 2 scores: bad value")
    // without counts, the errors are counted
    LangfuseExport.scoreRejection("""{"errors":[{},{"message":{"k":1}}]}""", 2).value shouldBe
      Some("""Langfuse rejected 2 of 2 scores: no message, {"k":1}""")
    LangfuseExport.scoreRejection("nope", 1).isLeft shouldBe true
  }
}
