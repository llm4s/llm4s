package org.llm4s.eval.dataset

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonSchemaValidatorSpec extends AnyFlatSpec with Matchers {

  "JsonSchemaValidator.validate" should "return Right for valid value" in {
    val schema = ujson.Obj(
      "type"     -> ujson.Str("object"),
      "required" -> ujson.Arr("query"),
      "properties" -> ujson.Obj(
        "query" -> ujson.Obj("type" -> ujson.Str("string"))
      )
    )
    val value = ujson.Obj("query" -> ujson.Str("hello"))

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "return Left with errors for invalid value" in {
    val schema = ujson.Obj(
      "type"     -> ujson.Str("object"),
      "required" -> ujson.Arr("query")
    )
    val value = ujson.Obj("answer" -> ujson.Num(42))

    val result = JsonSchemaValidator.validate(value, schema)
    result shouldBe a[Left[_, _]]
    result.swap.getOrElse(List.empty) should not be empty
  }

  "type keyword" should "match string type" in {
    val schema = ujson.Obj("type" -> ujson.Str("string"))
    val value  = ujson.Str("hello")

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "reject number for string type" in {
    val schema = ujson.Obj("type" -> ujson.Str("string"))
    val value  = ujson.Num(42)

    val result = JsonSchemaValidator.validate(value, schema)
    result shouldBe a[Left[_, _]]
    val errors = result.swap.getOrElse(List.empty)
    errors should have size 1
    errors.head should include("string")
  }

  it should "match object type" in {
    val schema = ujson.Obj("type" -> ujson.Str("object"))
    val value  = ujson.Obj("key" -> ujson.Str("value"))

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "match array type" in {
    val schema = ujson.Obj("type" -> ujson.Str("array"))
    val value  = ujson.Arr(ujson.Str("a"), ujson.Str("b"))

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "match number type" in {
    val schema = ujson.Obj("type" -> ujson.Str("number"))
    val value  = ujson.Num(3.14)

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "match boolean type" in {
    val schema = ujson.Obj("type" -> ujson.Str("boolean"))
    val value  = ujson.Bool(true)

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "match null type" in {
    val schema = ujson.Obj("type" -> ujson.Str("null"))
    val value  = ujson.Null

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "silently ignore unsupported type strings" in {
    val schema = ujson.Obj("type" -> ujson.Str("custom-unknown-type"))
    val value  = ujson.Str("anything")

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  "required keyword" should "pass when all required keys present" in {
    val schema = ujson.Obj("required" -> ujson.Arr("a", "b"))
    val value  = ujson.Obj("a" -> ujson.Num(1), "b" -> ujson.Num(2))

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "fail with missing key in error message" in {
    val schema = ujson.Obj("required" -> ujson.Arr("a", "b"))
    val value  = ujson.Obj("a" -> ujson.Num(1))

    val result = JsonSchemaValidator.validate(value, schema)
    result shouldBe a[Left[_, _]]
    val errors = result.swap.getOrElse(List.empty)
    errors should have size 1
    errors.head should include("b")
  }

  it should "report multiple missing keys" in {
    val schema = ujson.Obj("required" -> ujson.Arr("a", "b", "c"))
    val value  = ujson.Obj()

    val result = JsonSchemaValidator.validate(value, schema)
    val errors = result.swap.getOrElse(List.empty)
    errors should have size 3
    errors.map(_.contains("Missing required field")).count(identity) shouldBe 3
  }

  it should "be ignored for non-object values" in {
    val schema = ujson.Obj("required" -> ujson.Arr("a"))
    val value  = ujson.Num(5)

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  "properties keyword" should "validate nested type" in {
    val schema = ujson.Obj(
      "properties" -> ujson.Obj(
        "count" -> ujson.Obj("type" -> ujson.Str("number"))
      )
    )
    val value = ujson.Obj("count" -> ujson.Str("five"))

    val result = JsonSchemaValidator.validate(value, schema)
    result shouldBe a[Left[_, _]]
    val errors = result.swap.getOrElse(List.empty)
    errors.head should include("count")
  }

  it should "pass for missing optional property" in {
    val schema = ujson.Obj(
      "properties" -> ujson.Obj(
        "count" -> ujson.Obj("type" -> ujson.Str("number"))
      )
    )
    val value = ujson.Obj()

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  it should "report multiple property errors" in {
    val schema = ujson.Obj(
      "properties" -> ujson.Obj(
        "a" -> ujson.Obj("type" -> ujson.Str("string")),
        "b" -> ujson.Obj("type" -> ujson.Str("number"))
      )
    )
    val value = ujson.Obj("a" -> ujson.Num(1), "b" -> ujson.Str("x"))

    val result = JsonSchemaValidator.validate(value, schema)
    val errors = result.swap.getOrElse(List.empty)
    errors should have size 2
    errors.exists(_.startsWith("a:")) shouldBe true
    errors.exists(_.startsWith("b:")) shouldBe true
  }

  it should "validate nested properties recursively" in {
    val schema = ujson.Obj(
      "type" -> ujson.Str("object"),
      "properties" -> ujson.Obj(
        "nested" -> ujson.Obj(
          "type" -> ujson.Str("object"),
          "properties" -> ujson.Obj(
            "inner" -> ujson.Obj("type" -> ujson.Str("string"))
          )
        )
      )
    )
    val value = ujson.Obj("nested" -> ujson.Obj("inner" -> ujson.Num(123)))

    val result = JsonSchemaValidator.validate(value, schema)
    result shouldBe a[Left[_, _]]
    val errors = result.swap.getOrElse(List.empty)
    errors.head should include("nested.inner")
  }

  "unknown keywords" should "be silently ignored" in {
    val schema = ujson.Obj(
      "type"                 -> ujson.Str("object"),
      "additionalProperties" -> ujson.Bool(false),
      "minLength"            -> ujson.Num(5)
    )
    val value = ujson.Obj("extra" -> ujson.Num(1))

    JsonSchemaValidator.validate(value, schema) shouldBe Right(())
  }

  "empty schema" should "accept any value" in {
    val schema = ujson.Obj()
    val values = List(
      ujson.Str("string"),
      ujson.Num(42),
      ujson.Bool(true),
      ujson.Null,
      ujson.Arr(),
      ujson.Obj()
    )

    values.foreach(value => JsonSchemaValidator.validate(value, schema) shouldBe Right(()))
  }
}
