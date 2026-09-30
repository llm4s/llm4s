package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.MDC

class MDCContextSpec extends AnyFlatSpec with Matchers {

  // Clean MDC before each test to avoid cross-contamination
  private def withCleanMDC[T](block: => T): T = {
    MDC.clear()
    try block
    finally MDC.clear()
  }

  // ==========================================================================
  // capture and set
  // ==========================================================================

  "MDCContext.capture" should "return empty map when MDC is empty" in withCleanMDC {
    MDCContext.capture() shouldBe empty
  }

  it should "capture current MDC values" in withCleanMDC {
    MDC.put("key1", "value1")
    MDC.put("key2", "value2")
    val captured = MDCContext.capture()
    captured shouldBe Map("key1" -> "value1", "key2" -> "value2")
  }

  "MDCContext.set" should "replace MDC contents" in withCleanMDC {
    MDC.put("old", "value")
    MDCContext.set(Map("new" -> "newvalue"))
    MDC.get("old") shouldBe null
    MDC.get("new") shouldBe "newvalue"
  }

  it should "handle empty map" in withCleanMDC {
    MDC.put("key", "value")
    MDCContext.set(Map.empty)
    MDC.get("key") shouldBe null
  }

  // ==========================================================================
  // withContext
  // ==========================================================================

  "MDCContext.withContext" should "set context for block duration" in withCleanMDC {
    val result = MDCContext.withContext(Map("traceId" -> "abc123")) {
      MDC.get("traceId")
    }
    result shouldBe "abc123"
  }

  it should "restore previous context after block" in withCleanMDC {
    MDC.put("original", "yes")
    MDCContext.withContext(Map("temp" -> "value")) {
      MDC.get("original") shouldBe null
      MDC.get("temp") shouldBe "value"
    }
    MDC.get("original") shouldBe "yes"
    MDC.get("temp") shouldBe null
  }

  it should "restore context even on exception" in withCleanMDC {
    MDC.put("safe", "true")
    intercept[RuntimeException] {
      MDCContext.withContext(Map("unsafe" -> "true")) {
        throw new RuntimeException("boom")
      }
    }
    MDC.get("safe") shouldBe "true"
    MDC.get("unsafe") shouldBe null
  }

  // ==========================================================================
  // withValues
  // ==========================================================================

  "MDCContext.withValues" should "add values to existing context" in withCleanMDC {
    MDC.put("existing", "yes")
    MDCContext.withValues("added" -> "also-yes") {
      MDC.get("existing") shouldBe "yes"
      MDC.get("added") shouldBe "also-yes"
    }
    MDC.get("existing") shouldBe "yes"
    MDC.get("added") shouldBe null
  }

  it should "handle multiple values" in withCleanMDC {
    MDCContext.withValues("a" -> "1", "b" -> "2", "c" -> "3") {
      MDC.get("a") shouldBe "1"
      MDC.get("b") shouldBe "2"
      MDC.get("c") shouldBe "3"
    }
  }

  it should "restore context even on exception" in withCleanMDC {
    MDC.put("preserved", "yes")
    intercept[RuntimeException] {
      MDCContext.withValues("temp" -> "val") {
        throw new RuntimeException("boom")
      }
    }
    MDC.get("preserved") shouldBe "yes"
  }

  // ==========================================================================
  // cleanup
  // ==========================================================================

  "MDCContext.cleanup" should "remove specified keys" in withCleanMDC {
    MDC.put("keep", "yes")
    MDC.put("remove1", "value")
    MDC.put("remove2", "value")
    MDCContext.cleanup("remove1", "remove2")
    MDC.get("keep") shouldBe "yes"
    MDC.get("remove1") shouldBe null
    MDC.get("remove2") shouldBe null
  }

  it should "handle non-existent keys gracefully" in withCleanMDC {
    noException should be thrownBy MDCContext.cleanup("nonexistent")
  }

  // ==========================================================================
  // preservingExecutionContext
  // ==========================================================================

  "MDCContext.preservingExecutionContext" should "preserve MDC across threads" in withCleanMDC {
    import scala.concurrent.{ Await, Future }
    import scala.concurrent.duration._

    MDC.put("traceId", "trace-123")
    val ec = MDCContext.preservingExecutionContext(scala.concurrent.ExecutionContext.global)

    val future = Future {
      MDC.get("traceId")
    }(ec)

    Await.result(future, 5.seconds) shouldBe "trace-123"
  }
}
