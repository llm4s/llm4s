package org.llm4s.schema

import org.llm4s.schema.SumFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write, ReadWriter }

import scala.util.Try

/**
 * As [[RoundTripSpec]], for sum types with fields (#2327): what uPickle writes for every case satisfies the derived
 * `anyOf`, and every document the schema allows is read back by uPickle into a value that it writes again as the
 * same document.
 */
class SumRoundTripSpec extends AnyFlatSpec with Matchers {

  private def writtenConforms[A: SchemaOf: ReadWriter](value: A): Unit = {
    val json = ujson.read(write(value))
    withClue(s"${ujson.write(json)}: ") {
      JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(strict = false), json) shouldBe Nil
      JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(), json) shouldBe Nil
    }
    read[A](json) shouldBe value
  }

  private val actions: List[Action] =
    List(Action.Refund(BigDecimal("12.50"), "late"), Action.Escalate(Team.Billing), Action.Reply("hello"))
  private val shapes: List[Shape] = List(Circle(1.5), Rectangle(2, 3), Dot, Blank())
  private val events: List[Event] =
    List(Click(1, 2), Typed("hi", Some(7L), List("a")), Typed("", None, Nil), Idle)
  private val payments: List[Payment] = List(
    Payment.Card("4242", Money(10, "EUR")),
    Payment.Voucher("V1", Some(Action.Escalate(Team.Shipping))),
    Payment.Voucher("V2", None),
    Payment.Free
  )

  "What uPickle writes for every case" should "satisfy the derived anyOf, and read back" in {
    actions.foreach(writtenConforms(_))
    shapes.foreach(writtenConforms(_))
    events.foreach(writtenConforms(_))
    payments.foreach(writtenConforms(_))
    writtenConforms(Drawing("d", shapes, Some(Rectangle(1, 1)), Click(0, 0)))
    writtenConforms(Drawing("d", Nil, None, Idle))
    writtenConforms[Option[Shape]](Some(Circle(2)))
    writtenConforms[List[Action]](actions)
  }

  "Every document the schema allows" should "be read by uPickle into a value written as the same document" in {
    def allRead[A: SchemaOf: ReadWriter]: Unit = {
      val schema  = SchemaOf[A].toJsonSchema()
      val samples = SchemaSamples.of(schema)
      samples should not be empty
      for (sample <- samples)
        withClue(s"${ujson.write(sample)} against ${ujson.write(schema)}: ") {
          JsonSchemaValidator.validate(schema, sample) shouldBe Nil
          val value = Try(read[A](sample)).toEither.left.map(_.toString)
          value shouldBe a[Right[?, ?]]
          // what was read is written again as a document the schema allows
          value.foreach(v => JsonSchemaValidator.validate(schema, ujson.read(write(v))) shouldBe Nil)
        }
    }
    allRead[Action]
    allRead[Shape]
    allRead[Event]
    allRead[Payment]
    allRead[Drawing]
    allRead[Option[Action]]
  }

  it should "reach every case of each sum type" in {
    def tags[A: SchemaOf](discriminator: String): Set[String] =
      SchemaSamples
        .of(SchemaOf[A].toJsonSchema())
        .map {
          case ujson.Str(tag) => tag
          case obj            => obj(discriminator).str
        }
        .toSet
    tags[Action]("$type") shouldBe Set("Refund", "Escalate", "Reply")
    tags[Shape]("$type") shouldBe Set("Circle", "rect", "Dot", "Blank")
    tags[Event]("kind") shouldBe Set("click", "Typed", "idle")
    tags[Payment]("$type") shouldBe Set("Card", "Voucher", "Free")
  }

  "The validator" should "reject a case under the wrong tag, or with another case's fields, which uPickle refuses too" in {
    val schema = SchemaOf[Shape].toJsonSchema()
    val wrong = List(
      """{"$type":"Circle","width":1,"h":2}""",
      """{"$type":"Rectangle","width":1,"h":2}""",
      """{"$type":"Square","side":1}""",
      """"Circle""""
    )
    for (document <- wrong) withClue(document) {
      JsonSchemaValidator.validate(schema, ujson.read(document)) should not be empty
      Try(read[Shape](document)).isFailure shouldBe true
    }
    // the discriminator is required, though uPickle would also take it in any position
    JsonSchemaValidator.validate(schema, ujson.read("""{"radius":1}""")) should not be empty
  }
}
