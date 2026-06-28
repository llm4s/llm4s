package org.llm4s.toolapi

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

/**
 * Tests for ToolRegistry and tool execution
 */
class ToolRegistrySpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Test result types
  case class MathResult(result: Double)
  implicit val mathResultRW: ReadWriter[MathResult] = macroRW

  case class StringResult(value: String)
  implicit val stringResultRW: ReadWriter[StringResult] = macroRW

  // Helper to create test tools — returns Result, never extracts
  def createAddTool(): Result[ToolFunction[Map[String, Any], MathResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Addition parameters")
      .withProperty(Schema.property("a", Schema.number("First number")))
      .withProperty(Schema.property("b", Schema.number("Second number")))

    ToolBuilder[Map[String, Any], MathResult](
      "add",
      "Adds two numbers",
      schema
    ).withHandler { extractor =>
      for {
        a <- extractor.getDouble("a")
        b <- extractor.getDouble("b")
      } yield MathResult(a + b)
    }.buildSafe()
  }

  def createMultiplyTool(): Result[ToolFunction[Map[String, Any], MathResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Multiplication parameters")
      .withProperty(Schema.property("a", Schema.number("First number")))
      .withProperty(Schema.property("b", Schema.number("Second number")))

    ToolBuilder[Map[String, Any], MathResult](
      "multiply",
      "Multiplies two numbers",
      schema
    ).withHandler { extractor =>
      for {
        a <- extractor.getDouble("a")
        b <- extractor.getDouble("b")
      } yield MathResult(a * b)
    }.buildSafe()
  }

  def createEchoTool(): Result[ToolFunction[Map[String, Any], StringResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withProperty(Schema.property("message", Schema.string("Message to echo")))

    ToolBuilder[Map[String, Any], StringResult](
      "echo",
      "Echoes the message",
      schema
    ).withHandler(extractor => extractor.getString("message").map(msg => StringResult(s"Echo: $msg")))
      .buildSafe()
  }

  /** Tool that sleeps for a given number of milliseconds then returns. Used for timeout tests. */
  def createSleepTool(): Result[ToolFunction[Map[String, Any], MathResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Sleep parameters")
      .withProperty(Schema.property("ms", Schema.integer("Milliseconds to sleep")))

    ToolBuilder[Map[String, Any], MathResult](
      "sleep",
      "Sleeps for given ms",
      schema
    ).withHandler { extractor =>
      for {
        ms <- extractor.getInt("ms")
      } yield {
        Thread.sleep(ms.toLong)
        MathResult(0.0)
      }
    }.buildSafe()
  }

  /** Tool that fails with IOException the first N times then succeeds. Used for retry tests. */
  def createFlakyTool(failCount: Int): Result[ToolFunction[Map[String, Any], MathResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Flaky parameters")
      .withProperty(Schema.property("x", Schema.number("Value")))
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    ToolBuilder[Map[String, Any], MathResult](
      "flaky",
      "Fails N times then succeeds",
      schema
    ).withHandler { extractor =>
      for {
        x <- extractor.getDouble("x")
      } yield {
        if (counter.getAndIncrement() < failCount) {
          throw new java.io.IOException("transient failure")
        }
        MathResult(x)
      }
    }.buildSafe()
  }

  /** Tool that always returns Left (handler error). Not retryable. */
  def createFailingTool(): Result[ToolFunction[Map[String, Any], MathResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Fail parameters")
      .withProperty(Schema.property("x", Schema.number("Value")))
    ToolBuilder[Map[String, Any], MathResult](
      "fail",
      "Always fails",
      schema
    ).withHandler(_ => Left("validation error")).buildSafe()
  }

  // ============ ToolRegistry.empty ============

  "ToolRegistry.empty" should "create an empty registry" in {
    val registry = ToolRegistry.empty

    registry.tools shouldBe empty
    registry.getTool("anything") shouldBe None
  }

  // ============ ToolRegistry Construction ============

  "ToolRegistry" should "hold provided tools" in {
    val result = for {
      addTool      <- createAddTool()
      multiplyTool <- createMultiplyTool()
    } yield {
      val registry = new ToolRegistry(Seq(addTool, multiplyTool))
      registry.tools should have size 2
      registry.getTool("add") shouldBe Some(addTool)
      registry.getTool("multiply") shouldBe Some(multiplyTool)
      registry.getTool("nonexistent") shouldBe None
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  // ============ Synchronous Execution ============

  "ToolRegistry.execute" should "execute a tool and return result" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val request  = ToolCallRequest("add", ujson.Obj("a" -> 5, "b" -> 3))
        val result   = registry.execute(request)
        result.isRight shouldBe true
        result.map(json => json("result").num) shouldBe Right(8.0)
      }
    )
  }

  it should "return error for unknown function" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val request  = ToolCallRequest("unknown_function", ujson.Obj())
        val result   = registry.execute(request)
        result.isLeft shouldBe true
        result.fold(identity, v => fail(s"Expected Left but got Right: $v")).getMessage should include(
          "not a recognized tool"
        )
      }
    )
  }

  it should "return error for invalid parameters" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val request  = ToolCallRequest("add", ujson.Obj("a" -> "not a number", "b" -> 3))
        val result   = registry.execute(request)
        result.isLeft shouldBe true
        result.fold(identity, v => fail(s"Expected Left but got Right: $v")).getMessage should include("wrong type")
      }
    )
  }

  it should "return error for missing parameters" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val request  = ToolCallRequest("add", ujson.Obj("a" -> 5))
        val result   = registry.execute(request)
        result.isLeft shouldBe true
        result.fold(identity, v => fail(s"Expected Left but got Right: $v")).getMessage should include("missing")
      }
    )
  }

  // ============ Asynchronous Execution ============

  "ToolRegistry.executeAsync" should "execute a tool asynchronously" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry     = new ToolRegistry(Seq(addTool))
        val request      = ToolCallRequest("add", ujson.Obj("a" -> 10, "b" -> 20))
        val futureResult = registry.executeAsync(request)
        val result       = Await.result(futureResult, 5.seconds)
        result.isRight shouldBe true
        result.map(json => json("result").num) shouldBe Right(30.0)
      }
    )
  }

  it should "return error asynchronously for unknown function" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry     = new ToolRegistry(Seq(addTool))
        val request      = ToolCallRequest("nonexistent", ujson.Obj())
        val futureResult = registry.executeAsync(request)
        val result       = Await.result(futureResult, 5.seconds)
        result.isLeft shouldBe true
        result.fold(identity, v => fail(s"Expected Left but got Right: $v")).getMessage should include(
          "not a recognized tool"
        )
      }
    )
  }

  // ============ Batch Execution with Strategies ============

  "ToolRegistry.executeAll with Sequential strategy" should "execute all requests in order" in {
    val result = for {
      addTool      <- createAddTool()
      multiplyTool <- createMultiplyTool()
    } yield {
      val registry = new ToolRegistry(Seq(addTool, multiplyTool))
      val requests = Seq(
        ToolCallRequest("add", ujson.Obj("a" -> 1, "b" -> 2)),
        ToolCallRequest("multiply", ujson.Obj("a" -> 3, "b" -> 4)),
        ToolCallRequest("add", ujson.Obj("a" -> 5, "b" -> 6))
      )
      val futureResults = registry.executeAll(requests, ToolExecutionStrategy.Sequential)
      val results       = Await.result(futureResults, 10.seconds)

      results should have size 3
      results(0).map(json => json("result").num) shouldBe Right(3.0)
      results(1).map(json => json("result").num) shouldBe Right(12.0)
      results(2).map(json => json("result").num) shouldBe Right(11.0)
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  "ToolRegistry.executeAll with Parallel strategy" should "execute all requests in parallel" in {
    val result = for {
      addTool      <- createAddTool()
      multiplyTool <- createMultiplyTool()
    } yield {
      val registry = new ToolRegistry(Seq(addTool, multiplyTool))
      val requests = Seq(
        ToolCallRequest("add", ujson.Obj("a" -> 10, "b" -> 20)),
        ToolCallRequest("multiply", ujson.Obj("a" -> 5, "b" -> 5)),
        ToolCallRequest("add", ujson.Obj("a" -> 100, "b" -> 200))
      )
      val futureResults = registry.executeAll(requests, ToolExecutionStrategy.Parallel)
      val results       = Await.result(futureResults, 10.seconds)

      results should have size 3
      results(0).map(json => json("result").num) shouldBe Right(30.0)
      results(1).map(json => json("result").num) shouldBe Right(25.0)
      results(2).map(json => json("result").num) shouldBe Right(300.0)
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  "ToolRegistry.executeAll with ParallelWithLimit strategy" should "execute in batches" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val requests = Seq(
          ToolCallRequest("add", ujson.Obj("a" -> 1, "b" -> 1)),
          ToolCallRequest("add", ujson.Obj("a" -> 2, "b" -> 2)),
          ToolCallRequest("add", ujson.Obj("a" -> 3, "b" -> 3)),
          ToolCallRequest("add", ujson.Obj("a" -> 4, "b" -> 4)),
          ToolCallRequest("add", ujson.Obj("a" -> 5, "b" -> 5))
        )
        val futureResults = registry.executeAll(requests, ToolExecutionStrategy.ParallelWithLimit(2))
        val results       = Await.result(futureResults, 10.seconds)

        results should have size 5
        results.map(_.map(json => json("result").num)) shouldBe Seq(
          Right(2.0),
          Right(4.0),
          Right(6.0),
          Right(8.0),
          Right(10.0)
        )
      }
    )
  }

  "ToolRegistry.executeAll" should "use Sequential as default strategy" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry      = new ToolRegistry(Seq(addTool))
        val requests      = Seq(ToolCallRequest("add", ujson.Obj("a" -> 1, "b" -> 1)))
        val futureResults = registry.executeAll(requests)
        val results       = Await.result(futureResults, 5.seconds)

        results should have size 1
        results.head.map(json => json("result").num) shouldBe Right(2.0)
      }
    )
  }

  it should "handle mixed success and failure" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val requests = Seq(
          ToolCallRequest("add", ujson.Obj("a" -> 1, "b" -> 2)),
          ToolCallRequest("unknown", ujson.Obj()),
          ToolCallRequest("add", ujson.Obj("a" -> 3, "b" -> 4))
        )
        val futureResults = registry.executeAll(requests, ToolExecutionStrategy.Sequential)
        val results       = Await.result(futureResults, 10.seconds)

        results should have size 3
        results(0).isRight shouldBe true
        results(1).isLeft shouldBe true
        results(2).isRight shouldBe true
      }
    )
  }

  it should "handle empty request list" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry      = new ToolRegistry(Seq(addTool))
        val futureResults = registry.executeAll(Seq.empty)
        val results       = Await.result(futureResults, 5.seconds)
        results shouldBe empty
      }
    )
  }

  // ============ Timeout and Retry ============

  "ToolRegistry.execute with timeout" should "return timeout error when tool sleeps longer than timeout" in {
    createSleepTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      sleepTool => {
        val registry = new ToolRegistry(Seq(sleepTool))
        val config   = ToolExecutionConfig(timeout = Some(100.millis))
        val request  = ToolCallRequest("sleep", ujson.Obj("ms" -> 500))
        val result   = registry.execute(request, config)
        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[ToolCallError.Timeout]
        result.left.toOption.get.getMessage should include("timed out")
        result.left.toOption.get.getMessage should include("sleep")
      }
    )
  }

  it should "succeed when tool completes within timeout" in {
    createSleepTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      sleepTool => {
        val registry = new ToolRegistry(Seq(sleepTool))
        val config   = ToolExecutionConfig(timeout = Some(2.seconds))
        val request  = ToolCallRequest("sleep", ujson.Obj("ms" -> 50))
        val result   = registry.execute(request, config)
        result.isRight shouldBe true
      }
    )
  }

  "ToolRegistry.execute with retry" should "succeed after retries when tool fails then succeeds" in {
    createFlakyTool(2).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      flakyTool => {
        val registry = new ToolRegistry(Seq(flakyTool))
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 3, baseDelay = 10.millis, backoffFactor = 1.5))
        )
        val request = ToolCallRequest("flaky", ujson.Obj("x" -> 42.0))
        val result  = registry.execute(request, config)
        result.isRight shouldBe true
        result.toOption.get("result").num shouldBe 42.0
      }
    )
  }

  it should "return failure when retry disabled and tool fails once" in {
    createFlakyTool(1).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      flakyTool => {
        val registry = new ToolRegistry(Seq(flakyTool))
        val config   = ToolExecutionConfig() // no retry
        val request  = ToolCallRequest("flaky", ujson.Obj("x" -> 1.0))
        val result   = registry.execute(request, config)
        result.isLeft shouldBe true
      }
    )
  }

  it should "not retry on non-retryable error (e.g. handler error)" in {
    createFailingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      failTool => {
        val registry = new ToolRegistry(Seq(failTool))
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 3, baseDelay = 10.millis))
        )
        val request = ToolCallRequest("fail", ujson.Obj("x" -> 1.0))
        val result  = registry.execute(request, config)
        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[ToolCallError.HandlerError]
      }
    )
  }

  // ============ Thread Interrupt on Timeout (Issue #1065) ============

  "ToolRegistry.execute with timeout" should "interrupt the worker thread when timeout fires" in {
    val interruptReceived = new java.util.concurrent.atomic.AtomicBoolean(false)
    val schema = Schema
      .`object`[Map[String, Any]]("Blocking tool")
      .withProperty(Schema.property("ms", Schema.integer("Sleep ms")))
    val hangingTool = ToolBuilder[Map[String, Any], MathResult](
      "hang",
      "Blocks until interrupted",
      schema
    ).withHandler { _ =>
      try
        Thread.sleep(10_000) // will be interrupted by timeout
      catch {
        case _: InterruptedException => interruptReceived.set(true)
      }
      Right(MathResult(0.0))
    }.buildSafe()

    hangingTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val config   = ToolExecutionConfig(timeout = Some(1.second))
        val request  = ToolCallRequest("hang", ujson.Obj("ms" -> 10_000))
        val result   = registry.execute(request, config)

        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[ToolCallError.Timeout]

        // Poll for the interrupt to propagate across threads (up to 3 seconds)
        var attempts = 0
        while (!interruptReceived.get() && attempts < 150) {
          Thread.sleep(20)
          attempts += 1
        }
        interruptReceived.get() shouldBe true
      }
    )
  }

  it should "not spuriously interrupt the worker thread when tool completes before timeout" in {
    val interruptReceived = new java.util.concurrent.atomic.AtomicBoolean(false)
    val schema = Schema
      .`object`[Map[String, Any]]("Fast tool")
      .withProperty(Schema.property("ms", Schema.integer("Sleep ms")))
    val fastTool = ToolBuilder[Map[String, Any], MathResult](
      "fast",
      "Completes quickly",
      schema
    ).withHandler { _ =>
      try
        Thread.sleep(50) // well within timeout
      catch {
        case _: InterruptedException => interruptReceived.set(true)
      }
      Right(MathResult(42.0))
    }.buildSafe()

    fastTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val config   = ToolExecutionConfig(timeout = Some(3.seconds))
        val request  = ToolCallRequest("fast", ujson.Obj("ms" -> 50))
        val result   = registry.execute(request, config)

        result.isRight shouldBe true
        result.toOption.get("result").num shouldBe 42.0
        // Give the scheduler time to fire if there were a bug
        Thread.sleep(300)
        interruptReceived.get() shouldBe false
      }
    )
  }

  it should "return Timeout error with correct tool name embedded in the message" in {
    createSleepTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      sleepTool => {
        val registry = new ToolRegistry(Seq(sleepTool))
        val config   = ToolExecutionConfig(timeout = Some(1.second))
        val request  = ToolCallRequest("sleep", ujson.Obj("ms" -> 5000))
        val result   = registry.execute(request, config)

        result.isLeft shouldBe true
        val err = result.left.toOption.get
        err shouldBe a[ToolCallError.Timeout]
        err.getMessage should include("sleep")
        err.getMessage should include("timed out")
      }
    )
  }

  it should "retry after a timeout when a retry policy is configured" in {
    val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val schema = Schema
      .`object`[Map[String, Any]]("Slow-first tool")
      .withProperty(Schema.property("x", Schema.number("Value")))
    val slowFirstTool = ToolBuilder[Map[String, Any], MathResult](
      "slowfirst",
      "Blocks on first call, fast on retry",
      schema
    ).withHandler { extractor =>
      for {
        x <- extractor.getDouble("x")
      } yield {
        if (callCount.getAndIncrement() == 0) {
          Thread.sleep(10_000) // will be interrupted by 1s timeout
        }
        MathResult(x)
      }
    }.buildSafe()

    slowFirstTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val config = ToolExecutionConfig(
          timeout = Some(1.second),
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 2, baseDelay = 100.millis))
        )
        val request = ToolCallRequest("slowfirst", ujson.Obj("x" -> 99.0))
        val result  = registry.execute(request, config)

        // Retry should succeed since second call is not blocked
        result.isRight shouldBe true
        result.toOption.get("result").num shouldBe 99.0
        callCount.get() shouldBe 2
      }
    )
  }

  "ToolRegistry.executeAll with timeout" should "handle multiple concurrent timeouts without starving thread pool" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Blocker")
      .withProperty(Schema.property("ms", Schema.integer("Sleep ms")))
    val hangingTool = ToolBuilder[Map[String, Any], MathResult](
      "blocker",
      "Hangs indefinitely",
      schema
    ).withHandler { _ =>
      try Thread.sleep(30_000)
      catch { case _: InterruptedException => () }
      Right(MathResult(0.0))
    }.buildSafe()

    hangingTool.fold(
      e => fail(s"Hanging tool creation failed: ${e.formatted}"),
      blocker =>
        createAddTool().fold(
          e => fail(s"Add tool creation failed: ${e.formatted}"),
          addTool => {
            val registry = new ToolRegistry(Seq(blocker, addTool))
            val config   = ToolExecutionConfig(timeout = Some(1.second))
            // 5 blocking + 5 fast calls interleaved
            val requests = (1 to 5).flatMap { i =>
              Seq(
                ToolCallRequest("blocker", ujson.Obj("ms" -> 30_000)),
                ToolCallRequest("add", ujson.Obj("a" -> i.toDouble, "b" -> i.toDouble))
              )
            }
            val future  = registry.executeAll(requests, ToolExecutionStrategy.Parallel, config)
            val results = Await.result(future, 15.seconds)

            results should have size 10
            // Even indices = blocker → Timeout; odd indices = add → success
            results.zipWithIndex.foreach {
              case (r, i) if i % 2 == 0 =>
                r.isLeft shouldBe true
                r.left.toOption.get shouldBe a[ToolCallError.Timeout]
              case (r, i) =>
                r.isRight shouldBe true
                val expected = ((i / 2) + 1).toDouble * 2
                r.toOption.get("result").num shouldBe expected
            }
          }
        )
    )
  }

  // ============ Retry Backoff Delay Coverage (relates to #1066) ============

  it should "succeed after backoff delay" in {
    createFlakyTool(2).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      flakyTool => {
        val registry = new ToolRegistry(Seq(flakyTool))
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 3, baseDelay = 50.millis, backoffFactor = 2.0))
        )
        val request = ToolCallRequest("flaky", ujson.Obj("x" -> 7.0))
        val result  = registry.execute(request, config)
        result.isRight shouldBe true
        result.toOption.get("result").num shouldBe 7.0
      }
    )
  }

  it should "wait approximately the configured backoff delay between retries" in {
    createFlakyTool(1).fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      flakyTool => {
        val registry  = new ToolRegistry(Seq(flakyTool))
        val baseDelay = 300.millis
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 2, baseDelay = baseDelay, backoffFactor = 1.0))
        )
        val request = ToolCallRequest("flaky", ujson.Obj("x" -> 5.0))
        val start   = System.currentTimeMillis()
        val result  = registry.execute(request, config)
        val elapsed = System.currentTimeMillis() - start

        result.isRight shouldBe true
        // Should have waited at least baseDelay (50ms tolerance for scheduling overhead)
        elapsed should be >= (baseDelay.toMillis - 50)
      }
    )
  }

  it should "apply exponential backoff factor correctly across multiple retries" in {
    val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val schema = Schema
      .`object`[Map[String, Any]]("Tracked tool")
      .withProperty(Schema.property("x", Schema.number("Value")))
    val trackedTool = ToolBuilder[Map[String, Any], MathResult](
      "tracked",
      "Fails 3 times then succeeds",
      schema
    ).withHandler { extractor =>
      for { x <- extractor.getDouble("x") } yield {
        if (callCount.getAndIncrement() < 3) throw new java.io.IOException("transient")
        MathResult(x)
      }
    }.buildSafe()

    trackedTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val registry = new ToolRegistry(Seq(tool))
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 4, baseDelay = 50.millis, backoffFactor = 2.0))
        )
        val result = registry.execute(ToolCallRequest("tracked", ujson.Obj("x" -> 1.0)), config)
        // 3 failures then success on 4th attempt
        result.isRight shouldBe true
        result.toOption.get("result").num shouldBe 1.0
        callCount.get() shouldBe 4
      }
    )
  }

  it should "exit immediately on non-retryable error without applying any backoff delay" in {
    createFailingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      failTool => {
        val registry = new ToolRegistry(Seq(failTool))
        val config = ToolExecutionConfig(
          retryPolicy = Some(ToolRetryPolicy(maxAttempts = 3, baseDelay = 5.seconds))
        )
        val request = ToolCallRequest("fail", ujson.Obj("x" -> 1.0))
        val start   = System.currentTimeMillis()
        val result  = registry.execute(request, config)
        val elapsed = System.currentTimeMillis() - start

        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[ToolCallError.HandlerError]
        // HandlerError is non-retryable — must exit fast, no 5s delay applied
        elapsed should be < 1000L
      }
    )
  }

  "ToolRegistry.executeAll with timeout" should "complete batch when one tool times out and others succeed" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool =>
        createSleepTool().fold(
          e2 => fail(s"Sleep tool failed: ${e2.formatted}"),
          sleepTool => {
            val registry = new ToolRegistry(Seq(addTool, sleepTool))
            val config   = ToolExecutionConfig(timeout = Some(150.millis))
            val requests = Seq(
              ToolCallRequest("add", ujson.Obj("a" -> 1, "b" -> 2)),
              ToolCallRequest("sleep", ujson.Obj("ms" -> 5000)),
              ToolCallRequest("add", ujson.Obj("a" -> 3, "b" -> 4))
            )
            val futureResults = registry.executeAll(requests, ToolExecutionStrategy.Parallel, config)
            val results       = Await.result(futureResults, 10.seconds)
            results should have size 3
            results(0).map(_("result").num) shouldBe Right(3.0)
            results(1).left.toOption.get shouldBe a[ToolCallError.Timeout]
            results(2).map(_("result").num) shouldBe Right(7.0)
          }
        )
    )
  }

  // ============ OpenAI Tool Format ============

  "ToolRegistry.getOpenAITools" should "generate OpenAI tool definitions" in {
    val result = for {
      addTool  <- createAddTool()
      echoTool <- createEchoTool()
    } yield {
      val registry = new ToolRegistry(Seq(addTool, echoTool))
      val tools    = registry.getOpenAITools()

      tools.arr should have size 2

      val addToolJson = tools.arr
        .find(t => t("function")("name").str == "add")
        .fold(fail("Expected to find 'add' tool in registry output"))(identity)
      addToolJson("type").str shouldBe "function"
      addToolJson("function")("description").str shouldBe "Adds two numbers"
      addToolJson("function")("parameters")("type").str shouldBe "object"
      addToolJson("function")("strict").bool shouldBe true
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  it should "respect strict parameter" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry       = new ToolRegistry(Seq(addTool))
        val strictTools    = registry.getOpenAITools(strict = true)
        val nonStrictTools = registry.getOpenAITools(strict = false)

        strictTools.arr.head("function")("strict").bool shouldBe true
        nonStrictTools.arr.head("function")("strict").bool shouldBe false
      }
    )
  }

  "ToolRegistry.getToolDefinitionsSafe" should "return OpenAI format for openai provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val tools    = registry.getToolDefinitionsSafe("openai").getOrElse(fail("getToolDefinitionsSafe failed"))
        tools shouldBe a[ujson.Arr]
        tools.arr should have size 1
      }
    )
  }

  it should "return OpenAI format for anthropic provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val tools    = registry.getToolDefinitionsSafe("anthropic").getOrElse(fail("getToolDefinitionsSafe failed"))
        tools shouldBe a[ujson.Arr]
      }
    )
  }

  it should "return OpenAI format for gemini provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val tools    = registry.getToolDefinitionsSafe("gemini").getOrElse(fail("getToolDefinitionsSafe failed"))
        tools shouldBe a[ujson.Arr]
      }
    )
  }

  it should "return Left for unsupported provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        registry.getToolDefinitionsSafe("unsupported_provider") shouldBe a[Left[_, _]]
      }
    )
  }
}
