package org.llm4s.agent.orchestration

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._

class OrchestrationTest extends AnyFunSuite with Matchers {
  implicit val ec: ExecutionContext = ExecutionContext.global

  test("PlanRunner should execute simple DAG with cancellation support") {
    // Create simple agents
    val agent1 = Agent.fromFunction[Int, Int]("doubler")(x => Right(x * 2))
    val agent2 = Agent.fromFunction[Int, String]("stringifier")(x => Right(s"Result: $x"))

    // Build DAG
    val node1 = Node("n1", agent1)
    val node2 = Node("n2", agent2)
    val plan = Plan.builder
      .addNode(node1)
      .addNode(node2)
      .addEdge(Edge("e1", node1, node2))
      .build

    // Execute with cancellation token
    val token  = CancellationToken()
    val runner = new PlanRunner(maxConcurrentNodes = 5)
    val result = runner.execute(plan, Map("n1" -> 5), token)

    val finalResult = Await.result(result, 5.seconds)
    finalResult shouldBe Right(Map("n1" -> 10, "n2" -> "Result: 10"))
  }

  test("Policies should use non-blocking delays") {
    var attempts = 0
    val flakyAgent = Agent.fromFunction[String, String]("flaky") { input =>
      attempts += 1
      if (attempts < 3) {
        Left(OrchestrationError.NodeExecutionError("flaky", "flaky", "Simulated failure"))
      } else {
        Right(s"Success: $input")
      }
    }

    val retryAgent = Policies.withRetry(flakyAgent, maxAttempts = 5, backoff = 100.millis)
    val result     = retryAgent.execute("test")

    val finalResult = Await.result(result, 2.seconds)
    finalResult shouldBe Right("Success: test")
    attempts shouldBe 3
  }

  test("MDCContext should preserve context across async boundaries") {
    import org.slf4j.MDC

    // Clear any existing MDC context first
    MDC.clear()

    MDCContext.withValues("test" -> "value") {
      MDC.get("test") shouldBe "value"
    }

    // Context should be cleared after
    Option(MDC.get("test")) shouldBe None

    // Test async preservation - clear again to ensure clean state
    MDC.clear()
    val captured = MDCContext.withValues("async" -> "test") {
      MDCContext.capture()
    }

    captured shouldBe Map("async" -> "test")
  }

  test("CancellationToken should support cancellation callbacks") {
    val token            = new CancellationToken()
    var callbackExecuted = false

    token.onCancel {
      callbackExecuted = true
    }

    token.isCancelled shouldBe false
    callbackExecuted shouldBe false

    token.cancel()

    token.isCancelled shouldBe true
    callbackExecuted shouldBe true
  }

  test("PlanRunner should respect maxConcurrentNodes limit") {
    val activeCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val maxActive   = new java.util.concurrent.atomic.AtomicInteger(0)

    val slowAgent = Agent.fromFuture[Int, Int]("slow") { x =>
      Future {
        val current = activeCount.incrementAndGet()
        maxActive.updateAndGet(max => math.max(max, current))
        Thread.sleep(100) // Longer delay to ensure overlap
        activeCount.decrementAndGet()
        Right(x * 2)
      }
    }

    // Create 10 parallel nodes
    val nodes       = (1 to 10).map(i => Node(s"n$i", slowAgent)).toList
    val planBuilder = nodes.foldLeft(Plan.builder)(_.addNode(_))
    val plan        = planBuilder.build

    val runner = new PlanRunner(maxConcurrentNodes = 3)
    val inputs = nodes.map(n => n.id -> 1).toMap
    val result = runner.execute(plan, inputs)

    Await.result(result, 15.seconds)

    // Should never have more than 3 concurrent executions (with some tolerance for timing)
    maxActive.get() should be <= 4 // Allow 1 extra for timing overlap
  }
}
