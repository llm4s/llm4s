package org.llm4s.reliability

import org.llm4s.annotation.Stable

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Configuration for reliable LLM provider calls.
 *
 * Provides retry logic, circuit breakers, and deadline enforcement
 * to make LLM API calls resilient to transient failures.
 *
 * @param retryPolicy Retry policy for transient failures
 * @param circuitBreaker Circuit breaker configuration
 * @param rateLimit Local rate limiting configuration
 * @param deadline Maximum time to wait for operation completion
 * @param enabled Whether reliability features are enabled (for opt-out)
 */
@Stable
final case class ReliabilityConfig private (
  retryPolicy: RetryPolicy,
  circuitBreaker: CircuitBreakerConfig,
  rateLimit: RateLimitConfig,
  deadline: Option[FiniteDuration],
  enabled: Boolean
) {
  def withEnabled(enabled: Boolean): ReliabilityConfig = copy(enabled = enabled)

  /** Disable all reliability features */
  def disabled: ReliabilityConfig =
    copy(enabled = false)

  /** Set retry policy */
  def withRetryPolicy(policy: RetryPolicy): ReliabilityConfig =
    copy(retryPolicy = policy)

  /** Set circuit breaker configuration */
  def withCircuitBreaker(config: CircuitBreakerConfig): ReliabilityConfig =
    copy(circuitBreaker = config)

  /** Set rate limit configuration */
  def withRateLimit(config: RateLimitConfig): ReliabilityConfig =
    copy(rateLimit = config)

  /** Set operation deadline */
  def withDeadline(duration: FiniteDuration): ReliabilityConfig =
    copy(deadline = Some(duration))

  def withDeadline(deadline: Option[FiniteDuration]): ReliabilityConfig = copy(deadline = deadline)

  /** Remove deadline */
  def withoutDeadline: ReliabilityConfig =
    copy(deadline = None)
}

object ReliabilityConfig {

  /** Creates a [[ReliabilityConfig]]. Named arguments are the supported way to construct one. */
  def apply(
    retryPolicy: RetryPolicy = RetryPolicy.exponentialBackoff(),
    circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig.default,
    rateLimit: RateLimitConfig = RateLimitConfig.disabled,
    deadline: Option[FiniteDuration] = Some(5.minutes),
    enabled: Boolean = true
  ): ReliabilityConfig =
    new ReliabilityConfig(retryPolicy, circuitBreaker, rateLimit, deadline, enabled)

  /**
   * Default configuration: exponential backoff + circuit breaker + 5 min deadline.
   */
  val default: ReliabilityConfig = ReliabilityConfig()

  /**
   * Conservative configuration: fewer retries, longer timeout.
   */
  val conservative: ReliabilityConfig = ReliabilityConfig(
    retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 2),
    circuitBreaker = CircuitBreakerConfig.conservative,
    deadline = Some(10.minutes)
  )

  /**
   * Aggressive configuration: more retries, faster recovery.
   */
  val aggressive: ReliabilityConfig = ReliabilityConfig(
    retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 5, baseDelay = 500.millis),
    circuitBreaker = CircuitBreakerConfig.aggressive,
    deadline = Some(3.minutes)
  )

  /**
   * Disabled configuration: no retries, no circuit breaker.
   * Use for testing or when you want direct error propagation.
   */
  val disabled: ReliabilityConfig = ReliabilityConfig(enabled = false)
}

/**
 * Circuit breaker configuration for service resilience.
 *
 * @param failureThreshold Number of consecutive failures before opening circuit
 * @param recoveryTimeout Time to wait before attempting recovery (half-open state)
 * @param successThreshold Number of successes in half-open state to close circuit
 */
@Stable
final case class CircuitBreakerConfig private (
  failureThreshold: Int,
  recoveryTimeout: FiniteDuration,
  successThreshold: Int
) {

  /** Set failure threshold */
  def withFailureThreshold(threshold: Int): CircuitBreakerConfig =
    copy(failureThreshold = threshold)

  /** Set recovery timeout */
  def withRecoveryTimeout(timeout: FiniteDuration): CircuitBreakerConfig =
    copy(recoveryTimeout = timeout)

  /** Set success threshold */
  def withSuccessThreshold(threshold: Int): CircuitBreakerConfig =
    copy(successThreshold = threshold)
}

object CircuitBreakerConfig {

  /** Creates a [[CircuitBreakerConfig]]. Named arguments are the supported way to construct one. */
  def apply(
    failureThreshold: Int = 5,
    recoveryTimeout: FiniteDuration = 30.seconds,
    successThreshold: Int = 2
  ): CircuitBreakerConfig =
    new CircuitBreakerConfig(failureThreshold, recoveryTimeout, successThreshold)

  /** Default circuit breaker: 5 failures, 30s recovery */
  val default: CircuitBreakerConfig = CircuitBreakerConfig()

  /** Conservative: fewer failures tolerated, longer recovery */
  val conservative: CircuitBreakerConfig = CircuitBreakerConfig(
    failureThreshold = 3,
    recoveryTimeout = 60.seconds,
    successThreshold = 3
  )

  /** Aggressive: more failures tolerated, faster recovery */
  val aggressive: CircuitBreakerConfig = CircuitBreakerConfig(
    failureThreshold = 10,
    recoveryTimeout = 15.seconds,
    successThreshold = 1
  )

  /** Disabled: never open circuit (for testing) */
  val disabled: CircuitBreakerConfig = CircuitBreakerConfig(
    failureThreshold = Int.MaxValue
  )
}

/**
 * Local token-bucket rate limiting configuration.
 *
 * @param enabled Whether local rate limiting is applied
 * @param requestsPerMinute Sustained request rate once the bucket is empty
 * @param burstCapacity Maximum tokens the bucket can hold, i.e. the largest burst allowed
 */
@Stable
final case class RateLimitConfig private (
  enabled: Boolean,
  requestsPerMinute: Int,
  burstCapacity: Int
) {

  /** Set requests per minute */
  def withRequestsPerMinute(rpm: Int): RateLimitConfig =
    copy(requestsPerMinute = rpm)

  /** Set burst capacity */
  def withBurstCapacity(burst: Int): RateLimitConfig =
    copy(burstCapacity = burst)

  /** Set enabled flag */
  def withEnabled(isEnabled: Boolean): RateLimitConfig =
    copy(enabled = isEnabled)
}

object RateLimitConfig {

  /** Creates a [[RateLimitConfig]]. Named arguments are the supported way to construct one. */
  def apply(enabled: Boolean = false, requestsPerMinute: Int = 60, burstCapacity: Int = 60): RateLimitConfig =
    new RateLimitConfig(enabled, requestsPerMinute, burstCapacity)

  /** Default: enabled, 60 requests/minute, burst of 60 */
  val default: RateLimitConfig = RateLimitConfig(enabled = true)

  /** Disabled: no local rate limiting (the field default in ReliabilityConfig) */
  val disabled: RateLimitConfig = RateLimitConfig(enabled = false)
}
