package org.llm4s.trace.store

import org.llm4s.trace.model.{ Span, SpanId, SpanKind, SpanStatus, Trace }
import org.llm4s.types.TraceId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.Executors
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.DurationInt

class InMemoryTraceStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var store: InMemoryTraceStore = _
  private val t1                        = Instant.parse("2024-01-15T10:00:00Z")
  private val t2                        = Instant.parse("2024-01-15T11:00:00Z")
  private val t3                        = Instant.parse("2024-01-15T12:00:00Z")

  override def beforeEach(): Unit =
    store = InMemoryTraceStore()

  "InMemoryTraceStore" should "save and retrieve a trace" in {
    val trace = Trace(
      traceId = TraceId("trace-1"),
      rootSpanId = None,
      metadata = Map("env" -> "test"),
      startTime = t1,
      endTime = Some(t2),
      status = SpanStatus.Ok
    )

    store.saveTrace(trace)
    val retrieved = store.getTrace(TraceId("trace-1"))

    retrieved shouldBe Some(trace)
  }

  it should "return None for non-existent trace" in {
    store.getTrace(TraceId("nonexistent")) shouldBe None
  }

  it should "save and retrieve spans for a trace" in {
    val traceId = TraceId("trace-1")
    val span1 = Span(
      spanId = SpanId("span-1"),
      traceId = traceId,
      parentSpanId = None,
      name = "span1",
      kind = SpanKind.ToolCall,
      startTime = t1,
      endTime = Some(t2),
      status = SpanStatus.Ok
    )
    val span2 = Span(
      spanId = SpanId("span-2"),
      traceId = traceId,
      parentSpanId = Some(SpanId("span-1")),
      name = "span2",
      kind = SpanKind.LlmCall,
      startTime = t2,
      endTime = Some(t3),
      status = SpanStatus.Ok
    )

    store.saveSpan(span1)
    store.saveSpan(span2)

    val spans = store.getSpans(traceId)
    spans should have size 2
    (spans.map(_.spanId) should contain).allOf(SpanId("span-1"), SpanId("span-2"))
  }

  it should "return empty list for spans of non-existent trace" in {
    store.getSpans(TraceId("nonexistent")) shouldBe empty
  }

  it should "query traces by time range" in {
    val trace1 = makeTrace("trace-1", t1, SpanStatus.Ok)
    val trace2 = makeTrace("trace-2", t2, SpanStatus.Ok)
    val trace3 = makeTrace("trace-3", t3, SpanStatus.Ok)

    store.saveTrace(trace1)
    store.saveTrace(trace2)
    store.saveTrace(trace3)

    val query = TraceQuery.withTimeRange(t1, t2)
    val page  = store.queryTraces(query)

    (page.traces.map(_.traceId) should contain).allOf(TraceId("trace-1"), TraceId("trace-2"))
    page.traces.map(_.traceId) should not contain TraceId("trace-3")
  }

  it should "query traces by status" in {
    val trace1 = makeTrace("trace-1", t1, SpanStatus.Ok)
    val trace2 = makeTrace("trace-2", t2, SpanStatus.Error("failed"))

    store.saveTrace(trace1)
    store.saveTrace(trace2)

    val query = TraceQuery.withStatus(SpanStatus.Error("failed"))
    val page  = store.queryTraces(query)

    page.traces should have size 1
    page.traces.head.traceId shouldBe TraceId("trace-2")
  }

  it should "query with pagination limit" in {
    for (i <- 1 to 10)
      store.saveTrace(makeTrace(s"trace-$i", t1.plusSeconds(i), SpanStatus.Ok))

    val query = TraceQuery(limit = 3)
    val page  = store.queryTraces(query)

    page.traces should have size 3
    page.nextCursor should not be empty
  }

  it should "query with cursor continuation" in {
    for (i <- 1 to 10)
      store.saveTrace(makeTrace(s"trace-$i", t1.plusSeconds(i), SpanStatus.Ok))

    val page1 = store.queryTraces(TraceQuery(limit = 3))
    page1.traces should have size 3

    val page2 = store.queryTraces(TraceQuery(limit = 3, cursor = page1.nextCursor))
    page2.traces should have size 3

    page1.traces.map(_.traceId).foreach(id => page2.traces.map(_.traceId) should not contain id)
  }

  it should "search by metadata key-value" in {
    val trace1 = makeTrace("trace-1", t1, SpanStatus.Ok, Map("environment" -> "production"))
    val trace2 = makeTrace("trace-2", t2, SpanStatus.Ok, Map("environment" -> "staging"))
    val trace3 = makeTrace("trace-3", t3, SpanStatus.Ok, Map("environment" -> "production"))

    store.saveTrace(trace1)
    store.saveTrace(trace2)
    store.saveTrace(trace3)

    val results = store.searchByMetadata("environment", "production")
    results should have size 2
    (results should contain).allOf(TraceId("trace-1"), TraceId("trace-3"))
  }

  it should "return empty list for metadata search with no matches" in {
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok, Map("env" -> "test")))
    store.searchByMetadata("nonexistent", "value") shouldBe empty
  }

  it should "delete a trace and its spans" in {
    val traceId = TraceId("trace-1")
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok))
    store.saveSpan(makeSpan("span-1", traceId))

    store.deleteTrace(traceId) shouldBe true
    store.getTrace(traceId) shouldBe None
    store.getSpans(traceId) shouldBe empty
  }

  it should "return false when deleting non-existent trace" in {
    store.deleteTrace(TraceId("nonexistent")) shouldBe false
  }

  it should "clear all data" in {
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-2", t2, SpanStatus.Ok))
    store.saveSpan(makeSpan("span-1", TraceId("trace-1")))

    store.clear()

    store.queryTraces(TraceQuery.empty).traces shouldBe empty
  }

  it should "return empty page on creation" in {
    val page = store.queryTraces(TraceQuery.empty)
    page.isEmpty shouldBe true
    page.traces shouldBe empty
  }

  it should "return all traces when no filters are set" in {
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-2", t2, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-3", t3, SpanStatus.Ok))

    val page = store.queryTraces(TraceQuery.empty)
    page.traces should have size 3
  }

  it should "return traces sorted by start time ascending" in {
    // Save in reverse chronological order to verify sorting
    store.saveTrace(makeTrace("trace-3", t3, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-2", t2, SpanStatus.Ok))

    val page = store.queryTraces(TraceQuery.empty)
    page.traces.map(_.traceId) shouldBe List(TraceId("trace-1"), TraceId("trace-2"), TraceId("trace-3"))
  }

  it should "query traces by metadata filter" in {
    val trace1 = makeTrace("trace-1", t1, SpanStatus.Ok, Map("env" -> "production"))
    val trace2 = makeTrace("trace-2", t2, SpanStatus.Ok, Map("env" -> "staging"))
    val trace3 = makeTrace("trace-3", t3, SpanStatus.Ok, Map("env" -> "production"))

    store.saveTrace(trace1)
    store.saveTrace(trace2)
    store.saveTrace(trace3)

    val page = store.queryTraces(TraceQuery.withMetadata("env", "production"))
    page.traces should have size 2
    (page.traces.map(_.traceId) should contain).allOf(TraceId("trace-1"), TraceId("trace-3"))
    page.traces.map(_.traceId) should not contain TraceId("trace-2")
  }

  it should "apply time range and status filters with AND semantics" in {
    // trace-1: t1, Ok  — matches both time range [t1,t2] and status Ok
    // trace-2: t2, Error — matches time range but not status
    // trace-3: t3, Ok  — outside time range
    store.saveTrace(makeTrace("trace-1", t1, SpanStatus.Ok))
    store.saveTrace(makeTrace("trace-2", t2, SpanStatus.Error("failed")))
    store.saveTrace(makeTrace("trace-3", t3, SpanStatus.Ok))

    val query = TraceQuery(startTimeFrom = Some(t1), startTimeTo = Some(t2), status = Some(SpanStatus.Ok))
    val page  = store.queryTraces(query)

    page.traces should have size 1
    page.traces.head.traceId shouldBe TraceId("trace-1")
  }

  it should "support concurrent span writes" in {
    implicit val ec: scala.concurrent.ExecutionContextExecutorService =
      ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))
    val traceId = TraceId("concurrent-trace")
    val futures = (1 to 100).map(i => Future(store.saveSpan(makeSpan(s"span-$i", traceId))))
    Await.result(Future.sequence(futures), 10.seconds)
    ec.shutdown()

    store.getSpans(traceId) should have size 100
  }

  private def makeTrace(
    id: String,
    startTime: Instant,
    status: SpanStatus,
    metadata: Map[String, String] = Map.empty
  ): Trace =
    Trace(
      traceId = TraceId(id),
      rootSpanId = None,
      metadata = metadata,
      startTime = startTime,
      endTime = Some(startTime.plusSeconds(60)),
      status = status
    )

  private def makeSpan(id: String, traceId: TraceId): Span =
    Span(
      spanId = SpanId(id),
      traceId = traceId,
      parentSpanId = None,
      name = s"span-$id",
      kind = SpanKind.Internal,
      startTime = Instant.now(),
      endTime = Some(Instant.now()),
      status = SpanStatus.Ok
    )
}
