package org.llm4s.ox

import _root_.ox.resilience.retryEither
import _root_.ox.scheduling.Schedule
import org.llm4s.error.{ CancelledError, LLMError, NetworkError, RateLimitError, ServiceError }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*

class RetryScheduleSpec extends AnyFlatSpec with Matchers {

  /** A schedule that does not wait, so only a provider's retry-after makes a retry wait. */
  private def quick(retries: Int): Schedule = Schedule.fixedInterval(1.milli).maxRetries(retries)

  /** Returns each of `outcomes` in turn (the last one again once they run out) and records when it was called. */
  final private class Script[A](outcomes: Result[A]*) {
    val calls: CopyOnWriteArrayList[Long] = new CopyOnWriteArrayList[Long]()
    def apply(): Result[A] = {
      calls.add(System.nanoTime()): Unit
      outcomes(math.min(calls.size() - 1, outcomes.size - 1))
    }
    def count: Int = calls.size()
    def gapMillis(between: Int): Long =
      (calls.get(between) - calls.get(between - 1)) / 1000000L
  }

  private val unavailable: LLMError = ServiceError(503, "test", "unavailable")

  "RetrySchedule.retry" should "retry a recoverable error until the call succeeds" in {
    val script = new Script[String](Left(unavailable), Left(NetworkError("reset", None, "test")), Right("ok"))
    RetrySchedule.retry(quick(5))(script()) shouldBe Right("ok")
    script.count shouldBe 3
  }

  it should "not retry a client-error status" in {
    val badRequest = ServiceError(400, "test", "bad request")
    val script     = new Script[String](Left(badRequest), Right("never"))
    RetrySchedule.retry(quick(5))(script()) shouldBe Left(badRequest)
    script.count shouldBe 1
  }

  it should "not retry a cancellation" in {
    val cancelled = CancelledError("test")
    val script    = new Script[String](Left(cancelled), Right("never"))
    RetrySchedule.retry(quick(5))(script()) shouldBe Left(cancelled)
    script.count shouldBe 1
  }

  it should "return the last failure once the schedule's retries are spent" in {
    val script = new Script[String](Left(unavailable))
    RetrySchedule.retry(quick(2))(script()) shouldBe Left(unavailable)
    script.count shouldBe 3
  }

  it should "wait a RateLimitError's retry-after before the next attempt" in {
    val limited = RateLimitError("test", 300.millis)
    val script  = new Script[String](Left(limited), Right("ok"))
    RetrySchedule.retry(quick(3))(script()) shouldBe Right("ok")
    script.count shouldBe 2
    script.gapMillis(1) should be >= 300L
  }

  it should "wait a ServiceError's retry-after before the next attempt" in {
    val busy   = ServiceError(503, "test", "busy").withRetryAfter(250.millis)
    val script = new Script[String](Left(busy), Right("ok"))
    RetrySchedule.retry(quick(3))(script()) shouldBe Right("ok")
    script.gapMillis(1) should be >= 250L
  }

  it should "wait only the schedule's delay when the provider gave no hint" in {
    val script = new Script[String](Left(unavailable), Right("ok"))
    RetrySchedule.retry(Schedule.fixedInterval(10.millis).maxRetries(1))(script()) shouldBe Right("ok")
    script.gapMillis(1) should be < 2000L
  }

  it should "not wait for a retry-after once the retries are spent" in {
    val limited = RateLimitError("test", 30.seconds)
    val script  = new Script[String](Left(limited))
    val started = System.nanoTime()
    RetrySchedule.retry(quick(0))(script()) shouldBe Left(limited)
    (System.nanoTime() - started) / 1000000L should be < 5000L
  }

  "RetrySchedule.config" should "drive Ox's retryEither with the library's retry rule" in {
    val retried = new Script[Int](Left(unavailable), Right(1))
    retryEither(RetrySchedule.config[Int](quick(3)))(retried()) shouldBe Right(1)
    retried.count shouldBe 2

    val refused    = ServiceError(404, "test", "no such model")
    val notRetried = new Script[Int](Left(refused), Right(1))
    retryEither(RetrySchedule.config[Int](quick(3)))(notRetried()) shouldBe Left(refused)
    notRetried.count shouldBe 1
  }

  "RetrySchedule.default" should "allow three retries, each at most 30 seconds apart" in {
    val intervals = RetrySchedule.default.intervals().toList
    intervals should have size 3
    all(intervals) should be <= 30.seconds
  }

  "RetrySchedule.policy" should "retry what RetryPolicy retries" in {
    val policy = RetrySchedule.policy[Unit]
    policy.isWorthRetrying(unavailable) shouldBe true
    policy.isWorthRetrying(ServiceError(401, "test", "unauthorised")) shouldBe false
    policy.isWorthRetrying(RateLimitError("test")) shouldBe true
    policy.isSuccess(()) shouldBe true
    RetrySchedule.config[Unit]().schedule shouldBe RetrySchedule.default
  }
}
