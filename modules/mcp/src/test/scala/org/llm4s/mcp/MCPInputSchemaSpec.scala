package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An MCP tool's `inputSchema` reaches the model as the server declared it (#3076): `additionalProperties` as written,
 * `$ref`s into `$defs` resolved, constraints kept, and what the model cannot carry as a keyword put in the description.
 */
class MCPInputSchemaSpec extends AnyFlatSpec with Matchers {

  private def rendered(inputSchema: ujson.Value): ujson.Value =
    MCPInputSchema.toObjectSchema("t", inputSchema).toJsonSchema(strict = false)

  private def property(inputSchema: ujson.Value, name: String): ujson.Value = rendered(inputSchema)("properties")(name)

  private def withProperty(name: String, schema: ujson.Value, extra: (String, ujson.Value)*): ujson.Obj =
    ujson.Obj.from(
      Seq("type" -> ujson.Str("object"), "properties" -> ujson.Obj(name -> schema)) ++ extra
    )

  "MCPInputSchema" should "leave a free-form object tool open, so it can be given arguments" in {
    rendered(ujson.Obj("type" -> "object"))("additionalProperties") shouldBe ujson.Bool(true)
  }

  it should "keep additionalProperties as the server wrote it, and close an object with properties that is silent" in {
    val open = withProperty("a", ujson.Obj("type" -> "string"), "additionalProperties" -> ujson.Bool(true))
    rendered(open)("additionalProperties") shouldBe ujson.Bool(true)
    rendered(withProperty("a", ujson.Obj("type" -> "string")))("additionalProperties") shouldBe ujson.Bool(false)
    val typedExtras =
      withProperty("a", ujson.Obj("type" -> "string"), "additionalProperties" -> ujson.Obj("type" -> "string"))
    rendered(typedExtras)("additionalProperties") shouldBe ujson.Bool(true)
  }

  it should "resolve a $ref into $defs or definitions, leaving no dangling reference" in {
    val address = ujson.Obj(
      "type"       -> "object",
      "properties" -> ujson.Obj("city" -> ujson.Obj("type" -> "string", "minLength" -> 1)),
      "required"   -> ujson.Arr("city")
    )
    val schema = withProperty(
      "home",
      ujson.Obj("$ref" -> "#/$defs/Address", "description" -> "Where they live"),
      "$defs" -> ujson.Obj("Address" -> address)
    )
    val home = property(schema, "home")
    home("type").str shouldBe "object"
    home("description").str shouldBe "Where they live"
    home("properties")("city")("minLength").num shouldBe 1
    home("required") shouldBe ujson.Arr("city")
    (rendered(schema).render() should not).include("$ref")

    val legacy = withProperty(
      "p",
      ujson.Obj("$ref" -> "#/definitions/N"),
      "definitions" -> ujson.Obj("N" -> ujson.Obj("type" -> "integer"))
    )
    property(legacy, "p")("type").str shouldBe "integer"
  }

  it should "accept any value at a recursive reference rather than follow it for ever" in {
    val node = ujson.Obj(
      "type"       -> "object",
      "properties" -> ujson.Obj("child" -> ujson.Obj("$ref" -> "#/$defs/Node"))
    )
    val tree =
      property(withProperty("root", ujson.Obj("$ref" -> "#/$defs/Node"), "$defs" -> ujson.Obj("Node" -> node)), "root")
    tree("properties")("child")("description").str should include("recursive reference #/$defs/Node")
  }

  it should "keep a string's, a number's, an integer's and an array's constraints" in {
    val schema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "code"  -> ujson.Obj("type" -> "string", "minLength" -> 2, "maxLength" -> 3, "pattern" -> "^[A-Z]+$"),
        "ratio" -> ujson.Obj("type" -> "number", "minimum" -> 0, "exclusiveMaximum" -> 1, "multipleOf" -> 0.5),
        "count" -> ujson.Obj("type" -> "integer", "minimum" -> 1, "maximum" -> 10),
        "old"   -> ujson.Obj("type" -> "integer", "minimum" -> 1, "exclusiveMinimum" -> true),
        "tags" -> ujson.Obj(
          "type"        -> "array",
          "items"       -> ujson.Obj("type" -> "string"),
          "minItems"    -> 1,
          "maxItems"    -> 5,
          "uniqueItems" -> true
        )
      )
    )
    val p = rendered(schema)("properties")
    p("code")("minLength").num shouldBe 2
    p("code")("maxLength").num shouldBe 3
    p("code")("pattern").str shouldBe "^[A-Z]+$"
    p("ratio")("minimum").num shouldBe 0
    p("ratio")("exclusiveMaximum").num shouldBe 1
    p("ratio")("multipleOf").num shouldBe 0.5
    p("count")("minimum").num shouldBe 1
    p("count")("maximum").num shouldBe 10
    p("old")("exclusiveMinimum").num shouldBe 1
    p("old").obj.contains("minimum") shouldBe false
    p("tags")("minItems").num shouldBe 1
    p("tags")("maxItems").num shouldBe 5
    p("tags")("uniqueItems").bool shouldBe true
  }

  it should "keep an enum of numbers, and a const, as the values allowed" in {
    property(withProperty("level", ujson.Obj("type" -> "integer", "enum" -> ujson.Arr(1, 2, 3))), "level") shouldBe
      ujson.Obj("type" -> "integer", "enum" -> ujson.Arr(1, 2, 3), "description" -> "")
    property(withProperty("kind", ujson.Obj("type" -> "string", "const" -> "fixed")), "kind")("enum") shouldBe ujson
      .Arr("fixed")
  }

  it should "read oneOf as anyOf, and a type of several names as an anyOf of each" in {
    val oneOf = property(
      withProperty("v", ujson.Obj("oneOf" -> ujson.Arr(ujson.Obj("type" -> "string"), ujson.Obj("type" -> "integer")))),
      "v"
    )
    oneOf("anyOf").arr.map(_("type").str).toSeq shouldBe Seq("string", "integer")
    val many = property(withProperty("v", ujson.Obj("type" -> ujson.Arr("string", "integer"))), "v")
    many("anyOf").arr.map(_("type").str).toSeq shouldBe Seq("string", "integer")
  }

  it should "describe a property by its title when it has no description, never as 'Parameter'" in {
    property(withProperty("q", ujson.Obj("type" -> "string", "title" -> "Query")), "q")(
      "description"
    ).str shouldBe "Query"
    property(withProperty("q", ujson.Obj("type" -> "string")), "q")("description").str shouldBe ""
  }

  it should "tell the model a default, a format and examples it cannot carry as keywords" in {
    val p = property(
      withProperty(
        "when",
        ujson.Obj(
          "type"        -> "string",
          "description" -> "When",
          "format"      -> "date-time",
          "default"     -> "now",
          "examples"    -> ujson.Arr("2026-01-01T00:00:00Z")
        )
      ),
      "when"
    )
    p("description").str shouldBe """When (default: "now"; format: date-time; examples: "2026-01-01T00:00:00Z")"""
    p.obj.contains("format") shouldBe false
    p.obj.contains("default") shouldBe false
  }

  it should "give an array without items items of any kind, not strings" in {
    property(withProperty("xs", ujson.Obj("type" -> "array")), "xs")("items") shouldBe ujson.Obj()
  }
}
