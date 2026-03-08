package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for AnthropicClient tool schema sanitization logic.
 *
 * Tests call the real `private[provider]` methods on a dummy client instance so
 * that Codecov sees the actual source lines executed.
 *
 * Two entry-points are exercised:
 *  - `convertToolToAnthropicTool` – the full conversion pipeline (schema → SDK Tool).
 *    Assertions are made on the rendered JSON of the SDK Tool's input schema.
 *  - `stripAdditionalProperties` – the recursive sanitiser, called directly with
 *    manually-constructed ujson for the anyOf / oneOf / allOf test cases.
 */
class AnthropicClientSanitizationTest extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────────────────
  // Shared dummy client (no real network calls are made)
  // ─────────────────────────────────────────────────────────────────────────

  private val client = new AnthropicClient(
    AnthropicConfig(
      apiKey = "test-api-key",
      model = "claude-3-haiku-20240307",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 32768,
      reserveCompletion = 8192
    )
  )

  // ─────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────

  private def makeTool(
    name: String,
    description: String,
    schema: org.llm4s.toolapi.ObjectSchema[Map[String, Any]]
  ): Result[ToolFunction[Map[String, Any], String]] =
    ToolBuilder[Map[String, Any], String](name, description, schema)
      .withHandler(_ => Right("result"))
      .buildSafe()

  /**
   * Convert via the real client method and return the input-schema as JSON string
   *  using the same Jackson mapper the client uses internally.
   */
  private def schemaJsonOf(toolResult: Result[ToolFunction[Map[String, Any], String]]): String =
    toolResult match {
      case Right(tool) =>
        val sdkTool = client.convertToolToAnthropicTool(tool)
        ObjectMappers.jsonMapper().writeValueAsString(sdkTool.inputSchema())
      case Left(err) => fail(s"makeTool failed: ${err.message}")
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Tests via convertToolToAnthropicTool (full pipeline)
  // ─────────────────────────────────────────────────────────────────────────

  "Anthropic schema sanitization" should "strip 'strict' and 'additionalProperties' from simple schemas" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test object")
      .withProperty(Schema.property("name", Schema.string("Name field")))
      .withProperty(Schema.property("age", Schema.integer("Age field")))

    val schemaJson = schemaJsonOf(makeTool("test_tool", "A test tool", schema))

    (schemaJson should not).include("\"strict\"")
    (schemaJson should not).include("\"additionalProperties\"")
  }

  it should "strip 'additionalProperties' recursively from nested objects" in {
    val addressSchema = Schema
      .`object`[Map[String, Any]]("Address")
      .withProperty(Schema.property("street", Schema.string("Street")))
      .withProperty(Schema.property("city", Schema.string("City")))

    val schema = Schema
      .`object`[Map[String, Any]]("Person")
      .withProperty(Schema.property("name", Schema.string("Name")))
      .withProperty(Schema.property("address", addressSchema))

    val schemaJson = schemaJsonOf(makeTool("person_tool", "Person tool", schema))

    (schemaJson should not).include("additionalProperties")
  }

  it should "strip 'additionalProperties' from array items schemas" in {
    val itemSchema = Schema
      .`object`[Map[String, Any]]("Tag")
      .withProperty(Schema.property("id", Schema.integer("Tag id")))
      .withProperty(Schema.property("label", Schema.string("Tag label")))

    val schema = Schema
      .`object`[Map[String, Any]]("Tagged")
      .withProperty(Schema.property("tags", Schema.array("Tags", itemSchema)))

    val schemaJson = schemaJsonOf(makeTool("tagged_tool", "Tagged tool", schema))

    (schemaJson should not).include("additionalProperties")
  }

  it should "strip 'additionalProperties' at all levels of a deeply nested structure" in {
    val level3 = Schema
      .`object`[Map[String, Any]]("Level 3")
      .withProperty(Schema.property("value", Schema.string("Value")))

    val level2 = Schema
      .`object`[Map[String, Any]]("Level 2")
      .withProperty(Schema.property("level3", level3))

    val schema = Schema
      .`object`[Map[String, Any]]("Level 1")
      .withProperty(Schema.property("level2", level2))

    val schemaJson = schemaJsonOf(makeTool("deep_tool", "Deep", schema))

    (schemaJson should not).include("additionalProperties")
  }

  it should "preserve tool name and description" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("username", Schema.string("Username")))

    makeTool("my_tool", "My description", schema) match {
      case Right(tool) =>
        val sdkTool = client.convertToolToAnthropicTool(tool)
        sdkTool.name() shouldBe "my_tool"
        sdkTool.description() shouldBe java.util.Optional.of("My description")
      case Left(err) => fail(s"makeTool failed: ${err.message}")
    }
  }

  it should "handle optional (non-required) properties without adding additionalProperties" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Optional input")
      .withProperty(Schema.property("req", Schema.string("Required")))
      .withProperty(Schema.property("opt", Schema.nullable(Schema.integer("Optional")), required = false))

    val schemaJson = schemaJsonOf(makeTool("opt_tool", "Optional tool", schema))

    (schemaJson should not).include("additionalProperties")
  }

  it should "handle nested arrays of objects" in {
    val cellSchema = Schema
      .`object`[Map[String, Any]]("Cell")
      .withProperty(Schema.property("v", Schema.integer("Value")))

    val schema = Schema
      .`object`[Map[String, Any]]("Matrix")
      .withProperty(
        Schema.property(
          "rows",
          Schema.array("Rows", Schema.array("Row", cellSchema))
        )
      )

    val schemaJson = schemaJsonOf(makeTool("matrix_tool", "Matrix", schema))

    (schemaJson should not).include("additionalProperties")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Tests via stripAdditionalProperties directly (anyOf / oneOf / allOf)
  // ─────────────────────────────────────────────────────────────────────────

  it should "strip 'additionalProperties' from anyOf branches (recursive)" in {
    val branchA = Schema
      .`object`[Map[String, Any]]("Branch A")
      .withProperty(Schema.property("kind", Schema.string("kind")))

    val branchB = Schema
      .`object`[Map[String, Any]]("Branch B")
      .withProperty(Schema.property("count", Schema.integer("count")))

    // Build raw JSON with a real anyOf and call the client's real recursive helper.
    val json = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "label" -> ujson.Obj(
          "anyOf" -> ujson.Arr(
            branchA.toJsonSchema(false),
            branchB.toJsonSchema(false)
          )
        )
      ),
      "additionalProperties" -> ujson.False
    )

    client.stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    val anyOfArr = json("properties")("label")("anyOf").arr
    anyOfArr should have size 2
    anyOfArr.foreach(branch => branch.obj should not contain key("additionalProperties"))
  }

  it should "strip 'additionalProperties' from oneOf branches (recursive)" in {
    val branchX = Schema
      .`object`[Map[String, Any]]("Branch X")
      .withProperty(Schema.property("x", Schema.string("x")))

    val json = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "payload" -> ujson.Obj(
          "oneOf" -> ujson.Arr(
            branchX.toJsonSchema(false),
            ujson.Obj("type" -> "string")
          )
        )
      ),
      "additionalProperties" -> ujson.False
    )

    client.stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    json("properties")("payload")("oneOf").arr.head.obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from allOf branches (recursive)" in {
    val base = Schema
      .`object`[Map[String, Any]]("Base")
      .withProperty(Schema.property("id", Schema.integer("id")))

    val ext = Schema
      .`object`[Map[String, Any]]("Extension")
      .withProperty(Schema.property("extra", Schema.string("extra")))

    val json = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "composite" -> ujson.Obj(
          "allOf" -> ujson.Arr(base.toJsonSchema(false), ext.toJsonSchema(false))
        )
      ),
      "additionalProperties" -> ujson.False
    )

    client.stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    json("properties")("composite")("allOf").arr.foreach { branch =>
      branch.obj should not contain key("additionalProperties")
    }
  }
}
