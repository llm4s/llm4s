package org.llm4s.samples.reliability

import org.llm4s.error.{ ErrorRecovery, ServiceError, ValidationError }
import org.llm4s.types.Result

import java.time.Instant
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * Retrying your own operation with [[org.llm4s.error.ErrorRecovery]], with no provider and no configuration.
 *
 * Four scenarios, all deterministic and instant because `sleepFn` records the delay instead of sleeping and
 * the circuit breaker's `clock` is a value the sample moves by hand:
 *
 *  1. A flaky operation fails twice with a retryable 503 [[org.llm4s.error.ServiceError]], then succeeds.
 *  1. A [[org.llm4s.error.ValidationError]] is not transient, so it is returned after one attempt.
 *  1. An operation that always fails: the attempts run out and an `ExecutionError` names the last error.
 *  1. A `CircuitBreaker` opens after repeated failures, rejects calls, and lets one through again once
 *     `recoveryTimeout` has passed on its clock.
 *
 * Which errors are retried and how long each waits is described in the `ErrorRecovery` Scaladoc
 * ([[org.llm4s.error.ErrorRecovery.recoverWithBackoff]]).
 *
 * Run this example:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.reliability.ErrorRecoveryExample"
 * }}}
 */
object ErrorRecoveryExample {

  private val baseDelay = 100.milliseconds

  def main(args: Array[String]): Unit = {
    flakyThenSuccess()
    nonRetryableError()
    alwaysFails()
    circuitBreaker()
  }

  /** Runs `operation` through `recoverWithBackoff`, recording delays instead of sleeping. */
  private def run(operation: () => Result[String], maxAttempts: Int): (Result[String], List[FiniteDuration]) = {
    val delays = ListBuffer.empty[FiniteDuration]
    val result = ErrorRecovery.recoverWithBackoff(
      operation,
      maxAttempts = maxAttempts,
      baseDelay = baseDelay,
      sleepFn = delay => { delays += delay; () }
    )
    (result, delays.toList)
  }

  private def describe(result: Result[String]): String =
    result.fold(error => s"Left(${error.getClass.getSimpleName}: ${error.message})", value => s"Right($value)")

  private def flakyThenSuccess(): Unit = {
    println("=== 1. Flaky operation: two 503s, then success ===")
    val calls = new AtomicInteger(0)
    val flaky: () => Result[String] = () =>
      if (calls.incrementAndGet() <= 2) Left(ServiceError(503, "demo-service", "temporarily unavailable"))
      else Right("payload from demo-service")

    val (result, delays) = run(flaky, maxAttempts = 5)
    println(s"Attempts: ${calls.get()}")
    println(s"Delays requested (ServiceError waits baseDelay * n): ${delays.map(_.toMillis).mkString(", ")} ms")
    println(s"Result: ${describe(result)}")
    println()
  }

  private def nonRetryableError(): Unit = {
    println("=== 2. Error that must not be retried ===")
    val calls = new AtomicInteger(0)
    val invalid: () => Result[String] = () => {
      calls.incrementAndGet()
      Left(ValidationError("query", "must not be empty"))
    }

    val (result, delays) = run(invalid, maxAttempts = 5)
    println(s"Attempts: ${calls.get()} of 5 allowed")
    println(s"Delays requested: ${delays.size}")
    println(s"Result: ${describe(result)}")
    println("Why: only transient errors (RetryPolicy.isTransient) are retried; repeating invalid input cannot succeed.")
    println()
  }

  private def alwaysFails(): Unit = {
    println("=== 3. Operation that always fails ===")
    val calls = new AtomicInteger(0)
    val down: () => Result[String] = () => {
      calls.incrementAndGet()
      Left(ServiceError(503, "demo-service", "down for maintenance"))
    }

    val (result, delays) = run(down, maxAttempts = 3)
    println(s"Attempts: ${calls.get()}")
    println(s"Delays requested: ${delays.map(_.toMillis).mkString(", ")} ms")
    println(s"Final error: ${describe(result)}")
    println()
  }

  private def circuitBreaker(): Unit = {
    println("=== 4. CircuitBreaker ===")
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val now   = new AtomicReference[Instant](start)
    val breaker = new ErrorRecovery.CircuitBreaker[String](
      failureThreshold = 3,
      recoveryTimeout = 30.seconds,
      clock = () => now.get()
    )

    val calls = new AtomicInteger(0)
    val failing: () => Result[String] = () => {
      calls.incrementAndGet()
      Left(ServiceError(503, "demo-service", "unavailable"))
    }
    val healthy: () => Result[String] = () => {
      calls.incrementAndGet()
      Right("ok")
    }

    def report(label: String, result: Result[String]): Unit =
      println(s"$label -> ${describe(result)} (operation calls so far: ${calls.get()})")

    1.to(4).foreach { i =>
      val note = if (i == 4) "circuit is open, rejected without calling the operation" else "closed, operation ran"
      println(s"Call $i: $note")
      report(s"  call $i", breaker.execute(failing))
    }

    now.set(start.plusSeconds(31))
    println("Clock advanced 31 seconds, past the 30 second recoveryTimeout")
    println("Call 5: half-open, one probe is let through")
    report("  call 5", breaker.execute(healthy))
    println("Call 6: the probe succeeded, so the circuit is closed again")
    report("  call 6", breaker.execute(healthy))
  }
}
