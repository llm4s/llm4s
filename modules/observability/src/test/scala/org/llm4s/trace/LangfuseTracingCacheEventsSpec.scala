package org.llm4s.trace

import org.llm4s.security.Secret
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.types.Result

import java.time.Instant

class LangfuseTracingCacheEventsSpec extends AnyFunSuite with Matchers {

  // Spy class to capture spans without making HTTP calls
  class LangfuseTracingSpy
      extends LangfuseTracing(
        "http://mock-url",
        Secret("mock-public-key"),
        Secret("mock-secret-key"),
        "test-env",
        "v1.0.0",
        "1.0.0"
      ) {
    var lastSpan: Option[SentSpan] = None

    override protected def sendBatch(request: ujson.Obj): Result[Unit] = {
      lastSpan = SentSpan.all(request.render()).lastOption
      Right(())
    }
  }

  test("LangfuseTracing handles CacheHit event and generates correct JSON") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheHit(
      similarity = 0.95,
      threshold = 0.90,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastSpan shouldBe defined
    val span = spy.lastSpan.get
    span.kind shouldBe "span"
    span.name shouldBe "Cache Hit"
    span.level shouldBe Some("DEFAULT")
    span.input("similarity").num shouldBe 0.95
    span.input("threshold").num shouldBe 0.90
    span.output("result").str shouldBe "hit"
    span.meta("similarity").num shouldBe 0.95
  }

  test("LangfuseTracing handles CacheMiss with LowSimilarity reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.LowSimilarity,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastSpan shouldBe defined
    val span = spy.lastSpan.get
    span.kind shouldBe "span"
    span.name shouldBe "Cache Miss"
    span.input("reason").str shouldBe "low_similarity"
    span.output("result").str shouldBe "miss"
  }

  test("LangfuseTracing handles CacheMiss with TtlExpired reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.TtlExpired,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastSpan.get.input("reason").str shouldBe "ttl_expired"
  }

  test("LangfuseTracing handles CacheMiss with OptionsMismatch reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.OptionsMismatch,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastSpan.get.input("reason").str shouldBe "options_mismatch"
  }
}
