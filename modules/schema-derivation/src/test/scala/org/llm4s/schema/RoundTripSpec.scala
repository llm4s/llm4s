package org.llm4s.schema

import org.llm4s.schema.Fixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write, ReadWriter }

import scala.util.Try

/**
 * The derived schema and uPickle's derived `ReadWriter` have to describe the same JSON. Two directions:
 * what uPickle writes satisfies the schema, and a document that satisfies the schema is read into the value
 * the document says. The golden specs pin the schema; these pin that it is the right one.
 */
class RoundTripSpec extends AnyFlatSpec with Matchers {

  /** What uPickle writes for `value` satisfies the schema (relaxed, so an absent `Option` is allowed), and reads back. */
  private def writtenConforms[A: SchemaOf: ReadWriter](value: A): Unit = {
    val json = ujson.read(write(value))
    JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(strict = false), json) shouldBe Nil
    read[A](json) shouldBe value
  }

  /** A document that satisfies the strict schema, which is what a model is held to, reads into `expected`. */
  private def conformingReads[A: SchemaOf: ReadWriter](document: String, expected: A): Unit = {
    val json = ujson.read(document)
    JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(), json) shouldBe Nil
    read[A](json) shouldBe expected
  }

  "What uPickle writes" should "satisfy the schema, and read back, for every supported shape" in {
    writtenConforms(Invoice("Acme", 1250.0, "GBP", "chairs"))
    writtenConforms(Scalars("x", 1, 2L, 1.5, 2.5f, true, BigDecimal("1.5"), BigInt(7)))
    writtenConforms(Nested(Inner(1), List(Inner(2), Inner(3)), Described("l")))
    writtenConforms(Optionals(Some(1), Some(Inner(2)), Some(Color.Green)))
    writtenConforms(Optionals(None, None, None))
    writtenConforms(Collections(List(1, 2), Vector("a"), Seq(true, false), Set(3), List(Some(1), None)))
    writtenConforms(Collections(Nil, Vector.empty, Seq.empty, Set.empty, Nil))
    writtenConforms(WithEnums(Color.Blue, Planet.Mars, List(A, B), Some(Color.Red)))
    writtenConforms(Renamed("ann", 30))
    writtenConforms(Defaults(6, Some("x")))
    writtenConforms(Box(42))
    writtenConforms(Box(List("a", "b")))
  }

  it should "use the case name for an enum and a sealed hierarchy of singletons, which the schema lists" in {
    ujson.read(write(Color.Green)) shouldBe ujson.Str("Green")
    ujson.read(write(Planet.Mars)) shouldBe ujson.Str("Mars")
    ujson.read(write[Letter](A)) shouldBe ujson.Str("A")
    JsonSchemaValidator.validate(SchemaOf[Color].toJsonSchema(), ujson.Str("Green")) shouldBe Nil
    JsonSchemaValidator.validate(SchemaOf[Planet].toJsonSchema(), ujson.Str("Mars")) shouldBe Nil
    JsonSchemaValidator.validate(SchemaOf[Letter].toJsonSchema(), ujson.Str("A")) shouldBe Nil
  }

  it should "write a renamed field under the name the schema gives it" in {
    ujson.read(write(Renamed("ann", 30))).obj.keys.toList shouldBe List("user_name", "age")
    SchemaOf[Renamed].toJsonSchema().obj("properties").obj.keys.toList shouldBe List("user_name", "age")
  }

  "A document that satisfies the schema" should "read into the value it describes" in {
    conformingReads(
      """{"vendor":"Acme","amount":1250,"currency":"GBP","description":"chairs"}""",
      Invoice("Acme", 1250.0, "GBP", "chairs")
    )
    conformingReads(
      """{"s":"x","i":1,"l":2,"d":1.5,"f":2.5,"b":true,"bd":"1.5","bi":"7"}""",
      Scalars("x", 1, 2L, 1.5, 2.5f, true, BigDecimal("1.5"), BigInt(7))
    )
    conformingReads(
      """{"inner":{"n":1},"items":[{"n":2}],"described":{"label":"l"}}""",
      Nested(Inner(1), List(Inner(2)), Described("l"))
    )
    conformingReads(
      """{"collection":null,"a":null,"b":null,"c":null}""".replace(""""collection":null,""", ""),
      Optionals(None, None, None)
    )
    conformingReads("""{"a":3,"b":{"n":4},"c":"Blue"}""", Optionals(Some(3), Some(Inner(4)), Some(Color.Blue)))
    conformingReads(
      """{"list":[1],"vector":["a"],"seq":[true],"set":[2],"options":[1,null]}""",
      Collections(List(1), Vector("a"), Seq(true), Set(2), List(Some(1), None))
    )
    conformingReads(
      """{"color":"Red","planet":"Earth","letters":["A","B"],"maybe":null}""",
      WithEnums(Color.Red, Planet.Earth, List(A, B), None)
    )
    conformingReads("""{"user_name":"ann","age":30}""", Renamed("ann", 30))
    conformingReads("""{"a":7,"b":null}""", Defaults(7, None))
    conformingReads("""{"value":9}""", Box(9))
  }

  it should "read into the defaults when a field with a default is absent, though the strict schema requires it" in {
    val json = ujson.read("{}")
    read[Defaults](json) shouldBe Defaults()
    JsonSchemaValidator.validate(SchemaOf[Defaults].toJsonSchema(), json) should not be empty
  }

  "The validator" should "reject what the schema forbids, and the reader refuses the same documents" in {
    val wrongType = """{"a":"three","b":null,"c":null}"""
    val badEnum   = """{"a":null,"b":null,"c":"Purple"}"""
    for (document <- List(wrongType, badEnum)) {
      JsonSchemaValidator.validate(SchemaOf[Optionals].toJsonSchema(), ujson.read(document)) should not be empty
      Try(read[Optionals](document)).isFailure shouldBe true
    }
    // the reader tolerates these; the schema is what holds a model to them
    val missing = """{"a":null,"b":null}"""
    val extra   = """{"a":null,"b":null,"c":null,"zzz":1}"""
    JsonSchemaValidator.validate(SchemaOf[Optionals].toJsonSchema(), ujson.read(missing)) should not be empty
    JsonSchemaValidator.validate(SchemaOf[Optionals].toJsonSchema(), ujson.read(extra)) should not be empty
  }
}
