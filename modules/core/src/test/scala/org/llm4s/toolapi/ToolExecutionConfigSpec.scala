package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ToolExecutionConfigSpec extends AnyFlatSpec with Matchers {

  // Capped backoff (#2816)

  "ToolRetryPolicy.delayBeforeAttempt" should "stop growing at maxDelay" in {
    val policy =
      ToolRetryPolicy(maxAttempts = 10, baseDelay = 100.millis, backoffFactor = 2.0, maxDelay = 1.second).toOption.get
    policy.delayBeforeAttempt(4) shouldBe 800.millis
    policy.delayBeforeAttempt(5) shouldBe 1.second
    policy.delayBeforeAttempt(9) shouldBe 1.second
  }

  it should "return maxDelay, not throw, once the exponential delay exceeds what a FiniteDuration holds" in {
    val policy = ToolRetryPolicy(maxAttempts = Int.MaxValue, baseDelay = 1.second, backoffFactor = 10.0).toOption.get
    policy.maxDelay shouldBe 30.seconds
    // 1 s * 10^17 is past FiniteDuration's 292 years; 10^400 is not even a finite Double.
    policy.delayBeforeAttempt(18) shouldBe 30.seconds
    policy.delayBeforeAttempt(401) shouldBe 30.seconds
    policy.delayBeforeAttempt(Int.MaxValue) shouldBe 30.seconds
    policy.jitteredDelayBeforeAttempt(Int.MaxValue, 0.0) shouldBe 30.seconds
  }

  it should "cap the first delay too when baseDelay is longer than maxDelay" in {
    val policy = ToolRetryPolicy(maxAttempts = 2, baseDelay = 5.seconds, maxDelay = 1.second).toOption.get
    policy.delayBeforeAttempt(1) shouldBe 1.second
  }

  "ToolRetryPolicy.delayBeforeAttempt" should "return baseDelay for the first retry" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis, backoffFactor = 2.0).toOption.get
    policy.delayBeforeAttempt(1) shouldBe 100.millis
  }

  it should "apply the backoff factor for later retries" in {
    val policy = ToolRetryPolicy(maxAttempts = 4, baseDelay = 100.millis, backoffFactor = 2.0).toOption.get
    policy.delayBeforeAttempt(2) shouldBe 200.millis
    policy.delayBeforeAttempt(3) shouldBe 400.millis
  }

  it should "stay constant when backoffFactor is 1.0" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 50.millis, backoffFactor = 1.0).toOption.get
    policy.delayBeforeAttempt(1) shouldBe 50.millis
    policy.delayBeforeAttempt(2) shouldBe 50.millis
  }

  // Jitter (#1708)

  private def retryable: Either[ToolCallError, ujson.Value] = Left(ToolCallError.Timeout("t", 1.second))

  "ToolRetryPolicy jitter" should "default to none, so the wait is exactly delayBeforeAttempt" in {
    val policy = ToolRetryPolicy(maxAttempts = 4, baseDelay = 100.millis).toOption.get
    policy.jitter shouldBe 0.0
    Seq(0.0, 0.5, 0.999).foreach { sample =>
      (1 to 3).foreach(n => policy.jitteredDelayBeforeAttempt(n, sample) shouldBe policy.delayBeforeAttempt(n))
    }
  }

  it should "shorten the wait by up to jitter of it, from the full delay at sample 0" in {
    val equal = ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis).flatMap(_.withJitter(0.5)).toOption.get
    equal.jitteredDelayBeforeAttempt(1, 0.0) shouldBe 200.millis
    equal.jitteredDelayBeforeAttempt(1, 0.5) shouldBe 150.millis
    equal.jitteredDelayBeforeAttempt(2, 0.5) shouldBe 300.millis
    val full = equal.withJitter(1.0).toOption.get
    full.jitteredDelayBeforeAttempt(1, 0.25) shouldBe 150.millis
    full.jitteredDelayBeforeAttempt(1, 0.9999) shouldBe 0.millis
  }

  it should "keep every jittered wait within [(1 - jitter) * d, d] and vary it between samples" in {
    val policy = ToolRetryPolicy(maxAttempts = 5, baseDelay = 100.millis, jitter = 0.8).toOption.get
    val random = new scala.util.Random(42)
    (1 to 4).foreach { n =>
      val max   = policy.delayBeforeAttempt(n).toMillis
      val waits = (1 to 200).map(_ => policy.jitteredDelayBeforeAttempt(n, random.nextDouble()).toMillis)
      all(waits) should ((be >= math.floor(max * 0.2).toLong).and(be <= max))
      waits.distinct.size should be > 1
    }
  }

  "ToolRetryPolicy setters" should "each change only their own field" in {
    val policy = ToolRetryPolicy(maxAttempts = 2, baseDelay = 10.millis).toOption.get
    policy.withMaxAttempts(4) shouldBe ToolRetryPolicy(maxAttempts = 4, baseDelay = 10.millis)
    policy.withBaseDelay(20.millis) shouldBe ToolRetryPolicy(maxAttempts = 2, baseDelay = 20.millis)
    policy.withBackoffFactor(3.0) shouldBe ToolRetryPolicy(maxAttempts = 2, baseDelay = 10.millis, backoffFactor = 3.0)
    policy.withJitter(0.25) shouldBe ToolRetryPolicy(maxAttempts = 2, baseDelay = 10.millis, jitter = 0.25)
    policy
      .withMaxDelay(5.seconds) shouldBe ToolRetryPolicy(maxAttempts = 2, baseDelay = 10.millis, maxDelay = 5.seconds)
    policy.withMaxDelay(5.seconds).map(_.maxDelay) shouldBe Right(5.seconds)
  }

  private def rejected(policy: org.llm4s.types.Result[ToolRetryPolicy], field: String) = {
    val error = policy.left.toOption.get
    error shouldBe a[ValidationError]
    error.message should include(field)
  }

  it should "return a ValidationError for a value out of range, never throw" in {
    val policy = ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli).toOption.get
    rejected(policy.withMaxAttempts(0), "maxAttempts")
    rejected(policy.withBackoffFactor(0.5), "backoffFactor")
    rejected(policy.withJitter(1.5), "jitter")
    rejected(policy.withBaseDelay(-1.milli), "baseDelay")
    rejected(policy.withMaxDelay(-1.milli), "maxDelay")
  }

  "ToolRetryPolicy.apply" should "return a ValidationError for a value out of range, never throw" in {
    rejected(ToolRetryPolicy(maxAttempts = 0, baseDelay = 1.milli), "maxAttempts")
    rejected(ToolRetryPolicy(maxAttempts = 1, baseDelay = -1.milli), "baseDelay")
    rejected(ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli, backoffFactor = 0.99), "backoffFactor")
    rejected(ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli, jitter = -0.1), "jitter")
    rejected(ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli, jitter = Double.NaN), "jitter")
    rejected(ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli, maxDelay = -1.second), "maxDelay")
  }

  it should "keep its constructor and copy private" in {
    assertTypeError("new ToolRetryPolicy(0, 1.milli, 2.0, 0.0, 1.second)")
    assertTypeError("ToolRetryPolicy(maxAttempts = 1, baseDelay = 1.milli).map(_.copy(maxAttempts = 0))")
  }

  "ToolExecutor.retryDelay" should "apply the policy's jitter with the injected sample" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis, jitter = 1.0).toOption.get
    ToolExecutor.retryDelay(retryable, attempt = 0, policy, () => 0.5) shouldBe Some(50.millis)
    ToolExecutor.retryDelay(retryable, attempt = 1, policy, () => 0.25) shouldBe Some(150.millis)
  }

  it should "wait exactly as without jitter when jitter is unset, whatever the sample" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 100.millis).toOption.get
    ToolExecutor.retryDelay(retryable, attempt = 0, policy, () => 0.9) shouldBe Some(100.millis)
    ToolExecutor.retryDelay(retryable, attempt = 1, policy) shouldBe Some(200.millis)
  }

  it should "not draw a sample when no retry follows" in {
    val policy = ToolRetryPolicy(maxAttempts = 2, baseDelay = 100.millis, jitter = 1.0).toOption.get
    val noDraw = () => fail("sampled although no retry follows")
    ToolExecutor.retryDelay(retryable, attempt = 1, policy, noDraw) shouldBe None
    ToolExecutor.retryDelay(Right(ujson.Null), attempt = 0, policy, noDraw) shouldBe None
  }

  // Growth-prone construction (#2346)

  "ToolExecutionConfig" should "default to no timeout and no retry" in {
    ToolExecutionConfig.default shouldBe ToolExecutionConfig()
    ToolExecutionConfig.default.timeout shouldBe None
    ToolExecutionConfig.default.retryPolicy shouldBe None
  }

  it should "change one field per setter, taking the value or an Option" in {
    val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 10.millis).toOption.get
    val config = ToolExecutionConfig.default.withTimeout(5.seconds).withRetryPolicy(policy)

    config shouldBe ToolExecutionConfig(timeout = Some(5.seconds), retryPolicy = Some(policy))
    config.withTimeout(Some(1.second)).timeout shouldBe Some(1.second)
    config.withTimeout(None) shouldBe ToolExecutionConfig(retryPolicy = Some(policy))
    config.withRetryPolicy(None) shouldBe ToolExecutionConfig(timeout = Some(5.seconds))
    config.withRetryPolicy(policy.withMaxAttempts(5).toOption).retryPolicy.map(_.maxAttempts) shouldBe Some(5)
  }

  it should "keep its constructor and copy private" in {
    assertTypeError("new ToolExecutionConfig(None, None)")
    assertTypeError("ToolExecutionConfig.default.copy(timeout = None)")
  }
}
