package org.llm4s.jev

import org.llm4s.error.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** The retry loop, run on a fake clock: nothing sleeps, and every delay it chose is recorded. */
class JevRetrySpec extends AnyFlatSpec with Matchers {

  /** A loop over `policy` with no jitter, whose sleeps advance the clock and are recorded. */
  final private class Harness(policy: JevRetryPolicy, random: () => Double = () => 0.0) {
    var now: Long                          = 0L
    val slept: ArrayBuffer[FiniteDuration] = ArrayBuffer.empty
    var calls                              = 0

    val retry = new JevRetry(
      policy,
      nanoTime = () => now,
      sleep = d => { slept += d; now += d.toNanos },
      random = random
    )

    /** Runs an operation that returns `outcomes` in turn, the last one repeating. */
    def run(outcomes: Result[String]*): Result[String] =
      retry.run { _ =>
        val outcome = outcomes(math.min(calls, outcomes.size - 1))
        calls += 1
        outcome
      }
  }

  private val unavailable: Result[String] = Left(ServiceError(503, "jev", "down"))

  // ---- the schedule ----

  "The retry loop" should "send a request that succeeds once, and never sleep" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(Right("ok")) shouldBe Right("ok")
    h.calls shouldBe 1
    h.slept shouldBe empty
  }

  it should "retry twice by default, doubling from 0.5 s, and return the last error as it is" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(unavailable) shouldBe unavailable
    h.calls shouldBe 3
    h.slept.toSeq shouldBe Seq(500.millis, 1.second)
  }

  it should "return the success that follows failures" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(unavailable, unavailable, Right("late")) shouldBe Right("late")
    h.calls shouldBe 3
  }

  it should "cap the doubling at the policy's longest delay" in {
    val h = new Harness(
      JevRetryPolicy(maxRetries = 5, backoffInitial = 1.second, backoffMax = 3.seconds, jitter = 0.0, budget = 1.hour)
    )

    h.run(unavailable)
    h.slept.toSeq shouldBe Seq(1.second, 2.seconds, 3.seconds, 3.seconds, 3.seconds)
  }

  it should "take the jitter fraction off each delay" in {
    val policy = JevRetryPolicy(
      maxRetries = 1,
      backoffInitial = 800.millis,
      backoffMax = 5.seconds,
      jitter = 0.25,
      budget = 1.hour
    )

    new Harness(policy, () => 0.0).retry.backoff(1) shouldBe 800.millis
    new Harness(policy, () => 1.0).retry.backoff(1) shouldBe 600.millis
    new Harness(policy, () => 0.5).retry.backoff(1) shouldBe 700.millis
  }

  it should "not overflow on a very high attempt number" in {
    val h = new Harness(JevRetryPolicy.default)

    h.retry.backoff(5000) shouldBe 5.seconds
  }

  it should "send each request once when the policy allows no retries" in {
    val h = new Harness(JevRetryPolicy.none)

    h.run(unavailable) shouldBe unavailable
    h.calls shouldBe 1
    h.slept shouldBe empty
  }

  // ---- what the server asked for ----

  it should "wait the delay a rate limit asks for instead of the computed one" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(Left(RateLimitError("jev", 7.seconds)), Right("ok")) shouldBe Right("ok")
    h.slept.toSeq shouldBe Seq(7.seconds)
  }

  it should "wait the delay a failed service asks for" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(Left(ServiceError(529, "jev", "overloaded").withRetryAfter(2.seconds)), Right("ok")) shouldBe Right("ok")
    h.slept.toSeq shouldBe Seq(2.seconds)
  }

  it should "use the computed delay when a rate limit names none" in {
    val h = new Harness(JevRetryPolicy.default)

    h.run(Left(RateLimitError("jev")), Right("ok")) shouldBe Right("ok")
    h.slept.toSeq shouldBe Seq(500.millis)
  }

  // ---- the budget ----

  it should "not start a retry whose delay would reach the budget" in {
    val limited = Left(RateLimitError("jev", 40.seconds))
    val h       = new Harness(JevRetryPolicy.default)

    h.run(limited) shouldBe limited
    h.calls shouldBe 1
    h.slept shouldBe empty
  }

  it should "count the time already spent, delays included, against the budget" in {
    val h      = new Harness(JevRetryPolicy.default.withMaxRetries(5))
    val twenty = Left(RateLimitError("jev", 20.seconds))

    h.run(twenty) shouldBe twenty
    h.calls shouldBe 2
    h.slept.toSeq shouldBe Seq(20.seconds)
  }

  it should "stop at a delay that exactly reaches the budget" in {
    val policy = JevRetryPolicy.default.withBudget(10.seconds)
    val exact  = Left(RateLimitError("jev", 10.seconds))
    val inside = Left(RateLimitError("jev", 9.seconds + 999.millis))

    new Harness(policy).run(exact).isLeft shouldBe true
    val h = new Harness(policy)
    h.run(inside, Right("ok")) shouldBe Right("ok")
    h.calls shouldBe 2
  }

  it should "count the time an attempt itself took against the budget" in {
    def callsWhenEachAttemptTakes(took: FiniteDuration): Int = {
      val h = new Harness(JevRetryPolicy.default.withBudget(5.seconds).withMaxRetries(1))
      h.retry.run { _ =>
        h.calls += 1; h.now += took.toNanos; unavailable
      }
      h.calls
    }

    callsWhenEachAttemptTakes(1.second) shouldBe 2    // 1 s spent plus the 0.5 s wait fits in 5 s
    callsWhenEachAttemptTakes(4800.millis) shouldBe 1 // 4.8 s spent plus the 0.5 s wait does not
  }

  it should "hand each attempt what is left of the budget, so a retry cannot overrun it" in {
    val h      = new Harness(JevRetryPolicy.default) // 30 s budget, 0.5 s first wait
    val handed = ArrayBuffer.empty[FiniteDuration]

    h.retry.run { left =>
      handed += left; h.calls += 1; h.now += 20.seconds.toNanos; unavailable
    }

    // the first attempt gets the whole budget; it took 20 s, then 0.5 s was waited: 9.5 s is left for the second,
    // which also takes "20 s", leaving nothing for the 1 s wait before a third
    handed.toSeq shouldBe Seq(30.seconds, 9500.millis)
    h.calls shouldBe 2
  }

  it should "not start an attempt when the wait itself used up the budget" in {
    val policy = JevRetryPolicy.default.withBudget(10.seconds)
    var now    = 0L
    var calls  = 0
    // a wait that overruns (an OS that slept longer than asked) leaves nothing for the next attempt
    val retry = new JevRetry(policy, nanoTime = () => now, sleep = _ => now += 11.seconds.toNanos, random = () => 0.0)

    retry.run { _ =>
      calls += 1; unavailable
    } shouldBe unavailable
    calls shouldBe 1
  }

  // ---- what is retried: LLM4S's one rule ----

  it should "retry what is transient: a rate limit, 408, 429, 5xx, a network failure, a timeout" in {
    val transient: Seq[LLMError] = Seq(
      RateLimitError("jev"),
      ServiceError(408, "jev", "x"),
      ServiceError(429, "jev", "x"),
      ServiceError(500, "jev", "x"),
      ServiceError(502, "jev", "x"),
      ServiceError(529, "jev", "overloaded"),
      NetworkError("down", None, "https://api.typesafe.ai"),
      TimeoutError("slow", 30.seconds, "jev")
    )

    transient.foreach { error =>
      val h = new Harness(JevRetryPolicy.default.withMaxRetries(1))
      withClue(error.getClass.getSimpleName + " ") {
        h.run(Left(error), Right("ok")) shouldBe Right("ok")
        h.calls shouldBe 2
      }
    }
  }

  it should "not retry what retrying cannot fix: a rejected key, an invalid request, a client error" in {
    val permanent: Seq[LLMError] = Seq(
      AuthenticationError("jev", "bad key"),
      ValidationError("request", "bad"),
      ServiceError(404, "jev", "no"),
      ServiceError(422, "jev", "no"),
      ProcessingError("jev-response", "malformed"),
      ConfigurationError("bad")
    )

    permanent.foreach { error =>
      val h = new Harness(JevRetryPolicy.default)
      withClue(error.getClass.getSimpleName + " ") {
        h.run(Left(error), Right("ok")) shouldBe Left(error)
        h.calls shouldBe 1
        h.slept shouldBe empty
      }
    }
  }

  // ---- cancellation ----

  it should "return a cancellation at once, without retrying it" in {
    val cancelled = Left(CancelledError("jev"))
    val h         = new Harness(JevRetryPolicy.default)

    h.run(cancelled) shouldBe cancelled
    h.calls shouldBe 1
  }

  it should "turn an interrupt thrown by the operation into a CancelledError" in {
    val h = new Harness(JevRetryPolicy.default)

    val outcome = h.retry.run[String](_ => throw new InterruptedException("stop"))

    outcome.left.toOption.get shouldBe a[CancelledError]
    h.slept shouldBe empty
    Thread.interrupted() shouldBe true // the flag is restored for the caller; clear it for the next test
  }

  it should "end the call with a CancelledError, and keep the interrupt flag, when interrupted while waiting" in {
    val retry = new JevRetry(
      JevRetryPolicy.default,
      nanoTime = () => 0L,
      sleep = _ => throw new InterruptedException("stop"),
      random = () => 0.0
    )
    var calls = 0

    val outcome = retry.run { _ =>
      calls += 1; unavailable
    }

    outcome.left.toOption.get shouldBe a[CancelledError]
    calls shouldBe 1
    Thread.interrupted() shouldBe true
  }
}
