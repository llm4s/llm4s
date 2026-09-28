package org.llm4s.trace.model

import org.llm4s.trace.model.TraceModelJson._
import org.llm4s.types.TraceId
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant

class TraceModelPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val genId: Gen[String] = Gen.uuid.map(_.toString)

  val genTraceId: Gen[TraceId] = genId.map(TraceId(_))

  val genSpanId: Gen[SpanId] = genId.map(SpanId(_))

  val genInstant: Gen[Instant] =
    Gen.choose(0L, 4102444800000L).map(Instant.ofEpochMilli)

  val genSpanKind: Gen[SpanKind] =
    Gen.oneOf(SpanKind.all)

  val genSpanStatusOk: Gen[SpanStatus]      = Gen.const(SpanStatus.Ok)
  val genSpanStatusRunning: Gen[SpanStatus] = Gen.const(SpanStatus.Running)
  val genSpanStatusError: Gen[SpanStatus]   = Gen.alphaStr.map(SpanStatus.Error(_))

  val genSpanStatus: Gen[SpanStatus] =
    Gen.oneOf(genSpanStatusOk, genSpanStatusRunning, genSpanStatusError)

  // SpanValues that round-trip losslessly through JSON
  val genStringValue: Gen[SpanValue]  = Gen.alphaStr.map(SpanValue.StringValue(_))
  val genLongValue: Gen[SpanValue]    = Gen.long.map(SpanValue.LongValue(_))
  val genDoubleValue: Gen[SpanValue]  = Gen.double.map(SpanValue.DoubleValue(_))
  val genBooleanValue: Gen[SpanValue] = Gen.oneOf(true, false).map(SpanValue.BooleanValue(_))
  val genStringListValue: Gen[SpanValue] =
    Gen.listOf(Gen.alphaStr).map(SpanValue.StringListValue(_))

  val genAnySpanValue: Gen[SpanValue] =
    Gen.oneOf(genStringValue, genLongValue, genDoubleValue, genBooleanValue, genStringListValue)

  val genRoundTrippableAttributes: Gen[Map[String, SpanValue]] =
    Gen.mapOf(Gen.zip(genNonEmptyString, genAnySpanValue))

  val genSpanEvent: Gen[SpanEvent] =
    for {
      name  <- genNonEmptyString
      ts    <- genInstant
      attrs <- genRoundTrippableAttributes
    } yield SpanEvent(name, ts, attrs)

  val genSpan: Gen[Span] =
    for {
      spanId       <- genSpanId
      traceId      <- genTraceId
      parentSpanId <- Gen.option(genSpanId)
      name         <- genNonEmptyString
      kind         <- genSpanKind
      startTime    <- genInstant
      endOffset    <- Gen.choose(0L, 60000L)
      hasEnd       <- Gen.oneOf(true, false)
      endTime = if (hasEnd) Some(startTime.plusMillis(endOffset)) else None
      status <- genSpanStatus
      attrs  <- genRoundTrippableAttributes
      events <- Gen.listOf(genSpanEvent)
    } yield Span(spanId, traceId, parentSpanId, name, kind, startTime, endTime, status, attrs, events)

  val genTrace: Gen[Trace] =
    for {
      traceId    <- genTraceId
      rootSpanId <- Gen.option(genSpanId)
      metadata   <- Gen.mapOf(Gen.zip(genNonEmptyString, Gen.alphaStr))
      startTime  <- genInstant
      endOffset  <- Gen.choose(0L, 60000L)
      hasEnd     <- Gen.oneOf(true, false)
      endTime = if (hasEnd) Some(startTime.plusMillis(endOffset)) else None
      status <- genSpanStatus
    } yield Trace(traceId, rootSpanId, metadata, startTime, endTime, status)

  // ---- SpanValue invariants ----

  "SpanValue type accessors" should "be mutually exclusive for StringValue" in {
    forAll(Gen.alphaStr) { s =>
      val sv = SpanValue.StringValue(s)
      sv.asString shouldBe defined
      sv.asLong shouldBe empty
      sv.asDouble shouldBe empty
      sv.asBoolean shouldBe empty
      sv.asStringList shouldBe empty
    }
  }

  it should "be mutually exclusive for LongValue" in {
    forAll(Gen.long) { n =>
      val sv = SpanValue.LongValue(n)
      sv.asString shouldBe empty
      sv.asLong shouldBe defined
      sv.asDouble shouldBe empty
      sv.asBoolean shouldBe empty
      sv.asStringList shouldBe empty
    }
  }

  it should "be mutually exclusive for DoubleValue" in {
    forAll(Gen.double) { d =>
      val sv = SpanValue.DoubleValue(d)
      sv.asString shouldBe empty
      sv.asLong shouldBe empty
      sv.asDouble shouldBe defined
      sv.asBoolean shouldBe empty
      sv.asStringList shouldBe empty
    }
  }

  it should "be mutually exclusive for BooleanValue" in {
    forAll(Gen.oneOf(true, false)) { b =>
      val sv = SpanValue.BooleanValue(b)
      sv.asString shouldBe empty
      sv.asLong shouldBe empty
      sv.asDouble shouldBe empty
      sv.asBoolean shouldBe defined
      sv.asStringList shouldBe empty
    }
  }

  it should "be mutually exclusive for StringListValue" in {
    forAll(Gen.listOf(Gen.alphaStr)) { lst =>
      val sv = SpanValue.StringListValue(lst)
      sv.asString shouldBe empty
      sv.asLong shouldBe empty
      sv.asDouble shouldBe empty
      sv.asBoolean shouldBe empty
      sv.asStringList shouldBe defined
    }
  }

  it should "preserve the value for StringValue" in {
    forAll(Gen.alphaStr)(s => SpanValue.StringValue(s).asString shouldBe Some(s))
  }

  it should "preserve the value for LongValue" in {
    forAll(Gen.long)(n => SpanValue.LongValue(n).asLong shouldBe Some(n))
  }

  it should "preserve the value for BooleanValue" in {
    forAll(Gen.oneOf(true, false))(b => SpanValue.BooleanValue(b).asBoolean shouldBe Some(b))
  }

  // ---- SpanKind JSON round-trip ----

  "SpanKind JSON serialisation" should "produce distinct strings for each variant" in {
    val serialised = SpanKind.all.map(_.toJson)
    serialised.distinct should have size SpanKind.all.size.toLong
  }

  it should "round-trip every variant" in {
    forAll(genSpanKind)(kind => TraceModelJson.parseSpanKind(kind.toJson) shouldBe Right(kind))
  }

  // ---- SpanStatus JSON round-trip ----

  "SpanStatus JSON serialisation" should "round-trip Ok" in {
    TraceModelJson.parseSpanStatus(SpanStatus.Ok.toJson) shouldBe Right(SpanStatus.Ok)
  }

  it should "round-trip Running" in {
    TraceModelJson.parseSpanStatus(SpanStatus.Running.toJson) shouldBe Right(SpanStatus.Running)
  }

  it should "round-trip Error with any message" in {
    forAll(Gen.alphaStr) { msg =>
      val status = SpanStatus.Error(msg)
      TraceModelJson.parseSpanStatus(status.toJson) shouldBe Right(status)
    }
  }

  // ---- SpanValue JSON round-trip (lossless types only) ----

  "SpanValue JSON serialisation" should "round-trip StringValue" in {
    forAll(Gen.alphaStr) { s =>
      val sv = SpanValue.StringValue(s)
      TraceModelJson.parseSpanValue(sv.toJson) shouldBe Right(sv)
    }
  }

  it should "round-trip BooleanValue" in {
    forAll(Gen.oneOf(true, false)) { b =>
      val sv = SpanValue.BooleanValue(b)
      TraceModelJson.parseSpanValue(sv.toJson) shouldBe Right(sv)
    }
  }

  it should "round-trip StringListValue" in {
    forAll(Gen.listOf(Gen.alphaStr)) { lst =>
      val sv = SpanValue.StringListValue(lst)
      TraceModelJson.parseSpanValue(sv.toJson) shouldBe Right(sv)
    }
  }

  it should "round-trip DoubleValue" in {
    forAll(Gen.double.suchThat(d => !d.isNaN && !d.isInfinite)) { d =>
      val sv     = SpanValue.DoubleValue(d)
      val parsed = TraceModelJson.parseSpanValue(sv.toJson)
      parsed.map(_.asDouble) shouldBe Right(Some(d))
    }
  }

  it should "round-trip LongValue" in {
    forAll(Gen.long) { n =>
      val sv     = SpanValue.LongValue(n)
      val parsed = TraceModelJson.parseSpanValue(sv.toJson)
      parsed.map(_.asLong) shouldBe Right(Some(n))
    }
  }

  // ---- Span builder invariants ----

  "Span.start" should "always produce Running status" in {
    forAll(genTraceId, genNonEmptyString, genSpanKind) { (traceId, name, kind) =>
      Span.start(traceId, name, kind).status shouldBe SpanStatus.Running
    }
  }

  it should "always produce no endTime" in {
    forAll(genTraceId, genNonEmptyString, genSpanKind) { (traceId, name, kind) =>
      Span.start(traceId, name, kind).endTime shouldBe empty
    }
  }

  it should "preserve traceId, name, and kind" in {
    forAll(genTraceId, genNonEmptyString, genSpanKind) { (traceId, name, kind) =>
      val span = Span.start(traceId, name, kind)
      span.traceId shouldBe traceId
      span.name shouldBe name
      span.kind shouldBe kind
    }
  }

  "Span.withAttribute" should "add the key without modifying other attributes" in {
    forAll(genSpan, genNonEmptyString, genAnySpanValue) { (span, key, value) =>
      val updated = span.withAttribute(key, value)
      updated.attributes should contain(key -> value)
      // all pre-existing keys (except the potentially overwritten one) still present
      span.attributes.foreach { case (k, v) =>
        if (k != key) updated.attributes should contain(k -> v)
      }
    }
  }

  it should "overwrite an existing key" in {
    forAll(genSpan, genNonEmptyString, genAnySpanValue, genAnySpanValue) { (span, key, v1, v2) =>
      val step1 = span.withAttribute(key, v1)
      val step2 = step1.withAttribute(key, v2)
      step2.attributes(key) shouldBe v2
    }
  }

  "Span.withEvent" should "append exactly one event" in {
    forAll(genSpan, genSpanEvent) { (span, event) =>
      val updated = span.withEvent(event)
      updated.events should have size (span.events.size.toLong + 1L)
      updated.events.last shouldBe event
    }
  }

  it should "preserve existing events" in {
    forAll(genSpan, genSpanEvent) { (span, event) =>
      val updated = span.withEvent(event)
      span.events.foreach(e => updated.events should contain(e))
    }
  }

  // ---- Span JSON round-trip ----

  "Span JSON serialisation" should "round-trip all fields" in {
    forAll(genSpan) { span =>
      val parsed = TraceModelJson.parseSpan(span.toJson)
      parsed.map(_.spanId) shouldBe Right(span.spanId)
      parsed.map(_.traceId) shouldBe Right(span.traceId)
      parsed.map(_.parentSpanId) shouldBe Right(span.parentSpanId)
      parsed.map(_.name) shouldBe Right(span.name)
      parsed.map(_.kind) shouldBe Right(span.kind)
      parsed.map(_.startTime) shouldBe Right(span.startTime)
      parsed.map(_.endTime) shouldBe Right(span.endTime)
      parsed.map(_.status) shouldBe Right(span.status)
      parsed.map(_.attributes) shouldBe Right(span.attributes)
      parsed.map(_.events) shouldBe Right(span.events)
    }
  }

  // ---- Trace builder invariants ----

  "Trace.start" should "produce Running status" in {
    forAll(genTraceId)(traceId => Trace.start(traceId).status shouldBe SpanStatus.Running)
  }

  it should "produce no endTime" in {
    forAll(genTraceId)(traceId => Trace.start(traceId).endTime shouldBe empty)
  }

  it should "preserve the traceId" in {
    forAll(genTraceId)(traceId => Trace.start(traceId).traceId shouldBe traceId)
  }

  "Trace.withMetadata" should "add the key without removing others" in {
    forAll(genTrace, genNonEmptyString, Gen.alphaStr) { (trace, key, value) =>
      val updated = trace.withMetadata(key, value)
      updated.metadata should contain(key -> value)
      trace.metadata.foreach { case (k, v) =>
        if (k != key) updated.metadata should contain(k -> v)
      }
    }
  }

  it should "overwrite an existing key" in {
    forAll(genTrace, genNonEmptyString, Gen.alphaStr, Gen.alphaStr) { (trace, key, v1, v2) =>
      val step1 = trace.withMetadata(key, v1)
      val step2 = step1.withMetadata(key, v2)
      step2.metadata(key) shouldBe v2
    }
  }

  // ---- Trace JSON round-trip ----

  "Trace JSON serialisation" should "round-trip all fields" in {
    forAll(genTrace) { trace =>
      val parsed = TraceModelJson.parseTrace(trace.toJson)
      parsed.map(_.traceId) shouldBe Right(trace.traceId)
      parsed.map(_.rootSpanId) shouldBe Right(trace.rootSpanId)
      parsed.map(_.metadata) shouldBe Right(trace.metadata)
      parsed.map(_.startTime) shouldBe Right(trace.startTime)
      parsed.map(_.endTime) shouldBe Right(trace.endTime)
      parsed.map(_.status) shouldBe Right(trace.status)
    }
  }
}
