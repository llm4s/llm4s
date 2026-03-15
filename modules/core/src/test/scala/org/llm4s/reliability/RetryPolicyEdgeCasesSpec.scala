package org.llm4s.reliability

import org.llm4s.error._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class RetryPolicyEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // linearBackoff with server-provided Retry-After
  // ==========================================================================

  "linearBackoff" should "use server-provided Retry-After when available" in {
    val policy         = RetryPolicy.linearBackoff(maxAttempts = 3, baseDelay = 2.seconds)
    val rateLimitError = RateLimitError("test", 5000L)

    // Should use server delay (5 seconds) instead of linear calculation
    policy.delayFor(1, rateLimitError) shouldBe 5.seconds
    policy.delayFor(2, rateLimitError) shouldBe 5.seconds
  }

  it should "use linear calculation when no server delay" in {
    val policy = RetryPolicy.linearBackoff(maxAttempts = 3, baseDelay = 2.seconds)
    val error  = TimeoutError("timeout", 1.second, "test")

    policy.delayFor(1, error) shouldBe 2.seconds
    policy.delayFor(2, error) shouldBe 4.seconds
    policy.delayFor(3, error) shouldBe 6.seconds
  }

  // ==========================================================================
  // fixedDelay with server-provided Retry-After
  // ==========================================================================

  "fixedDelay" should "use server-provided Retry-After when available" in {
    val policy         = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 2.seconds)
    val rateLimitError = RateLimitError("test", 10000L)

    policy.delayFor(1, rateLimitError) shouldBe 10.seconds
  }

  it should "use fixed delay when no server delay" in {
    val policy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 2.seconds)
    val error  = NetworkError("fail", None, "test")

    policy.delayFor(1, error) shouldBe 2.seconds
    policy.delayFor(2, error) shouldBe 2.seconds
  }

  // ==========================================================================
  // noRetry
  // ==========================================================================

  "noRetry" should "return Duration.Zero for delayFor" in {
    val policy = RetryPolicy.noRetry
    val error  = TimeoutError("test", 1.second, "test")

    policy.delayFor(1, error) shouldBe Duration.Zero
    policy.delayFor(5, error) shouldBe Duration.Zero
  }

  it should "report nothing as retryable" in {
    val policy = RetryPolicy.noRetry

    policy.isRetryable(RateLimitError("test", 60L)) shouldBe false
    policy.isRetryable(TimeoutError("test", 1.second, "test")) shouldBe false
    policy.isRetryable(ServiceError(500, "test", "error")) shouldBe false
    policy.isRetryable(NetworkError("test", None, "test")) shouldBe false
    policy.isRetryable(AuthenticationError("test", "test")) shouldBe false
  }

  // ==========================================================================
  // exponentialBackoff with RateLimitError without retryAfter
  // ==========================================================================

  "exponentialBackoff" should "use exponential calculation for RateLimitError without server delay" in {
    val policy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second, maxDelay = 32.seconds)
    // RateLimitError always has a retryDelay due to its retryDelay method fallback
    // so this will use the server-provided delay
    val rateLimitError = RateLimitError("test", 3000L)
    policy.delayFor(1, rateLimitError) shouldBe 3.seconds
  }

  // ==========================================================================
  // custom retry policy with custom retryable function
  // ==========================================================================

  "custom" should "use custom retryable function" in {
    val policy = RetryPolicy.custom(
      attempts = 2,
      delayFn = (_, _) => 100.millis,
      retryableFn = {
        case _: AuthenticationError => true // normally not retryable
        case _                      => false
      }
    )

    policy.isRetryable(AuthenticationError("test", "test")) shouldBe true
    policy.isRetryable(TimeoutError("timeout", 1.second, "test")) shouldBe false
    policy.isRetryable(RateLimitError("test", 60L)) shouldBe false
  }

  it should "use custom delay function" in {
    val policy = RetryPolicy.custom(
      attempts = 5,
      delayFn = (attempt, _) => (attempt * 100).millis
    )

    val error = TimeoutError("test", 1.second, "test")
    policy.delayFor(1, error) shouldBe 100.millis
    policy.delayFor(2, error) shouldBe 200.millis
    policy.delayFor(3, error) shouldBe 300.millis
  }

  // ==========================================================================
  // isRetryable: ProcessingError and ExecutionError
  // ==========================================================================

  "isRetryable" should "return false for ProcessingError" in {
    val policy = RetryPolicy.exponentialBackoff()
    policy.isRetryable(ProcessingError("test", "bad data")) shouldBe false
  }

  it should "return false for ExecutionError" in {
    val policy = RetryPolicy.exponentialBackoff()
    policy.isRetryable(ExecutionError("test", "op")) shouldBe false
  }
}
