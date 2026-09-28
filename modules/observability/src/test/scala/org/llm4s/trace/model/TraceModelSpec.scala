package org.llm4s.trace.model

import org.llm4s.types.TraceId
import org.llm4s.trace.model.TraceModelJson._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class TraceModelSpec extends AnyFlatSpec with Matchers {

  private val fixedTimestamp  = Instant.parse("2024-01-15T10:30:00Z")
  private val fixedTimestamp2 = Instant.parse("2024-01-15T10:35:00Z")

  "SpanId" should "generate unique IDs" in {
    val id1 = SpanId.generate()
    val id2 = SpanId.generate()
    (id1 should not).equal(id2)
    id1.value should not be empty
  }

  it should "serialize and deserialize correctly" in {
    val spanId = SpanId("test-span-123")
    val json   = spanId.toJson
    val parsed = TraceModelJson.parseSpanId(json)
    parsed shouldBe Right(spanId)
  }

  it should "have custom toString returning the value" in {
    val spanId = SpanId("my-span-id")
    spanId.toString shouldBe "my-span-id"
  }

  it should "return error when parsing non-string JSON" in {
    val result = TraceModelJson.parseSpanId(ujson.Num(123))
    result shouldBe a[Left[?, ?]]
  }

  "SpanValue" should "support all variants" in {
    SpanValue.StringValue("hello").asString shouldBe Some("hello")
    SpanValue.LongValue(42L).asLong shouldBe Some(42L)
    SpanValue.DoubleValue(3.14).asDouble shouldBe Some(3.14)
    SpanValue.BooleanValue(true).asBoolean shouldBe Some(true)
    SpanValue.StringListValue(List("a", "b")).asStringList shouldBe Some(List("a", "b"))
  }

  it should "return None for wrong type conversions" in {
    SpanValue.StringValue("hello").asLong shouldBe None
    SpanValue.LongValue(42L).asString shouldBe None
    SpanValue.DoubleValue(3.14).asBoolean shouldBe None
  }

  it should "round-trip serialize all variants" in {
    def roundTrip(sv: SpanValue) = TraceModelJson.parseSpanValue(sv.toJson)

    roundTrip(SpanValue.StringValue("hello")) shouldBe Right(SpanValue.StringValue("hello"))
    roundTrip(SpanValue.LongValue(42L)) shouldBe Right(SpanValue.LongValue(42L))
    roundTrip(SpanValue.DoubleValue(3.14)) shouldBe Right(SpanValue.DoubleValue(3.14))
    roundTrip(SpanValue.BooleanValue(true)) shouldBe Right(SpanValue.BooleanValue(true))
    roundTrip(SpanValue.StringListValue(List("a", "b"))) shouldBe Right(SpanValue.StringListValue(List("a", "b")))
  }

  it should "return error for invalid JSON" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj())
    result shouldBe a[Left[?, ?]]
  }

  it should "support legacy String format" in {
    val result = TraceModelJson.parseSpanValue(ujson.Str("legacy-string"))
    result shouldBe Right(SpanValue.StringValue("legacy-string"))
  }

  it should "support legacy Double format" in {
    val result = TraceModelJson.parseSpanValue(ujson.Num(3.14))
    result.map(_.asDouble) shouldBe Right(Some(3.14))
  }

  it should "support legacy Boolean format" in {
    val result = TraceModelJson.parseSpanValue(ujson.Bool(true))
    result shouldBe Right(SpanValue.BooleanValue(true))
  }

  it should "support legacy StringList format" in {
    val result = TraceModelJson.parseSpanValue(ujson.Arr(ujson.Str("a"), ujson.Str("b")))
    result shouldBe Right(SpanValue.StringListValue(List("a", "b")))
  }

  it should "return error for legacy StringList with non-string elements" in {
    val result = TraceModelJson.parseSpanValue(ujson.Arr(ujson.Str("a"), ujson.Num(1)))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing type field" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("value" -> ujson.Str("test")))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for unknown type" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "UnknownType", "value" -> ujson.Str("test")))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid String value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "String", "value" -> ujson.Num(123)))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid Long string value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "Long", "value" -> ujson.Str("not-a-number")))
    result shouldBe a[Left[?, ?]]
  }

  it should "parse Long from numeric value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "Long", "value" -> ujson.Num(42.0)))
    result.map(_.asLong) shouldBe Right(Some(42L))
  }

  it should "return error for invalid Double value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "Double", "value" -> ujson.Str("not-a-double")))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid Boolean value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "Boolean", "value" -> ujson.Str("not-a-bool")))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for StringList with non-string element in typed format" in {
    val result = TraceModelJson.parseSpanValue(
      ujson.Obj("type" -> "StringList", "value" -> ujson.Arr(ujson.Str("a"), ujson.Num(1)))
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid StringList value" in {
    val result = TraceModelJson.parseSpanValue(ujson.Obj("type" -> "StringList", "value" -> ujson.Str("not-an-array")))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for non-object/array/primitive JSON" in {
    val result = TraceModelJson.parseSpanValue(ujson.Null)
    result shouldBe a[Left[?, ?]]
  }

  "SpanStatus" should "serialize and deserialize all variants" in {
    def roundTrip(s: SpanStatus) = TraceModelJson.parseSpanStatus(s.toJson)

    roundTrip(SpanStatus.Ok) shouldBe Right(SpanStatus.Ok)
    roundTrip(SpanStatus.Error("something failed")) shouldBe Right(SpanStatus.Error("something failed"))
    roundTrip(SpanStatus.Running) shouldBe Right(SpanStatus.Running)
  }

  it should "return error for invalid JSON" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Str("invalid"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for unknown status type" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Obj("type" -> "UnknownStatus"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing type field in status" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Obj("message" -> "error"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid type field in status" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Obj("type" -> 123))
    result shouldBe a[Left[?, ?]]
  }

  it should "parse Error status with missing message" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Obj("type" -> "Error"))
    result shouldBe Right(SpanStatus.Error(""))
  }

  it should "parse Error status with non-string message" in {
    val result = TraceModelJson.parseSpanStatus(ujson.Obj("type" -> "Error", "message" -> 123))
    result shouldBe Right(SpanStatus.Error(""))
  }

  "SpanKind" should "include all required kinds" in {
    (SpanKind.all should contain).allOf(
      SpanKind.Internal,
      SpanKind.LlmCall,
      SpanKind.ToolCall,
      SpanKind.AgentCall,
      SpanKind.NodeExecution,
      SpanKind.Embedding,
      SpanKind.Rag,
      SpanKind.Cache
    )
  }

  it should "serialize and deserialize all kinds" in {
    def roundTrip(k: SpanKind) = TraceModelJson.parseSpanKind(k.toJson)

    SpanKind.all.foreach(kind => roundTrip(kind) shouldBe Right(kind))
  }

  it should "return error for unknown kind" in {
    val result = TraceModelJson.parseSpanKind(ujson.Str("UnknownKind"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for non-string kind" in {
    val result = TraceModelJson.parseSpanKind(ujson.Num(123))
    result shouldBe a[Left[?, ?]]
  }

  "SpanEvent" should "construct with defaults" in {
    val event = SpanEvent("test-event")
    event.name shouldBe "test-event"
    event.attributes shouldBe empty
  }

  it should "construct with attributes using apply overload" in {
    val attrs = Map("key" -> SpanValue.StringValue("value"))
    val event = SpanEvent("test-event", attrs)
    event.name shouldBe "test-event"
    event.attributes shouldBe attrs
  }

  it should "serialize and deserialize" in {
    val event = SpanEvent(
      name = "test-event",
      timestamp = fixedTimestamp,
      attributes = Map(
        "key1" -> SpanValue.StringValue("value1"),
        "key2" -> SpanValue.LongValue(42L)
      )
    )
    val json   = event.toJson
    val parsed = TraceModelJson.parseSpanEvent(json)
    parsed.map(_.name) shouldBe Right(event.name)
    parsed.map(_.timestamp) shouldBe Right(event.timestamp)
    parsed.map(_.attributes("key1").asString) shouldBe Right(Some("value1"))
    parsed.map(_.attributes("key2").asLong) shouldBe Right(Some(42L))
  }

  it should "return error for missing fields" in {
    val result = TraceModelJson.parseSpanEvent(ujson.Obj())
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid timestamp format" in {
    val result = TraceModelJson.parseSpanEvent(
      ujson.Obj("name" -> "test", "timestamp" -> "invalid-date", "attributes" -> ujson.Obj())
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing timestamp" in {
    val result = TraceModelJson.parseSpanEvent(
      ujson.Obj("name" -> "test", "attributes" -> ujson.Obj())
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing attributes" in {
    val result = TraceModelJson.parseSpanEvent(
      ujson.Obj("name" -> "test", "timestamp" -> fixedTimestamp.toString)
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for non-object JSON" in {
    val result = TraceModelJson.parseSpanEvent(ujson.Str("invalid"))
    result shouldBe a[Left[?, ?]]
  }

  "Span" should "start with Running status" in {
    val traceId = TraceId("test-trace")
    val span    = Span.start(traceId, "test-span", SpanKind.Internal)
    span.status shouldBe SpanStatus.Running
    span.endTime shouldBe None
  }

  it should "support builder methods" in {
    val traceId = TraceId("test-trace")
    val span = Span
      .start(traceId, "test-span", SpanKind.ToolCall)
      .withAttribute("key1", SpanValue.StringValue("value1"))
      .withAttribute("key2", SpanValue.BooleanValue(true))
      .withEvent(SpanEvent("event1"))
      .end(fixedTimestamp)
      .withStatus(SpanStatus.Ok)

    span.attributes should have size 2
    span.events should have size 1
    span.endTime shouldBe Some(fixedTimestamp)
    span.status shouldBe SpanStatus.Ok
  }

  it should "round-trip serialize completely" in {
    val traceId  = TraceId("test-trace-123")
    val parentId = SpanId("parent-span")
    val span = Span(
      spanId = SpanId("span-123"),
      traceId = traceId,
      parentSpanId = Some(parentId),
      name = "test-span",
      kind = SpanKind.ToolCall,
      startTime = fixedTimestamp,
      endTime = Some(fixedTimestamp2),
      status = SpanStatus.Ok,
      attributes = Map(
        "string_attr" -> SpanValue.StringValue("hello"),
        "long_attr"   -> SpanValue.LongValue(100L),
        "double_attr" -> SpanValue.DoubleValue(3.14),
        "bool_attr"   -> SpanValue.BooleanValue(true)
      ),
      events = List(
        SpanEvent("event1", fixedTimestamp, Map("e_key" -> SpanValue.StringValue("e_val")))
      )
    )

    val json   = span.toJson
    val parsed = TraceModelJson.parseSpan(json)

    parsed.map(_.spanId) shouldBe Right(span.spanId)
    parsed.map(_.traceId) shouldBe Right(span.traceId)
    parsed.map(_.parentSpanId) shouldBe Right(span.parentSpanId)
    parsed.map(_.name) shouldBe Right(span.name)
    parsed.map(_.kind) shouldBe Right(span.kind)
    parsed.map(_.startTime) shouldBe Right(span.startTime)
    parsed.map(_.endTime) shouldBe Right(span.endTime)
    parsed.map(_.status) shouldBe Right(span.status)
    parsed.map(_.attributes.keys) shouldBe Right(span.attributes.keys)
    parsed.map(_.events.head.name) shouldBe Right(span.events.head.name)
  }

  it should "round-trip serialize without parent or endTime" in {
    val traceId = TraceId("test-trace")
    val span = Span(
      spanId = SpanId("span-123"),
      traceId = traceId,
      parentSpanId = None,
      name = "test-span",
      kind = SpanKind.LlmCall,
      startTime = fixedTimestamp,
      endTime = None,
      status = SpanStatus.Running
    )

    val json   = span.toJson
    val parsed = TraceModelJson.parseSpan(json)

    parsed.map(_.parentSpanId) shouldBe Right(None)
    parsed.map(_.endTime) shouldBe Right(None)
  }

  it should "return error for invalid JSON" in {
    val result = TraceModelJson.parseSpan(ujson.Str("invalid"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing required fields" in {
    val result = TraceModelJson.parseSpan(ujson.Obj())
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing spanId" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing traceId" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing name" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing kind" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "startTime"  -> fixedTimestamp.toString,
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid startTime format" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> "invalid-date",
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing startTime" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid endTime type" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Num(123),
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid endTime format" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> "invalid-date",
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing status" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "attributes" -> ujson.Obj(),
        "events"     -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing attributes" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"    -> "span-1",
        "traceId"   -> "trace-1",
        "name"      -> "test",
        "kind"      -> "Internal",
        "startTime" -> fixedTimestamp.toString,
        "status"    -> ujson.Obj("type" -> "Ok"),
        "events"    -> ujson.Arr()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing events" in {
    val result = TraceModelJson.parseSpan(
      ujson.Obj(
        "spanId"     -> "span-1",
        "traceId"    -> "trace-1",
        "name"       -> "test",
        "kind"       -> "Internal",
        "startTime"  -> fixedTimestamp.toString,
        "status"     -> ujson.Obj("type" -> "Ok"),
        "attributes" -> ujson.Obj()
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "end with default time" in {
    val traceId = TraceId("test-trace")
    val span    = Span.start(traceId, "test-span", SpanKind.Internal).end()
    span.endTime shouldBe defined
  }

  "Trace" should "start with Running status" in {
    val trace = Trace.start()
    trace.status shouldBe SpanStatus.Running
    trace.endTime shouldBe None
    trace.rootSpanId shouldBe None
  }

  it should "start with metadata overload" in {
    val metadata = Map("env" -> "test", "version" -> "1.0")
    val trace    = Trace.start(metadata)
    trace.status shouldBe SpanStatus.Running
    trace.metadata shouldBe metadata
    trace.endTime shouldBe None
  }

  it should "start with empty metadata default" in {
    val trace = Trace(TraceId("test"), None, startTime = fixedTimestamp, endTime = None, status = SpanStatus.Ok)
    trace.metadata shouldBe empty
  }

  it should "end with default time" in {
    val trace = Trace.start().end()
    trace.endTime shouldBe defined
  }

  it should "support builder methods" in {
    val trace = Trace
      .start()
      .withMetadata("environment", "production")
      .withMetadata("version", "1.0.0")
      .end(fixedTimestamp)
      .withStatus(SpanStatus.Ok)

    trace.metadata should have size 2
    trace.metadata("environment") shouldBe "production"
    trace.endTime shouldBe Some(fixedTimestamp)
    trace.status shouldBe SpanStatus.Ok
  }

  it should "round-trip serialize completely" in {
    val traceId    = TraceId("trace-123")
    val rootSpanId = SpanId("root-span")
    val trace = Trace(
      traceId = traceId,
      rootSpanId = Some(rootSpanId),
      metadata = Map("env" -> "test", "service" -> "llm4s"),
      startTime = fixedTimestamp,
      endTime = Some(fixedTimestamp2),
      status = SpanStatus.Ok
    )

    val json   = trace.toJson
    val parsed = TraceModelJson.parseTrace(json)

    parsed.map(_.traceId) shouldBe Right(trace.traceId)
    parsed.map(_.rootSpanId) shouldBe Right(trace.rootSpanId)
    parsed.map(_.metadata) shouldBe Right(trace.metadata)
    parsed.map(_.startTime) shouldBe Right(trace.startTime)
    parsed.map(_.endTime) shouldBe Right(trace.endTime)
    parsed.map(_.status) shouldBe Right(trace.status)
  }

  it should "round-trip serialize with Error status" in {
    val traceId = TraceId("trace-123")
    val trace = Trace(
      traceId = traceId,
      rootSpanId = None,
      metadata = Map.empty,
      startTime = fixedTimestamp,
      endTime = Some(fixedTimestamp2),
      status = SpanStatus.Error("something went wrong")
    )

    val json   = trace.toJson
    val parsed = TraceModelJson.parseTrace(json)

    parsed.map(_.status) match {
      case Right(err: SpanStatus.Error) => err.message shouldBe "something went wrong"
      case other                        => fail(s"Expected Right(SpanStatus.Error), got $other")
    }
  }

  it should "return error for invalid JSON" in {
    val result = TraceModelJson.parseTrace(ujson.Str("invalid"))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing required fields" in {
    val result = TraceModelJson.parseTrace(ujson.Obj())
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for non-string traceId" in {
    val result = TraceModelJson.parseTraceId(ujson.Num(123))
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing traceId in Trace" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj(),
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Null,
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid metadata value" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj("key" -> ujson.Num(123)),
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Null,
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing metadata" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Null,
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid startTime format in Trace" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj(),
        "startTime"  -> "invalid-date",
        "endTime"    -> ujson.Null,
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing startTime in Trace" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj(),
        "endTime"    -> ujson.Null,
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for invalid endTime type in Trace" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj(),
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Num(123),
        "status"     -> ujson.Obj("type" -> "Ok")
      )
    )
    result shouldBe a[Left[?, ?]]
  }

  it should "return error for missing status in Trace" in {
    val result = TraceModelJson.parseTrace(
      ujson.Obj(
        "traceId"    -> "trace-1",
        "rootSpanId" -> ujson.Null,
        "metadata"   -> ujson.Obj(),
        "startTime"  -> fixedTimestamp.toString,
        "endTime"    -> ujson.Null
      )
    )
    result shouldBe a[Left[?, ?]]
  }
}
