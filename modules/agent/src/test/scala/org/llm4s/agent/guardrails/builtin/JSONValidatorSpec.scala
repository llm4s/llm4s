package org.llm4s.agent.guardrails.builtin

import org.llm4s.testutil.SmallStack
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ujson.Obj

class JSONValidatorSpec extends AnyFunSuite with Matchers {

  test("valid JSON without schema passes") {
    val validator = JSONValidator()
    validator.validate("""{"a":1}""").isRight shouldBe true
  }

  test("invalid JSON string fails") {
    val validator = JSONValidator()
    validator.validate("""{a:1}""").isLeft shouldBe true
  }

  test("valid JSON satisfies required fields") {
    val schema    = Obj("required" -> ujson.Arr("name", "age"))
    val validator = JSONValidator.withSchema(schema)
    validator.validate("""{"name":"bob","age":20}""").isRight shouldBe true
  }

  test("missing fields fails validation") {
    val schema    = Obj("required" -> ujson.Arr("name", "age"))
    val validator = JSONValidator.withSchema(schema)

    val result = validator.validate("""{"name":"bob"}""")

    result.isLeft shouldBe true

    val message = result match {
      case Left(err) => err.formatted
      case Right(_)  => ""
    }

    message should include("Missing required JSON fields")
  }

  test("required field check fails if root is not an object") {
    val schema    = Obj("required" -> ujson.Arr("name"))
    val validator = JSONValidator.withSchema(schema)

    val result = validator.validate("""["not", "an", "object"]""")
    result.isLeft shouldBe true

    val message = result.left.toOption.map(_.formatted).getOrElse("")
    message should include("Schema requires an object")
    message should include("non-object value")
  }

  test("empty required array passes any object") {
    val schema    = Obj("required" -> ujson.Arr())
    val validator = JSONValidator.withSchema(schema)
    validator.validate("""{}""").isRight shouldBe true
  }

  test("schema without required field passes any valid JSON") {
    val schema    = Obj("type" -> "object")
    val validator = JSONValidator.withSchema(schema)
    validator.validate("""{"anything": "goes"}""").isRight shouldBe true
  }

  // --- property types ---

  /** A validator whose schema declares one property, `f`, with the given `type` (raw JSON). */
  private def schemaWith(typeJson: String): JSONValidator =
    JSONValidator.withSchema(ujson.read(s"""{"properties":{"f":{"type":$typeJson}}}"""))

  private def errorOf(result: Result[String]): String =
    result.left.toOption.map(_.formatted).getOrElse("")

  Seq(
    "string"  -> "\"x\"",
    "number"  -> "1.5",
    "number"  -> "3",
    "integer" -> "3",
    "integer" -> "3.0",
    "boolean" -> "true",
    "boolean" -> "false",
    "object"  -> "{}",
    "array"   -> "[]",
    "null"    -> "null"
  ).foreach { case (declared, json) =>
    test(s"type '$declared' accepts $json") {
      val doc = s"""{"f":$json}"""
      schemaWith(s""""$declared"""").validate(doc) shouldBe Right(doc)
    }
  }

  Seq(
    ("string", "1", "number"),
    ("string", "null", "null"),
    ("string", "true", "boolean"),
    ("number", "\"1\"", "string"),
    ("integer", "1.5", "number"),
    ("integer", "\"3\"", "string"),
    ("boolean", "\"true\"", "string"),
    ("object", "[]", "array"),
    ("array", "{}", "object"),
    ("null", "0", "number")
  ).foreach { case (declared, json, actual) =>
    test(s"type '$declared' rejects $json, naming '$actual'") {
      val result = schemaWith(s""""$declared"""").validate(s"""{"f":$json}""")
      errorOf(result) should include(s"Field 'f' has type '$actual', expected '$declared'")
    }
  }

  test("the type mismatch reads as the issue describes it") {
    val schema    = ujson.read("""{"required":["name"],"properties":{"name":{"type":"string"}}}""")
    val validator = JSONValidator.withSchema(schema)

    validator.validate("""{"name":"John"}""").isRight shouldBe true
    errorOf(validator.validate("""{"name":123}""")) should include(
      "Field 'name' has type 'number', expected 'string'"
    )
  }

  test("a list of types accepts any of them and names all of them when none matches") {
    val validator = schemaWith("""["string","null"]""")

    validator.validate("""{"f":"x"}""").isRight shouldBe true
    validator.validate("""{"f":null}""").isRight shouldBe true
    errorOf(validator.validate("""{"f":1}""")) should include("has type 'number', expected 'string' or 'null'")
  }

  test("a property the document does not contain is not checked") {
    schemaWith(""""string"""").validate("""{"other":1}""").isRight shouldBe true
  }

  test("a missing required field is reported before any type mismatch") {
    val schema    = ujson.read("""{"required":["a"],"properties":{"b":{"type":"string"}}}""")
    val validator = JSONValidator.withSchema(schema)

    val message = errorOf(validator.validate("""{"b":1}"""))
    message should include("Missing required JSON fields: a")
    (message should not).include("has type")
  }

  test("every mismatch is reported, in the order the schema lists the properties") {
    // Six properties, past the size at which an immutable Map stops keeping insertion order.
    val names  = Seq("zeta", "alpha", "mu", "beta", "omega", "gamma")
    val schema = ujson.read(names.map(n => s""""$n":{"type":"string"}""").mkString("""{"properties":{""", ",", "}}"))
    val doc    = names.map(n => s""""$n":1""").mkString("{", ",", "}")

    val message = errorOf(JSONValidator.withSchema(schema).validate(doc))

    val positions = names.map(n => message.indexOf(s"Field '$n'"))
    all(positions) should be >= 0
    positions shouldBe positions.sorted
  }

  test("a type this validator does not know is not enforced") {
    schemaWith(""""decimal"""").validate("""{"f":"anything"}""").isRight shouldBe true
    schemaWith("""["string","decimal"]""").validate("""{"f":1}""").isRight shouldBe true
    schemaWith("""[]""").validate("""{"f":1}""").isRight shouldBe true
    schemaWith("""5""").validate("""{"f":1}""").isRight shouldBe true
  }

  test("a property that declares no type, or is not an object, is not enforced") {
    val schema    = ujson.read("""{"properties":{"a":{"description":"free"},"b":true}}""")
    val validator = JSONValidator.withSchema(schema)

    validator.validate("""{"a":1,"b":"x"}""").isRight shouldBe true
  }

  test("properties alone do not reject a value that is not an object") {
    val validator = schemaWith(""""string"""")

    validator.validate("""[1,2]""").isRight shouldBe true
    validator.validate(""""text"""").isRight shouldBe true
  }

  test("required still rejects a value that is not an object when properties are also present") {
    val schema    = ujson.read("""{"required":["f"],"properties":{"f":{"type":"string"}}}""")
    val validator = JSONValidator.withSchema(schema)

    errorOf(validator.validate("""[1]""")) should include("Schema requires an object")
  }

  test("only top-level properties are checked") {
    val schema = ujson.read(
      """{"properties":{"a":{"type":"object","properties":{"b":{"type":"string"}}}}}"""
    )
    JSONValidator.withSchema(schema).validate("""{"a":{"b":1}}""").isRight shouldBe true
  }

  // The output is model text: nested too deeply it is rejected like any other non-JSON, before it
  // becomes a value that overflows the stack of whatever traverses it next (#1562). On a 1 MB stack,
  // so deterministic.
  test("output nested too deeply is rejected as not valid JSON instead of being parsed") {
    Seq("[" * 100000 + "]" * 100000, "{\"a\":" * 100000 + "1" + "}" * 100000).foreach { deep =>
      withClue(deep.take(8) + ": ") {
        SmallStack.run(JSONValidator().validate(deep)) match {
          case Right(Left(e)) =>
            e.message should include("not valid JSON")
            e.message should include("512")
          case other => fail(s"expected Right(Left(error)), got ${other.toString.take(200)}")
        }
      }
    }
  }

  test("output nested 512 levels deep is valid JSON, 513 is not") {
    SmallStack.run(JSONValidator().validate("[" * 512 + "]" * 512)).map(_.isRight) shouldBe Right(true)
    SmallStack.run(JSONValidator().validate("[" * 513 + "]" * 513)).map(_.isLeft) shouldBe Right(true)
  }
}
