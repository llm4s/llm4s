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

  "ToolRegistry.getToolDefinitions" should "return OpenAI format for openai provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val tools    = registry.getToolDefinitions("openai")
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
        val tools    = registry.getToolDefinitions("anthropic")
        tools shouldBe a[ujson.Arr]
      }
    )
  }

  it should "return OpenAI format for gemini provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        val tools    = registry.getToolDefinitions("gemini")
        tools shouldBe a[ujson.Arr]
      }
    )
  }

  it should "throw exception for unsupported provider" in {
    createAddTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      addTool => {
        val registry = new ToolRegistry(Seq(addTool))
        an[IllegalArgumentException] should be thrownBy {
          registry.getToolDefinitions("unsupported_provider")
        }
      }
    )
  }
}
