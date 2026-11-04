package org.llm4s.schema

import org.llm4s.schema.Fixtures.*
import org.llm4s.schema.NamedTupleFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A named tuple's schema (#2328) is the schema of the case class with the same fields: the same properties in the
 * same order, described the same way, with the same `required` list in strict and relaxed mode.
 */
class NamedTupleSchemaSpec extends AnyFlatSpec with Matchers {

  private def sameSchema[T: SchemaOf, C: SchemaOf]: Unit = {
    SchemaOf[T].schema("d") shouldBe SchemaOf[C].schema("d")
    SchemaOf[T].jsonSchema("d", strict = true) shouldBe SchemaOf[C].jsonSchema("d", strict = true)
    SchemaOf[T].jsonSchema("d", strict = false) shouldBe SchemaOf[C].jsonSchema("d", strict = false)
  }

  "A named tuple's schema" should "be the schema of the case class with the same fields" in {
    sameSchema[Review, ReviewClass]
  }

  it should "describe elements of every supported kind as a case class's fields" in {
    sameSchema[Mixed, MixedClass]
  }

  // A list's item is described by its type's name, which is the case class's name or the named tuple as written
  private def withoutDescriptions(json: ujson.Value): ujson.Value = json match {
    case obj: ujson.Obj =>
      ujson.Obj.from(obj.value.collect { case (k, v) if k != "description" => k -> withoutDescriptions(v) })
    case arr: ujson.Arr => ujson.Arr.from(arr.value.map(withoutDescriptions))
    case other          => other
  }

  private def sameShape[T: SchemaOf, C: SchemaOf]: Unit = {
    withoutDescriptions(SchemaOf[T].toJsonSchema()) shouldBe withoutDescriptions(SchemaOf[C].toJsonSchema())
    withoutDescriptions(SchemaOf[T].toJsonSchema(strict = false)) shouldBe
      withoutDescriptions(SchemaOf[C].toJsonSchema(strict = false))
  }

  it should "describe a named tuple nested in a named tuple, a List and an Option as a nested case class" in {
    sameShape[Plot, PlotClass]
    val properties = SchemaOf[Plot].toJsonSchema()("properties")
    properties("point")("description") shouldBe ujson.Str("point")
    properties("points")("items")("description") shouldBe ujson.Str("(x: Int, y: Int)")
    properties("label")("description") shouldBe ujson.Str("label")
  }

  it should "describe a case class field of a named tuple type as a field of the equivalent case class" in {
    sameShape[HoldsTuples, HoldsClasses]
    SchemaOf[HoldsTuples].toJsonSchema()("properties")("review") shouldBe
      SchemaOf[HoldsClasses].toJsonSchema()("properties")("review")
  }

  it should "describe a sum type element as its anyOf, as for a case class field" in {
    SchemaOf[WithSum].jsonSchema("d", strict = true) shouldBe SchemaOf[WithSumClass].jsonSchema("d", strict = true)
    SchemaOf[WithSum].toJsonSchema()("properties")("action").obj.contains("anyOf") shouldBe true
  }

  it should "list every element in strict mode and leave an Option out of required in relaxed mode" in {
    SchemaOf[Review].toJsonSchema()("required") shouldBe ujson.Arr("sentiment", "confidence", "quote")
    SchemaOf[Review].toJsonSchema(strict = false)("required") shouldBe ujson.Arr("sentiment", "confidence")
    SchemaOf[Review].toJsonSchema()("properties")("quote")("type") shouldBe ujson.Arr("string", "null")
  }

  it should "describe an element by its type's @description, else by its name" in {
    val properties = SchemaOf[Mixed].toJsonSchema()("properties")
    properties("described")("description") shouldBe ujson.Str("A described inner type")
    properties("count")("description") shouldBe ujson.Str("count")
    properties("color")("enum") shouldBe ujson.Arr("Red", "Green", "Blue")
  }

  it should "be described as the named tuple type is written" in {
    SchemaOf[(a: Int, b: String)].typeName shouldBe "(a: Int, b: String)"
    SchemaOf[(a: Int, b: String)].toJsonSchema()("description") shouldBe ujson.Str("(a: Int, b: String)")
    SchemaOf[(items: List[(x: Int, y: Option[Color])], n: Long)].typeName shouldBe
      "(items: List[(x: Int, y: Option[Color])], n: Long)"
    SchemaOf[Review].typeName shouldBe "(sentiment: Color, confidence: Double, quote: Option[String])"
  }

  it should "be an object with no properties for the empty named tuple" in {
    SchemaOf[NamedTuple.Empty].toJsonSchema()("properties") shouldBe ujson.Obj()
  }

  it should "be core's ObjectSchema, so it can be a tool's parameters" in {
    SchemaOf[Review].definition shouldBe a[org.llm4s.toolapi.ObjectSchema[?]]
  }
}
