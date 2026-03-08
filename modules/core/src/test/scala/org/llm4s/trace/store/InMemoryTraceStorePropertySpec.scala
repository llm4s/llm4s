package org.llm4s.trace.store

import org.llm4s.trace.model._
import org.llm4s.types.TraceId
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant

class InMemoryTraceStorePropertySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with BeforeAndAfterEach {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  private var store: InMemoryTraceStore = _

  override def beforeEach(): Unit =
    store = InMemoryTraceStore()

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val genId: Gen[String] = Gen.uuid.map(_.toString)

  val genTraceId: Gen[TraceId] = genId.map(TraceId(_))

  val genSpanId: Gen[SpanId] = genId.map(SpanId(_))

  val genInstant: Gen[Instant] =
    Gen.choose(0L, 4102444800000L).map(Instant.ofEpochMilli)

  val genSpanKind: Gen[SpanKind] = Gen.oneOf(SpanKind.all)

  val genSpanStatus: Gen[SpanStatus] =
    Gen.oneOf(
      Gen.const(SpanStatus.Ok),
      Gen.const(SpanStatus.Running),
      Gen.alphaStr.map(SpanStatus.Error(_))
    )

  val genSpan: Gen[Span] =
    for {
      spanId    <- genSpanId
      traceId   <- genTraceId
      name      <- genNonEmptyString
      kind      <- genSpanKind
      startTime <- genInstant
      status    <- genSpanStatus
    } yield Span(spanId, traceId, None, name, kind, startTime, None, status)

  def genSpanForTrace(traceId: TraceId): Gen[Span] =
    for {
      spanId    <- genSpanId
      name      <- genNonEmptyString
      kind      <- genSpanKind
      startTime <- genInstant
      status    <- genSpanStatus
    } yield Span(spanId, traceId, None, name, kind, startTime, None, status)

  val genTrace: Gen[Trace] =
    for {
      traceId   <- genTraceId
      startTime <- genInstant
      status    <- genSpanStatus
    } yield Trace(traceId, None, Map.empty, startTime, None, status)

  def genTraceWithMetadata(key: String, value: String): Gen[Trace] =
    genTrace.map(_.withMetadata(key, value))

  // ---- Trace save/get identity ----

  "InMemoryTraceStore.saveTrace / getTrace" should "retrieve exactly what was saved" in {
    forAll(genTrace) { trace =>
      store.clear()
      store.saveTrace(trace)
      store.getTrace(trace.traceId) shouldBe Some(trace)
    }
  }

  it should "return None for an unknown traceId" in {
    forAll(genTraceId) { traceId =>
      store.clear()
      store.getTrace(traceId) shouldBe None
    }
  }

  it should "overwrite on duplicate traceId" in {
    forAll(genTrace) { trace =>
      store.clear()
      val updated = trace.withStatus(SpanStatus.Ok)
      store.saveTrace(trace)
      store.saveTrace(updated)
      store.getTrace(trace.traceId) shouldBe Some(updated)
    }
  }

  // ---- Span save/get identity ----

  "InMemoryTraceStore.saveSpan / getSpans" should "retrieve all saved spans for a trace" in {
    forAll(genTraceId, Gen.nonEmptyListOf(genSpanId), Gen.choose(1, 5)) { (traceId, spanIds, count) =>
      store.clear()
      val spans = spanIds.take(count).map { sid =>
        Span(sid, traceId, None, "test", SpanKind.Internal, Instant.now(), None, SpanStatus.Ok)
      }
      spans.foreach(store.saveSpan)
      val retrieved = store.getSpans(traceId)
      retrieved should have size spans.size.toLong
      spans.foreach(s => retrieved should contain(s))
    }
  }

  it should "return empty list for a trace with no spans" in {
    forAll(genTraceId) { traceId =>
      store.clear()
      store.getSpans(traceId) shouldBe empty
    }
  }

  // ---- Span isolation ----

  "InMemoryTraceStore" should "not mix spans between different traces" in {
    forAll(genTraceId, genTraceId) { (traceIdA, traceIdB) =>
      whenever(traceIdA != traceIdB) {
        store.clear()
        val spanA = Span(SpanId("a"), traceIdA, None, "spanA", SpanKind.Internal, Instant.now(), None, SpanStatus.Ok)
        val spanB = Span(SpanId("b"), traceIdB, None, "spanB", SpanKind.Internal, Instant.now(), None, SpanStatus.Ok)
        store.saveSpan(spanA)
        store.saveSpan(spanB)
        store.getSpans(traceIdA) should contain only spanA
        store.getSpans(traceIdB) should contain only spanB
      }
    }
  }

  // ---- deleteTrace ----

  "InMemoryTraceStore.deleteTrace" should "remove the trace and its spans" in {
    forAll(genTrace) { trace =>
      store.clear()
      val span = Span(SpanId("x"), trace.traceId, None, "s", SpanKind.Internal, Instant.now(), None, SpanStatus.Ok)
      store.saveTrace(trace)
      store.saveSpan(span)
      store.deleteTrace(trace.traceId) shouldBe true
      store.getTrace(trace.traceId) shouldBe None
      store.getSpans(trace.traceId) shouldBe empty
    }
  }

  it should "return false for a non-existent traceId" in {
    forAll(genTraceId) { traceId =>
      store.clear()
      store.deleteTrace(traceId) shouldBe false
    }
  }

  // ---- queryTraces: status filter ----

  "InMemoryTraceStore.queryTraces" should "return only traces with the queried status" in {
    forAll(genSpanStatus, Gen.nonEmptyListOf(genTrace)) { (targetStatus, traces) =>
      store.clear()
      traces.foreach(t => store.saveTrace(t.withStatus(SpanStatus.Running)))
      // Save a few with the exact target status (distinct traceIds)
      val matching = traces.take(3).map(_.withStatus(targetStatus))
      matching.foreach(store.saveTrace)

      val page = store.queryTraces(TraceQuery.withStatus(targetStatus))
      page.traces.foreach(_.status shouldBe targetStatus)
    }
  }

  // ---- queryTraces: time-range filter ----

  it should "return only traces whose startTime is within [from, to]" in {
    forAll(genInstant, Gen.choose(1000L, 100000L), Gen.listOf(genTrace)) { (from, rangeMs, traces) =>
      val to = from.plusMillis(rangeMs)
      store.clear()
      traces.foreach(t => store.saveTrace(t))
      val page = store.queryTraces(TraceQuery.withTimeRange(from, to))
      page.traces.foreach { t =>
        (t.startTime.isBefore(to) || t.startTime == to) shouldBe true
        (t.startTime.isAfter(from) || t.startTime == from) shouldBe true
      }
    }
  }

  // ---- queryTraces: pagination invariants ----

  it should "never return more traces than the requested limit" in {
    forAll(Gen.nonEmptyListOf(genTrace), Gen.choose(1, 10)) { (traces, limit) =>
      store.clear()
      // Use distinct traceIds
      val distinct = traces.distinctBy(_.traceId)
      distinct.foreach(store.saveTrace)
      val page = store.queryTraces(TraceQuery.withLimit(limit))
      page.traces.size should be <= limit
    }
  }

  it should "cover all saved traces across pages with no duplicates" in {
    forAll(Gen.nonEmptyListOf(genTrace)) { rawTraces =>
      val traces = rawTraces.distinctBy(_.traceId)
      store.clear()
      traces.foreach(store.saveTrace)

      val limit                                           = 3
      var cursor: Option[String]                          = None
      var allRetrieved: List[org.llm4s.trace.model.Trace] = List.empty
      var safetyCounter                                   = 0

      while (safetyCounter <= traces.size + 5) {
        val query = TraceQuery(limit = limit, cursor = cursor)
        val page  = store.queryTraces(query)
        allRetrieved = allRetrieved ++ page.traces
        cursor = page.nextCursor
        safetyCounter += 1
        if (cursor.isEmpty) safetyCounter = traces.size + 100 // exit
      }

      allRetrieved.map(_.traceId) should contain theSameElementsAs traces.map(_.traceId)
      // No duplicates
      allRetrieved.map(_.traceId).distinct should have size allRetrieved.size.toLong
    }
  }

  // ---- searchByMetadata ----

  "InMemoryTraceStore.searchByMetadata" should "return only traceIds that have the given key=value" in {
    forAll(genNonEmptyString, genNonEmptyString, Gen.nonEmptyListOf(genTrace)) { (key, value, traces) =>
      store.clear()
      val distinct                = traces.distinctBy(_.traceId)
      val (matching, nonMatching) = distinct.splitAt(distinct.size / 2 + 1)
      matching.foreach(t => store.saveTrace(t.withMetadata(key, value)))
      nonMatching.foreach(t => store.saveTrace(t))

      val found = store.searchByMetadata(key, value)
      found should contain theSameElementsAs matching.map(_.traceId)
    }
  }

  it should "return empty list when no trace has the given key=value" in {
    forAll(genNonEmptyString, genNonEmptyString) { (key, value) =>
      store.clear()
      store.searchByMetadata(key, value) shouldBe empty
    }
  }

  // ---- clear ----

  "InMemoryTraceStore.clear" should "remove all traces and spans" in {
    forAll(Gen.nonEmptyListOf(genTrace)) { traces =>
      store.clear()
      val distinct = traces.distinctBy(_.traceId)
      distinct.foreach(store.saveTrace)
      store.clear()
      distinct.foreach(t => store.getTrace(t.traceId) shouldBe None)
    }
  }
}
