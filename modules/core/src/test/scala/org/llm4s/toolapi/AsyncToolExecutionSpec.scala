package org.llm4s.toolapi

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

class AsyncToolExecutionSpec extends AnyFlatSpec with Matchers {

  // Result type for testing
  case class TestResult(value: String, timestamp: Long)
  implicit val testResultRW: ReadWriter[TestResult] = macroRW[TestResult]

  /**
   * Creates a test tool that records when it was called and can simulate delays.
   * Returns Result — never extracts from the container.
   */
  def createDelayingTool(
    name: String,
    delayMs: Long = 0
  ): Result[ToolFunction[Map[String, Any], TestResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("input", Schema.string("Input value")))

    ToolBuilder[Map[String, Any], TestResult](
      name,
      s"Test tool with ${delayMs}ms delay",
      schema
    ).withHandler { extractor =>
      val startTime = System.currentTimeMillis()
      if (delayMs > 0) {
        Thread.sleep(delayMs)
      }
      extractor.getString("input").map(input => TestResult(s"$name: $input", startTime))
    }.buildSafe()
  }

  // ==========================================================================
  // ToolExecutionStrategy Tests
  // ==========================================================================

  "ToolExecutionStrategy.Sequential" should "be the default" in {
    ToolExecutionStrategy.default shouldBe ToolExecutionStrategy.Sequential
  }

  "ToolExecutionStrategy.ParallelWithLimit" should "require positive concurrency" in {
    an[IllegalArgumentException] should be thrownBy {
      ToolExecutionStrategy.ParallelWithLimit(0)
    }

    an[IllegalArgumentException] should be thrownBy {
      ToolExecutionStrategy.ParallelWithLimit(-1)
    }

    // Valid cases should work
    noException should be thrownBy {
      ToolExecutionStrategy.ParallelWithLimit(1)
    }

    noException should be thrownBy {
      ToolExecutionStrategy.ParallelWithLimit(10)
    }
  }

  // ==========================================================================
  // ToolRegistry.executeAsync Tests
  // ==========================================================================

  "ToolRegistry.executeAsync" should "execute a tool asynchronously" in {
    createDelayingTool("async_tool", 0).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val request  = ToolCallRequest("async_tool", ujson.Obj("input" -> "test_value"))
        val future   = registry.executeAsync(request)
        val result   = Await.result(future, 5.seconds)

        result shouldBe a[Right[_, _]]
        val json   = result.fold(e => fail(s"Expected success: ${e.getMessage}"), identity)
        val parsed = read[TestResult](json)
        parsed.value shouldBe "async_tool: test_value"
      }
    )
  }

  it should "return error for unknown function" in {
    createDelayingTool("known_tool", 0).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val request  = ToolCallRequest("unknown_tool", ujson.Obj("input" -> "test"))
        val future   = registry.executeAsync(request)
        val result   = Await.result(future, 5.seconds)

        result shouldBe a[Left[_, _]]
        val error = result.fold(identity, v => fail(s"Expected error but got: $v"))
        error shouldBe a[ToolCallError.UnknownFunction]
        error.asInstanceOf[ToolCallError.UnknownFunction].toolName shouldBe "unknown_tool"
      }
    )
  }

  // ==========================================================================
  // ToolRegistry.executeAll Tests
  // ==========================================================================

  "ToolRegistry.executeAll" should "execute requests sequentially with Sequential strategy" in {
    val result = for {
      tool1 <- createDelayingTool("tool1", 50)
      tool2 <- createDelayingTool("tool2", 50)
      tool3 <- createDelayingTool("tool3", 50)
    } yield {
      val registry = new ToolRegistry(Seq(tool1, tool2, tool3))
      val requests = Seq(
        ToolCallRequest("tool1", ujson.Obj("input" -> "a")),
        ToolCallRequest("tool2", ujson.Obj("input" -> "b")),
        ToolCallRequest("tool3", ujson.Obj("input" -> "c"))
      )

      val startTime = System.currentTimeMillis()
      val future    = registry.executeAll(requests, ToolExecutionStrategy.Sequential)
      val results   = Await.result(future, 10.seconds)
      val totalTime = System.currentTimeMillis() - startTime

      // Sequential execution: should take at least 150ms (3 x 50ms)
      totalTime should be >= 150L

      // All results should be successful
      results.foreach(r => r shouldBe a[Right[_, _]])

      // Results should be in order
      results.size shouldBe 3

      val values = results.map { r =>
        val json = r.fold(e => fail(s"Expected success: ${e.getMessage}"), identity)
        read[TestResult](json).value
      }
      values shouldBe Seq("tool1: a", "tool2: b", "tool3: c")
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "execute requests in parallel with Parallel strategy" in {
    val result = for {
      tool1 <- createDelayingTool("tool1", 100)
      tool2 <- createDelayingTool("tool2", 100)
      tool3 <- createDelayingTool("tool3", 100)
    } yield {
      val registry = new ToolRegistry(Seq(tool1, tool2, tool3))
      val requests = Seq(
        ToolCallRequest("tool1", ujson.Obj("input" -> "a")),
        ToolCallRequest("tool2", ujson.Obj("input" -> "b")),
        ToolCallRequest("tool3", ujson.Obj("input" -> "c"))
      )

      val startTime = System.currentTimeMillis()
      val future    = registry.executeAll(requests, ToolExecutionStrategy.Parallel)
      val results   = Await.result(future, 10.seconds)
      val totalTime = System.currentTimeMillis() - startTime

      // Parallel execution: should take around 100ms, not 300ms
      // Allow some tolerance for thread scheduling
      totalTime should be < 250L

      // All results should be successful
      results.foreach(r => r shouldBe a[Right[_, _]])

      // Results should still be in order
      results.size shouldBe 3

      val values = results.map { r =>
        val json = r.fold(e => fail(s"Expected success: ${e.getMessage}"), identity)
        read[TestResult](json).value
      }
      values shouldBe Seq("tool1: a", "tool2: b", "tool3: c")
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "respect concurrency limit with ParallelWithLimit strategy" in {
    val result = for {
      tool1 <- createDelayingTool("tool1", 100)
      tool2 <- createDelayingTool("tool2", 100)
      tool3 <- createDelayingTool("tool3", 100)
      tool4 <- createDelayingTool("tool4", 100)
    } yield {
      val registry = new ToolRegistry(Seq(tool1, tool2, tool3, tool4))
      val requests = Seq(
        ToolCallRequest("tool1", ujson.Obj("input" -> "a")),
        ToolCallRequest("tool2", ujson.Obj("input" -> "b")),
        ToolCallRequest("tool3", ujson.Obj("input" -> "c")),
        ToolCallRequest("tool4", ujson.Obj("input" -> "d"))
      )

      val startTime = System.currentTimeMillis()
      val future    = registry.executeAll(requests, ToolExecutionStrategy.ParallelWithLimit(2))
      val results   = Await.result(future, 10.seconds)
      val totalTime = System.currentTimeMillis() - startTime

      // With limit of 2, 4 tools should take ~200ms (2 batches of 2)
      // Should be faster than sequential (400ms) but slower than full parallel (100ms)
      totalTime should be >= 180L
      totalTime should be < 350L

      // All results should be successful
      results.foreach(r => r shouldBe a[Right[_, _]])

      // Results should still be in order
      results.size shouldBe 4
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "handle mixed success and failure results" in {
    // Create a tool that fails for a specific input
    ToolBuilder[Map[String, Any], TestResult](
      "failing_tool",
      "Tool that fails on 'fail' input",
      Schema
        .`object`[Map[String, Any]]("Test")
        .withProperty(Schema.property("input", Schema.string("Input")))
    ).withHandler { extractor =>
      extractor.getString("input").flatMap { input =>
        if (input == "fail") {
          Left("Intentional failure")
        } else {
          Right(TestResult(s"success: $input", System.currentTimeMillis()))
        }
      }
    }.buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        failingTool => {
          val registry = new ToolRegistry(Seq(failingTool))
          val requests = Seq(
            ToolCallRequest("failing_tool", ujson.Obj("input" -> "ok1")),
            ToolCallRequest("failing_tool", ujson.Obj("input" -> "fail")),
            ToolCallRequest("failing_tool", ujson.Obj("input" -> "ok2"))
          )

          val future  = registry.executeAll(requests, ToolExecutionStrategy.Parallel)
          val results = Await.result(future, 5.seconds)

          // First and third should succeed
          results(0) shouldBe a[Right[_, _]]
          results(2) shouldBe a[Right[_, _]]

          // Second should fail
          results(1) shouldBe a[Left[_, _]]
          results(1).fold(identity, v => fail(s"Expected error but got: $v")) shouldBe a[ToolCallError.HandlerError]
        }
      )
  }

  it should "handle empty request list" in {
    createDelayingTool("tool", 0).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val future   = registry.executeAll(Seq.empty, ToolExecutionStrategy.Parallel)
        val results  = Await.result(future, 5.seconds)
        results shouldBe empty
      }
    )
  }

  it should "handle single request" in {
    createDelayingTool("tool", 0).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val requests = Seq(ToolCallRequest("tool", ujson.Obj("input" -> "single")))
        val future   = registry.executeAll(requests, ToolExecutionStrategy.Parallel)
        val results  = Await.result(future, 5.seconds)
        results.size shouldBe 1
        results.head shouldBe a[Right[_, _]]
      }
    )
  }

  // ==========================================================================
  // Default Strategy Tests
  // ==========================================================================

  it should "use Sequential by default" in {
    createDelayingTool("tool", 50).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val requests = Seq(
          ToolCallRequest("tool", ujson.Obj("input" -> "a")),
          ToolCallRequest("tool", ujson.Obj("input" -> "b"))
        )

        val startTime = System.currentTimeMillis()
        // Use default (no strategy specified)
        val future    = registry.executeAll(requests)
        val results   = Await.result(future, 10.seconds)
        val totalTime = System.currentTimeMillis() - startTime

        // Sequential: should take at least 100ms (2 x 50ms)
        totalTime should be >= 100L
        results.size shouldBe 2
      }
    )
  }
}
