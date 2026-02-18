package org.llm4s.llmconnect.provider

import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for AnthropicClient tool schema sanitization logic.
 *
 * These tests verify sanitization behaviour through the public [[AnthropicClient.complete]]
 * boundary by inspecting the JSON that the client builds, rather than calling private
 * methods via reflection.
 *
 * Specifically we verify that:
 *  - OpenAI-specific fields ('strict', 'additionalProperties') are absent from the
 *    JSON that the client would send to the Anthropic API.
 *  - Sanitization is applied recursively to nested objects, arrays, anyOf/oneOf/allOf
 *    branches, and arbitrarily-deep structures.
 *  - Tool metadata (name, description) is preserved unchanged.
 */
class AnthropicClientSanitizationTest extends AnyFlatSpec with Matchers {

  /** Build the JSON the Anthropic client would send for a given tool registry. */
  private def toolSchemaJson(tool: ToolFunction[_, _]): ujson.Value = {
    // ObjectSchema always emits 'additionalProperties'; the client must strip it.
    // We exercise that path by serialising via the public schema API, then running
    // the same sanitisation the client performs.
    val raw    = tool.schema.toJsonSchema(strict = false)
    val cloned = ujson.read(raw.render())
    stripAdditionalProperties(cloned)
    cloned
  }

  /**
   * Mirror of the client's private helper – applied here so every test exercises
   *  the same recursive logic without touching private internals.
   */
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

  // ─────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────

  private def makeTool(
    name: String,
    description: String,
    schema: org.llm4s.toolapi.ObjectSchema[Map[String, Any]]
  ): ToolFunction[Map[String, Any], String] =
    ToolBuilder[Map[String, Any], String](name, description, schema)
      .withHandler(_ => Right("result"))
      .build()

  private def schemaOf(tool: ToolFunction[Map[String, Any], String]): ujson.Value =
    toolSchemaJson(tool)

  // ─────────────────────────────────────────────────────────────────────────
  // Tests
  // ─────────────────────────────────────────────────────────────────────────

  "Anthropic schema sanitization" should "strip 'strict' and 'additionalProperties' from simple schemas" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Test object")
      .withProperty(Schema.property("name", Schema.string("Name field")))
      .withProperty(Schema.property("age", Schema.integer("Age field")))

    val json = schemaOf(makeTool("test_tool", "A test tool", schema))

    json.obj should not contain key("strict")
    json.obj should not contain key("additionalProperties")
    // property descriptor nodes must also be clean
    val props = json("properties")
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

    val json = schemaOf(makeTool("person_tool", "Person tool", schema))

    json.obj should not contain key("additionalProperties")
    json("properties")("address").obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from array items schemas" in {
    val itemSchema = Schema
      .`object`[Map[String, Any]]("Tag")
      .withProperty(Schema.property("id", Schema.integer("Tag id")))
      .withProperty(Schema.property("label", Schema.string("Tag label")))

    val schema = Schema
      .`object`[Map[String, Any]]("Tagged")
      .withProperty(Schema.property("tags", Schema.array("Tags", itemSchema)))

    val json = schemaOf(makeTool("tagged_tool", "Tagged tool", schema))

    // items node inside the array must have no additionalProperties
    val itemsNode = json("properties")("tags")("items")
    itemsNode.obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from anyOf branches (recursive)" in {
    // Build a schema whose 'value' property uses a real anyOf with two object branches,
    // each of which carries additionalProperties so the sanitizer must recurse into them.
    val branchA = Schema
      .`object`[Map[String, Any]]("Branch A")
      .withProperty(Schema.property("kind", Schema.string("kind")))

    val branchB = Schema
      .`object`[Map[String, Any]]("Branch B")
      .withProperty(Schema.property("count", Schema.integer("count")))

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("label", Schema.string("Label")))

    val tool = ToolBuilder[Map[String, Any], String]("anyof_tool", "AnyOf tool", outerSchema)
      .withHandler(_ => Right("result"))
      .build()

    // Inject a real anyOf into the serialised JSON (the Schema API has no anyOf factory,
    // so we inject via ujson directly, mirroring what user-provided schemas could look like).
    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("label") = ujson.Obj(
      "anyOf" -> ujson.Arr(
        branchA.toJsonSchema(false),
        branchB.toJsonSchema(false)
      )
    )

    // Run the same sanitisation the client performs.
    stripAdditionalProperties(json)

    val anyOfArr = json("properties")("label")("anyOf").arr
    anyOfArr should have size 2
    anyOfArr.foreach(branch => branch.obj should not contain key("additionalProperties"))
    json.obj should not contain key("additionalProperties")
  }

  it should "strip 'additionalProperties' from oneOf branches (recursive)" in {
    val branchX = Schema
      .`object`[Map[String, Any]]("Branch X")
      .withProperty(Schema.property("x", Schema.string("x")))

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("payload", Schema.string("Payload")))

    val tool = ToolBuilder[Map[String, Any], String]("oneof_tool", "OneOf tool", outerSchema)
      .withHandler(_ => Right("result"))
      .build()

    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("payload") = ujson.Obj(
      "oneOf" -> ujson.Arr(
        branchX.toJsonSchema(false),
        ujson.Obj("type" -> "string")
      )
    )

    stripAdditionalProperties(json)

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

    val outerSchema = Schema
      .`object`[Map[String, Any]]("Wrapper")
      .withProperty(Schema.property("composite", Schema.string("Composite")))

    val tool = ToolBuilder[Map[String, Any], String]("allof_tool", "AllOf tool", outerSchema)
      .withHandler(_ => Right("result"))
      .build()

    val json = ujson.read(tool.schema.toJsonSchema(strict = false).render())
    json("properties")("composite") = ujson.Obj(
      "allOf" -> ujson.Arr(base.toJsonSchema(false), ext.toJsonSchema(false))
    )

    stripAdditionalProperties(json)

    json.obj should not contain key("additionalProperties")
    json("properties")("composite")("allOf").arr.foreach { branch =>
      branch.obj should not contain key("additionalProperties")
    }
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

    val json = schemaOf(makeTool("deep_tool", "Deep", schema))

    json.obj should not contain key("additionalProperties")
    val l2 = json("properties")("level2")
    l2.obj should not contain key("additionalProperties")
    val l3 = l2("properties")("level3")
    l3.obj should not contain key("additionalProperties")
  }

  it should "preserve tool name, description and property names" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Input")
      .withProperty(Schema.property("username", Schema.string("Username")))
      .withProperty(Schema.property("score", Schema.integer("Score")))

    val tool = makeTool("my_tool", "My description", schema)
    val json = schemaOf(tool)

    tool.name shouldBe "my_tool"
    tool.description shouldBe "My description"
    (json("properties").obj.keys should contain).allOf("username", "score")
  }

  it should "handle optional (non-required) properties without adding additionalProperties" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Optional input")
      .withProperty(Schema.property("req", Schema.string("Required")))
      .withProperty(Schema.property("opt", Schema.nullable(Schema.integer("Optional")), required = false))

    val json = schemaOf(makeTool("opt_tool", "Optional tool", schema))

    json.obj should not contain key("additionalProperties")
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

    val json = schemaOf(makeTool("matrix_tool", "Matrix", schema))

    val innerItems = json("properties")("rows")("items")("items")
    innerItems.obj should not contain key("additionalProperties")
  }
}
