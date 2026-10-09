package org.llm4s.schema

import upickle.default.ReadWriter

/** The types the specs derive schemas for, each with the `ReadWriter` the schema has to agree with. */
object Fixtures {

  @description("An invoice extracted from text")
  case class Invoice(
    @description("Name of the vendor or supplier") vendor: String,
    @description("Total invoice amount as a decimal number") amount: Double,
    @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String,
    @description("Brief description of goods or services") description: String
  ) derives SchemaOf,
        ReadWriter

  case class Scalars(s: String, i: Int, l: Long, d: Double, f: Float, b: Boolean, bd: BigDecimal, bi: BigInt)
      derives SchemaOf,
        ReadWriter

  case class Inner(n: Int) derives SchemaOf, ReadWriter

  @description("A described inner type")
  case class Described(@description("the label") label: String) derives SchemaOf, ReadWriter

  case class Nested(inner: Inner, items: List[Inner], described: Described) derives SchemaOf, ReadWriter

  enum Color derives SchemaOf, ReadWriter {
    case Red, Green, Blue
  }

  enum Planet(val mass: Double) derives SchemaOf, ReadWriter {
    case Earth extends Planet(1.0)
    case Mars  extends Planet(0.1)
  }

  sealed trait Letter derives SchemaOf, ReadWriter
  case object A extends Letter
  case object B extends Letter

  case class Optionals(a: Option[Int], b: Option[Inner], c: Option[Color]) derives SchemaOf, ReadWriter

  case class Collections(
    list: List[Int],
    vector: Vector[String],
    seq: Seq[Boolean],
    set: Set[Int],
    options: List[Option[Int]]
  ) derives SchemaOf,
        ReadWriter

  case class WithEnums(color: Color, planet: Planet, letters: List[Letter], maybe: Option[Color])
      derives SchemaOf,
        ReadWriter

  case class Renamed(@upickle.implicits.key("user_name") userName: String, age: Int) derives SchemaOf, ReadWriter

  case class Defaults(a: Int = 5, b: Option[String] = None) derives SchemaOf, ReadWriter

  case class Box[T](value: T) derives SchemaOf, ReadWriter

  given SchemaOf[java.time.Instant] = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

  case class Event(at: java.time.Instant, @description("when it ends") until: java.time.Instant) derives SchemaOf

  /** Types with no derived schema: they are only given to the compiler, to see what it says. */
  case class WithMap(counts: Map[String, Int])
  case class Tree(value: Int, children: List[Tree])
  case class Self(next: Option[Self])
  sealed trait Shape
  case class Circle(radius: Double) extends Shape
  case object Dot                   extends Shape
  case class WithShape(shape: Shape)
  case class WithFunction(f: Int => Int)
  class NotACaseClass(val x: Int)
}
