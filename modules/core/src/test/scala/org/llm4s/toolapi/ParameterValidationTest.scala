package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class ParameterValidationTest extends AnyFlatSpec with Matchers {

  // Result type for testing
  case class TestResult(message: String)
  implicit val testResultRW: ReadWriter[TestResult] = macroRW[TestResult]

  def createTestTool(name: String = "test_tool"): ToolFunction[Map[String, Any], TestResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("requiredString", Schema.string("A required string parameter")))
      .withProperty(Schema.property("requiredNumber", Schema.number("A required number parameter")))
      .withProperty(Schema.property("optionalBool", Schema.boolean("An optional boolean parameter")))

    ToolBuilder[Map[String, Any], TestResult](
      name,
      "Test tool for parameter validation",
      schema
    ).withHandler { extractor =>
      for {
        str <- extractor.getString("requiredString")
        num <- extractor.getDouble("requiredNumber")
        bool = extractor.getBoolean("optionalBool").toOption
      } yield TestResult(s"Got string: $str, number: $num, bool: $bool")
    }.build()
  }

  "ToolFunction" should "provide clear error message for null arguments" in {
    val tool   = createTestTool("inventory_tool")
    val result = tool.execute(ujson.Null)

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getFormattedMessage

    message should include("inventory_tool")
    message should include("null arguments")
    message should include("expected an object")
  }

  "ToolFunction" should "provide clear error message for missing required parameters" in {
    val tool = createTestTool()
    val result = tool.execute(
      ujson.Obj(
        "requiredString" -> "test"
        // Missing requiredNumber
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getMessage

    message should include("requiredNumber")
    message should include("missing")
    message should include("available:")
    message should include("requiredString")
  }

  "ToolFunction" should "provide clear error message for type mismatches" in {
    val tool = createTestTool()
    val result = tool.execute(
      ujson.Obj(
        "requiredString" -> 123,           // Wrong type: number instead of string
        "requiredNumber" -> "not a number" // Wrong type: string instead of number
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getMessage

    // Should report the first error encountered
    message should include("has wrong type")
    message should (include("expected string").or(include("expected number")))
  }

  "ToolFunction" should "provide clear error message for null values in required fields" in {
    val tool = createTestTool()
    val result = tool.execute(
      ujson.Obj(
        "requiredString" -> ujson.Null,
        "requiredNumber" -> 42
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getMessage

    message should include("requiredString")
    message should include("null")
    message should include("required but value was null")
  }

  "SafeParameterExtractor" should "provide helpful error for accessing properties on non-objects" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> "John", // This is a string, not an object
        "age"  -> 30
      )
    )

    val result = extractor.getString("user.name") // Trying to access .name on a string

    (result should be).a(Symbol("left"))
    val error = result.left.getOrElse(fail("Expected error"))

    error should include("cannot access parameter")
    error should include("name")
    error should include("string")
    error should include("not an object")
  }

  "SafeParameterExtractor" should "list available properties when a required one is missing" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> ujson.Obj(
          "firstName" -> "John",
          "lastName"  -> "Doe",
          "email"     -> "john@example.com"
        )
      )
    )

    val result = extractor.getString("user.username") // username doesn't exist

    (result should be).a(Symbol("left"))
    val error = result.left.getOrElse(fail("Expected error"))

    error should include("username")
    error should include("missing")
    error should include("available:")
    // The error should show available properties from the user object where username is missing
    // These should be the properties inside user, not the root level properties
    error should include("firstName")
    error should include("lastName")
    error should include("email")
    // The actual error message will say "user.username" but available parameters
    // should be from the user object, not include "user" as an available key
  }

  "SafeParameterExtractor" should "show root-level keys when root parameter is missing" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> ujson.Obj("name" -> "John"),
        "age"  -> 30,
        "city" -> "New York"
      )
    )

    val result = extractor.getString("country") // country doesn't exist at root

    (result should be).a(Symbol("left"))
    val error = result.left.getOrElse(fail("Expected error"))

    error should include("country")
    error should include("missing")
    error should include("available:")
    // Should show root-level keys where country is missing
    error should include("user")
    error should include("age")
    error should include("city")
    (error should not).include("name") // Should NOT include nested properties
  }

  "SafeParameterExtractor" should "handle nested null values gracefully" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> ujson.Null
      )
    )

    val result = extractor.getString("user.name")

    (result should be).a(Symbol("left"))
    val error = result.left.getOrElse(fail("Expected error"))

    error should include("cannot access parameter")
    error should include("name")
    error should include("null")
  }

  "SafeParameterExtractor" should "show correct keys for deeply nested missing parameters" in {
    val extractor = SafeParameterExtractor(
      ujson.Obj(
        "user" -> ujson.Obj(
          "profile" -> ujson.Obj(
            "firstName" -> "John",
            "lastName"  -> "Doe",
            "settings" -> ujson.Obj(
              "theme"         -> "dark",
              "notifications" -> true
            )
          )
        )
      )
    )

    // Test missing parameter at different nesting levels
    val result1 = extractor.getString("user.profile.settings.language")
    (result1 should be).a(Symbol("left"))
    val error1 = result1.left.getOrElse(fail("Expected error"))
    error1 should include("available:")
    error1 should include("theme")
    error1 should include("notifications")
    (error1 should not).include("firstName") // Should NOT include keys from parent level

    val result2 = extractor.getString("user.profile.email")
    (result2 should be).a(Symbol("left"))
    val error2 = result2.left.getOrElse(fail("Expected error"))
    error2 should include("available:")
    error2 should include("firstName")
    error2 should include("lastName")
    error2 should include("settings")
    (error2 should not).include("theme") // Should NOT include keys from nested level
  }

  "ToolFunction with complex nested parameters" should "provide clear path information in errors" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Complex parameters")
      .withProperty(
        Schema.property(
          "user",
          Schema
            .`object`[Map[String, Any]]("User object")
            .withProperty(
              Schema.property(
                "profile",
                Schema
                  .`object`[Map[String, Any]]("Profile object")
                  .withProperty(
                    Schema.property(
                      "settings",
                      Schema
                        .`object`[Map[String, Any]]("Settings object")
                        .withProperty(Schema.property("theme", Schema.string("Theme name")))
                    )
                  )
              )
            )
        )
      )

    val tool = ToolBuilder[Map[String, Any], TestResult](
      "complex_tool",
      "Tool with nested parameters",
      schema
    ).withHandler { extractor =>
      extractor.getString("user.profile.settings.theme").map(theme => TestResult(s"Theme: $theme"))
    }.build()

    // Test with missing nested property
    val result = tool.execute(
      ujson.Obj(
        "user" -> ujson.Obj(
          "profile" -> ujson.Obj(
            // Missing settings object
          )
        )
      )
    )

    (result should be).a(Symbol("left"))
    val error   = result.left.getOrElse(fail("Expected error"))
    val message = error.getMessage

    message should include("settings")
    message should include("missing")
    message should include("user.profile.settings")
  }

  "ToolCallError" should "format multiple errors nicely" in {
    val error = ToolCallError.InvalidArguments(
      "test_tool",
      List(
        ToolParameterError.MissingParameter("name", "string"),
        ToolParameterError.TypeMismatch("age", "number", "string"),
        ToolParameterError.NullParameter("email", "string")
      )
    )

    val message = error.getFormattedMessage

    message should include("Tool call 'test_tool' has parameter issues:")
    message should include("- required parameter 'name' (type: string) is missing")
    message should include("- parameter 'age' has wrong type - expected number but got string")
    message should include("- parameter 'email' (type: string) is required but value was null")
  }
}
