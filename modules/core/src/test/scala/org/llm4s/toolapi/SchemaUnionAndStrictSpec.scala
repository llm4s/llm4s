package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The cases the schema model gained before the baseline (#2814), a union and a JSON Schema written as JSON, and
 * what `strict` does to an optional property.
 */
class SchemaUnionAndStrictSpec extends AnyFlatSpec with Matchers {

  private val point = Schema.`object`[Any]("A point").withRequiredField("x", Schema.number("x"))

  "AnyOfSchema" should "render its alternatives, in order, under anyOf" in {
    val schema = Schema.anyOf[Any]("A name or a point", Schema.string("A name"), point)

    schema.toJsonSchema(strict = false) shouldBe ujson.Obj(
      "description" -> "A name or a point",
      "anyOf"       -> ujson.Arr(Schema.string("A name").toJsonSchema(false), point.toJsonSchema(false))
    )
  }

  it should "pass strict on to its alternatives" in {
    val optional = Schema.`object`[Any]("o").withOptionalField("n", Schema.string("n"))
    val json     = Schema.anyOf[Any]("u", optional).toJsonSchema(strict = true)

    json("anyOf")(0)("required").arr.map(_.str).toSeq shouldBe Seq("n")
  }

  it should "always have an alternative, and change with its setters" in {
    assertTypeError("""Schema.anyOf[Any]("none")""")
    assertTypeError("""AnyOfSchema[Any]("none", Seq.empty)""")
    val one = Schema.anyOf[Any]("u", Schema.string("a"))
    one.withAlternative(Schema.integer("b")).alternatives shouldBe Seq(Schema.string("a"), Schema.integer("b"))
    one.withAlternatives(Schema.boolean("c")).alternatives shouldBe Seq(Schema.boolean("c"))
    one.withDescription("v").description shouldBe "v"
  }

  it should "gain a null alternative when nullable, once" in {
    val union    = Schema.anyOf[Any]("u", Schema.string("a"), Schema.integer("b"))
    val nullable = Schema.nullable(union).toJsonSchema(strict = false)

    nullable("anyOf").arr.last shouldBe ujson.Obj("type" -> "null")
    nullable("anyOf").arr should have size 3
    nullable.obj.contains("type") shouldBe false
    Schema.nullable(Schema.nullable(union)).toJsonSchema(strict = false) shouldBe nullable
  }

  "RawJsonSchema" should "be emitted as it was written, whatever strict is" in {
    val written = ujson.Obj("type" -> "string", "pattern" -> "^[a-z]+$", "format" -> "hostname")
    val schema  = Schema.raw(written)

    schema.toJsonSchema(strict = true) shouldBe written
    schema.toJsonSchema(strict = false) shouldBe written
  }

  it should "not change when the JSON it was built from, or the JSON it returned, is mutated" in {
    val written = ujson.Obj("type" -> "string")
    val schema  = Schema.raw(written)
    written("type") = "integer"
    schema.json("minLength") = 3
    schema.toJsonSchema(strict = false)("x") = 1

    schema.toJsonSchema(strict = true) shouldBe ujson.Obj("type" -> "string")
    schema.withJson(ujson.Obj("$ref" -> "#/x")).json shouldBe ujson.Obj("$ref" -> "#/x")
  }

  it should "become nullable by its type, or as an anyOf with null when it declares none" in {
    Schema.nullable(Schema.raw(ujson.Obj("type" -> "string", "pattern" -> "a"))).toJsonSchema(false) shouldBe
      ujson.Obj("type" -> ujson.Arr("string", "null"), "pattern" -> "a")
    Schema.nullable(Schema.raw(ujson.Obj("description" -> "d", "$ref" -> "#/x"))).toJsonSchema(false) shouldBe
      ujson.Obj(
        "description" -> "d",
        "anyOf"       -> ujson.Arr(ujson.Obj("description" -> "d", "$ref" -> "#/x"), ujson.Obj("type" -> "null"))
      )
    // the empty schema accepts anything, null included
    Schema.nullable(Schema.raw(ujson.Obj())).toJsonSchema(false) shouldBe ujson.Obj()
  }

  "ObjectSchema under strict" should "list an optional property as required and make it nullable" in {
    val schema = Schema
      .`object`[Any]("Search")
      .withRequiredField("query", Schema.string("What to look for"))
      .withOptionalField("limit", Schema.integer("How many").withMinimum(1))
      .withOptionalField("sort", Schema.string("Order").withEnum(Seq("asc", "desc")))

    val strict = schema.toJsonSchema(strict = true)
    strict("required").arr.map(_.str).toSeq shouldBe Seq("query", "limit", "sort")
    strict("properties")("query")("type") shouldBe ujson.Str("string")
    strict("properties")("limit")("type") shouldBe ujson.Arr("integer", "null")
    strict("properties")("limit")("minimum").num shouldBe 1
    strict("properties")("sort")("type") shouldBe ujson.Arr("string", "null")
    strict("properties")("sort")("enum") shouldBe ujson.Arr("asc", "desc", ujson.Null)
  }

  it should "leave an optional property as it is when not strict" in {
    val schema = Schema.`object`[Any]("o").withOptionalField("limit", Schema.integer("How many"))
    val json   = schema.toJsonSchema(strict = false)

    json("required").arr shouldBe empty
    json("properties")("limit")("type") shouldBe ujson.Str("integer")
  }

  it should "not widen an optional property that is already nullable, and widen optional unions and nested objects" in {
    val nested = Schema.`object`[Any]("Nested").withOptionalField("deep", Schema.boolean("deep"))
    val schema = Schema
      .`object`[Any]("o")
      .withOptionalField("already", Schema.nullable(Schema.string("s")))
      .withOptionalField("union", Schema.anyOf[Any]("u", Schema.string("a"), Schema.integer("b")))
      .withOptionalField("nested", nested)

    val props = schema.toJsonSchema(strict = true)("properties")
    props("already")("type") shouldBe ujson.Arr("string", "null")
    props("union")("anyOf").arr.map(_("type")).toSeq shouldBe Seq[ujson.Value]("string", "integer", "null")
    props("nested")("type") shouldBe ujson.Arr("object", "null")
    props("nested")("properties")("deep")("type") shouldBe ujson.Arr("boolean", "null")
  }

  "The setters that reset the other bound" should "be gone: each bound has its own setter" in {
    assertTypeError("""Schema.integer("n").withRange(max = Some(5))""")
    assertTypeError("""Schema.number("n").withExclusiveRange(max = Some(5.0))""")
    assertTypeError("""Schema.string("s").withLengthConstraints(max = Some(5))""")
    assertTypeError("""Schema.array("a", Schema.string("s")).withSizeConstraints(max = Some(5))""")

    val bounded = Schema.integer("n").withMinimum(1).withMaximum(5)
    bounded.minimum shouldBe Some(1)
    bounded.maximum shouldBe Some(5)
  }
}
