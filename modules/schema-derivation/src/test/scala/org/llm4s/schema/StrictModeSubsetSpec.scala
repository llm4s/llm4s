package org.llm4s.schema

import org.llm4s.schema.SumFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What `completeStructuredOf` sends for a sum type, and for a case class that holds one, stays inside the subset
 * of JSON Schema that OpenAI's structured outputs accept in strict mode
 * (https://platform.openai.com/docs/guides/structured-outputs#supported-schemas): the root is an object and not an
 * `anyOf`, every object sets `additionalProperties: false` and lists every property as required, and only the
 * supported keywords appear.
 */
class StrictModeSubsetSpec extends AnyFlatSpec with Matchers {

  private val Supported =
    Set("type", "description", "properties", "required", "additionalProperties", "items", "enum", "anyOf")

  /** The departures from the strict-mode subset, each with its path; empty when there are none. */
  private def violations(schema: ujson.Value, path: String = "$"): List[String] = {
    val obj      = schema.obj
    val keywords = obj.keySet.toList.filterNot(Supported).map(k => s"$path: unsupported keyword '$k'")
    val objectRules =
      if (obj.get("type").contains(ujson.Str("object"))) {
        val properties = obj.get("properties").map(_.obj.keySet.toSet).getOrElse(Set.empty)
        val required   = obj.get("required").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
        List(
          Option.when(!obj.get("additionalProperties").contains(ujson.False))(
            s"$path: an object without additionalProperties: false"
          ),
          Option.when(required != properties)(s"$path: required ${required.toList.sorted} is not every property")
        ).flatten
      } else Nil
    val nested =
      obj.get("properties").toList.flatMap(_.obj.toList.flatMap { case (k, v) => violations(v, s"$path.$k") }) ++
        obj.get("items").toList.flatMap(violations(_, s"$path[]")) ++
        obj
          .get("anyOf")
          .toList
          .flatMap(_.arr.toList.zipWithIndex.flatMap { case (v, i) =>
            violations(v, s"$path.anyOf[$i]")
          })
    keywords ++ objectRules ++ nested
  }

  private def rootRules(schema: ujson.Value): List[String] =
    List(
      Option.when(schema.obj.contains("anyOf"))("the root is an anyOf"),
      Option.when(!schema.obj.get("type").contains(ujson.Str("object")))("the root is not an object")
    ).flatten

  private def sent[A: SchemaOf]: ujson.Value = {
    val schema = SchemaOf[A].toJsonSchema()
    if (schema.obj.contains("anyOf")) StructuredOutput.wrap(schema) else schema
  }

  "The schema sent for a sum type" should "be in OpenAI's strict-mode subset, with the anyOf below the root" in {
    for (schema <- List(sent[Action], sent[Shape], sent[Event], sent[Payment], sent[Option[Action]]))
      withClue(ujson.write(schema)) {
        rootRules(schema) shouldBe Nil
        violations(schema) shouldBe Nil
        schema("properties")(StructuredOutput.WrapperKey).obj.contains("anyOf") shouldBe true
      }
  }

  it should "be in the subset for a case class holding sum types, which is sent unwrapped" in {
    val schema = sent[Drawing]
    rootRules(schema) shouldBe Nil
    violations(schema) shouldBe Nil
    schema.obj should not contain key("anyOf")
  }

  "The check" should "catch what strict mode refuses" in {
    rootRules(SchemaOf[Action].toJsonSchema()) should contain("the root is an anyOf")
    violations(
      ujson.Obj("type" -> "object", "properties" -> ujson.Obj(), "required" -> ujson.Arr())
    ) should not be empty
    violations(ujson.Obj("type" -> "string", "const" -> "x")) should not be empty
    violations(SchemaOf[Drawing].toJsonSchema(strict = false)) should not be empty
  }
}
