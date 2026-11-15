package org.llm4s.reliability

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class ReliabilityConfigSpec extends AnyFlatSpec with Matchers {

  // RetryPolicy compares by reference, so the configs compared whole below share one policy.
  private val policy = RetryPolicy.noRetry

  // A config with no field at its default, so a setter that resets another field is caught.
  private def reliability(
    rateLimit: RateLimitConfig = RateLimitConfig.default,
    deadline: Option[FiniteDuration] = Some(1.minute),
    enabled: Boolean = false
  ): ReliabilityConfig =
    ReliabilityConfig(
      retryPolicy = policy,
      circuitBreaker = CircuitBreakerConfig.aggressive,
      rateLimit = rateLimit,
      deadline = deadline,
      enabled = enabled
    )

  // A rate limit with no field at its default, so a setter that resets another field is caught.
  private val rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 30, burstCapacity = 10)

  // ==========================================================================
  // ReliabilityConfig defaults and presets
  // ==========================================================================

  "ReliabilityConfig.default" should "have sensible defaults" in {
    val config = ReliabilityConfig.default
    config.enabled shouldBe true
    config.deadline shouldBe Some(5.minutes)
    config.retryPolicy.maxAttempts shouldBe 3
  }

  "ReliabilityConfig.conservative" should "have fewer retries and longer deadline" in {
    val config = ReliabilityConfig.conservative
    config.enabled shouldBe true
    config.retryPolicy.maxAttempts shouldBe 2
    config.deadline shouldBe Some(10.minutes)
    config.circuitBreaker.failureThreshold shouldBe 3
  }

  "ReliabilityConfig.aggressive" should "have more retries and shorter deadline" in {
    val config = ReliabilityConfig.aggressive
    config.enabled shouldBe true
    config.retryPolicy.maxAttempts shouldBe 5
    config.deadline shouldBe Some(3.minutes)
    config.circuitBreaker.failureThreshold shouldBe 10
  }

  "ReliabilityConfig.disabled" should "have enabled = false" in {
    val config = ReliabilityConfig.disabled
    config.enabled shouldBe false
  }

  // ==========================================================================
  // ReliabilityConfig fluent API
  // ==========================================================================

  "ReliabilityConfig fluent API" should "support disabled" in {
    val config = ReliabilityConfig.default.disabled
    config.enabled shouldBe false
  }

  it should "support withRetryPolicy" in {
    val policy = RetryPolicy.noRetry
    val config = ReliabilityConfig.default.withRetryPolicy(policy)
    config.retryPolicy.maxAttempts shouldBe 1
  }

  it should "support withCircuitBreaker" in {
    val cb     = CircuitBreakerConfig(failureThreshold = 99)
    val config = ReliabilityConfig.default.withCircuitBreaker(cb)
    config.circuitBreaker.failureThreshold shouldBe 99
  }

  it should "support withDeadline" in {
    val config = ReliabilityConfig.default.withDeadline(1.minute)
    config.deadline shouldBe Some(1.minute)
  }

  it should "support withoutDeadline" in {
    val config = ReliabilityConfig.default.withoutDeadline
    config.deadline shouldBe None
  }

  it should "support withEnabled" in {
    reliability().withEnabled(true) shouldBe reliability(enabled = true)
    reliability(enabled = true).withEnabled(false) shouldBe reliability()
  }

  it should "support withDeadline with an Option" in {
    reliability().withDeadline(None) shouldBe reliability(deadline = None)
    reliability(deadline = None).withDeadline(Some(2.minutes)) shouldBe reliability(deadline = Some(2.minutes))
  }

  it should "support withRateLimit" in {
    ReliabilityConfig.default.withRateLimit(RateLimitConfig.default).rateLimit shouldBe RateLimitConfig.default
    reliability().withRateLimit(RateLimitConfig.disabled) shouldBe reliability(rateLimit = RateLimitConfig.disabled)
  }

  it should "support chaining" in {
    val config = ReliabilityConfig.default
      .withRetryPolicy(RetryPolicy.fixedDelay(maxAttempts = 5, delay = 1.second))
      .withCircuitBreaker(CircuitBreakerConfig.aggressive)
      .withDeadline(2.minutes)

    config.retryPolicy.maxAttempts shouldBe 5
    config.circuitBreaker.failureThreshold shouldBe 10
    config.deadline shouldBe Some(2.minutes)
  }

  it should "not mutate the original config" in {
    val original = ReliabilityConfig.default
    val modified = original.disabled.withoutDeadline
    original.enabled shouldBe true
    original.deadline shouldBe Some(5.minutes)
    modified.enabled shouldBe false
    modified.deadline shouldBe None
  }

  // ==========================================================================
  // CircuitBreakerConfig defaults and presets
  // ==========================================================================

  "CircuitBreakerConfig.default" should "have sensible defaults" in {
    val config = CircuitBreakerConfig.default
    config.failureThreshold shouldBe 5
    config.recoveryTimeout shouldBe 30.seconds
    config.successThreshold shouldBe 2
  }

  "CircuitBreakerConfig.conservative" should "tolerate fewer failures" in {
    val config = CircuitBreakerConfig.conservative
    config.failureThreshold shouldBe 3
    config.recoveryTimeout shouldBe 60.seconds
    config.successThreshold shouldBe 3
  }

  "CircuitBreakerConfig.aggressive" should "tolerate more failures with faster recovery" in {
    val config = CircuitBreakerConfig.aggressive
    config.failureThreshold shouldBe 10
    config.recoveryTimeout shouldBe 15.seconds
    config.successThreshold shouldBe 1
  }

  "CircuitBreakerConfig.disabled" should "never open circuit" in {
    CircuitBreakerConfig.disabled.failureThreshold shouldBe Int.MaxValue
  }

  // ==========================================================================
  // CircuitBreakerConfig fluent API
  // ==========================================================================

  "CircuitBreakerConfig fluent API" should "support withFailureThreshold" in {
    CircuitBreakerConfig.default.withFailureThreshold(20).failureThreshold shouldBe 20
  }

  it should "support withRecoveryTimeout" in {
    CircuitBreakerConfig.default.withRecoveryTimeout(2.minutes).recoveryTimeout shouldBe 2.minutes
  }

  it should "support withSuccessThreshold" in {
    CircuitBreakerConfig.default.withSuccessThreshold(5).successThreshold shouldBe 5
  }

  it should "support chaining" in {
    val config = CircuitBreakerConfig.default
      .withFailureThreshold(7)
      .withRecoveryTimeout(45.seconds)
      .withSuccessThreshold(3)

    config.failureThreshold shouldBe 7
    config.recoveryTimeout shouldBe 45.seconds
    config.successThreshold shouldBe 3
  }

  // ==========================================================================
  // RateLimitConfig defaults and presets
  // ==========================================================================

  "RateLimitConfig()" should "be disabled, 60 requests per minute, burst 60" in {
    val config = RateLimitConfig()
    config.enabled shouldBe false
    config.requestsPerMinute shouldBe 60
    config.burstCapacity shouldBe 60
  }

  "RateLimitConfig.default" should "be enabled, 60 requests per minute, burst 60" in {
    RateLimitConfig.default shouldBe RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = 60)
  }

  "RateLimitConfig.disabled" should "be disabled, 60 requests per minute, burst 60" in {
    RateLimitConfig.disabled shouldBe RateLimitConfig(enabled = false, requestsPerMinute = 60, burstCapacity = 60)
  }

  "ReliabilityConfig.default" should "not rate limit" in {
    ReliabilityConfig.default.rateLimit shouldBe RateLimitConfig.disabled
  }

  // ==========================================================================
  // RateLimitConfig fluent API
  // ==========================================================================

  "RateLimitConfig fluent API" should "support withRequestsPerMinute" in {
    rateLimit.withRequestsPerMinute(120) shouldBe RateLimitConfig(
      enabled = true,
      requestsPerMinute = 120,
      burstCapacity = 10
    )
  }

  it should "support withBurstCapacity" in {
    rateLimit.withBurstCapacity(5) shouldBe RateLimitConfig(enabled = true, requestsPerMinute = 30, burstCapacity = 5)
  }

  it should "support withEnabled" in {
    rateLimit.withEnabled(false) shouldBe RateLimitConfig(enabled = false, requestsPerMinute = 30, burstCapacity = 10)
    rateLimit.withEnabled(false).withEnabled(true) shouldBe rateLimit
  }
}
