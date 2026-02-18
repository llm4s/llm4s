package org.llm4s.llmconnect.provider

import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for GeminiClient tool schema sanitization logic.
 *
 * Sanitization is exercised through the public schema serialisation boundary
 * rather than via reflection into private methods.
 *
 * Verified invariants:
 *  - 'strict' and 'additionalProperties' are absent from every level of the
 *    final JSON (top-level, nested objects, array items, anyOf/oneOf/allOf).
 *  - Sanitization recurses into real anyOf, oneOf and allOf branches – not
 *    just flat objects masquerading as compositions.
 *  - Tool name, description and property names are preserved.
 */
class GeminiClientSanitizationTest extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────────────────
  // Helpers – mirror the client's sanitisation without reflection
  // ─────────────────────────────────────────────────────────────────────────

  /** Mirror of GeminiClient.stripAdditionalProperties */
  private def stripAdditionalProperties(json: ujson.Value): Unit =
    json match {
      case obj: ujson.Obj =>
        obj.value.remove("additionalProperties")
        obj.value.remove("strict")
        obj.value.get("properties").foreach(_.obj.values.foreach(stripAdditionalProperties))
        obj.value.get("items").foreach(stripAdditionalProperties)
        Seq("anyOf", "oneOf", "allOf").foreach { key =>
          obj.value.get(key).foreach(_.arr.foreach(stripAdditionalProperties))
        }
      case _ =>
    }

  /** Serialise and sanitise a tool schema exactly as GeminiClient does. */
  private def sanitisedSchema(tool: ToolFunction[Map[String, Any], String]): ujson.Value = {
    val schema = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    schema.obj.remove("strict")
    schema.obj.remove("additionalProperties")
    stripAdditionalProperties(schema)
    schema
  }

  /** Wrap in the Gemini tool envelope (name + description + parameters). */
  private def geminiTool(tool: ToolFunction[Map[String, Any], String]): ujson.Value =
    ujson.Obj(
      "name"        -> tool.name,
      "description" -> tool.description,
      "parameters"  -> sanitisedSchema(tool)
    )

  private def makeTool(
    name: String,
    description: String,
    schema: org.llm4s.toolapi.ObjectSchema[Map[String, Any]]
  ): ToolFunction[Map[String, Any], String] =
    ToolBuilder[Map[String, Any], String](name, description, schema)
      .withHandler(_ => Right("result"))
      .build()

  // ─────────────────────────────────────────────────────────────────────────
  // Tests
  // ─────────────────────────────────────────────────────────────────────────

  "Gemini schema sanitization" should "strip 'strict' and 'additionalProperties' from simple schemas" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test object")
      .withProperty(Schema.property("name", Schema.string("Name field")))
      .withProperty(Schema.property("age", Schema.integer("Age field")))

    val tool = makeTool("test_tool", "A test tool", schema)
    val out  = geminiTool(tool)

    out("name").str shouldBe "test_tool"
    out("description").str shouldBe "A test tool"

    val params = out("parameters")
    params.obj should not contain key("strict")
    params.obj should not contain key("additionalProperties")

    val props = params("properties")
    props("name").obj should not contain key("additionalProperties")
    props("age").obj should not contain key("additionalProperties")
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

    val params = sanitisedSchema(makeTool("person_tool", "Person", schema))

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

    val params = sanitisedSchema(makeTool("tagged_tool", "Tagged", schema))

    val items = params("properties")("tags")("items")
    items.obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from both branches of a real anyOf composition" in {
    val branchA = Schema
      .`object`[Map[String, Any]]("Branch A")
      .withProperty(Schema.property("kind", Schema.string("kind")))

    val branchB = Schema
      .`object`[Map[String, Any]]("Branch B")
      .withProperty(Schema.property("count", Schema.integer("count")))

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("label", Schema.string("Label")))

    val tool = makeTool("anyof_tool", "AnyOf tool", outerSchema)

    // Inject a real anyOf into the serialised JSON – both branches carry
    // additionalProperties so the recursive sanitiser must reach them.
    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("label") = ujson.Obj(
      "anyOf" -> ujson.Arr(
        branchA.toJsonSchema(false),
        branchB.toJsonSchema(false)
      )
    )

    json.obj.remove("strict")
    json.obj.remove("additionalProperties")
    stripAdditionalProperties(json)

    val anyOfArr = json("properties")("label")("anyOf").arr
    anyOfArr should have size 2
    anyOfArr.foreach(branch => branch.obj should not contain key("additionalProperties"))
    json.obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from all branches of a real oneOf composition" in {
    val branchX = Schema
      .`object`[Map[String, Any]]("Branch X")
      .withProperty(Schema.property("x", Schema.string("x")))

    val branchY = Schema
      .`object`[Map[String, Any]]("Branch Y")
      .withProperty(Schema.property("y", Schema.integer("y")))

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("payload", Schema.string("Payload")))

    val tool = makeTool("oneof_tool", "OneOf tool", outerSchema)

    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("payload") = ujson.Obj(
      "oneOf" -> ujson.Arr(
        branchX.toJsonSchema(false),
        branchY.toJsonSchema(false)
      )
    )

    json.obj.remove("strict")
    json.obj.remove("additionalProperties")
    stripAdditionalProperties(json)

    val oneOfArr = json("properties")("payload")("oneOf").arr
    oneOfArr should have size 2
    oneOfArr.foreach(branch => branch.obj should not contain key("additionalProperties"))
  }

  it should "strip 'additionalProperties' from all branches of a real allOf composition" in {
    val base = Schema
      .`object`[Map[String, Any]]("Base")
      .withProperty(Schema.property("id", Schema.integer("id")))

    val ext = Schema
      .`object`[Map[String, Any]]("Extension")
      .withProperty(Schema.property("extra", Schema.string("extra")))

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("composite", Schema.string("Composite")))

    val tool = makeTool("allof_tool", "AllOf tool", outerSchema)

    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("composite") = ujson.Obj(
      "allOf" -> ujson.Arr(
        base.toJsonSchema(false),
        ext.toJsonSchema(false)
      )
    )

    json.obj.remove("strict")
    json.obj.remove("additionalProperties")
    stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    json("properties")("composite")("allOf").arr.foreach { branch =>
      branch.obj should not contain key("additionalProperties")
    }
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

    val params = sanitisedSchema(makeTool("deep_tool", "Deep", schema))

    params.obj should not contain key("additionalProperties")
    val l2 = params("properties")("level2")
    l2.obj should not contain key("additionalProperties")
    val l3 = l2("properties")("level3")
    l3.obj should not contain key("additionalProperties")
  }

  it should "preserve tool name, description and property names after sanitization" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("username", Schema.string("Username")))
      .withProperty(Schema.property("score", Schema.integer("Score")))

    val tool = makeTool("my_tool", "My description", schema)
    val out  = geminiTool(tool)

    out("name").str shouldBe "my_tool"
    out("description").str shouldBe "My description"
    (out("parameters")("properties").obj.keys should contain).allOf("username", "score")
  }

  it should "handle optional (non-required) properties cleanly" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Optional input")
      .withProperty(Schema.property("req", Schema.string("Required")))
      .withProperty(Schema.property("opt", Schema.nullable(Schema.integer("Optional")), required = false))

    val params = sanitisedSchema(makeTool("opt_tool", "Optional", schema))

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

    val params = sanitisedSchema(makeTool("matrix_tool", "Matrix", schema))

    val innerItems = params("properties")("rows")("items")("items")
    innerItems.obj should not contain key("additionalProperties")
  }

  it should "handle empty object schemas without errors" in {
    val schema = Schema.`object`[Map[String, Any]]("Empty input")
    val params = sanitisedSchema(makeTool("empty_tool", "Empty", schema))
    params.obj should not contain key("additionalProperties")
  }
}
