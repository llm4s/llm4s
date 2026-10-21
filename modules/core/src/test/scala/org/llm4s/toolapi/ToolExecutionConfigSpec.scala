package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ToolExecutionConfigSpec extends AnyFlatSpec with Matchers {

  "ToolRetryPolicy.delayBeforeAttempt" should "return baseDelay for the first retry" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis, backoffFactor = 2.0)
    policy.delayBeforeAttempt(1) shouldBe 100.millis
  }

  it should "apply the backoff factor for later retries" in {
    val policy = ToolRetryPolicy(maxAttempts = 4, baseDelay = 100.millis, backoffFactor = 2.0)
    policy.delayBeforeAttempt(2) shouldBe 200.millis
    policy.delayBeforeAttempt(3) shouldBe 400.millis
  }

  it should "stay constant when backoffFactor is 1.0" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 50.millis, backoffFactor = 1.0)
    policy.delayBeforeAttempt(1) shouldBe 50.millis
    policy.delayBeforeAttempt(2) shouldBe 50.millis
  }

  // Jitter (#1708)

  private def retryable: Either[ToolCallError, ujson.Value] = Left(ToolCallError.Timeout("t", 1.second))

  "ToolRetryPolicy jitter" should "default to none, so the wait is exactly delayBeforeAttempt" in {
    val policy = ToolRetryPolicy(maxAttempts = 4, baseDelay = 100.millis)
    policy.jitter shouldBe 0.0
    Seq(0.0, 0.5, 0.999).foreach { sample =>
      (1 to 3).foreach(n => policy.jitteredDelayBeforeAttempt(n, sample) shouldBe policy.delayBeforeAttempt(n))
    }
  }

  it should "shorten the wait by up to jitter of it, from the full delay at sample 0" in {
    val equal = ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis).withJitter(0.5)
    equal.jitteredDelayBeforeAttempt(1, 0.0) shouldBe 200.millis
    equal.jitteredDelayBeforeAttempt(1, 0.5) shouldBe 150.millis
    equal.jitteredDelayBeforeAttempt(2, 0.5) shouldBe 300.millis
    val full = equal.withJitter(1.0)
    full.jitteredDelayBeforeAttempt(1, 0.25) shouldBe 150.millis
    full.jitteredDelayBeforeAttempt(1, 0.9999) shouldBe 0.millis
  }

  it should "keep every jittered wait within [(1 - jitter) * d, d] and vary it between samples" in {
    val policy = ToolRetryPolicy(maxAttempts = 5, baseDelay = 100.millis, jitter = 0.8)
    val random = new scala.util.Random(42)
    (1 to 4).foreach { n =>
      val max   = policy.delayBeforeAttempt(n).toMillis
      val waits = (1 to 200).map(_ => policy.jitteredDelayBeforeAttempt(n, random.nextDouble()).toMillis)
      all(waits) should ((be >= math.floor(max * 0.2).toLong).and(be <= max))
      waits.distinct.size should be > 1
    }
  }

  it should "reject a jitter outside 0.0 to 1.0" in {
    an[IllegalArgumentException] should be thrownBy ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli, jitter = -0.1)
    an[IllegalArgumentException] should be thrownBy ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli)
      .withJitter(1.5)
  }

  "ToolRegistry.retryDelay" should "apply the policy's jitter with the injected sample" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis, jitter = 1.0)
    ToolRegistry.retryDelay(retryable, attempt = 0, policy, () => 0.5) shouldBe Some(50.millis)
    ToolRegistry.retryDelay(retryable, attempt = 1, policy, () => 0.25) shouldBe Some(150.millis)
  }

  it should "wait exactly as without jitter when jitter is unset, whatever the sample" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis)
    ToolRegistry.retryDelay(retryable, attempt = 0, policy, () => 0.9) shouldBe Some(100.millis)
    ToolRegistry.retryDelay(retryable, attempt = 1, policy) shouldBe Some(200.millis)
  }

  it should "not draw a sample when no retry follows" in {
    val policy = ToolRetryPolicy(maxAttempts = 2, baseDelay = 100.millis, jitter = 1.0)
    val noDraw = () => fail("sampled although no retry follows")
    ToolRegistry.retryDelay(retryable, attempt = 1, policy, noDraw) shouldBe None
    ToolRegistry.retryDelay(Right(ujson.Null), attempt = 0, policy, noDraw) shouldBe None
  }
}
