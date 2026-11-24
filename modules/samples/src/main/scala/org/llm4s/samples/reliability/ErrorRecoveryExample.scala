package org.llm4s.samples.reliability

import org.llm4s.error.{ ServiceError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.types.Result

import java.time.Instant
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * What [[org.llm4s.reliability.ReliableClient]] retries and what it does not, with no provider and no
 * configuration: the client it wraps is scripted.
 *
 * Four scenarios, all deterministic and instant because `sleep` records the delay instead of sleeping and the
 * `clock` is a value the sample moves by hand:
 *
 *  1. A flaky client fails twice with a retryable 503 [[org.llm4s.error.ServiceError]], then succeeds.
 *  1. A [[org.llm4s.error.ValidationError]] is not transient, so it is returned after one attempt.
 *  1. A client that always fails: the attempts run out and the last error is returned unchanged.
 *  1. The circuit opens after repeated failures, refuses calls with a `CircuitOpenError`, and lets one through
 *     again once `recoveryTimeout` has passed on its clock.
 *
 * Which errors are retried is `RetryPolicy.isRetryable`; how long each waits is the policy's `delayFor`.
 *
 * Run this example:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.reliability.ErrorRecoveryExample"
 * }}}
 */
object ErrorRecoveryExample {

  private val baseDelay    = 100.milliseconds
  private val conversation = Conversation(Seq(UserMessage("Hello")))

  def main(args: Array[String]): Unit = {
    flakyThenSuccess()
    nonRetryableError()
    alwaysFails()
    circuitBreaker()
  }

  /** A client whose every call is answered by `reply`, counting the calls. */
  final private class Scripted(reply: () => Result[String]) extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      reply().map { text =>
        Completion(
          id = "demo",
          created = Instant.now(),
          model = "demo-model",
          message = AssistantMessage(text)
        )
      }
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Runs one call through a `ReliableClient` over `client`, recording delays instead of sleeping. */
  private def run(client: Scripted, maxAttempts: Int): (Result[String], List[FiniteDuration]) = {
    val delays = ListBuffer.empty[FiniteDuration]
    val reliable = new ReliableClient(
      client,
      "demo-service",
      ReliabilityConfig(retryPolicy = RetryPolicy.linearBackoff(maxAttempts = maxAttempts, baseDelay = baseDelay)),
      sleep = delay => { delays += delay; () }
    )
    (reliable.complete(conversation).map(_.content), delays.toList)
  }

  private def describe(result: Result[String]): String =
    result.fold(error => s"Left(${error.getClass.getSimpleName}: ${error.message})", value => s"Right($value)")

  private def flakyThenSuccess(): Unit = {
    println("=== 1. Flaky client: two 503s, then success ===")
    val attempts = new AtomicInteger(0)
    val flaky = new Scripted(() =>
      if (attempts.incrementAndGet() <= 2) Left(ServiceError(503, "demo-service", "temporarily unavailable"))
      else Right("payload from demo-service")
    )

    val (result, delays) = run(flaky, maxAttempts = 5)
    println(s"Attempts: ${flaky.calls.get()}")
    println(s"Delays requested (linear backoff waits baseDelay * n): ${delays.map(_.toMillis).mkString(", ")} ms")
    println(s"Result: ${describe(result)}")
    println()
  }

  private def nonRetryableError(): Unit = {
    println("=== 2. Error that must not be retried ===")
    val invalid = new Scripted(() => Left(ValidationError("query", "must not be empty")))

    val (result, delays) = run(invalid, maxAttempts = 5)
    println(s"Attempts: ${invalid.calls.get()} of 5 allowed")
    println(s"Delays requested: ${delays.size}")
    println(s"Result: ${describe(result)}")
    println("Why: only transient errors (RetryPolicy.isRetryable) are retried; repeating invalid input cannot succeed.")
    println()
  }

  private def alwaysFails(): Unit = {
    println("=== 3. Client that always fails ===")
    val down = new Scripted(() => Left(ServiceError(503, "demo-service", "down for maintenance")))

    val (result, delays) = run(down, maxAttempts = 3)
    println(s"Attempts: ${down.calls.get()}")
    println(s"Delays requested: ${delays.map(_.toMillis).mkString(", ")} ms")
    println(s"Final error, the last attempt's, unchanged: ${describe(result)}")
    println()
  }

  private def circuitBreaker(): Unit = {
    println("=== 4. Circuit breaker ===")
    val start   = Instant.parse("2026-01-01T00:00:00Z")
    val now     = new AtomicReference[Instant](start)
    val healthy = new AtomicReference[Boolean](false)
    val service = new Scripted(() =>
      if (healthy.get()) Right("ok") else Left(ServiceError(503, "demo-service", "unavailable"))
    )
    val breaker = new ReliableClient(
      service,
      "demo-service",
      ReliabilityConfig(
        // no retries, so each call is one attempt and the circuit's count is easy to follow
        retryPolicy = RetryPolicy.noRetry,
        circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, recoveryTimeout = 30.seconds, successThreshold = 1)
      ),
      clock = () => now.get()
    )

    def report(label: String): Unit = {
      val result = breaker.complete(conversation).map(_.content)
      println(s"$label -> ${describe(result)} (client calls so far: ${service.calls.get()})")
    }

    1.to(4).foreach { i =>
      val note = if (i == 4) "circuit is open, refused without calling the client" else "closed, the call ran"
      println(s"Call $i: $note")
      report(s"  call $i")
    }

    now.set(start.plusSeconds(31))
    healthy.set(true)
    println("Clock advanced 31 seconds, past the 30 second recoveryTimeout")
    println("Call 5: half-open, one probe is let through")
    report("  call 5")
    println("Call 6: the probe succeeded, so the circuit is closed again")
    report("  call 6")
  }
}
