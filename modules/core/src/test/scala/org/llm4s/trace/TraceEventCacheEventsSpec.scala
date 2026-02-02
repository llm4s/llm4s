package org.llm4s.trace

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class TraceEventCacheEventsSpec extends AnyFunSuite with Matchers {

  test("CacheHit toJson includes all required fields") {
    val event = TraceEvent.CacheHit(
      similarity = 0.95,
      threshold = 0.90,
      timestamp = Instant.parse("2024-01-01T10:00:00Z")
    )

    val json = event.toJson.obj
    json("event_type").str shouldBe "cache_hit"
    json("timestamp").str shouldBe "2024-01-01T10:00:00Z"
    json("similarity").num shouldBe 0.95
    json("threshold").num shouldBe 0.90
  }

  test("CacheMiss toJson with LowSimilarity reason") {
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.LowSimilarity,
      timestamp = Instant.parse("2024-01-01T10:00:00Z")
    )

    val json = event.toJson.obj
    json("event_type").str shouldBe "cache_miss"
    json("timestamp").str shouldBe "2024-01-01T10:00:00Z"
    json("reason").str shouldBe "low_similarity"
  }

  test("CacheMiss toJson with TtlExpired reason") {
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.TtlExpired,
      timestamp = Instant.parse("2024-01-01T10:00:00Z")
    )

    val json = event.toJson.obj
    json("reason").str shouldBe "ttl_expired"
  }

  test("CacheMiss toJson with OptionsMismatch reason") {
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.OptionsMismatch,
      timestamp = Instant.parse("2024-01-01T10:00:00Z")
    )

    val json = event.toJson.obj
    json("reason").str shouldBe "options_mismatch"
  }

  test("CacheMissReason ADT has correct string values") {
    TraceEvent.CacheMissReason.LowSimilarity.value shouldBe "low_similarity"
    TraceEvent.CacheMissReason.TtlExpired.value shouldBe "ttl_expired"
    TraceEvent.CacheMissReason.OptionsMismatch.value shouldBe "options_mismatch"
  }
}
