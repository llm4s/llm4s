package org.llm4s.trace

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.types.Result

import java.time.Instant

class LangfuseTracingCacheEventsSpec extends AnyFunSuite with Matchers {

  // Spy class to capture events without making HTTP calls
  class LangfuseTracingSpy
      extends LangfuseTracing(
        "http://mock-url",
        "mock-public-key",
        "mock-secret-key",
        "test-env",
        "v1.0.0",
        "1.0.0"
      ) {
    var lastEventJson: Option[ujson.Obj] = None

    override protected def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
      if (events.nonEmpty) {
        lastEventJson = Some(events.last)
      }
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

    spy.lastEventJson shouldBe defined
    val json = spy.lastEventJson.get
    json("type").str shouldBe "span-create"

    val body = json("body").obj
    body("name").str shouldBe "Cache Hit"
    body("input").obj("similarity").num shouldBe 0.95
    body("input").obj("threshold").num shouldBe 0.90
    body("output").obj("result").str shouldBe "hit"
  }

  test("LangfuseTracing handles CacheMiss with LowSimilarity reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.LowSimilarity,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastEventJson shouldBe defined
    val json = spy.lastEventJson.get
    json("type").str shouldBe "span-create"

    val body = json("body").obj
    body("name").str shouldBe "Cache Miss"
    body("input").obj("reason").str shouldBe "low_similarity"
    body("output").obj("result").str shouldBe "miss"
  }

  test("LangfuseTracing handles CacheMiss with TtlExpired reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.TtlExpired,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastEventJson shouldBe defined
    val body = spy.lastEventJson.get("body").obj
    body("input").obj("reason").str shouldBe "ttl_expired"
  }

  test("LangfuseTracing handles CacheMiss with OptionsMismatch reason") {
    val spy = new LangfuseTracingSpy()
    val event = TraceEvent.CacheMiss(
      reason = TraceEvent.CacheMissReason.OptionsMismatch,
      timestamp = Instant.now()
    )

    val result = spy.traceEvent(event)
    result.isRight shouldBe true

    spy.lastEventJson shouldBe defined
    val body = spy.lastEventJson.get("body").obj
    body("input").obj("reason").str shouldBe "options_mismatch"
  }
}
