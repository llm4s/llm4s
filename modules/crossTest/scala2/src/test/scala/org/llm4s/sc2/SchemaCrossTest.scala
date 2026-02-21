package org.llm4s.sc2

import org.llm4s.toolapi._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for tool schema serialization (SchemaDefinition.toJsonSchema).
 * Verifies that schema types, descriptions, and parameter structure are stable across Scala 2.13 and 3.x.
 * No deserialization (no round-trip); only serialize and assert JSON structure.
 * Same logic as sc3.SchemaCrossTest; positive cases only (schema construction and toJsonSchema).
 */
class SchemaCrossTest extends AnyFlatSpec with Matchers {

  "StringSchema" should "serialize to JSON with type string and description" in {
    val schema = Schema.string("A string parameter")
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("string")
    json.obj("description") shouldBe ujson.Str("A string parameter")
  }

  it should "include enum when withEnum is used" in {
    val schema = Schema.string("Choice").withEnum(Seq("a", "b", "c"))
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("string")
    json.obj("enum").arr.map(_.str).toSet shouldBe Set("a", "b", "c")
  }

  "IntegerSchema" should "serialize to JSON with type integer and description" in {
    val schema = Schema.integer("An integer")
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("integer")
    json.obj("description") shouldBe ujson.Str("An integer")
  }

  "NumberSchema" should "serialize to JSON with type number and description" in {
    val schema = Schema.number("A number")
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("number")
    json.obj("description") shouldBe ujson.Str("A number")
  }

  "BooleanSchema" should "serialize to JSON with type boolean and description" in {
    val schema = Schema.boolean("A boolean")
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("boolean")
    json.obj("description") shouldBe ujson.Str("A boolean")
  }

  "ObjectSchema" should "serialize to JSON with type object, properties and required" in {
    val schema = Schema
      .`object`[Map[String, Any]]("An object")
      .withProperty(Schema.property("name", Schema.string("The name"), required = true))
      .withProperty(Schema.property("count", Schema.integer("The count"), required = false))
    val json = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("object")
    json.obj("description") shouldBe ujson.Str("An object")
    json.obj.contains("properties") shouldBe true
    json.obj("properties").obj.contains("name") shouldBe true
    json.obj("properties").obj.contains("count") shouldBe true
    json.obj("required").arr.map(_.str) should contain("name")
  }

  "ArraySchema" should "serialize to JSON with type array and items" in {
    val schema = Schema.array("List of strings", Schema.string("Item"))
    val json   = schema.toJsonSchema(strict = true)
    json.obj("type") shouldBe ujson.Str("array")
    json.obj("description") shouldBe ujson.Str("List of strings")
    json.obj.contains("items") shouldBe true
    json.obj("items").obj("type") shouldBe ujson.Str("string")
  }

  "NullableSchema" should "serialize to type array including null" in {
    val schema = Schema.nullable(Schema.string("Optional string"))
    val json   = schema.toJsonSchema(strict = true)
    val typeVal = json.obj("type")
    typeVal match {
      case arr: ujson.Arr => arr.arr.map(_.str).toSet should contain("null")
      case _              => fail("expected type to be array of types")
    }
  }

  "Builtin tool toOpenAITool" should "produce function object with name, description, parameters" in {
    val reg  = new ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val arr  = reg.getOpenAITools(strict = true)
    val first = arr.arr.head
    first.obj("type") shouldBe ujson.Str("function")
    val fn = first.obj("function")
    fn.obj.contains("name") shouldBe true
    fn.obj.contains("description") shouldBe true
    fn.obj.contains("parameters") shouldBe true
    fn.obj("parameters").obj.contains("type") shouldBe true
    fn.obj("parameters").obj.contains("properties") shouldBe true
  }

  it should "preserve tool name in serialized schema" in {
    val reg = new ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val arr = reg.getOpenAITools(strict = true)
    val names = arr.arr.map(_.obj("function").obj("name").str)
    names should contain("get_current_datetime")
    names should contain("calculator")
    names should contain("generate_uuid")
    names should contain("json_tool")
  }
}
