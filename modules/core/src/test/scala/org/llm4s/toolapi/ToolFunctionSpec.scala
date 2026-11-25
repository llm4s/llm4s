package org.llm4s.toolapi

import org.llm4s.llmconnect.model.ToolCallId
import org.llm4s.toolapi.IdempotencyKey
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
  def createGreetingTool(): Result[ToolFunction[GreetingResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Greeting parameters")
      .withProperty(Schema.property("name", Schema.string("Name to greet")))

    ToolBuilder[GreetingResult](
      "greet",
      "Creates a greeting message",
      schema
    ).withHandler(extractor => extractor.getString("name").map(name => GreetingResult(s"Hello, $name!")))
      .build()
  }

  def createZeroParamTool(): Result[ToolFunction[EmptyResult]] = {
    val schema = Schema.`object`[Map[String, Any]]("No parameters required")

    ToolBuilder[EmptyResult](
      "ping",
      "Returns success without parameters",
      schema
    ).withHandler(_ => Right(EmptyResult(success = true))).build()
  }

  // ============ ToolFunction.toolDefinition ============

  "ToolFunction.toolDefinition" should "generate correct OpenAI format" in {
    createGreetingTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val json = tool.toolDefinition(strict = true)
        json("type").str shouldBe "function"
        json("function")("name").str shouldBe "greet"
        json("function")("description").str shouldBe "Creates a greeting message"
        json("function")("parameters")("type").str shouldBe "object"
      }
    )
  }

  it should "set strict to true by default" in {
    createGreetingTool()
      .map(_.toolDefinition(strict = true))
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        json => json("function")("strict").bool shouldBe true
      )
  }

  it should "allow setting strict to false" in {
    createGreetingTool()
      .map(_.toolDefinition(strict = false))
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        json => json("function")("strict").bool shouldBe false
      )
  }

  it should "include parameter schema" in {
    createGreetingTool()
      .map(_.toolDefinition(strict = true))
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

    ToolBuilder[MathResult](
      "fail_tool",
      "Always fails",
      schema
    ).withHandler(extractor =>
      extractor.getDouble("value").flatMap(_ => Left(ToolHandlerError.failed("intentional failure")))
    ).build()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.execute(ujson.Obj("value" -> 10))
          result.isLeft shouldBe true
          val error = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
          error shouldBe a[ToolCallError.HandlerError]
          error.message should include("intentional failure")
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
        error.message should include("null arguments")
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

  // ============ One handler error type (#2815) ============

  private def adder(handler: SafeParameterExtractor => Either[ToolHandlerError, MathResult]) =
    ToolBuilder[MathResult](
      "add",
      "Adds two numbers",
      Schema
        .`object`[Map[String, Any]]("Params")
        .withProperty(Schema.property("a", Schema.number("First number")))
        .withProperty(Schema.property("b", Schema.number("Second number")))
    ).withHandler(handler).build().fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  private def sum = adder { args =>
    for {
      a <- args.getDouble("a")
      b <- args.getDouble("b")
      _ <- Either.cond(a + b < 100, (), ToolHandlerError.failed("the sum must stay below 100"))
    } yield MathResult(a + b)
  }

  "A handler" should "read its arguments and report its own failure in one for comprehension" in {
    sum.execute(ujson.Obj("a" -> 5, "b" -> 3)).map(_("result").num) shouldBe Right(8.0)
  }

  it should "have a getter's error reported as InvalidArguments, with the parameter error itself" in {
    sum.execute(ujson.Obj("a" -> 5)) shouldBe Left(
      ToolCallError.InvalidArguments("add", List(ToolParameterError.MissingParameter("b", "number", List("a"))))
    )
    sum.execute(ujson.Obj("a" -> "five", "b" -> 3)) shouldBe Left(
      ToolCallError.InvalidArguments("add", List(ToolParameterError.TypeMismatch("a", "number", "string")))
    )
  }

  it should "have its own message reported as HandlerError" in {
    sum.execute(ujson.Obj("a" -> 60, "b" -> 60)) shouldBe
      Left(ToolCallError.HandlerError("add", "the sum must stay below 100"))
  }

  it should "send the model the same JSON for its own failure as when the error was a String" in {
    val error = sum.execute(ujson.Obj("a" -> 60, "b" -> 60)).left.toOption.get
    ToolCallErrorJson.toJson(error).render() shouldBe
      """{"isError":true,"toolName":"add","message":"failed with error: the sum must stay below 100","errorType":"handler_error"}"""
  }

  it should "need no declared type when it mixes a getter's error, a helper's message and its own failure" in {
    def halve(n: Double): Either[String, Double] = Either.cond(n % 2 == 0, n / 2, s"$n is odd")
    val known                                    = Map(2.0 -> "two")
    def handler(args: SafeParameterExtractor) =
      for {
        a    <- args.getDouble("a")
        half <- halve(a).left.map(ToolHandlerError.failed)
        _    <- known.get(half).toRight(ToolHandlerError.failed(s"$half is not known"))
      } yield MathResult(half)
    val tool = adder(handler)

    tool.execute(ujson.Obj("a" -> 4)).map(_("result").num) shouldBe Right(2.0)
    tool.execute(ujson.Obj("a" -> 3)) shouldBe Left(ToolCallError.HandlerError("add", "3.0 is odd"))
    tool.execute(ujson.Obj("a" -> 8)) shouldBe Left(ToolCallError.HandlerError("add", "4.0 is not known"))
    tool.execute(ujson.Obj()) shouldBe Left(
      ToolCallError.InvalidArguments("add", List(ToolParameterError.MissingParameter("a", "number", Nil)))
    )
  }

  "ToolHandlerError" should "have a message whichever case it is" in {
    val errors: List[ToolHandlerError] =
      List(ToolHandlerError.failed("no such city"), ToolParameterError.NullParameter("city", "string"))
    errors.map(_.message) shouldBe
      List("no such city", "parameter 'city' (type: string) is required but value was null")
  }

  it should "be a Failed that equals another of the same message and can replace it" in {
    ToolHandlerError.failed("a") shouldBe ToolHandlerError.Failed("a")
    ToolHandlerError.Failed("a").withMessage("b") shouldBe ToolHandlerError.Failed("b")
  }

  it should "have every error of validateRequired listed, one per parameter, in the JSON the model reads" in {
    val checked = adder { args =>
      args
        .validateRequired("a" -> ToolParameterType.Number, "b" -> ToolParameterType.Number)
        .map(_ => MathResult(0))
    }
    val error = checked.execute(ujson.Obj("a" -> "x")).left.toOption.get

    error shouldBe ToolCallError.InvalidArguments(
      "add",
      List(
        ToolParameterError.TypeMismatch("a", "number", "string"),
        ToolParameterError.MissingParameter("b", "number", List("a"))
      )
    )
    val json = ToolCallErrorJson.toJson(error)
    json("errorType").str shouldBe "invalid_arguments"
    json("parameterErrors").arr.map(e => e("parameterName").str -> e("kind").str).toSeq shouldBe
      Seq("a" -> "type_mismatch", "b" -> "missing_parameter")
  }

  it should "be given the call it runs for, structured errors included" in {
    val keyed = adder(args =>
      args.call
        .map(_.idempotencyKey)
        .toRight(ToolHandlerError.failed("no call"))
        .flatMap(_ => args.getDouble("a"))
        .map(MathResult(_))
    )
    keyed.execute(ujson.Obj(), ToolCallInfo(ToolCallId("call-1"), IdempotencyKey("key-1"))) shouldBe Left(
      ToolCallError.InvalidArguments("add", List(ToolParameterError.MissingParameter("a", "number", Nil)))
    )
    keyed.execute(ujson.Obj("a" -> 1)) shouldBe Left(ToolCallError.HandlerError("add", "no call"))
  }

  "ToolFunction" should "have no second way to execute that ignores its handler" in {
    assertTypeError("""sum.executeEnhanced(ujson.Obj(), _ => Right(MathResult(0)))""")
    assertTypeError("""SafeParameterExtractor.enhanced(ujson.Obj())""")
    assertTypeError("""SafeParameterExtractor(ujson.Obj()).getStringEnhanced("x")""")
  }

  // ============ ToolBuilder ============

  "ToolBuilder" should "build a tool with handler" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("value", Schema.number("A value")))

    ToolBuilder[MathResult](
      "double",
      "Doubles a number",
      schema
    ).withHandler(extractor => extractor.getDouble("value").map(v => MathResult(v * 2)))
      .build()
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

    val builder = ToolBuilder[EmptyResult](
      "no_handler",
      "No handler defined",
      schema
    )

    val result = builder.build()
    result.isLeft shouldBe true
  }

  it should "allow replacing handler with withHandler" in {
    val schema = Schema.`object`[Map[String, Any]]("Params")

    val builder1 = ToolBuilder[EmptyResult](
      "test",
      "Test",
      schema
    ).withHandler(_ => Right(EmptyResult(success = false)))

    val builder2 = builder1.withHandler(_ => Right(EmptyResult(success = true)))

    builder2
      .build()
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
