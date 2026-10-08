package org.llm4s.toolapi

import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import upickle.default._

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

class AsyncToolExecutionSpec extends AnyFlatSpec with Matchers with Eventually {

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

  /**
   * Observes how many calls of one tool are in flight at once, so a test can prove that calls
   * overlapped (or did not) instead of inferring it from elapsed time, which depends on how busy
   * the machine is.
   *
   * With a `barrier`, every call counts it down on entry and then waits for it to open: it opens
   * only once that many calls are running at the same time, so a strategy that runs them one
   * after another leaves the first call waiting. The wait is bounded, and a call that times out
   * returns a `Left`, so such a strategy fails the test instead of hanging it. Without a
   * barrier a call holds for a short while so that overlapping calls would be observed.
   *
   * With a `gate` as well, a call that got past the barrier then stays running until the test
   * opens the gate. The test can then look at what else started while those calls were running.
   */
  final private class ConcurrencyProbe(barrier: Option[CountDownLatch], gate: Option[CountDownLatch] = None) {
    private val active    = new AtomicInteger(0)
    private val peak      = new AtomicInteger(0)
    private val startedIn = new ConcurrentLinkedQueue[String]()

    /** The most calls that were ever running at the same time. */
    def maxConcurrent: Int = peak.get()

    /** How many calls have started so far. */
    def startCount: Int = startedIn.size()

    /** The `input` of each call, in the order the calls started. */
    def startOrder: Seq[String] = startedIn.asScala.toSeq

    def tool(name: String): Result[ToolFunction[Map[String, Any], TestResult]] = {
      val schema = Schema
        .`object`[Map[String, Any]]("Test parameters")
        .withProperty(Schema.property("input", Schema.string("Input value")))

      ToolBuilder[Map[String, Any], TestResult](name, "Test tool that observes concurrency", schema)
        .withHandler { extractor =>
          extractor.getString("input").flatMap { input =>
            peak.accumulateAndGet(active.incrementAndGet(), (a, b) => math.max(a, b))
            startedIn.add(input)
            val outcome = Try {
              barrier match {
                case Some(b) =>
                  b.countDown()
                  if (!b.await(BarrierWait.toSeconds, TimeUnit.SECONDS))
                    Left(s"calls did not overlap within $BarrierWait")
                  else
                    gate.fold[Either[String, Unit]](Right(())) { g =>
                      if (g.await(BarrierWait.toSeconds, TimeUnit.SECONDS)) Right(())
                      else Left(s"the test did not open the gate within $BarrierWait")
                    }
                case None =>
                  Thread.sleep(HoldTime.toMillis)
                  Right(())
              }
            }
            active.decrementAndGet(): Unit
            outcome.fold(e => Left(e.getMessage), identity).map(_ => TestResult(s"$name: $input", 0L))
          }
        }
        .buildSafe()
    }
  }

  // Generous on purpose: only a strategy that fails to overlap its calls ever waits this long.
  private val BarrierWait = 10.seconds
  private val HoldTime    = 50.millis

  private def requestsFor(name: String, inputs: String*): Seq[ToolCallRequest] =
    inputs.map(i => ToolCallRequest(name, ujson.Obj("input" -> i)))

  private def valuesOf(results: Seq[Either[ToolCallError, ujson.Value]]): Seq[String] =
    results.map { r =>
      val json = r.fold(e => fail(s"Expected success: ${e.getMessage}"), identity)
      read[TestResult](json).value
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
    val probe = new ConcurrencyProbe(None)
    val result = probe.tool("probe").map { tool =>
      val registry = new ToolRegistry(Seq(tool))
      val results =
        Await.result(
          registry.executeAll(requestsFor("probe", "a", "b", "c"), ToolExecutionStrategy.Sequential),
          30.seconds
        )

      // Never more than one call at a time, and each started only after the one before it ended
      probe.maxConcurrent shouldBe 1
      probe.startOrder shouldBe Seq("a", "b", "c")

      results.foreach(r => r shouldBe a[Right[_, _]])
      valuesOf(results) shouldBe Seq("probe: a", "probe: b", "probe: c")
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "execute requests in parallel with Parallel strategy" in {
    // Each call waits until all three are running at once, so the test can only pass if they overlap
    val probe = new ConcurrencyProbe(Some(new CountDownLatch(3)))
    val result = probe.tool("probe").map { tool =>
      val registry = new ToolRegistry(Seq(tool))
      val results =
        Await
          .result(registry.executeAll(requestsFor("probe", "a", "b", "c"), ToolExecutionStrategy.Parallel), 30.seconds)

      probe.maxConcurrent shouldBe 3

      // All results should be successful
      results.foreach(r => r shouldBe a[Right[_, _]])

      // Results should still be in order
      valuesOf(results) shouldBe Seq("probe: a", "probe: b", "probe: c")
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "respect concurrency limit with ParallelWithLimit strategy" in {
    // The first two calls wait for each other, so a limit of 2 must really run two at once; they
    // then stay running until the gate opens, which gives a third call every chance to start early
    val gate  = new CountDownLatch(1)
    val probe = new ConcurrencyProbe(Some(new CountDownLatch(2)), Some(gate))
    val result = probe.tool("probe").map { tool =>
      val registry = new ToolRegistry(Seq(tool))
      val future =
        registry.executeAll(requestsFor("probe", "a", "b", "c", "d"), ToolExecutionStrategy.ParallelWithLimit(2))

      implicit val patience: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(10, Millis))
      eventually(probe.startCount shouldBe 2)
      // Hold the two calls open for a moment: with the limit enforced nothing else may start
      Thread.sleep(300)
      probe.startCount shouldBe 2
      probe.maxConcurrent shouldBe 2

      gate.countDown()
      val results = Await.result(future, 30.seconds)

      // Two at once, never three, even though four requests were made
      probe.maxConcurrent shouldBe 2
      probe.startCount shouldBe 4

      // All results should be successful
      results.foreach(r => r shouldBe a[Right[_, _]])

      // Results should still be in order
      valuesOf(results) shouldBe Seq("probe: a", "probe: b", "probe: c", "probe: d")
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
    val probe = new ConcurrencyProbe(None)
    val result = probe.tool("probe").map { tool =>
      val registry = new ToolRegistry(Seq(tool))
      // Use default (no strategy specified)
      val results = Await.result(registry.executeAll(requestsFor("probe", "a", "b")), 30.seconds)

      probe.maxConcurrent shouldBe 1
      probe.startOrder shouldBe Seq("a", "b")
      results.size shouldBe 2
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }
}
