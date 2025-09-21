package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class EnhancedErrorMessagesTest extends AnyFlatSpec with Matchers {

  // Result type for testing
  case class TestResult(message: String)
  implicit val testResultRW: ReadWriter[TestResult] = macroRW[TestResult]

  def createEnhancedTool(name: String = "test_tool"): ToolFunction[Map[String, Any], TestResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("item", Schema.string("Item name")))
      .withProperty(Schema.property("quantity", Schema.number("Item quantity")))
      .withProperty(Schema.property("location", Schema.string("Storage location")))

    // Create a tool that uses SafeParameterExtractor in enhanced mode
    ToolBuilder[Map[String, Any], TestResult](
      name,
      "Test tool for enhanced error messages",
      schema
    ).withHandler { params =>
      // Use SafeParameterExtractor in enhanced mode for structured errors
      val result = for {
        item <- params.getStringEnhanced("item")
        qty  <- params.getDoubleEnhanced("quantity")
        loc  <- params.getStringEnhanced("location")
      } yield TestResult(s"Success: $item, $qty, $loc")

      result.left.map(_.getMessage)
    }.build()
  }

  "Enhanced error messages" should "clearly indicate null arguments" in {
    val tool   = createEnhancedTool("add_inventory_item")
    val result = tool.execute(ujson.Null)

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    message shouldBe "Tool call 'add_inventory_item' received null arguments - expected an object with required parameters"
  }

  "Enhanced error messages" should "clearly indicate missing required parameters" in {
    val tool = createEnhancedTool("add_inventory_item")
    val result = tool.execute(
      ujson.Obj(
        "item" -> "Apple"
        // Missing: quantity and location
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    // Should get the first missing parameter
    message should include("Tool call 'add_inventory_item'")
    message should include("required parameter")
    message should include("'quantity'")
    message should include("(type: number)")
    message should include("is missing")
  }

  "Enhanced error messages" should "clearly indicate null values for required parameters" in {
    val tool = createEnhancedTool("add_inventory_item")
    val result = tool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "quantity" -> ujson.Null,
        "location" -> "Warehouse A"
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    message shouldBe "Tool call 'add_inventory_item' failed with error: parameter 'quantity' (type: number) is required but value was null"
  }

  "Enhanced error messages" should "clearly indicate type mismatches" in {
    val tool = createEnhancedTool("add_inventory_item")
    val result = tool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "quantity" -> "five", // Wrong type: string instead of number
        "location" -> "Warehouse A"
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    message shouldBe "Tool call 'add_inventory_item' failed with error: parameter 'quantity' has wrong type - expected number but got string"
  }

  "Enhanced error messages" should "handle execution errors consistently" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("value", Schema.number("A value")))

    val tool = ToolBuilder[Map[String, Any], TestResult](
      "divide_by_value",
      "Divides 10 by the provided value",
      schema
    ).withHandler { params =>
      params.getDoubleEnhanced("value") match {
        case Right(0)  => Left("cannot divide by zero")
        case Right(v)  => Right(TestResult(s"Result: ${10.0 / v}"))
        case Left(err) => Left(err.getMessage)
      }
    }.build()

    val result = tool.execute(ujson.Obj("value" -> 0))

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    message shouldBe "Tool call 'divide_by_value' failed with error: cannot divide by zero"
  }

  "ToolParameterError messages" should "be clear and consistent" in {
    // Test MissingParameter
    val missing = ToolParameterError.MissingParameter(
      "email",
      "string",
      List("name", "age", "address")
    )
    missing.getMessage shouldBe "required parameter 'email' (type: string) is missing (available: name, age, address)"

    // Test NullParameter
    val nullParam = ToolParameterError.NullParameter("age", "number")
    nullParam.getMessage shouldBe "parameter 'age' (type: number) is required but value was null"

    // Test TypeMismatch
    val typeMismatch = ToolParameterError.TypeMismatch("count", "integer", "string")
    typeMismatch.getMessage shouldBe "parameter 'count' has wrong type - expected integer but got string"

    // Test InvalidNesting
    val invalidNesting = ToolParameterError.InvalidNesting("name", "user", "string")
    invalidNesting.getMessage shouldBe "cannot access parameter 'name' because parent 'user' is string, not an object"
  }

  "ToolCallError messages" should "have consistent format" in {
    // Test UnknownFunction
    val unknown = ToolCallError.UnknownFunction("foo_bar")
    unknown.getFormattedMessage shouldBe "Tool call 'foo_bar' is not a recognized tool"

    // Test NullArguments
    val nullArgs = ToolCallError.NullArguments("process_data")
    nullArgs.getFormattedMessage shouldBe "Tool call 'process_data' received null arguments - expected an object with required parameters"

    // Test InvalidArguments with single error
    val singleError = ToolCallError.InvalidArguments(
      "calculate",
      List(ToolParameterError.MissingParameter("value", "number"))
    )
    singleError.getFormattedMessage shouldBe "Tool call 'calculate' required parameter 'value' (type: number) is missing"

    // Test InvalidArguments with multiple errors
    val multipleErrors = ToolCallError.InvalidArguments(
      "submit_form",
      List(
        ToolParameterError.MissingParameter("email", "string"),
        ToolParameterError.TypeMismatch("age", "number", "string"),
        ToolParameterError.NullParameter("name", "string")
      )
    )
    multipleErrors.getFormattedMessage should include("Tool call 'submit_form' has parameter issues:")
    multipleErrors.getFormattedMessage should include("- required parameter 'email' (type: string) is missing")
    multipleErrors.getFormattedMessage should include(
      "- parameter 'age' has wrong type - expected number but got string"
    )
    multipleErrors.getFormattedMessage should include(
      "- parameter 'name' (type: string) is required but value was null"
    )

    // Test ExecutionError
    val execError = ToolCallError.ExecutionError("process", new RuntimeException("Network timeout"))
    execError.getFormattedMessage shouldBe "Tool call 'process' failed during execution: Network timeout"

    // Test HandlerError
    val handlerError = ToolCallError.HandlerError("validate", "Invalid format")
    handlerError.getFormattedMessage shouldBe "Tool call 'validate' failed with error: Invalid format"
  }

  "SafeParameterExtractor (enhanced mode)" should "provide helpful information about available properties" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "firstName" -> "John",
        "lastName"  -> "Doe",
        "email"     -> "john@example.com"
      )
    )

    val result = extractor.getStringEnhanced("username")

    (result should be).a(Symbol("left"))
    val error = result.left.getOrElse(fail("Expected error"))
    error.getMessage should include("required parameter 'username' (type: string) is missing")
    error.getMessage should include("available: email, firstName, lastName")
  }

  "SafeParameterExtractor (enhanced mode)" should "handle nested parameters correctly" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> ujson.Obj(
          "profile" -> ujson.Obj(
            "name" -> "John"
          )
        )
      )
    )

    // Test successful nested access
    val nameResult = extractor.getStringEnhanced("user.profile.name")
    nameResult shouldBe Right("John")

    // Test missing nested property
    val ageResult = extractor.getIntEnhanced("user.profile.age")
    (ageResult should be).a(Symbol("left"))
    val error = ageResult.left.getOrElse(fail("Expected error"))
    error.getMessage should include("required parameter 'user.profile.age' (type: integer) is missing")
  }

  "SafeParameterExtractor (enhanced mode)" should "handle optional parameters correctly" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "required" -> "value",
        "optional" -> ujson.Null
      )
    )

    // Optional parameter that's missing should return None
    val missing = extractor.getOptionalString("nonexistent")
    missing shouldBe Right(None)

    // Optional parameter that's null should return None
    val nullValue = extractor.getOptionalString("optional")
    nullValue shouldBe Right(None)

    // Optional parameter with wrong type should return error
    val wrongType = extractor.getOptionalInt("required")
    (wrongType should be).a(Symbol("left"))
    val error = wrongType.left.getOrElse(fail("Expected error"))
    error.getMessage should include("has wrong type - expected integer but got string")
  }
}
