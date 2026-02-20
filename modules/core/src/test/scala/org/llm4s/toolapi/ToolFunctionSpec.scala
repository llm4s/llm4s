package org.llm4s.toolapi

import scala.annotation.nowarn

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Tests for ToolFunction and ToolBuilder
 */
class ToolFunctionSpec extends AnyFlatSpec with Matchers {

  // Test result types
  case class GreetingResult(greeting: String)
  implicit val greetingResultRW: ReadWriter[GreetingResult] = macroRW

  case class MathResult(result: Double)
  implicit val mathResultRW: ReadWriter[MathResult] = macroRW

  case class EmptyResult(success: Boolean)
  implicit val emptyResultRW: ReadWriter[EmptyResult] = macroRW

  // Helper to create test tools — returns Result, never extracts
  def createGreetingTool(): Result[ToolFunction[Map[String, Any], GreetingResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Greeting parameters")
      .withProperty(Schema.property("name", Schema.string("Name to greet")))

    ToolBuilder[Map[String, Any], GreetingResult](
      "greet",
      "Creates a greeting message",
      schema
    ).withHandler(extractor => extractor.getString("name").map(name => GreetingResult(s"Hello, $name!")))
      .buildSafe()
  }

  def createZeroParamTool(): Result[ToolFunction[Map[String, Any], EmptyResult]] = {
    val schema = Schema.`object`[Map[String, Any]]("No parameters required")

    ToolBuilder[Map[String, Any], EmptyResult](
      "ping",
      "Returns success without parameters",
      schema
    ).withHandler(_ => Right(EmptyResult(success = true))).buildSafe()
  }

  // ============ ToolFunction.toOpenAITool ============

  "ToolFunction.toOpenAITool" should "generate correct OpenAI format" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val json = tool.toOpenAITool()
        json("type").str shouldBe "function"
        json("function")("name").str shouldBe "greet"
        json("function")("description").str shouldBe "Creates a greeting message"
        json("function")("parameters")("type").str shouldBe "object"
      }
    )
  }

  it should "set strict to true by default" in {
    createGreetingTool()
      .map(_.toOpenAITool())
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        json => json("function")("strict").bool shouldBe true
      )
  }

  it should "allow setting strict to false" in {
    createGreetingTool()
      .map(_.toOpenAITool(strict = false))
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        json => json("function")("strict").bool shouldBe false
      )
  }

  it should "include parameter schema" in {
    createGreetingTool()
      .map(_.toOpenAITool())
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        json => {
          val params = json("function")("parameters")
          params("properties")("name")("type").str shouldBe "string"
          params("properties")("name")("description").str shouldBe "Name to greet"
        }
      )
  }

  // ============ ToolFunction.execute ============

  "ToolFunction.execute" should "execute with valid parameters" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj("name" -> "World"))
        result.isRight shouldBe true
        result.map(json => json("greeting").str) shouldBe Right("Hello, World!")
      }
    )
  }

  it should "return HandlerError for handler failures" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("value", Schema.number("A value")))

    ToolBuilder[Map[String, Any], MathResult](
      "fail_tool",
      "Always fails",
      schema
    ).withHandler(extractor => extractor.getDouble("value").flatMap(_ => Left("intentional failure")))
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.execute(ujson.Obj("value" -> 10))
          result.isLeft shouldBe true
          val error = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
          error shouldBe a[ToolCallError.HandlerError]
          error.getMessage should include("intentional failure")
        }
      )
  }

  it should "return NullArguments for null input with required params" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)
        result.isLeft shouldBe true
        val error = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
        error shouldBe a[ToolCallError.NullArguments]
        error.getMessage should include("null arguments")
      }
    )
  }

  it should "handle null input for zero-parameter tools" in {
    createZeroParamTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)
        result.isRight shouldBe true
        result.map(json => json("success").bool) shouldBe Right(true)
      }
    )
  }

  it should "handle empty object for zero-parameter tools" in {
    createZeroParamTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj())
        result.isRight shouldBe true
        result.map(json => json("success").bool) shouldBe Right(true)
      }
    )
  }

  // ============ ToolFunction.executeEnhanced ============

  "ToolFunction.executeEnhanced" should "execute with enhanced error reporting" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("a", Schema.number("First number")))
      .withProperty(Schema.property("b", Schema.number("Second number")))

    ToolBuilder[Map[String, Any], MathResult](
      "add",
      "Adds two numbers",
      schema
    ).withHandler(_ => Right(MathResult(0))) // Original handler not used
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.executeEnhanced(
            ujson.Obj("a" -> 5, "b" -> 3),
            extractor =>
              for {
                a <- extractor.getDoubleEnhanced("a").left.map(List(_))
                b <- extractor.getDoubleEnhanced("b").left.map(List(_))
              } yield MathResult(a + b)
          )
          result.isRight shouldBe true
          result.map(json => json("result").num) shouldBe Right(8.0)
        }
      )
  }

  it should "return InvalidArguments for parameter errors" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("required_field", Schema.string("Required")))

    ToolBuilder[Map[String, Any], GreetingResult](
      "test_tool",
      "Test tool",
      schema
    ).withHandler(_ => Right(GreetingResult("")))
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.executeEnhanced(
            ujson.Obj(), // Missing required field
            extractor => extractor.getStringEnhanced("required_field").left.map(List(_)).map(GreetingResult(_))
          )
          result.isLeft shouldBe true
          val error = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
          error shouldBe a[ToolCallError.InvalidArguments]
          error.getMessage should include("missing")
        }
      )
  }

  it should "handle null input for zero-parameter tools" in {
    createZeroParamTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.executeEnhanced(
          ujson.Null,
          _ => Right(EmptyResult(success = true))
        )
        result.isRight shouldBe true
      }
    )
  }

  it should "return NullArguments for null input with required params" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.executeEnhanced(
          ujson.Null,
          extractor => extractor.getStringEnhanced("name").left.map(List(_)).map(GreetingResult(_))
        )
        result.isLeft shouldBe true
        val error = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
        error shouldBe a[ToolCallError.NullArguments]
      }
    )
  }

  // ============ ToolBuilder ============

  "ToolBuilder" should "build a tool with handler" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("value", Schema.number("A value")))

    ToolBuilder[Map[String, Any], MathResult](
      "double",
      "Doubles a number",
      schema
    ).withHandler(extractor => extractor.getDouble("value").map(v => MathResult(v * 2)))
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          tool.name shouldBe "double"
          tool.description shouldBe "Doubles a number"

          val result = tool.execute(ujson.Obj("value" -> 5))
          result.map(json => json("result").num) shouldBe Right(10.0)
        }
      )
  }

  it should "return Left when building without handler" in {
    val schema = Schema.`object`[Map[String, Any]]("Params")

    val builder = ToolBuilder[Map[String, Any], EmptyResult](
      "no_handler",
      "No handler defined",
      schema
    )

    val result = builder.buildSafe()
    result.isLeft shouldBe true
  }

  it should "build a tool via deprecated build() when handler is defined" in {
    val schema = Schema.`object`[Map[String, Any]]("Params")

    @nowarn("cat=deprecation")
    val tool = ToolBuilder[Map[String, Any], EmptyResult](
      "test",
      "Test",
      schema
    ).withHandler(_ => Right(EmptyResult(success = true)))
      .build()

    tool.name shouldBe "test"
  }

  it should "throw IllegalStateException via deprecated build() when handler is not defined" in {
    val schema = Schema.`object`[Map[String, Any]]("Params")

    @nowarn("cat=deprecation")
    val result = scala.util.Try {
      ToolBuilder[Map[String, Any], EmptyResult](
        "no_handler",
        "No handler defined",
        schema
      ).build()
    }

    result.isFailure shouldBe true
    result.failed.get shouldBe a[IllegalStateException]
  }

  it should "allow replacing handler with withHandler" in {
    val schema = Schema.`object`[Map[String, Any]]("Params")

    val builder1 = ToolBuilder[Map[String, Any], EmptyResult](
      "test",
      "Test",
      schema
    ).withHandler(_ => Right(EmptyResult(success = false)))

    val builder2 = builder1.withHandler(_ => Right(EmptyResult(success = true)))

    builder2
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.execute(ujson.Obj())
          result.map(json => json("success").bool) shouldBe Right(true)
        }
      )
  }

  // ============ ToolFunction Properties ============

  "ToolFunction" should "expose name and description" in {
    createGreetingTool().map(_.name) shouldBe Right("greet")
    createGreetingTool().map(_.description) shouldBe Right("Creates a greeting message")
  }

  it should "expose schema" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => tool.schema shouldBe a[ObjectSchema[_]]
    )
  }
}
