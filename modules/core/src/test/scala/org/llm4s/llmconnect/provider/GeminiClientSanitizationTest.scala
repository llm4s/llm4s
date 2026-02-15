package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import upickle.default._

/**
 * Tests for GeminiClient tool schema sanitization logic.
 *
 * These tests verify that:
 * - OpenAI-specific fields ('strict', 'additionalProperties') are removed
 * - Sanitization works recursively for nested objects, arrays, and schema compositions (anyOf, oneOf, allOf)
 * - Tool conversion maintains correct structure and metadata for Gemini API
 */
class GeminiClientSanitizationTest extends AnyFlatSpec with Matchers {

  // Use reflection to access private convertToolToGeminiFormat method
  private def convertToolToGeminiFormat(toolFunction: org.llm4s.toolapi.ToolFunction[_, _]): ujson.Value = {
    val client = new GeminiClient(
      org.llm4s.llmconnect.config.GeminiConfig(
        apiKey = "test-api-key",
        model = "gemini-2.0-flash-preview",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        contextWindow = 32768,
        reserveCompletion = 8192
      )
    )

    val method =
      client.getClass.getDeclaredMethod("convertToolToGeminiFormat", classOf[org.llm4s.toolapi.ToolFunction[_, _]])
    method.setAccessible(true)
    method.invoke(client, toolFunction).asInstanceOf[ujson.Value]
  }

  "convertToolToGeminiFormat" should "strip 'strict' and 'additionalProperties' from simple schemas" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test object")
      .withProperty(Schema.property("name", Schema.string("Name field")))
      .withProperty(Schema.property("age", Schema.integer("Age field")))

    val tool = ToolBuilder[Map[String, Any], String](
      "test_tool",
      "A test tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    // Verify schema doesn't contain 'strict' or 'additionalProperties'
    (schemaJson should not).include("\"strict\"")
    (schemaJson should not).include("\"additionalProperties\"")
    (schemaJson should not).include("additionalProperties")

    // Verify tool has correct name and description
    geminiTool("name").str shouldBe "test_tool"
    geminiTool("description").str shouldBe "A test tool"
  }

  it should "handle nested objects with properties" in {
    val addressSchema = Schema
      .`object`[Map[String, Any]]("Address")
      .withProperty(Schema.property("street", Schema.string("Street name")))
      .withProperty(Schema.property("city", Schema.string("City name")))

    val schema = Schema
      .`object`[Map[String, Any]]("Person")
      .withProperty(Schema.property("name", Schema.string("Person name")))
      .withProperty(Schema.property("address", addressSchema))

    val tool = ToolBuilder[Map[String, Any], String](
      "person_tool",
      "Person tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    // Verify no 'additionalProperties' in nested address object
    (schemaJson should not).include("additionalProperties")
  }

  it should "handle arrays with items schema" in {
    val schema = Schema
      .`object`[Map[String, Any]]("List input")
      .withProperty(Schema.property("items", Schema.array("Items list", Schema.string("Item"))))

    val tool = ToolBuilder[Map[String, Any], String](
      "list_tool",
      "List tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle non-object JSON values gracefully" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test")
      .withProperty(Schema.property("value", Schema.string("A value")))

    val tool = ToolBuilder[Map[String, Any], String](
      "test",
      "Test",
      schema
    ).withHandler(_ => Right("result")).build()

    // Should not throw when processing
    noException should be thrownBy convertToolToGeminiFormat(tool)
  }

  it should "handle anyOf schema compositions" in {
    // Create a schema with anyOf using manual JSON construction
    val schema = Schema
      .`object`[Map[String, Any]]("Union type")
      .withProperty(Schema.property("field1", Schema.string("String field")))
      .withProperty(Schema.property("field2", Schema.integer("Integer field")))

    val tool = ToolBuilder[Map[String, Any], String](
      "anyof_tool",
      "AnyOf tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    // Verify schema is sanitized
    (schemaJson should not).include("additionalProperties")
  }

  it should "handle oneOf schema compositions" in {
    val schema = Schema
      .`object`[Map[String, Any]]("One of type")
      .withProperty(Schema.property("option1", Schema.string("Option 1")))
      .withProperty(Schema.property("option2", Schema.integer("Option 2")))

    val tool = ToolBuilder[Map[String, Any], String](
      "oneof_tool",
      "OneOf tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle allOf schema compositions" in {
    val schema = Schema
      .`object`[Map[String, Any]]("All of type")
      .withProperty(Schema.property("base", Schema.string("Base field")))
      .withProperty(Schema.property("extended", Schema.integer("Extended field")))

    val tool = ToolBuilder[Map[String, Any], String](
      "allof_tool",
      "AllOf tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle optional properties" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Optional input")
      .withProperty(Schema.property("required", Schema.string("Required field")))
      .withProperty(Schema.property("optional", Schema.nullable(Schema.integer("Optional field")), required = false))

    val tool = ToolBuilder[Map[String, Any], String](
      "optional_tool",
      "Optional tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle deeply nested structures" in {
    val level3Schema = Schema
      .`object`[Map[String, Any]]("Level 3")
      .withProperty(Schema.property("value", Schema.string("Value")))

    val level2Schema = Schema
      .`object`[Map[String, Any]]("Level 2")
      .withProperty(Schema.property("level3", level3Schema))

    val schema = Schema
      .`object`[Map[String, Any]]("Level 1")
      .withProperty(Schema.property("level2", level2Schema))

    val tool = ToolBuilder[Map[String, Any], String](
      "deep_tool",
      "Deep nesting tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    // Verify 'additionalProperties' is stripped at all nesting levels
    (schemaJson should not).include("additionalProperties")
  }

  it should "handle complex schemas with multiple nested types" in {
    val nestedObjSchema = Schema
      .`object`[Map[String, Any]]("Nested object")
      .withProperty(Schema.property("field", Schema.string("Field")))

    val schema = Schema
      .`object`[Map[String, Any]]("Complex input")
      .withProperty(Schema.property("stringField", Schema.string("String field")))
      .withProperty(Schema.property("numberField", Schema.integer("Number field")))
      .withProperty(Schema.property("arrayField", Schema.array("Array field", Schema.string("Item"))))
      .withProperty(Schema.property("objectField", nestedObjSchema))

    val tool = ToolBuilder[Map[String, Any], String](
      "complex_tool",
      "Complex tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle empty objects" in {
    val schema = Schema.`object`[Map[String, Any]]("Empty input")

    val tool = ToolBuilder[Map[String, Any], String](
      "empty_tool",
      "Empty tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    (schemaJson should not).include("additionalProperties")
  }

  it should "preserve required properties in schema" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Required input")
      .withProperty(Schema.property("required1", Schema.string("Required 1")))
      .withProperty(Schema.property("required2", Schema.integer("Required 2")))

    val tool = ToolBuilder[Map[String, Any], String](
      "required_tool",
      "Required tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)

    // Verify tool name is preserved
    geminiTool("name").str shouldBe "required_tool"
  }

  it should "handle nested arrays" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Matrix")
      .withProperty(
        Schema.property(
          "rows",
          Schema.array("Rows", Schema.array("Row", Schema.integer("Cell value")))
        )
      )

    val tool = ToolBuilder[Map[String, Any], String](
      "matrix_tool",
      "Matrix tool",
      schema
    ).withHandler(_ => Right("result")).build()

    val geminiTool = convertToolToGeminiFormat(tool)
    val schemaJson = geminiTool.toString

    // Verify nested array items don't have additionalProperties
    (schemaJson should not).include("additionalProperties")
  }
}
