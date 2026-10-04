package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for SchemaDefinition sealed trait and all schema types
 */
class SchemaDefinitionSpec extends AnyFlatSpec with Matchers {

  // ============ StringSchema ============

  "StringSchema" should "generate basic JSON schema" in {
    val schema = StringSchema("A test string")
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "string"
    json("description").str shouldBe "A test string"
  }

  it should "include enum values when specified" in {
    val schema = StringSchema("Status").withEnum(Seq("pending", "active", "done"))
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "string"
    json("enum").arr.map(_.str) shouldBe Seq("pending", "active", "done")
  }

  it should "include length constraints when specified" in {
    val schema = StringSchema("Username").withLengthConstraints(min = Some(3), max = Some(20))
    val json   = schema.toJsonSchema(strict = false)

    json("minLength").num.toInt shouldBe 3
    json("maxLength").num.toInt shouldBe 20
  }

  it should "support chained configuration" in {
    val schema = StringSchema("Color")
      .withEnum(Seq("red", "green", "blue"))
      .withLengthConstraints(min = Some(3), max = Some(5))
    val json = schema.toJsonSchema(strict = false)

    json("enum").arr.map(_.str) shouldBe Seq("red", "green", "blue")
    json("minLength").num.toInt shouldBe 3
    json("maxLength").num.toInt shouldBe 5
  }

  // ============ NumberSchema ============

  "NumberSchema" should "generate basic JSON schema" in {
    val schema = NumberSchema("A number value")
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "number"
    json("description").str shouldBe "A number value"
  }

  it should "generate integer type when isInteger is true" in {
    val schema = NumberSchema("An integer value", isInteger = true)
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "integer"
  }

  it should "include range constraints" in {
    val schema = NumberSchema("Price").withRange(min = Some(0.0), max = Some(1000.0))
    val json   = schema.toJsonSchema(strict = false)

    json("minimum").num shouldBe 0.0
    json("maximum").num shouldBe 1000.0
  }

  it should "include exclusive range constraints" in {
    val schema = NumberSchema("Rate").withExclusiveRange(min = Some(0.0), max = Some(1.0))
    val json   = schema.toJsonSchema(strict = false)

    json("exclusiveMinimum").num shouldBe 0.0
    json("exclusiveMaximum").num shouldBe 1.0
  }

  it should "include multipleOf constraint" in {
    val schema = NumberSchema("Amount").withMultipleOf(0.01)
    val json   = schema.toJsonSchema(strict = false)

    json("multipleOf").num shouldBe 0.01
  }

  // ============ IntegerSchema ============

  "IntegerSchema" should "generate integer JSON schema" in {
    val schema = IntegerSchema("An integer value")
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "integer"
    json("description").str shouldBe "An integer value"
  }

  it should "include range constraints" in {
    val schema = IntegerSchema("Age").withRange(min = Some(0), max = Some(150))
    val json   = schema.toJsonSchema(strict = false)

    json("minimum").num.toInt shouldBe 0
    json("maximum").num.toInt shouldBe 150
  }

  it should "include exclusive range constraints" in {
    val schema = IntegerSchema("Score").withExclusiveRange(min = Some(0), max = Some(100))
    val json   = schema.toJsonSchema(strict = false)

    json("exclusiveMinimum").num.toInt shouldBe 0
    json("exclusiveMaximum").num.toInt shouldBe 100
  }

  it should "include multipleOf constraint" in {
    val schema = IntegerSchema("Quantity").withMultipleOf(5)
    val json   = schema.toJsonSchema(strict = false)

    json("multipleOf").num.toInt shouldBe 5
  }

  // ============ BooleanSchema ============

  "BooleanSchema" should "generate boolean JSON schema" in {
    val schema = BooleanSchema("A flag value")
    val json   = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "boolean"
    json("description").str shouldBe "A flag value"
  }

  // ============ ArraySchema ============

  "ArraySchema" should "generate array JSON schema with item schema" in {
    val itemSchema = StringSchema("Item name")
    val schema     = ArraySchema("A list of items", itemSchema)
    val json       = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "array"
    json("description").str shouldBe "A list of items"
    json("items")("type").str shouldBe "string"
  }

  it should "include size constraints" in {
    val schema = ArraySchema("Tags", StringSchema("Tag"))
      .withSizeConstraints(min = Some(1), max = Some(10))
    val json = schema.toJsonSchema(strict = false)

    json("minItems").num.toInt shouldBe 1
    json("maxItems").num.toInt shouldBe 10
  }

  it should "include uniqueItems constraint" in {
    val schema = ArraySchema("Unique tags", StringSchema("Tag"))
      .withUniqueItems(true)
    val json = schema.toJsonSchema(strict = false)

    json("uniqueItems").bool shouldBe true
  }

  it should "support nested array schemas" in {
    val innerSchema = ArraySchema("Inner array", IntegerSchema("Number"))
    val schema      = ArraySchema("Matrix", innerSchema)
    val json        = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "array"
    json("items")("type").str shouldBe "array"
    json("items")("items")("type").str shouldBe "integer"
  }

  // ============ ObjectSchema ============

  "ObjectSchema" should "generate object JSON schema with properties" in {
    val schema = ObjectSchema[Map[String, Any]]("A user object", Seq.empty)
      .withProperty(PropertyDefinition("name", StringSchema("User name")))
      .withProperty(PropertyDefinition("age", IntegerSchema("User age")))
    val json = schema.toJsonSchema(strict = false)

    json("type").str shouldBe "object"
    json("description").str shouldBe "A user object"
    json("properties")("name")("type").str shouldBe "string"
    json("properties")("age")("type").str shouldBe "integer"
  }

  it should "mark required fields in required array" in {
    val schema = ObjectSchema[Map[String, Any]]("Test object", Seq.empty)
      .withProperty(PropertyDefinition("required1", StringSchema("Required"), required = true))
      .withProperty(PropertyDefinition("optional1", StringSchema("Optional"), required = false))
      .withProperty(PropertyDefinition("required2", IntegerSchema("Required int"), required = true))
    val json = schema.toJsonSchema(strict = false)

    val required = json("required").arr.map(_.str).toSet
    required should contain("required1")
    required should contain("required2")
    required should not contain "optional1"
  }

  it should "make all properties required in strict mode" in {
    val schema = ObjectSchema[Map[String, Any]]("Test object", Seq.empty)
      .withProperty(PropertyDefinition("required1", StringSchema("Required"), required = true))
      .withProperty(PropertyDefinition("optional1", StringSchema("Optional"), required = false))
    val json = schema.toJsonSchema(strict = true)

    val required = json("required").arr.map(_.str).toSet
    required should contain("required1")
    required should contain("optional1")
  }

  it should "support withRequiredField helper" in {
    val schema = ObjectSchema[Map[String, Any]]("Test", Seq.empty)
      .withRequiredField("name", StringSchema("Name"))
    val json = schema.toJsonSchema(strict = false)

    json("required").arr.map(_.str) should contain("name")
  }

  it should "support withOptionalField helper" in {
    val schema = ObjectSchema[Map[String, Any]]("Test", Seq.empty)
      .withOptionalField("nickname", StringSchema("Nickname"))
    val json = schema.toJsonSchema(strict = false)

    json("properties")("nickname")("type").str shouldBe "string"
    json("required").arr.map(_.str) should not contain "nickname"
  }

  it should "include additionalProperties setting" in {
    val schemaFalse = ObjectSchema[Map[String, Any]]("Test", Seq.empty, additionalProperties = false)
    val schemaTrue  = ObjectSchema[Map[String, Any]]("Test", Seq.empty, additionalProperties = true)

    schemaFalse.toJsonSchema(strict = false)("additionalProperties").bool shouldBe false
    schemaTrue.toJsonSchema(strict = false)("additionalProperties").bool shouldBe true
  }

  "ObjectSchema" should "render a field added twice once in required, in first-occurrence order" in {
    val schema = ObjectSchema[Map[String, Any]]("o", Seq.empty)
      .withRequiredField("x", StringSchema("x"))
      .withRequiredField("y", StringSchema("y"))
      .withRequiredField("x", StringSchema("x"))
    schema.toJsonSchema(strict = false)("required").arr.map(_.str).toSeq shouldBe Seq("x", "y")
  }

  // ============ NullableSchema ============

  "NullableSchema" should "add null to type for simple schema" in {
    val schema = NullableSchema(StringSchema("Optional string"))
    val json   = schema.toJsonSchema(strict = false)

    val types = json("type").arr.map(_.str)
    types should contain("string")
    types should contain("null")
  }

  it should "add null to existing type array" in {
    // This tests the case where we already have an array type
    val innerSchema = NullableSchema(StringSchema("Already nullable"))
    val schema      = NullableSchema(innerSchema)
    val json        = schema.toJsonSchema(strict = false)

    val types = json("type").arr.map(_.str)
    types should contain("null")
    types should contain("string")
  }

  it should "not repeat null when the schema is nullable twice" in {
    val json = NullableSchema(NullableSchema(StringSchema("twice"))).toJsonSchema(strict = false)
    json("type").arr.map(_.str).toSeq shouldBe Seq("string", "null")
  }

  it should "preserve description from underlying schema" in {
    val schema = NullableSchema(StringSchema("Important description"))
    val json   = schema.toJsonSchema(strict = false)

    json("description").str shouldBe "Important description"
  }

  // ============ Schema Builder ============

  "Schema builder" should "create string schema" in {
    val schema = Schema.string("A string")
    schema shouldBe a[StringSchema]
    schema.description shouldBe "A string"
  }

  it should "create number schema" in {
    val schema = Schema.number("A number")
    schema shouldBe a[NumberSchema]
    schema.description shouldBe "A number"
  }

  it should "create integer schema" in {
    val schema = Schema.integer("An integer")
    schema shouldBe a[IntegerSchema]
    schema.description shouldBe "An integer"
  }

  it should "create boolean schema" in {
    val schema = Schema.boolean("A boolean")
    schema shouldBe a[BooleanSchema]
    schema.description shouldBe "A boolean"
  }

  it should "create array schema" in {
    val schema = Schema.array("Numbers", Schema.integer("A number"))
    schema shouldBe a[ArraySchema[_]]
    schema.description shouldBe "Numbers"
  }

  it should "create object schema" in {
    val schema = Schema.`object`[Map[String, Any]]("An object")
    schema shouldBe a[ObjectSchema[_]]
    schema.description shouldBe "An object"
  }

  it should "create nullable schema" in {
    val schema = Schema.nullable(Schema.string("Optional"))
    schema shouldBe a[NullableSchema[_]]
  }

  it should "create property definition" in {
    val prop = Schema.property("name", Schema.string("Name"), required = true)
    prop.name shouldBe "name"
    prop.required shouldBe true
    prop.schema shouldBe a[StringSchema]
  }

  // ============ PropertyDefinition ============

  "PropertyDefinition" should "default to required = true" in {
    val prop = PropertyDefinition("field", StringSchema("A field"))
    prop.required shouldBe true
  }

  it should "allow specifying required = false" in {
    val prop = PropertyDefinition("field", StringSchema("A field"), required = false)
    prop.required shouldBe false
  }
}
