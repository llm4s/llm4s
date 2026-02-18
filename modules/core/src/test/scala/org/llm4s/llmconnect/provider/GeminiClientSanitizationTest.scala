package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for GeminiClient tool schema sanitization logic.
 *
 * Tests call the real `private[provider]` methods on a dummy client instance so
 * that Codecov sees the actual source lines executed.
 *
 * Two entry-points are exercised:
 *  - `convertToolToGeminiFormat` – the full conversion pipeline (returns ujson.Value
 *    with "name", "description", "parameters" keys).  Assertions are made on the
 *    parsed JSON.
 *  - `stripAdditionalProperties` – the recursive sanitiser, called directly with
 *    manually-constructed ujson for the anyOf / oneOf / allOf test cases.
 */
class GeminiClientSanitizationTest extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────────────────
  // Shared dummy client (no real network calls are made)
  // ─────────────────────────────────────────────────────────────────────────

  private val client = new GeminiClient(
    GeminiConfig(
      apiKey = "test-api-key",
      model = "gemini-2.0-flash-preview",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
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

  // ─────────────────────────────────────────────────────────────────────────
  // Tests via convertToolToGeminiFormat (full pipeline)
  // ─────────────────────────────────────────────────────────────────────────

  "Gemini schema sanitization" should "strip 'strict' and 'additionalProperties' from simple schemas" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test object")
      .withProperty(Schema.property("name", Schema.string("Name field")))
      .withProperty(Schema.property("age", Schema.integer("Age field")))

    makeTool("test_tool", "A test tool", schema) match {
      case Right(tool) =>
        val out = client.convertToolToGeminiFormat(tool)

        out("name").str shouldBe "test_tool"
        out("description").str shouldBe "A test tool"

        val params = out("parameters")
        params.obj should not contain key("strict")
        params.obj should not contain key("additionalProperties")

        val props = params("properties")
        props("name").obj should not contain key("additionalProperties")
        props("age").obj should not contain key("additionalProperties")
      case Left(err) => fail(s"makeTool failed: ${err.message}")
    }
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

    val params = makeTool("person_tool", "Person", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )

    params.obj should not contain key("additionalProperties")
    params("properties")("address").obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from object items inside arrays" in {
    val tagSchema = Schema
      .`object`[Map[String, Any]]("Tag")
      .withProperty(Schema.property("id", Schema.integer("id")))
      .withProperty(Schema.property("label", Schema.string("label")))

    val schema = Schema
      .`object`[Map[String, Any]]("Tagged")
      .withProperty(Schema.property("tags", Schema.array("Tags", tagSchema)))

    val params = makeTool("tagged_tool", "Tagged", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )

    params("properties")("tags")("items").obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' at every level of a 3-deep nested structure" in {
    val level3 = Schema
      .`object`[Map[String, Any]]("Level 3")
      .withProperty(Schema.property("value", Schema.string("Value")))

    val level2 = Schema
      .`object`[Map[String, Any]]("Level 2")
      .withProperty(Schema.property("level3", level3))

    val schema = Schema
      .`object`[Map[String, Any]]("Level 1")
      .withProperty(Schema.property("level2", level2))

    val params = makeTool("deep_tool", "Deep", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )

    params.obj should not contain key("additionalProperties")
    val l2 = params("properties")("level2")
    l2.obj should not contain key("additionalProperties")
    l2("properties")("level3").obj should not contain key("additionalProperties")
  }

  it should "preserve tool name, description and property names after sanitization" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("username", Schema.string("Username")))
      .withProperty(Schema.property("score", Schema.integer("Score")))

    makeTool("my_tool", "My description", schema) match {
      case Right(tool) =>
        val out = client.convertToolToGeminiFormat(tool)
        out("name").str shouldBe "my_tool"
        out("description").str shouldBe "My description"
        (out("parameters")("properties").obj.keys should contain).allOf("username", "score")
      case Left(err) => fail(s"makeTool failed: ${err.message}")
    }
  }

  it should "handle optional (non-required) properties cleanly" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Optional input")
      .withProperty(Schema.property("req", Schema.string("Required")))
      .withProperty(Schema.property("opt", Schema.nullable(Schema.integer("Optional")), required = false))

    val params = makeTool("opt_tool", "Optional", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )

    params.obj should not contain key("additionalProperties")
  }

  it should "handle nested arrays of object items (matrix of objects)" in {
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

    val params = makeTool("matrix_tool", "Matrix", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )

    params("properties")("rows")("items")("items").obj should not contain key("additionalProperties")
  }

  it should "handle empty object schemas without errors" in {
    val schema = Schema.`object`[Map[String, Any]]("Empty input")
    val params = makeTool("empty_tool", "Empty", schema).fold(
      err => fail(s"makeTool failed: ${err.message}"),
      tool => client.convertToolToGeminiFormat(tool)("parameters")
    )
    params.obj should not contain key("additionalProperties")
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Tests via stripAdditionalProperties directly (anyOf / oneOf / allOf)
  // ─────────────────────────────────────────────────────────────────────────

  it should "strip 'additionalProperties' from both branches of a real anyOf composition" in {
    val branchA = Schema
      .`object`[Map[String, Any]]("Branch A")
      .withProperty(Schema.property("kind", Schema.string("kind")))

    val branchB = Schema
      .`object`[Map[String, Any]]("Branch B")
      .withProperty(Schema.property("count", Schema.integer("count")))

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

  it should "strip 'additionalProperties' from all branches of a real oneOf composition" in {
    val branchX = Schema
      .`object`[Map[String, Any]]("Branch X")
      .withProperty(Schema.property("x", Schema.string("x")))

    val branchY = Schema
      .`object`[Map[String, Any]]("Branch Y")
      .withProperty(Schema.property("y", Schema.integer("y")))

    val json = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "payload" -> ujson.Obj(
          "oneOf" -> ujson.Arr(
            branchX.toJsonSchema(false),
            branchY.toJsonSchema(false)
          )
        )
      ),
      "additionalProperties" -> ujson.False
    )

    client.stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    json("properties")("payload")("oneOf").arr.foreach { branch =>
      branch.obj should not contain key("additionalProperties")
    }
  }

  it should "strip 'additionalProperties' from all branches of a real allOf composition" in {
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
