package org.llm4s.trace.store

import org.llm4s.trace.model.{ SpanStatus, Trace }
import org.llm4s.types.TraceId
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant

class TraceQueryPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 100)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val genInstant: Gen[Instant] =
    Gen.choose(0L, 4102444800000L).map(Instant.ofEpochMilli)

  val genSpanStatus: Gen[SpanStatus] = Gen.oneOf(
    Gen.const(SpanStatus.Ok),
    Gen.const(SpanStatus.Running),
    Gen.alphaStr.map(SpanStatus.Error(_))
  )

  val genLimit: Gen[Int] = Gen.choose(1, 1000)

  val genTrace: Gen[Trace] =
    for {
      id        <- Gen.uuid.map(u => TraceId(u.toString))
      startTime <- genInstant
      status    <- genSpanStatus
    } yield Trace(id, None, Map.empty, startTime, None, status)

  // ---- TraceQuery.empty ----

  "TraceQuery.empty" should "have no time constraints" in {
    TraceQuery.empty.startTimeFrom shouldBe empty
    TraceQuery.empty.startTimeTo shouldBe empty
  }

  it should "have no status filter" in {
    TraceQuery.empty.status shouldBe empty
  }

  it should "have no metadata filter" in {
    TraceQuery.empty.metadata shouldBe empty
  }

  it should "have no cursor" in {
    TraceQuery.empty.cursor shouldBe empty
  }

  it should "use the default limit of 50" in {
    TraceQuery.empty.limit shouldBe 50
  }

  // ---- TraceQuery.withLimit ----

  "TraceQuery.withLimit" should "set exactly the given limit" in {
    forAll(genLimit)(n => TraceQuery.withLimit(n).limit shouldBe n)
  }

  it should "leave all filters unset" in {
    forAll(genLimit) { n =>
      val q = TraceQuery.withLimit(n)
      q.startTimeFrom shouldBe empty
      q.startTimeTo shouldBe empty
      q.status shouldBe empty
      q.metadata shouldBe empty
      q.cursor shouldBe empty
    }
  }

  // ---- TraceQuery.withTimeRange ----

  "TraceQuery.withTimeRange" should "set both time bounds" in {
    forAll(genInstant, genInstant) { (from, to) =>
      val q = TraceQuery.withTimeRange(from, to)
      q.startTimeFrom shouldBe Some(from)
      q.startTimeTo shouldBe Some(to)
    }
  }

  it should "leave status, metadata, cursor unset" in {
    forAll(genInstant, genInstant) { (from, to) =>
      val q = TraceQuery.withTimeRange(from, to)
      q.status shouldBe empty
      q.metadata shouldBe empty
      q.cursor shouldBe empty
    }
  }

  // ---- TraceQuery.withStatus ----

  "TraceQuery.withStatus" should "set exactly the given status" in {
    forAll(genSpanStatus)(status => TraceQuery.withStatus(status).status shouldBe Some(status))
  }

  it should "leave time range, metadata, cursor unset" in {
    forAll(genSpanStatus) { status =>
      val q = TraceQuery.withStatus(status)
      q.startTimeFrom shouldBe empty
      q.startTimeTo shouldBe empty
      q.metadata shouldBe empty
      q.cursor shouldBe empty
    }
  }

  // ---- TraceQuery.withMetadata ----

  "TraceQuery.withMetadata" should "set exactly one metadata entry" in {
    forAll(genNonEmptyString, Gen.alphaStr) { (key, value) =>
      val q = TraceQuery.withMetadata(key, value)
      q.metadata shouldBe Map(key -> value)
    }
  }

  it should "leave time range, status, cursor unset" in {
    forAll(genNonEmptyString, Gen.alphaStr) { (key, value) =>
      val q = TraceQuery.withMetadata(key, value)
      q.startTimeFrom shouldBe empty
      q.startTimeTo shouldBe empty
      q.status shouldBe empty
      q.cursor shouldBe empty
    }
  }

  // ---- TracePage computed fields ----

  "TracePage.hasNext" should "be true iff nextCursor is defined" in {
    forAll(Gen.listOf(genTrace), Gen.option(genNonEmptyString)) { (traces, cursor) =>
      TracePage(traces, cursor).hasNext shouldBe cursor.isDefined
    }
  }

  "TracePage.isEmpty" should "be true iff traces list is empty" in {
    forAll(Gen.listOf(genTrace), Gen.option(genNonEmptyString)) { (traces, cursor) =>
      TracePage(traces, cursor).isEmpty shouldBe traces.isEmpty
    }
  }

  "TracePage.size" should "equal the number of traces" in {
    forAll(Gen.listOf(genTrace), Gen.option(genNonEmptyString)) { (traces, cursor) =>
      TracePage(traces, cursor).size shouldBe traces.size
    }
  }

  it should "be consistent with isEmpty" in {
    forAll(Gen.listOf(genTrace), Gen.option(genNonEmptyString)) { (traces, cursor) =>
      val page = TracePage(traces, cursor)
      (page.size == 0) shouldBe page.isEmpty
    }
  }
}
