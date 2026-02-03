package org.llm4s.trace

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ConsoleTracingCacheEventsSpec extends AnyFunSuite with Matchers {

  test("ConsoleTracing handles CacheHit event without errors") {
    val tracing = new ConsoleTracing()
    val event = TraceEvent.CacheHit(
      similarity = 0.95,
      threshold = 0.90,
      timestamp = Instant.now()
    )

    noException should be thrownBy tracing.traceEvent(event)
  }

  test("ConsoleTracing handles CacheMiss with LowSimilarity reason") {
    val tracing = new ConsoleTracing()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.LowSimilarity,
      timestamp = Instant.now()
    )

    noException should be thrownBy tracing.traceEvent(event)
  }

  test("ConsoleTracing handles CacheMiss with TtlExpired reason") {
    val tracing = new ConsoleTracing()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.TtlExpired,
      timestamp = Instant.now()
    )

    noException should be thrownBy tracing.traceEvent(event)
  }

  test("ConsoleTracing handles CacheMiss with OptionsMismatch reason") {
    val tracing = new ConsoleTracing()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.OptionsMismatch,
      timestamp = Instant.now()
    )

    noException should be thrownBy tracing.traceEvent(event)
  }
}
