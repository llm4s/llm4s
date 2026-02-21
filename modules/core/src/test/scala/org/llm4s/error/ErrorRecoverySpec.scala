package org.llm4s.error

import org.llm4s.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Tests for ErrorRecovery utilities: backoff retry logic and CircuitBreaker pattern
 */
class ErrorRecoverySpec extends AnyFlatSpec with Matchers {

  // ============ recoverWithBackoff ============

  "ErrorRecovery.recoverWithBackoff" should "return success immediately on first try" in {
    var callCount = 0
    val operation = () => {
      callCount += 1
      Result.success("success")
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 3, baseDelay = 10.millis)

    result shouldBe Right("success")
    callCount shouldBe 1
  }

  it should "retry on recoverable errors" in {
    var callCount = 0
    val operation = () => {
      callCount += 1
      if (callCount < 3) {
        Result.failure[String](RateLimitError("provider", 10L))
      } else {
        Result.success("success after retries")
      }
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 5, baseDelay = 10.millis)

    result shouldBe Right("success after retries")
    callCount shouldBe 3
  }

  it should "not retry on non-recoverable errors" in {
    var callCount = 0
    val operation = () => {
      callCount += 1
      Result.failure[String](AuthenticationError("provider", "invalid key"))
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 5, baseDelay = 10.millis)

    result.isLeft shouldBe true
    callCount shouldBe 1
  }

  it should "return ExecutionError after max attempts" in {
    var callCount = 0
    val operation = () => {
      callCount += 1
      Result.failure[String](RateLimitError("provider"))
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 3, baseDelay = 10.millis)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ExecutionError]
    result.left.toOption.get.message should include("failed after 3 attempts")
    callCount shouldBe 3
  }

  it should "retry on TimeoutError" in {
    var callCount = 0
    val operation = () => {
      callCount += 1
      if (callCount < 2) {
        Result.failure[String](TimeoutError("timeout", 30.seconds, "api-call"))
      } else {
        Result.success("recovered")
      }
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 3, baseDelay = 10.millis)

    result shouldBe Right("recovered")
    callCount shouldBe 2
  }

  it should "use retry delay from RateLimitError when available" in {
    var callCount                  = 0
    var lastCallTime: Option[Long] = None

    val operation = () => {
      val currentTime = System.currentTimeMillis()
      lastCallTime.foreach { last =>
        // Verify delay was applied (with some tolerance for test execution time)
        (currentTime - last) should be >= 5L
      }
      lastCallTime = Some(currentTime)

      callCount += 1
      if (callCount < 2) {
        Result.failure[String](RateLimitError("provider", 10L)) // 10ms retry delay
      } else {
        Result.success("success")
      }
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 3, baseDelay = 10.millis)

    result shouldBe Right("success")
  }

  // ============ CircuitBreaker ============

  "CircuitBreaker" should "start in Closed state" in {
    val cb = new ErrorRecovery.CircuitBreaker[String]()

    val result = cb.execute(() => Result.success("success"))

    result shouldBe Right("success")
  }

  it should "allow successful calls in Closed state" in {
    val cb = new ErrorRecovery.CircuitBreaker[Int]()

    (1 to 10).foreach { i =>
      val result = cb.execute(() => Result.success(i))
      result shouldBe Right(i)
    }
  }

  it should "track failures in Closed state" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 3)

    // First 2 failures - still Closed
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Should still allow calls
    val result = cb.execute(() => Result.success("success"))
    result shouldBe Right("success")
  }

  it should "open after reaching failure threshold" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 3)

    // Reach threshold
    (1 to 3).foreach(_ => cb.execute(() => Result.failure(ServiceError(500, "p", "error"))))

    // Next call should fail immediately with circuit breaker error
    val result = cb.execute(() => Result.success("should not execute"))

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ServiceError]
    result.left.toOption.get.message should include("Circuit breaker is open")
  }

  it should "reset failure count on success" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 3)

    // 2 failures
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Success resets count
    cb.execute(() => Result.success("success"))

    // 2 more failures - still not at threshold
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Should still be Closed
    val result = cb.execute(() => Result.success("success"))
    result shouldBe Right("success")
  }

  it should "transition from Open to HalfOpen after recovery timeout" in {
    var fakeTime = 0L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 50.millis,
      clock = () => fakeTime
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Advance the clock past the recovery timeout — no Thread.sleep needed
    fakeTime += 100

    // Next call should be allowed (HalfOpen state)
    val result = cb.execute(() => Result.success("recovered"))
    result shouldBe Right("recovered")
  }

  it should "close circuit on success in HalfOpen state" in {
    var fakeTime = 0L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 50.millis,
      clock = () => fakeTime
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Advance the clock past the recovery timeout — no Thread.sleep needed
    fakeTime += 100

    // Successful call in HalfOpen closes circuit
    cb.execute(() => Result.success("success"))

    // Should now be fully Closed - multiple calls should work
    (1 to 5).foreach { i =>
      val result = cb.execute(() => Result.success(s"call-$i"))
      result shouldBe Right(s"call-$i")
    }
  }

  it should "reopen circuit on failure in HalfOpen state" in {
    var fakeTime = 0L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 50.millis,
      clock = () => fakeTime
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Advance the clock past the recovery timeout — no Thread.sleep needed
    fakeTime += 100

    // Failure in HalfOpen reopens circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Should be Open again - immediate rejection
    val result = cb.execute(() => Result.success("should not execute"))
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Circuit breaker is open")
  }

  it should "work with default parameters" in {
    val cb = new ErrorRecovery.CircuitBreaker[String]()

    // Default threshold is 5
    (1 to 4).foreach(_ => cb.execute(() => Result.failure(ServiceError(500, "p", "error"))))

    // Should still be Closed
    val result = cb.execute(() => Result.success("success"))
    result shouldBe Right("success")
  }

  // ============ CircuitState ============

  "CircuitState" should "have Closed, Open, and HalfOpen states" in {
    import ErrorRecovery._

    val states: Seq[CircuitState] = Seq(Closed, Open, HalfOpen)

    states should have size 3
    states should contain(Closed)
    states should contain(Open)
    states should contain(HalfOpen)
  }

  // ============ Integration Tests ============

  "ErrorRecovery" should "handle mixed error types correctly" in {
    var callCount = 0
    val errors = List(
      RateLimitError("p"),              // Recoverable - retry
      TimeoutError("t", 1.second, "o"), // Recoverable - retry
      AuthenticationError("p", "m")     // Non-recoverable - stop
    )

    val operation = () => {
      val error = errors(Math.min(callCount, errors.size - 1))
      callCount += 1
      Result.failure[String](error)
    }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 5, baseDelay = 10.millis)

    result.isLeft shouldBe true
    callCount shouldBe 3 // Stopped on non-recoverable error
  }

  it should "combine with CircuitBreaker for comprehensive error handling" in {
    val cb        = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 3, recoveryTimeout = 50.millis)
    var callCount = 0

    // Simulate a flaky service that recovers
    val operation = () =>
      cb.execute { () =>
        callCount += 1
        if (callCount < 3) {
          Result.failure[String](ServiceError(503, "p", "unavailable"))
        } else {
          Result.success("success")
        }
      }

    val result = ErrorRecovery.recoverWithBackoff(operation, maxAttempts = 5, baseDelay = 10.millis)

    // The combination should eventually succeed
    result shouldBe Right("success")
  }
}
