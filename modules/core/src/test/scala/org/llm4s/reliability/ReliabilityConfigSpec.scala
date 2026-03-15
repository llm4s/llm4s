package org.llm4s.reliability

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class ReliabilityConfigSpec extends AnyFlatSpec with Matchers {

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
}
