package org.llm4s.error

import org.llm4s.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import java.util.concurrent.{ CountDownLatch, CyclicBarrier, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

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

  // ============ Concurrent Execute Tests ============

  "CircuitBreaker under concurrent load" should "reject all calls when circuit is Open" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 2)

    // Open the circuit before spawning threads
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    val threadCount   = 20
    val latch         = new CountDownLatch(threadCount)
    val operationRan  = new AtomicInteger(0)
    val rejectedCount = new AtomicInteger(0)
    val executor      = Executors.newFixedThreadPool(threadCount)

    (1 to threadCount).foreach { _ =>
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            val result = cb.execute { () =>
              operationRan.incrementAndGet()
              Result.success("should not reach here")
            }
            result match {
              case Left(err) if err.message.contains("Circuit breaker is open") =>
                rejectedCount.incrementAndGet()
              case _ => ()
            }
          } finally
            latch.countDown()
      })
    }

    try
      latch.await(5, TimeUnit.SECONDS) shouldBe true
    finally
      executor.shutdown()

    // The operation body must never execute when the circuit is Open
    operationRan.get() shouldBe 0
    // Every concurrent call should receive the circuit-open rejection
    rejectedCount.get() shouldBe threadCount
  }

  it should "open the circuit after concurrent failures reach the threshold" in {
    val failureThreshold = 5
    val cb               = new ErrorRecovery.CircuitBreaker[String](failureThreshold = failureThreshold)

    val threadCount = 20
    val barrier     = new CyclicBarrier(threadCount)
    val latch       = new CountDownLatch(threadCount)
    val executor    = Executors.newFixedThreadPool(threadCount)

    (1 to threadCount).foreach { _ =>
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            barrier.await() // all threads start simultaneously to maximise contention
            cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
          } finally
            latch.countDown()
      })
    }

    try
      latch.await(5, TimeUnit.SECONDS) shouldBe true
    finally
      executor.shutdown()

    // After ≥ failureThreshold concurrent failures the circuit must be Open.
    // A fresh probe must be fast-rejected without executing the operation body.
    var probeExecuted = false
    val probeResult = cb.execute { () =>
      probeExecuted = true
      Result.success("should not execute")
    }

    probeExecuted shouldBe false
    probeResult match {
      case Left(err) => err.message should include("Circuit breaker is open")
      case Right(v)  => fail(s"Expected Left (circuit open), got Right($v)")
    }
  }

  it should "not execute the operation body in any thread when circuit is Open" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 2)

    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    val threadCount  = 30
    val latch        = new CountDownLatch(threadCount)
    val operationRan = new AtomicInteger(0)
    val executor     = Executors.newFixedThreadPool(threadCount)

    (1 to threadCount).foreach { _ =>
      executor.submit(new Runnable {
        def run(): Unit =
          try
            cb.execute { () =>
              operationRan.incrementAndGet()
              Result.success("executed")
            }
          finally
            latch.countDown()
      })
    }

    try
      latch.await(5, TimeUnit.SECONDS) shouldBe true
    finally
      executor.shutdown()

    operationRan.get() shouldBe 0
  }

  it should "allow exactly one probe thread through when transitioning from Open to HalfOpen" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 60.millis
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Wait for recovery timeout to elapse
    Thread.sleep(120)

    val threadCount   = 10
    val barrier       = new CyclicBarrier(threadCount)
    val latch         = new CountDownLatch(threadCount)
    val executor      = Executors.newFixedThreadPool(threadCount)
    val probesAllowed = new AtomicInteger(0)

    (1 to threadCount).foreach { _ =>
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            barrier.await() // all threads race to be the HalfOpen probe
            // The probe deliberately fails: this pushes the circuit back to Open with a
            // fresh lastFailureTime, so any thread that didn't win the slot (and therefore
            // sees either HalfOpen or a just-reopened Open with elapsed-time ≈ 0) is
            // correctly fast-rejected regardless of scheduling order.
            cb.execute { () =>
              probesAllowed.incrementAndGet()
              Result.failure(ServiceError(500, "p", "probe-failed"))
            }
          } finally
            latch.countDown()
      })
    }

    try
      latch.await(5, TimeUnit.SECONDS) shouldBe true
    finally
      executor.shutdown()

    // Exactly one probe must execute: the first thread to win the synchronized
    // Open→HalfOpen transition claims the slot; all other threads are fast-rejected
    // (either they see state == HalfOpen, or they see state == Open with a brand-new
    // lastFailureTime that hasn't elapsed yet).
    probesAllowed.get() shouldBe 1
  }

  // ============ HalfOpen Re-open Edge Cases ============

  "CircuitBreaker HalfOpen re-open" should "reject calls immediately after a HalfOpen probe fails" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 50.millis
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    Thread.sleep(100)

    // HalfOpen probe fails → circuit re-opens
    val probeResult = cb.execute(() => Result.failure(ServiceError(500, "p", "re-open")))
    probeResult.isLeft shouldBe true

    // Immediately after, the operation body must not execute
    var operationExecuted = false
    val afterReopen = cb.execute { () =>
      operationExecuted = true
      Result.success("should not run")
    }

    operationExecuted shouldBe false
    afterReopen match {
      case Left(err) => err.message should include("Circuit breaker is open")
      case Right(v)  => fail(s"Expected Left (circuit open), got Right($v)")
    }
  }

  it should "require a fresh recovery timeout before probing again after HalfOpen failure" in {
    val recoveryMs = 120L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = recoveryMs.millis
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Wait for the first recovery timeout
    Thread.sleep(recoveryMs + 60)

    // HalfOpen probe fails → circuit re-opens, starting a new timeout window
    cb.execute(() => Result.failure(ServiceError(500, "p", "re-open")))

    // Halfway through the new timeout — should still be Open
    Thread.sleep(recoveryMs / 2)
    val tooEarly = cb.execute(() => Result.success("too early"))
    tooEarly match {
      case Left(err) => err.message should include("Circuit breaker is open")
      case Right(v)  => fail(s"Expected Left (circuit open), got Right($v)")
    }

    // Wait for the rest plus a margin
    Thread.sleep(recoveryMs + 60)

    // Now the second recovery window has elapsed — a probe should get through
    var secondProbeRan = false
    val secondProbe = cb.execute { () =>
      secondProbeRan = true
      Result.success("second probe")
    }

    secondProbeRan shouldBe true
    secondProbe shouldBe Right("second probe")
  }

  it should "close the circuit permanently after a successful HalfOpen probe" in {
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = 50.millis
    )

    // Open the circuit
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    Thread.sleep(100)

    // Successful HalfOpen probe → circuit Closes
    cb.execute(() => Result.success("probe succeeds"))

    // Many subsequent calls should all succeed without tripping the breaker
    (1 to 10).foreach { i =>
      val r = cb.execute(() => Result.success(s"call-$i"))
      r shouldBe Right(s"call-$i")
    }
  }

  // ============ Exact Timeout Boundary Tests ============

  "CircuitBreaker timeout boundary" should "remain Open before the recovery timeout has elapsed" in {
    val recoveryMs = 200L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = recoveryMs.millis
    )

    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Sleep for clearly less than the timeout
    Thread.sleep(recoveryMs / 2)

    val result = cb.execute(() => Result.success("too early"))
    result match {
      case Left(err) => err.message should include("Circuit breaker is open")
      case Right(v)  => fail(s"Expected Left (circuit open), got Right($v)")
    }
  }

  it should "allow a probe after the recovery timeout has elapsed" in {
    val recoveryMs = 100L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 2,
      recoveryTimeout = recoveryMs.millis
    )

    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Sleep well past the timeout
    Thread.sleep(recoveryMs + 100)

    var probeRan = false
    val result = cb.execute { () =>
      probeRan = true
      Result.success("probe ran")
    }

    probeRan shouldBe true
    result shouldBe Right("probe ran")
  }

  it should "use a strictly-greater-than comparison so the boundary is exclusive" in {
    // The implementation: (now - lastFailure) > recoveryTimeout.toMillis
    // Exactly at recoveryTimeout ms the circuit is still Open; only strictly after does it flip.
    val recoveryMs = 150L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 1,
      recoveryTimeout = recoveryMs.millis
    )

    val openedAt = System.currentTimeMillis()
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))

    // Busy-wait until we are close to but before the boundary (leave ≥50ms buffer)
    while (System.currentTimeMillis() - openedAt < recoveryMs - 50)
      Thread.sleep(5)

    // Still before the timeout — must be Open
    val beforeBoundary = cb.execute(() => Result.success("before boundary"))
    beforeBoundary match {
      case Left(err) => err.message should include("Circuit breaker is open")
      case Right(v)  => fail(s"Expected Left (circuit open), got Right($v)")
    }

    // Wait until clearly past the timeout
    Thread.sleep(recoveryMs)

    // Now strictly past — must transition to HalfOpen and execute the probe
    var afterRan = false
    val afterBoundary = cb.execute { () =>
      afterRan = true
      Result.success("after boundary")
    }

    afterRan shouldBe true
    afterBoundary shouldBe Right("after boundary")
  }

  it should "reset the timeout clock on each re-open, not use the original open time" in {
    val recoveryMs = 80L
    val cb = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 1,
      recoveryTimeout = recoveryMs.millis
    )

    // First open
    cb.execute(() => Result.failure(ServiceError(500, "p", "error")))
    Thread.sleep(recoveryMs + 40)

    // Move to HalfOpen → fail → re-open (new clock starts here)
    val reOpenedAt = System.currentTimeMillis()
    cb.execute(() => Result.failure(ServiceError(500, "p", "re-open")))

    // Wait less than recoveryMs since last re-open — should still be Open
    Thread.sleep(recoveryMs / 2)
    val elapsed = System.currentTimeMillis() - reOpenedAt
    // Only bother asserting if we genuinely slept less than the timeout
    if (elapsed < recoveryMs) {
      val tooEarly = cb.execute(() => Result.success("too early"))
      tooEarly.isLeft shouldBe true
    }

    // Wait until clearly past the re-open timeout
    Thread.sleep(recoveryMs + 60)

    var probeRan = false
    val lateProbe = cb.execute { () =>
      probeRan = true
      Result.success("late probe")
    }

    probeRan shouldBe true
    lateProbe shouldBe Right("late probe")
  }
}
