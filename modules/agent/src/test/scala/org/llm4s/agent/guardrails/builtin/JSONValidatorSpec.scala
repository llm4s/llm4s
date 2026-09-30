package org.llm4s.agent.guardrails.builtin

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
}
