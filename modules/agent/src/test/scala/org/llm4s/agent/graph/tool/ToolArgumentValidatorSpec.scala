package org.llm4s.agent.graph.tool

import org.llm4s.toolapi._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolArgumentValidatorSpec extends AnyFlatSpec with Matchers {

  private val v = ToolArgumentValidator.default

  private def check(schema: ujson.Value, args: ujson.Value): Vector[String] = v.validate(schema, args)

  private def schemaOf(fields: (String, ujson.Value)*): ujson.Value = ujson.Obj.from(fields)

  private def obj(props: (String, ujson.Value)*): ujson.Value = ujson.Obj.from(props)

  "validate" should "accept valid arguments and report nothing" in {
    val schema = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField("query", StringSchema("q"))
      .toJsonSchema(strict = true)
    check(schema, obj("query" -> "hi")) shouldBe Vector.empty
  }

  it should "report a missing required property" in {
    val schema = ObjectSchema[Unit]("o", Seq.empty).withRequiredField("query", StringSchema("q")).toJsonSchema(true)
    check(schema, obj()) shouldBe Vector("$.query: required property missing")
  }

  it should "reject an extra property when additionalProperties is false, allow it when true or absent" in {
    val closed = ObjectSchema[Unit]("o", Seq.empty).withRequiredField("a", StringSchema("a")).toJsonSchema(true)
    check(closed, obj("a" -> "x", "extra" -> 1)) shouldBe Vector("$.extra: property not allowed")
    val open = ObjectSchema[Unit]("o", Seq.empty, additionalProperties = true)
      .withRequiredField("a", StringSchema("a"))
      .toJsonSchema(true)
    check(open, obj("a" -> "x", "extra" -> 1)) shouldBe Vector.empty
    val absent = schemaOf("type" -> "object", "properties" -> obj("a" -> obj("type" -> "string")))
    check(absent, obj("a" -> "x", "extra" -> 1)) shouldBe Vector.empty
  }

  it should "treat strict as all required and non-strict as only the required ones" in {
    val schema = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField("a", StringSchema("a"))
      .withOptionalField("b", StringSchema("b"))
    check(schema.toJsonSchema(true), obj("a" -> "x")) shouldBe Vector("$.b: required property missing")
    check(schema.toJsonSchema(false), obj("a" -> "x")) shouldBe Vector.empty
    check(schema.toJsonSchema(false), obj("b" -> "x")) shouldBe Vector("$.a: required property missing")
  }

  it should "report a type mismatch and nothing else for that node" in {
    val schema = IntegerSchema("n", minimum = Some(1)).toJsonSchema(true)
    check(schema, ujson.Str("x")) shouldBe Vector("$: expected integer, got string")
    val o = ObjectSchema[Unit]("o", Seq.empty).withRequiredField("limit", IntegerSchema("l", minimum = Some(1)))
    check(o.toJsonSchema(true), obj("limit" -> "x")) shouldBe Vector("$.limit: expected integer, got string")
  }

  it should "return exactly one message for non-object arguments to an object schema" in {
    val schema = ObjectSchema[Unit]("o", Seq.empty).withRequiredField("a", StringSchema("a")).toJsonSchema(true)
    check(schema, ujson.Str("nope")) shouldBe Vector("$: expected object, got string")
  }

  it should "check enum structurally" in {
    val schema = StringSchema("m").withEnum(Seq("a", "b")).toJsonSchema(true)
    check(schema, ujson.Str("a")) shouldBe Vector.empty
    check(ujson.Obj("enum" -> ujson.Arr(1, obj("k" -> 2))), obj("k" -> 2)) shouldBe Vector.empty
    val wrapped =
      ObjectSchema[Unit]("o", Seq.empty).withRequiredField("mode", StringSchema("m").withEnum(Seq("a", "b")))
    check(wrapped.toJsonSchema(true), obj("mode" -> "x")) shouldBe
      Vector("""$.mode: "x" is not one of ["a","b"]""")
  }

  it should "check string lengths in code points" in {
    val schema = StringSchema("q").withLengthConstraints(Some(1), Some(2)).toJsonSchema(true)
    check(schema, ujson.Str("")) shouldBe Vector("$: length 0 is below minLength 1")
    check(schema, ujson.Str("😀")) shouldBe Vector.empty
    check(schema, ujson.Str("😀😀")) shouldBe Vector.empty
    check(schema, ujson.Str("abc")) shouldBe Vector("$: length 3 is above maxLength 2")
    val o = ObjectSchema[Unit]("o", Seq.empty).withRequiredField("q", StringSchema("q").withLengthConstraints(Some(1)))
    check(o.toJsonSchema(true), obj("q" -> "")) shouldBe Vector("$.q: length 0 is below minLength 1")
  }

  it should "check numeric bounds" in {
    val o = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField("limit", IntegerSchema("l", minimum = Some(1), maximum = Some(100)))
      .toJsonSchema(true)
    check(o, obj("limit" -> 500)) shouldBe Vector("$.limit: 500 is above maximum 100")
    check(o, obj("limit" -> 0)) shouldBe Vector("$.limit: 0 is below minimum 1")
    check(o, obj("limit" -> 50)) shouldBe Vector.empty
    val ex = IntegerSchema("l", exclusiveMinimum = Some(1), exclusiveMaximum = Some(10)).toJsonSchema(true)
    check(ex, ujson.Num(1)) shouldBe Vector("$: 1 is not above exclusiveMinimum 1")
    check(ex, ujson.Num(10)) shouldBe Vector("$: 10 is not below exclusiveMaximum 10")
    check(ex, ujson.Num(5)) shouldBe Vector.empty
    val frac = NumberSchema("n", maximum = Some(1.5)).toJsonSchema(true)
    check(frac, ujson.Num(2.25)) shouldBe Vector("$: 2.25 is above maximum 1.5")
  }

  it should "check multipleOf with tolerance" in {
    val five = IntegerSchema("n").withMultipleOf(5).toJsonSchema(true)
    check(five, ujson.Num(10)) shouldBe Vector.empty
    check(five, ujson.Num(7)) shouldBe Vector("$: 7 is not a multiple of 5")
    val tenth = NumberSchema("n").withMultipleOf(0.1).toJsonSchema(true)
    check(tenth, ujson.Num(0.3)) shouldBe Vector.empty
    check(tenth, ujson.Num(0.35)) shouldBe Vector("$: 0.35 is not a multiple of 0.1")
  }

  it should "distinguish integer from number" in {
    val schema = IntegerSchema("n").toJsonSchema(true)
    check(schema, ujson.Num(1.5)) shouldBe Vector("$: expected integer, got number")
    check(schema, ujson.Num(2.0)) shouldBe Vector.empty
    check(NumberSchema("n").toJsonSchema(true), ujson.Num(1.5)) shouldBe Vector.empty
    check(BooleanSchema("b").toJsonSchema(true), ujson.Bool(true)) shouldBe Vector.empty
    check(BooleanSchema("b").toJsonSchema(true), ujson.Null) shouldBe Vector("$: expected boolean, got null")
  }

  it should "check array size, uniqueness and elements with indexed paths" in {
    val tags = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField(
        "tags",
        ArraySchema("t", StringSchema("s")).withSizeConstraints(Some(1), Some(3)).withUniqueItems()
      )
      .toJsonSchema(true)
    check(tags, obj("tags" -> ujson.Arr())) shouldBe Vector("$.tags: 0 items is below minItems 1")
    check(tags, obj("tags" -> ujson.Arr("a", "b", "c", "d"))) shouldBe Vector("$.tags: 4 items is above maxItems 3")
    check(tags, obj("tags" -> ujson.Arr("a", "a"))) shouldBe Vector("$.tags: items are not unique")
    check(tags, obj("tags" -> ujson.Arr("a", "b", "c", 4))) shouldBe
      Vector("$.tags: 4 items is above maxItems 3", "$.tags[3]: expected string, got integer")
    check(tags, obj("tags" -> ujson.Arr("a", 1))) shouldBe Vector("$.tags[1]: expected string, got integer")
  }

  it should "accept null for a nullable schema and refuse other types" in {
    val schema = NullableSchema(StringSchema("s")).toJsonSchema(true)
    check(schema, ujson.Null) shouldBe Vector.empty
    check(schema, ujson.Str("x")) shouldBe Vector.empty
    check(schema, ujson.Num(1)) shouldBe Vector("$: expected string or null, got integer")
  }

  it should "validate a nested object in an array in an object" in {
    val inner = ObjectSchema[Unit]("i", Seq.empty).withRequiredField("n", IntegerSchema("n", minimum = Some(0)))
    val outer = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField("items", ArraySchema("a", inner))
      .toJsonSchema(true)
    check(outer, obj("items" -> ujson.Arr(obj("n" -> 1), obj("n" -> -1), obj()))) shouldBe
      Vector("$.items[1].n: -1 is below minimum 0", "$.items[2].n: required property missing")
  }

  it should "report every violation in a deterministic order" in {
    val schema = ObjectSchema[Unit]("o", Seq.empty)
      .withRequiredField("a", IntegerSchema("a", maximum = Some(1)))
      .withRequiredField("b", StringSchema("b").withLengthConstraints(Some(2)))
      .withRequiredField("c", BooleanSchema("c"))
      .toJsonSchema(true)
    check(schema, obj("a" -> 5, "b" -> "x", "z" -> 1)) shouldBe Vector(
      "$.c: required property missing",
      "$.z: property not allowed",
      "$.a: 5 is above maximum 1",
      "$.b: length 1 is below minLength 2"
    )
  }

  it should "validate a 50-deep nested object" in {
    val depth = 50
    val schema = (1 to depth).foldLeft[ujson.Value](obj("type" -> "string")) { (acc, _) =>
      obj("type" -> "object", "properties" -> obj("x" -> acc), "required" -> ujson.Arr("x"))
    }
    val good = (1 to depth).foldLeft[ujson.Value](ujson.Str("leaf"))((acc, _) => obj("x" -> acc))
    val bad  = (1 to depth).foldLeft[ujson.Value](ujson.Num(1))((acc, _) => obj("x" -> acc))
    check(schema, good) shouldBe Vector.empty
    check(schema, bad) shouldBe Vector(List.fill(depth)("x").mkString("$.", ".", "") + ": expected string, got integer")
  }

  "unsupported" should "name the path of an unsupported keyword" in {
    val schema = obj(
      "type"       -> "object",
      "properties" -> obj("q" -> obj("type" -> "string", "pattern" -> "^a"))
    )
    v.unsupported(schema) shouldBe Vector("$.properties.q.pattern")
  }

  it should "find unsupported keywords under items and root" in {
    val schema = obj(
      "type"  -> "array",
      "items" -> obj("type" -> "string", "format" -> "uri"),
      "$ref"  -> "#/x"
    )
    v.unsupported(schema).toSet shouldBe Set("$.items.format", "$.$ref")
  }

  it should "refuse a non-boolean additionalProperties" in {
    val schema = obj("type" -> "object", "additionalProperties" -> obj("type" -> "string"))
    v.unsupported(schema) shouldBe Vector("$.additionalProperties")
  }

  it should "refuse malformed keyword values, by path" in {
    val schema = obj(
      "type"     -> "object",
      "required" -> ujson.Arr("a", 1),
      "properties" -> obj(
        "a" -> obj(
          "type"             -> "number",
          "minimum"          -> "1",
          "maximum"          -> true,
          "exclusiveMinimum" -> ujson.Null,
          "exclusiveMaximum" -> obj(),
          "multipleOf"       -> "2"
        ),
        "s" -> obj("type" -> "string", "minLength" -> "1", "maxLength" -> ujson.Arr()),
        "l" -> obj(
          "type"        -> "array",
          "items"       -> obj("type" -> "string", "enum" -> "x"),
          "minItems"    -> "0",
          "maxItems"    -> false,
          "uniqueItems" -> "yes"
        ),
        "o" -> obj("type" -> "object", "properties" -> ujson.Arr())
      )
    )
    v.unsupported(schema) shouldBe Vector(
      "$.required",
      "$.properties.a.minimum",
      "$.properties.a.maximum",
      "$.properties.a.exclusiveMinimum",
      "$.properties.a.exclusiveMaximum",
      "$.properties.a.multipleOf",
      "$.properties.s.minLength",
      "$.properties.s.maxLength",
      "$.properties.l.items.enum",
      "$.properties.l.minItems",
      "$.properties.l.maxItems",
      "$.properties.l.uniqueItems",
      "$.properties.o.properties"
    )
  }

  it should "refuse a non-array required, and a count that is negative or fractional" in {
    v.unsupported(obj("type" -> "object", "required" -> "a")) shouldBe Vector("$.required")
    v.unsupported(obj("type" -> "string", "minLength" -> -1, "maxLength" -> 1.5)) shouldBe
      Vector("$.minLength", "$.maxLength")
    v.unsupported(obj("type" -> "array", "minItems" -> 0, "maxItems" -> 3, "uniqueItems" -> false)) shouldBe
      Vector.empty
  }

  it should "return empty for every SchemaDefinition constructor in core, strict and non-strict" in {
    val inner = ObjectSchema[Unit]("i", Seq.empty)
      .withRequiredField("s", StringSchema("s").withEnum(Seq("a")).withLengthConstraints(Some(1), Some(5)))
      .withOptionalField("n", NumberSchema("n", minimum = Some(0), maximum = Some(9), multipleOf = Some(0.5)))
      .withOptionalField(
        "nx",
        NumberSchema("n", isInteger = true, exclusiveMinimum = Some(0), exclusiveMaximum = Some(9))
      )
      .withRequiredField("i", IntegerSchema("i", Some(0), Some(9), Some(-1), Some(10), Some(3)))
      .withRequiredField("b", BooleanSchema("b"))
      .withRequiredField(
        "arr",
        ArraySchema("a", StringSchema("s")).withSizeConstraints(Some(1), Some(2)).withUniqueItems()
      )
      .withOptionalField("nul", NullableSchema(StringSchema("s")))
      .withOptionalField("nulObj", NullableSchema(ObjectSchema[Unit]("x", Seq.empty)))
    val all: Seq[SchemaDefinition[?]] = Seq(
      inner,
      StringSchema("s"),
      NumberSchema("n"),
      IntegerSchema("i"),
      BooleanSchema("b"),
      ArraySchema("a", inner),
      NullableSchema(inner)
    )
    for {
      s      <- all
      strict <- Seq(true, false)
    }
      v.unsupported(s.toJsonSchema(strict)) shouldBe Vector.empty
  }

  "a nullable enum" should "accept null, accept its values and refuse others" in {
    val schema = NullableSchema(StringSchema("m").withEnum(Seq("a"))).toJsonSchema(true)
    check(schema, ujson.Null) shouldBe Vector.empty
    check(schema, ujson.Str("a")) shouldBe Vector.empty
    check(schema, ujson.Str("b")) shouldBe Vector("""$: "b" is not one of ["a"]""")
  }

  "unsupported" should "flag unknown or malformed type declarations" in {
    v.unsupported(obj("type" -> "date")) shouldBe Vector("$.type")
    v.unsupported(obj("type" -> ujson.Arr())) shouldBe Vector("$.type")
    v.unsupported(obj("type" -> ujson.Arr("string", 1))) shouldBe Vector("$.type")
    v.unsupported(obj("type" -> ujson.Arr("string", "foo"))) shouldBe Vector("$.type")
    v.unsupported(obj("type" -> 3)) shouldBe Vector("$.type")
    v.unsupported(obj("properties" -> obj("a" -> obj("type" -> "wat")))) shouldBe Vector("$.properties.a.type")
    v.unsupported(obj("type" -> ujson.Arr("string", "null"))) shouldBe Vector.empty
  }

  "numbers" should "render large whole values without exponent or fraction" in {
    val schema = NumberSchema("n", maximum = Some(1e15)).toJsonSchema(true)
    check(schema, ujson.Num(1e16)) shouldBe Vector("$: 10000000000000000 is above maximum 1000000000000000")
  }

  "the validator" should "never throw on malformed schemas or arguments" in {
    val args: Seq[ujson.Value] =
      Seq(ujson.Null, ujson.Bool(true), ujson.Num(1.5), ujson.Str("x"), ujson.Arr(1, "a"), obj("a" -> 1))
    val schemas: Seq[ujson.Value] = Seq(
      ujson.Null,
      ujson.Str("x"),
      obj(),
      obj("type"      -> "object", "properties"  -> ujson.Arr()),
      obj("type"      -> "object", "properties"  -> obj("a" -> 3)),
      obj("required"  -> ujson.Arr(1, ujson.Null, "a")),
      obj("required"  -> "a"),
      obj("type"      -> ujson.Arr()),
      obj("type"      -> ujson.Arr(1)),
      obj("type"      -> 5),
      obj("items"     -> 1, "minItems"           -> "x", "uniqueItems" -> 1),
      obj("enum"      -> 1, "minimum"            -> "a", "multipleOf"  -> 0, "additionalProperties" -> obj()),
      obj("minLength" -> ujson.Null, "maxLength" -> ujson.Arr())
    )
    for {
      s <- schemas
      a <- args
    } {
      noException should be thrownBy v.validate(s, a)
      noException should be thrownBy v.unsupported(s)
    }
  }

  "unsupported" should "report a multipleOf that is not a positive finite number" in {
    v.unsupported(obj("type" -> "number", "multipleOf" -> 0)) shouldBe Vector("$.multipleOf")
    v.unsupported(obj("properties" -> obj("a" -> obj("type" -> "integer", "multipleOf" -> -5)))) shouldBe
      Vector("$.properties.a.multipleOf")
    v.unsupported(obj("type" -> "number", "multipleOf" -> Double.NaN)) shouldBe Vector("$.multipleOf")
    v.unsupported(obj("type" -> "number", "multipleOf" -> Double.PositiveInfinity)) shouldBe Vector("$.multipleOf")
    v.unsupported(obj("type" -> "number", "multipleOf" -> 0.5)) shouldBe Vector.empty
  }

  "validate" should "stay safe and ignore a non-positive or non-finite multipleOf" in {
    Seq(0.0, -5.0, Double.NaN, Double.PositiveInfinity).foreach { m =>
      noException should be thrownBy check(obj("type" -> "number", "multipleOf" -> m), ujson.Num(7))
    }
  }

  "multipleOf" should "be exact in decimal, not relative" in {
    def mult(n: Double, m: Double) = check(obj("type" -> "number", "multipleOf" -> m), ujson.Num(n)).isEmpty
    mult(0.3, 0.1) shouldBe true
    mult(7, 5) shouldBe false
    mult(1000000000.5, 1) shouldBe false
    mult(1e20, 1) shouldBe true
    mult(2.5, 0.5) shouldBe true
    // the decimal text of the double is used, so a value that prints as 0.30000000000000004 is refused
    mult(0.30000000000000004, 0.1) shouldBe false
  }

  "unsupported" should "report a non-finite bound or count" in {
    for {
      k   <- Seq("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")
      bad <- Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    }
      v.unsupported(obj("type" -> "number", k -> bad)) shouldBe Vector(s"$$.$k")
    for {
      k   <- Seq("minLength", "maxLength", "minItems", "maxItems")
      bad <- Seq(Double.NaN, Double.PositiveInfinity)
    }
      v.unsupported(obj(k -> bad)) shouldBe Vector(s"$$.$k")
  }

  "unsupported" should "report a type array with duplicate entries" in {
    v.unsupported(obj("type" -> ujson.Arr("string", "null", "null"))) shouldBe Vector("$.type")
    v.unsupported(obj("properties" -> obj("a" -> obj("type" -> ujson.Arr("string", "string"))))) shouldBe
      Vector("$.properties.a.type")
  }
}
