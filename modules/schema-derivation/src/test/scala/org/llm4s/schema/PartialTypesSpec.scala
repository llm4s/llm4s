package org.llm4s.schema

// The build compiles with -source:3.3, which turns off field access by name on named tuples; Scala 3.7's default
// source level, which users have, has it.
import scala.language.`3.7`

import org.llm4s.schema.StreamFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The partial type `PartialOf` derives: `A`'s field names, each an `Option` of the field's partial value. */
class PartialTypesSpec extends AnyFlatSpec with Matchers {

  private val invoices = summon[PartialOf[Invoice]]

  "PartialOf[Invoice]" should "have exactly Invoice's field names, in order" in {
    summon[
      NamedTuple.Names[invoices.Out] =:=
        ("vendor", "currency", "items", "status", "note", "totalCents", "tags", "lines")
    ] should not be null
  }

  it should "give each field an Option of its partial value" in {
    type Item = (sku: Option[String], quantity: Option[Int], price: Option[BigDecimal])
    type Expected = (
      vendor: Option[String],
      currency: Option[String],
      items: Option[List[Item]],
      status: Option[Status],
      note: Option[Option[String]],
      totalCents: Option[Long],
      tags: Option[Vector[String]],
      lines: Option[Seq[Int]]
    )
    summon[invoices.Out =:= Expected] should not be null
  }

  it should "let a field be read by name, and refuse a field Invoice does not have" in {
    scala.compiletime.testing.typeCheckErrors("(p: invoices.Out) => p.vendor.getOrElse(\"\")") shouldBe empty
    scala.compiletime.testing.typeCheckErrors("(p: invoices.Out) => p.items.getOrElse(Nil).map(_.sku)") shouldBe empty
    val errors = scala.compiletime.testing.typeCheckErrors("(p: invoices.Out) => p.vendr")
    errors should not be empty
    errors.head.message should include("vendr")
    // the field keeps its Scala name, not the key uPickle writes it under
    scala.compiletime.testing.typeCheckErrors("(p: invoices.Out) => p.total_cents") should not be empty
  }

  it should "not be derived for a type that is not a case class with fields" in {
    val sum = scala.compiletime.testing.typeCheckErrors("PartialOf.derived[Decision]")
    sum.map(_.message).mkString should include("No PartialOf")
    scala.compiletime.testing.typeCheckErrors("PartialOf.derived[Status]").map(_.message).mkString should include(
      "not one"
    )
    scala.compiletime.testing.typeCheckErrors("summon[PartialOf[String]]") should not be empty
  }

  it should "need a uPickle reader for a field type with no partial value" in {
    scala.compiletime.testing.typeCheckErrors("PartialOf.derived[Holder]").map(_.message).mkString should include(
      "has no uPickle Reader"
    )
  }

  it should "read a case class whole inside itself, so that a recursive type terminates" in {
    // SchemaOf refuses a recursive type; PartialOf still terminates on one
    val trees = summon[PartialOf[Tree]]
    type Expected = (label: Option[String], children: Option[List[Tree]])
    summon[trees.Out =:= Expected] should not be null
  }
}
