package org.llm4s.schema.iron

import org.llm4s.schema.SchemaOf
import org.llm4s.schema.iron.IronFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The JSON Schema each supported constraint is written as, in strict and in non-strict mode. */
class IronSchemaSpec extends AnyFlatSpec with Matchers {

  private def property(schema: ujson.Value, name: String): ujson.Value = schema("properties")(name)

  // The schema of `A` in both modes: the bounds are the same in each, and only `required` differs
  private def both[A](using of: SchemaOf[A]): List[ujson.Value] =
    List(of.toJsonSchema(), of.toJsonSchema(strict = false))

  "The issue's Review" should "carry each field's bounds, with its description, in strict and non-strict mode" in {
    both[Review].foreach { schema =>
      property(schema, "title") shouldBe ujson.Obj(
        "type"        -> "string",
        "description" -> "Short headline",
        "minLength"   -> 1,
        "maxLength"   -> 80
      )
      property(schema, "rating") shouldBe ujson.Obj(
        "type"        -> "integer",
        "description" -> "Stars",
        "minimum"     -> 1,
        "maximum"     -> 5
      )
      property(schema, "tags") shouldBe ujson.Obj(
        "type"        -> "array",
        "description" -> "tags",
        "items"       -> ujson.Obj("type" -> "string", "description" -> "string"),
        "minItems"    -> 1,
        "maxItems"    -> 10
      )
    }
    SchemaOf[Review].toJsonSchema()("required") shouldBe ujson.Arr("title", "rating", "tags")
    SchemaOf[Review].toJsonSchema(strict = false)("required") shouldBe ujson.Arr("title", "rating", "tags")
  }

  it should "write the same document whether it is built from core's model or as JSON" in {
    ujson.write(SchemaOf[Review].definition.toJsonSchema(true)) shouldBe ujson.write(SchemaOf[Review].toJsonSchema())
    ujson.write(SchemaOf[Numbers].definition.toJsonSchema(false)) shouldBe
      ujson.write(SchemaOf[Numbers].toJsonSchema(strict = false))
    ujson.write(SchemaOf[Lengths].definition.toJsonSchema(true)) shouldBe ujson.write(SchemaOf[Lengths].toJsonSchema())
  }

  "A numeric constraint" should "be minimum and maximum, an integer's exclusive bound as the inclusive one it equals" in {
    both[Numbers].foreach { schema =>
      def bounds(name: String): Map[String, ujson.Value] =
        property(schema, name).obj.view.filterKeys(k => k != "type" && k != "description").toMap
      def n(d: Double): ujson.Value = ujson.Num(d)

      bounds("positive") shouldBe Map("minimum" -> n(1))
      bounds("positive0") shouldBe Map("minimum" -> n(0))
      bounds("negative") shouldBe Map("maximum" -> n(-1))
      bounds("negative0") shouldBe Map("maximum" -> n(0))
      bounds("greater") shouldBe Map("minimum" -> n(11))
      bounds("greaterEqual") shouldBe Map("minimum" -> n(10))
      bounds("less") shouldBe Map("maximum" -> n(9))
      bounds("lessEqual") shouldBe Map("maximum" -> n(10))
      bounds("exactly") shouldBe Map("minimum" -> n(7), "maximum" -> n(7))
      bounds("open") shouldBe Map("exclusiveMinimum" -> n(0), "exclusiveMaximum" -> n(1))
      bounds("openClosed") shouldBe Map("exclusiveMinimum" -> n(0), "maximum" -> n(1))
      bounds("closedOpen") shouldBe Map("minimum" -> n(0), "exclusiveMaximum" -> n(1))
      bounds("closed") shouldBe Map("minimum" -> n(-1.5), "maximum" -> n(1.5))
      bounds("above") shouldBe Map("exclusiveMinimum" -> n(0.5))
      bounds("wholeAbove") shouldBe Map("minimum" -> n(1))
      bounds("tightest") shouldBe Map("minimum" -> n(3), "maximum" -> n(50))
    }
  }

  it should "keep the base type's JSON type: integer for Int and Long, number for Double and Float" in {
    val schema = SchemaOf[Numbers].toJsonSchema()
    List("positive", "positive0", "tightest").map(property(schema, _)("type")) shouldBe List.fill(3)(
      ujson.Str("integer")
    )
    List("open", "closedOpen").map(property(schema, _)("type")) shouldBe List.fill(2)(ujson.Str("number"))
  }

  "A length constraint" should "be minLength and maxLength on a string, minItems and maxItems on a collection" in {
    both[Lengths].foreach { schema =>
      def bounds(name: String): Map[String, ujson.Value] =
        property(schema, name).obj.view.filterKeys(k => !Set("type", "description", "items").contains(k)).toMap
      def n(i: Int): ujson.Value = ujson.Num(i)

      bounds("name") shouldBe Map("minLength" -> n(2))
      bounds("code") shouldBe Map("minLength" -> n(3), "maxLength" -> n(3))
      bounds("note") shouldBe Map("minLength" -> n(1))
      bounds("short") shouldBe Map("maxLength" -> n(5))
      bounds("items") shouldBe Map("minItems" -> n(1))
      bounds("pair") shouldBe Map("minItems" -> n(2), "maxItems" -> n(2))
      bounds("unique") shouldBe Map("maxItems" -> n(3))
    }
  }

  "A refined type inside an Option or a List" should "keep its bounds there" in {
    val schema = SchemaOf[Wrapped].toJsonSchema()
    property(schema, "maybe") shouldBe ujson.Obj(
      "type"        -> ujson.Arr("integer", "null"),
      "description" -> "maybe",
      "minimum"     -> 1
    )
    property(schema, "each")("items") shouldBe ujson.Obj(
      "type"        -> "string",
      "description" -> "string",
      "minLength"   -> 1
    )
    SchemaOf[Wrapped].toJsonSchema(strict = false)("required") shouldBe ujson.Arr("each")
  }

  "A refined list of a sum type" should "get its bounds in the JSON Schema, which core's model cannot hold" in {
    val shapes = property(SchemaOf[Drawing].toJsonSchema(), "shapes")
    shapes("maxItems") shouldBe ujson.Num(3)
    shapes("items")("anyOf").arr should have size 2
  }

  "A refined type on its own" should "be described by its base type's name" in {
    import io.github.iltotore.iron.:|
    import io.github.iltotore.iron.constraint.all.Positive
    val of = SchemaOf[Int :| Positive]
    of.typeName shouldBe "integer"
    of.typeDescription shouldBe None
    of.isOptional shouldBe false
    of.toJsonSchema() shouldBe ujson.Obj("type" -> "integer", "description" -> "integer", "minimum" -> 1)
  }

  "Writing bounds" should "refuse a schema the bounds cannot be written into, rather than drop them" in {
    val bounds = Bounds(Some(1), None, None, None, None, None)
    val error = intercept[IllegalStateException](
      IronSchemas.bounded(org.llm4s.toolapi.Schema.boolean("flag"), bounds, "Flag")
    )
    error.getMessage should include("Flag")
    intercept[IllegalStateException](IronSchemas.bounded(ujson.Obj("type" -> "boolean"), bounds)).getMessage should
      include("boolean")
    intercept[IllegalStateException](IronSchemas.bounded(ujson.Obj("anyOf" -> ujson.Arr()), bounds)).getMessage should
      include("(none)")
  }
}
