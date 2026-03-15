package org.llm4s.eval.dataset

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class JsonSchemaValidatorPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val genAnyValue: Gen[ujson.Value] = Gen.oneOf(
    Gen.alphaStr.map(ujson.Str(_)),
    Gen.choose(-1000L, 1000L).map(n => ujson.Num(n.toDouble)),
    Gen.oneOf(true, false).map(ujson.Bool(_)),
    Gen.const(ujson.Null),
    Gen.const(ujson.Arr()),
    Gen.const(ujson.Obj())
  )

  // Each entry: (typeName, matching generator, mismatching generator)
  val typeEntries: Seq[(String, Gen[ujson.Value], Gen[ujson.Value])] = Seq(
    ("string", Gen.alphaStr.map(ujson.Str(_)), Gen.choose(-100L, 100L).map(n => ujson.Num(n.toDouble))),
    (
      "number",
      Gen.choose(-100L, 100L).map(n => ujson.Num(n.toDouble)),
      Gen.alphaStr.suchThat(_.nonEmpty).map(ujson.Str(_))
    ),
    ("boolean", Gen.oneOf(true, false).map(ujson.Bool(_)), Gen.alphaStr.suchThat(_.nonEmpty).map(ujson.Str(_))),
    ("null", Gen.const(ujson.Null), Gen.alphaStr.suchThat(_.nonEmpty).map(ujson.Str(_))),
    ("object", Gen.const(ujson.Obj()), Gen.alphaStr.suchThat(_.nonEmpty).map(ujson.Str(_))),
    ("array", Gen.const(ujson.Arr()), Gen.alphaStr.suchThat(_.nonEmpty).map(ujson.Str(_)))
  )

  // ---- empty schema always passes ----

  "JsonSchemaValidator.validate" should "return Right(()) for any value when schema is empty {}" in {
    forAll(genAnyValue)(value => JsonSchemaValidator.validate(value, ujson.Obj()) shouldBe Right(()))
  }

  // ---- type keyword: matching type → Right ----

  "JsonSchemaValidator type keyword" should "accept values of the declared type" in {
    for ((typeName, matching, _) <- typeEntries)
      forAll(matching) { value =>
        val schema = ujson.Obj("type" -> ujson.Str(typeName))
        JsonSchemaValidator.validate(value, schema) shouldBe Right(())
      }
  }

  it should "reject values of a different type with exactly one error" in {
    for ((typeName, _, mismatching) <- typeEntries)
      forAll(mismatching) { value =>
        val schema = ujson.Obj("type" -> ujson.Str(typeName))
        val result = JsonSchemaValidator.validate(value, schema)
        result.isLeft shouldBe true
        result.swap.getOrElse(Nil) should have size 1
      }
  }

  it should "produce an error message that mentions the expected type name" in {
    for ((typeName, _, mismatching) <- typeEntries)
      forAll(mismatching) { value =>
        val schema = ujson.Obj("type" -> ujson.Str(typeName))
        val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
        errors.head should include(typeName)
      }
  }

  it should "silently skip unknown type strings without producing errors" in {
    forAll(genAnyValue, genNonEmptyString) { (value, unknownType) =>
      // Prefix with "x-" to guarantee it's not a real JSON type keyword
      val schema = ujson.Obj("type" -> ujson.Str(s"x-$unknownType"))
      JsonSchemaValidator.validate(value, schema) shouldBe Right(())
    }
  }

  // ---- jsonTypeName: all JSON value types appear in error messages ----

  "JsonSchemaValidator" should "report 'object' in the error message when the actual value is a JSON object" in {
    forAll(genNonEmptyString) { key =>
      val value  = ujson.Obj(key -> ujson.Str("v"))
      val schema = ujson.Obj("type" -> ujson.Str("string"))
      val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
      errors should have size 1
      errors.head should include("object")
    }
  }

  it should "report 'array' in the error message when the actual value is a JSON array" in {
    val value  = ujson.Arr(ujson.Str("a"))
    val schema = ujson.Obj("type" -> ujson.Str("string"))
    val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
    errors should have size 1
    errors.head should include("array")
  }

  it should "report 'boolean' in the error message when the actual value is a JSON boolean" in {
    forAll(Gen.oneOf(true, false)) { b =>
      val value  = ujson.Bool(b)
      val schema = ujson.Obj("type" -> ujson.Str("string"))
      val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
      errors should have size 1
      errors.head should include("boolean")
    }
  }

  it should "report 'null' in the error message when the actual value is JSON null" in {
    val value  = ujson.Null
    val schema = ujson.Obj("type" -> ujson.Str("string"))
    val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
    errors should have size 1
    errors.head should include("null")
  }

  // ---- required keyword ----

  "JsonSchemaValidator required keyword" should "return Right when all required keys are present in the object" in {
    forAll(Gen.nonEmptyListOf(genNonEmptyString).map(_.distinct)) { keys =>
      val value  = ujson.Obj.from(keys.map(k => k -> ujson.Str("v")))
      val schema = ujson.Obj("required" -> ujson.Arr.from(keys.map(ujson.Str.apply)))
      JsonSchemaValidator.validate(value, schema) shouldBe Right(())
    }
  }

  it should "return Left with one error per missing required field" in {
    forAll(Gen.nonEmptyListOf(genNonEmptyString).map(_.distinct)) { missingKeys =>
      val value  = ujson.Obj() // all keys absent
      val schema = ujson.Obj("required" -> ujson.Arr.from(missingKeys.map(ujson.Str.apply)))
      val result = JsonSchemaValidator.validate(value, schema)
      result.isLeft shouldBe true
      val errors = result.swap.getOrElse(Nil)
      errors.size shouldBe missingKeys.size
    }
  }

  it should "mention each missing field name in the error messages" in {
    forAll(Gen.nonEmptyListOf(genNonEmptyString).map(_.distinct)) { missingKeys =>
      val value  = ujson.Obj()
      val schema = ujson.Obj("required" -> ujson.Arr.from(missingKeys.map(ujson.Str.apply)))
      val errors = JsonSchemaValidator.validate(value, schema).swap.getOrElse(Nil)
      missingKeys.foreach(key => errors.exists(_.contains(key)) shouldBe true)
    }
  }

  it should "prefix missing-field errors with the parent property path when validating nested objects" in {
    forAll(genNonEmptyString, genNonEmptyString) { (propName, requiredField) =>
      // schema: { properties: { propName: { required: [requiredField] } } }
      // value:  { propName: {} }  ← empty nested object; requiredField is missing
      val value = ujson.Obj(propName -> ujson.Obj())
      val schema = ujson.Obj(
        "properties" -> ujson.Obj(
          propName -> ujson.Obj("required" -> ujson.Arr(ujson.Str(requiredField)))
        )
      )
      val result = JsonSchemaValidator.validate(value, schema)
      result.isLeft shouldBe true
      val errors = result.swap.getOrElse(Nil)
      // Each error message must start with the parent property name prefix
      errors.foreach(err => err should startWith(propName))
    }
  }

  // ---- properties keyword: error path includes property name ----

  "JsonSchemaValidator properties keyword" should "prefix errors with the property key name" in {
    forAll(genNonEmptyString) { propName =>
      val value  = ujson.Obj(propName -> ujson.Str("a string"))
      val schema = ujson.Obj("properties" -> ujson.Obj(propName -> ujson.Obj("type" -> ujson.Str("number"))))
      val result = JsonSchemaValidator.validate(value, schema)
      result.isLeft shouldBe true
      val errors = result.swap.getOrElse(Nil)
      errors.foreach(err => err should startWith(propName))
    }
  }

  it should "produce one error per failing property" in {
    forAll(Gen.choose(1, 5)) { n =>
      // Create n properties each with a type mismatch (string value, number schema)
      val propNames = (0 until n).map(i => s"prop$i").toList
      val value     = ujson.Obj.from(propNames.map(k => k -> ujson.Str("not-a-number")))
      val schema = ujson.Obj(
        "properties" -> ujson.Obj.from(
          propNames.map(k => k -> ujson.Obj("type" -> ujson.Str("number")))
        )
      )
      val result = JsonSchemaValidator.validate(value, schema)
      result.isLeft shouldBe true
      result.swap.getOrElse(Nil) should have size n.toLong
    }
  }

  // ---- error accumulation ----

  "JsonSchemaValidator" should "accumulate errors from both properties and required checks" in {
    forAll(Gen.nonEmptyListOf(genNonEmptyString).map(_.distinct)) { missingKeys =>
      // Object has a property with wrong type AND is missing required fields
      val propName = "score"
      val reqKeys  = missingKeys.filterNot(_ == propName)
      if (reqKeys.nonEmpty) {
        val value = ujson.Obj(propName -> ujson.Str("not-a-number"))
        val schema = ujson.Obj(
          "properties" -> ujson.Obj(propName -> ujson.Obj("type" -> ujson.Str("number"))),
          "required"   -> ujson.Arr.from(reqKeys.map(ujson.Str.apply))
        )
        val result = JsonSchemaValidator.validate(value, schema)
        result.isLeft shouldBe true
        // Expect: 1 type error for propName + 1 error per missing required key
        result.swap.getOrElse(Nil).size should be >= (1 + reqKeys.size)
      }
    }
  }

  // ---- unknown keywords produce no errors ----

  "JsonSchemaValidator" should "produce no errors for a schema with only unknown keywords" in {
    forAll(genAnyValue, genNonEmptyString) { (value, keyword) =>
      val schema = ujson.Obj(s"x-$keyword" -> ujson.Str("ignored"))
      JsonSchemaValidator.validate(value, schema) shouldBe Right(())
    }
  }

  it should "ignore standard-but-unsupported JSON Schema keywords such as description and title" in {
    forAll(genAnyValue) { value =>
      val schema = ujson.Obj(
        "description" -> ujson.Str("ignored"),
        "title"       -> ujson.Str("ignored"),
        "examples"    -> ujson.Arr()
      )
      JsonSchemaValidator.validate(value, schema) shouldBe Right(())
    }
  }
}
