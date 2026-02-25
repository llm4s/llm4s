package org.llm4s.reliability

import org.llm4s.error._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class RetryPolicyTest extends AnyFunSuite with Matchers {

  test("exponentialBackoff calculates correct delays") {
    val policy = RetryPolicy.exponentialBackoff(maxAttempts = 4, baseDelay = 1.second, maxDelay = 10.seconds)
    val error  = TimeoutError("test", 1.second, "test-op")

    policy.maxAttempts shouldBe 4
    policy.delayFor(1, error) shouldBe 1.second
    policy.delayFor(2, error) shouldBe 2.seconds
    policy.delayFor(3, error) shouldBe 4.seconds
    policy.delayFor(4, error) shouldBe 8.seconds
  }

  test("exponentialBackoff respects maxDelay cap") {
    val policy = RetryPolicy.exponentialBackoff(maxAttempts = 10, baseDelay = 1.second, maxDelay = 5.seconds)
    val error  = TimeoutError("test", 1.second, "test-op")

    policy.delayFor(1, error) shouldBe 1.second
    policy.delayFor(2, error) shouldBe 2.seconds
    policy.delayFor(3, error) shouldBe 4.seconds
    policy.delayFor(4, error) shouldBe 5.seconds // Capped
    policy.delayFor(5, error) shouldBe 5.seconds // Capped
  }

  test("exponentialBackoff respects server-provided Retry-After header") {
    val policy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second)
    val rateLimitError = RateLimitError(
      provider = "test",
      retryAfter = 5000L // 5 seconds in millis
    )

    // Should use server delay instead of exponential
    policy.delayFor(1, rateLimitError) shouldBe 5.seconds
    policy.delayFor(2, rateLimitError) shouldBe 5.seconds
  }

  test("linearBackoff calculates correct delays") {
    val policy = RetryPolicy.linearBackoff(maxAttempts = 4, baseDelay = 2.seconds)
    val error  = TimeoutError("test", 1.second, "test-op")

    policy.maxAttempts shouldBe 4
    policy.delayFor(1, error) shouldBe 2.seconds
    policy.delayFor(2, error) shouldBe 4.seconds
    policy.delayFor(3, error) shouldBe 6.seconds
    policy.delayFor(4, error) shouldBe 8.seconds
  }

  test("fixedDelay returns constant delay") {
    val policy = RetryPolicy.fixedDelay(maxAttempts = 5, delay = 3.seconds)
    val error  = TimeoutError("test", 1.second, "test-op")

    policy.maxAttempts shouldBe 5
    policy.delayFor(1, error) shouldBe 3.seconds
    policy.delayFor(2, error) shouldBe 3.seconds
    policy.delayFor(3, error) shouldBe 3.seconds
  }

  test("noRetry policy has maxAttempts = 1 and nothing is retryable") {
    val policy = RetryPolicy.noRetry

    policy.maxAttempts shouldBe 1
    policy.isRetryable(RateLimitError("test", 60L)) shouldBe false
    policy.isRetryable(TimeoutError("test", 1.second, "test")) shouldBe false
    policy.isRetryable(NetworkError("test", None, "test")) shouldBe false
  }

  test("custom policy allows user-defined logic") {
    val policy = RetryPolicy.custom(
      attempts = 3,
      delayFn = (attempt, _) => (attempt * 500).millis,
      retryableFn = {
        case _: TimeoutError => true
        case _               => false
      }
    )

    val timeoutError = TimeoutError("test", 1.second, "test")
    val authError    = AuthenticationError("test", "test")

    policy.maxAttempts shouldBe 3
    policy.delayFor(1, timeoutError) shouldBe 500.millis
    policy.delayFor(2, timeoutError) shouldBe 1000.millis
    policy.isRetryable(timeoutError) shouldBe true
    policy.isRetryable(authError) shouldBe false
  }

  test("isRetryable returns true for RateLimitError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = RateLimitError("test", 60L)

    policy.isRetryable(error) shouldBe true
  }

  test("isRetryable returns true for TimeoutError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = TimeoutError("Timed out", 1.second, "test")

    policy.isRetryable(error) shouldBe true
  }

  test("isRetryable returns true for NetworkError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = NetworkError("Connection failed", None, "test")

    policy.isRetryable(error) shouldBe true
  }

  test("isRetryable returns true for ServiceError with 5xx status") {
    val policy = RetryPolicy.exponentialBackoff()

    policy.isRetryable(ServiceError(500, "test", "Internal server error")) shouldBe true
    policy.isRetryable(ServiceError(502, "test", "Bad gateway")) shouldBe true
    policy.isRetryable(ServiceError(503, "test", "Service unavailable")) shouldBe true
  }

  test("isRetryable returns true for ServiceError with 429 (rate limit)") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = ServiceError(429, "test", "Too many requests")

    policy.isRetryable(error) shouldBe true
  }

  test("isRetryable returns true for ServiceError with 408 (timeout)") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = ServiceError(408, "test", "Request timeout")

    policy.isRetryable(error) shouldBe true
  }

  test("isRetryable returns false for ServiceError with 4xx status (except 408/429)") {
    val policy = RetryPolicy.exponentialBackoff()

    policy.isRetryable(ServiceError(400, "test", "Bad request")) shouldBe false
    policy.isRetryable(ServiceError(401, "test", "Unauthorized")) shouldBe false
    policy.isRetryable(ServiceError(403, "test", "Forbidden")) shouldBe false
    policy.isRetryable(ServiceError(404, "test", "Not found")) shouldBe false
  }

  test("isRetryable returns false for AuthenticationError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = AuthenticationError("Invalid API key", "test")

    policy.isRetryable(error) shouldBe false
  }

  test("isRetryable returns false for ValidationError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = ValidationError("input", "Invalid input")

    policy.isRetryable(error) shouldBe false
  }

  test("isRetryable returns false for ConfigurationError") {
    val policy = RetryPolicy.exponentialBackoff()
    val error  = ConfigurationError("Missing config")

    policy.isRetryable(error) shouldBe false
  }
}
