package org.llm4s.schema

import org.llm4s.toolapi.{ NullableSchema, Schema }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The JSON builders write what core's schema model writes, and widen an `anyOf` as well as a typed schema. */
class JsonSchemasSpec extends AnyFlatSpec with Matchers {

  "nullable" should "write what NullableSchema writes for a typed schema" in {
    val schemas = List(
      Schema.string("s"),
      Schema.string("e").withEnum(Seq("a", "b")),
      Schema.integer("i"),
      Schema.array("l", Schema.string("x"))
    )
    for (schema <- schemas)
      JsonSchemas.nullable(schema.toJsonSchema(true)) shouldBe NullableSchema(schema).toJsonSchema(true)
  }

  it should "add null to a type array once, and give a schema without a type the type null" in {
    JsonSchemas.nullable(ujson.Obj("type" -> ujson.Arr("string", "integer")))("type") shouldBe
      ujson.Arr("string", "integer", "null")
    JsonSchemas.nullable(ujson.Obj("type" -> ujson.Arr("string", "null")))("type") shouldBe ujson.Arr("string", "null")
    JsonSchemas.nullable(ujson.Obj("description" -> "any"))("type") shouldBe ujson.Arr("null")
    JsonSchemas.nullable(ujson.Obj("type" -> "string", "enum" -> ujson.Arr("a", ujson.Null)))("enum") shouldBe
      ujson.Arr("a", ujson.Null)
  }

  it should "add a null alternative to an anyOf once, leaving the input unchanged" in {
    val union = JsonSchemas.anyOf("u", List(ujson.Obj("type" -> "string")))
    val once  = JsonSchemas.nullable(union)
    once("anyOf") shouldBe ujson.Arr(ujson.Obj("type" -> "string"), ujson.Obj("type" -> "null"))
    JsonSchemas.nullable(once) shouldBe once
    union("anyOf").arr should have size 1
  }

  "tagged" should "put the discriminator first and required, replacing a property of the same name" in {
    val obj = JsonSchemas.obj("o", List(("a", ujson.Obj("type" -> "string"), true), ("$type", ujson.Obj(), true)), true)
    val tagged = JsonSchemas.tagged(obj, "$type", "T")
    tagged("properties").obj.keys.toList shouldBe List("$type", "a")
    tagged("properties")("$type")("enum") shouldBe ujson.Arr("T")
    tagged("required") shouldBe ujson.Arr("$type", "a")
  }

  "obj" should "list only the required properties when not strict" in {
    val obj = JsonSchemas.obj("o", List(("a", ujson.Obj(), true), ("b", ujson.Obj(), false)), strict = false)
    obj("required") shouldBe ujson.Arr("a")
  }
}
