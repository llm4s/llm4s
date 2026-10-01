package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.slf4j.Logger

import java.util.concurrent.Executors
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

class RateLimitedLoggerSpec extends AnyFlatSpec with Matchers with MockFactory {

  "RateLimitedLogger" should "log the first call regardless of rate limit config" in {
    val mockLogger  = mock[Logger]
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 60, throttleCount = 100)

    (mockLogger.warn(_: String)).expects("Test message").once()

    val result = rateLimited.warn("Test message")
    result shouldBe true
  }

  it should "suppress subsequent calls within the time window" in {
    val mockLogger  = mock[Logger]
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 60, throttleCount = 100)

    (mockLogger.warn(_: String)).expects("Test message").once()

    val result1 = rateLimited.warn("Test message")
    val result2 = rateLimited.warn("Test message")

    result1 shouldBe true
    result2 shouldBe false
  }

  it should "log calls after window reset" in {
    val mockLogger  = mock[Logger]
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 60, throttleCount = 100)

    // Both invocations should log without aggregated count because we reset the state
    (mockLogger.warn(_: String)).expects("Test message").twice()

    val result1 = rateLimited.warn("Test message")
    result1 shouldBe true

    val result2 = rateLimited.warn("Test message")
    result2 shouldBe false

    rateLimited.reset()

    val result3 = rateLimited.warn("Test message")
    result3 shouldBe true
  }

  it should "suppress calls until count threshold is reached" in {
    val mockLogger = mock[Logger]
    // Time window is large, rely on count threshold (3 events max before forcing log)
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 3600, throttleCount = 3)

    (mockLogger.warn(_: String)).expects("Test message").once()
    (mockLogger.warn(_: String)).expects("Test message (3 events since last log)").once()

    val r1 = rateLimited.warn("Test message") // event 1 -> logs (count = 1), resets count to 0
    val r2 = rateLimited.warn("Test message") // event 1 -> suppressed, count is now 1
    val r3 = rateLimited.warn("Test message") // event 2 -> suppressed, count is now 2
    val r4 = rateLimited.warn("Test message") // event 3 -> threshold reached (>= 3) -> logs, count = 3, resets to 0

    r1 shouldBe true
    r2 shouldBe false
    r3 shouldBe false
    r4 shouldBe true
  }

  it should "be thread-safe under concurrent load" in {
    val mockLogger  = mock[Logger]
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 3600, throttleCount = 1000)

    (mockLogger.warn(_: String)).expects(*).anyNumberOfTimes()

    val threadCount                   = 10
    val callsPerThread                = 100
    val executor                      = Executors.newFixedThreadPool(threadCount)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    val futures = (1 to threadCount).map { _ =>
      Future {
        (1 to callsPerThread).foreach(_ => rateLimited.warn("Concurrent message"))
      }
    }

    val allDone = Future.sequence(futures)
    Await.result(allDone, 5.seconds)
    executor.shutdown()

    succeed
  }

  it should "never throw exceptions on malformed inputs" in {
    val mockLogger  = mock[Logger]
    val rateLimited = RateLimitedLogger(mockLogger, throttleSeconds = 60, throttleCount = 100)

    (mockLogger.warn(_: String)).expects(*).once()

    noException should be thrownBy {
      rateLimited.warn(null)
    }
  }
}
