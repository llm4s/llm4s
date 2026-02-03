package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Unit tests for agent execution policies
 */
class PoliciesSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit val ec: ExecutionContext = ExecutionContext.global

  // Test agents
  val successAgent = TypedAgent.fromFunction[String, String]("success")(s => Right(s"success: $s"))

  val recoverableFailureAgent = TypedAgent.fromFunction[String, String]("recoverable-failure") { _ =>
    Left(OrchestrationError.NodeExecutionError("test", "recoverable", "Recoverable error"))
  }

  val nonRecoverableFailureAgent = TypedAgent.fromFunction[String, String]("non-recoverable-failure") { _ =>
    Left(OrchestrationError.PlanValidationError("Non-recoverable error"))
  }

  var attemptCounter = 0
  val flakyAgent = TypedAgent.fromFunction[String, String]("flaky") { s =>
    attemptCounter += 1
    if (attemptCounter < 3) {
      Left(OrchestrationError.NodeExecutionError("flaky", "flaky", "Temporary failure"))
    } else {
      Right(s"success after retries: $s")
    }
  }

  "Policies.withRetry" should "retry recoverable failures" in {
    attemptCounter = 0 // Reset counter
    val retryAgent = Policies.withRetry(flakyAgent, maxAttempts = 3, backoff = 10.millis)

    whenReady(retryAgent.execute("test")) { result =>
      result.isRight shouldBe true
      result.getOrElse("") should include("success after retries")
      attemptCounter shouldBe 3
    }
  }

  "Policies.withRetry" should "not retry non-recoverable failures" in {
    val retryAgent = Policies.withRetry(nonRecoverableFailureAgent, maxAttempts = 3)

    whenReady(retryAgent.execute("test")) { result =>
      result.isLeft shouldBe true
      result.swap
        .getOrElse(throw new RuntimeException("Expected Left"))
        .shouldBe(a[OrchestrationError.PlanValidationError])
    }
  }

  "Policies.withRetry" should "succeed immediately if first attempt succeeds" in {
    val retryAgent = Policies.withRetry(successAgent, maxAttempts = 3)

    whenReady(retryAgent.execute("test"))(result => result shouldBe Right("success: test"))
  }

  "Policies.withTimeout" should "timeout slow operations" in {
    val slowAgent = TypedAgent.fromFuture[String, String]("slow") { s =>
      Future {
        Thread.sleep(500)
        Right(s"slow: $s")
      }
    }

    val timeoutAgent = Policies.withTimeout(slowAgent, 100.millis)

    whenReady(timeoutAgent.execute("test")) { result =>
      result.isLeft shouldBe true
      result.swap
        .getOrElse(throw new RuntimeException("Expected Left"))
        .shouldBe(a[OrchestrationError.AgentTimeoutError])
    }
  }

  "Policies.withTimeout" should "succeed for fast operations" in {
    val fastAgent = TypedAgent.fromFuture[String, String]("fast") { s =>
      Future {
        Thread.sleep(10)
        Right(s"fast: $s")
      }
    }

    val timeoutAgent = Policies.withTimeout(fastAgent, 100.millis)

    whenReady(timeoutAgent.execute("test"))(result => result shouldBe Right("fast: test"))
  }

  "Policies.withFallback" should "use fallback when primary fails" in {
    val primaryAgent  = recoverableFailureAgent
    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))

    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)

    whenReady(fallbackWrapped.execute("test"))(result => result shouldBe Right("fallback: test"))
  }

  "Policies.withFallback" should "use primary when it succeeds" in {
    val primaryAgent  = successAgent
    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))

    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)

    whenReady(fallbackWrapped.execute("test"))(result => result shouldBe Right("success: test"))
  }

  "Policies.withFallback" should "return primary error when both fail" in {
    val primaryAgent = TypedAgent.fromFunction[String, String]("primary") { _ =>
      Left(OrchestrationError.NodeExecutionError("primary", "primary", "Primary failure"))
    }
    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback") { _ =>
      Left(OrchestrationError.NodeExecutionError("fallback", "fallback", "Fallback failure"))
    }

    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)

    whenReady(fallbackWrapped.execute("test")) { result =>
      result.isLeft shouldBe true
      val error = result.swap
        .getOrElse(throw new RuntimeException("Expected Left"))
        .asInstanceOf[OrchestrationError.NodeExecutionError]
      error.nodeId shouldBe "primary" // Should return primary error
    }
  }

  "Policies.withPolicies" should "combine multiple policies correctly" in {
    // Create an agent that fails twice then succeeds
    var policyAttempts = 0
    val testAgent = TypedAgent.fromFunction[String, String]("policy-test") { s =>
      policyAttempts += 1
      if (policyAttempts < 3) {
        Left(OrchestrationError.NodeExecutionError("test", "test", "Temporary failure"))
      } else {
        Right(s"success: $s")
      }
    }

    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))

    policyAttempts = 0 // Reset counter

    val enhancedAgent = Policies.withPolicies(
      testAgent,
      retry = Some((3, 10.millis)),
      timeout = Some(1.second),
      fallback = Some(fallbackAgent)
    )

    whenReady(enhancedAgent.execute("test")) { result =>
      result.isRight shouldBe true
      result.getOrElse("") should include("success: test")
      policyAttempts shouldBe 3
    }
  }

  "Policies.withPolicies" should "use fallback when retries are exhausted" in {
    val alwaysFailingAgent = TypedAgent.fromFunction[String, String]("always-failing") { _ =>
      Left(OrchestrationError.NodeExecutionError("failing", "failing", "Always fails"))
    }

    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))

    val enhancedAgent = Policies.withPolicies(
      alwaysFailingAgent,
      retry = Some((2, 1.milli)),
      fallback = Some(fallbackAgent)
    )

    whenReady(enhancedAgent.execute("test"))(result => result shouldBe Right("fallback: test"))
  }

  "Policy composition" should "have correct ordering (timeout -> retry -> fallback)" in {
    // This test verifies that policies are applied in the correct order
    val slowAgent = TypedAgent.fromFuture[String, String]("slow-then-success") { s =>
      Future {
        Thread.sleep(200)
        Right(s"slow: $s")
      }
    }

    val fallbackAgent = TypedAgent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))

    val enhancedAgent = Policies.withPolicies(
      slowAgent,
      retry = Some((2, 10.millis)),
      timeout = Some(50.millis), // Shorter than agent execution time
      fallback = Some(fallbackAgent)
    )

    whenReady(enhancedAgent.execute("test"), timeout(500.millis)) { result =>
      // Should timeout on each retry attempt, then use fallback
      result shouldBe Right("fallback: test")
    }
  }
}
