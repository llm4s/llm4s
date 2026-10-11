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

  /** Cases renamed with `@upickle.implicits.key`, which uPickle writes instead of the case name. */
  sealed trait Stage derives SchemaOf, ReadWriter
  @upickle.implicits.key("pending")
  case object Pending extends Stage
  case object Done    extends Stage

  enum Level derives SchemaOf, ReadWriter {
    @upickle.implicits.key("lo") case Low
    case High
  }

  case class Keyed(stage: Stage, level: Level, levels: List[Level], maybe: Option[Stage]) derives SchemaOf, ReadWriter

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

  /** The same generic class nested in itself with different type arguments is finite, not recursive. */
  case class Boxes(twice: Box[Box[Int]], ofInner: Box[Inner]) derives SchemaOf, ReadWriter

  given SchemaOf[java.time.Instant] = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

  case class Event(at: java.time.Instant, @description("when it ends") until: java.time.Instant) derives SchemaOf

  /** A sealed hierarchy with fields, described by a hand-written given, which the derivation takes as it is. */
  sealed trait Payment
  case class Card(last4: String) extends Payment
  case object Cash               extends Payment
  object Payment {
    given SchemaOf[Payment] = SchemaOf.string("the payment method, as text")
  }

  /** A recursive type described by a hand-written given. */
  case class Category(name: String, children: List[Category])
  object Category {
    given SchemaOf[Category] = SchemaOf.string("the category path, for example books/fiction")
  }

  case class Order(id: Int, payment: Payment, category: Category, more: List[Category]) derives SchemaOf

  /** Types with no derived schema: they are only given to the compiler, to see what it says. */
  case class WithMap(counts: Map[String, Int])
  case class Tree(value: Int, children: List[Tree])
  case class Self(next: Option[Self])
  sealed trait Shape
  case class Circle(radius: Double) extends Shape
  case object Dot                   extends Shape
  case class WithShape(shape: Shape)
  case class WithFunction(f: Int => Int)
  case class Node[A](value: A, next: Option[Node[A]])
  case class Grow[A](value: A, next: Option[Grow[List[A]]])
  case class Ping(pong: Option[Pong])
  case class Pong(ping: Ping)
  case class Wrapped(inner: Box[Wrapped])
  sealed trait Outer
  sealed trait Middle extends Outer
  case object Leaf    extends Middle
  case class WithOuter(outer: Outer)
  class NotACaseClass(val x: Int)
  case class WithNestedOption(value: Option[Option[Int]])
  case class WithTuple(pair: (Int, String))

  /** Each level holds the one below three times: 3^12 paths to Wide0, 13 distinct types. */
  case class Wide0(n: Int) derives SchemaOf
  case class Wide1(a: Wide0, b: Wide0, c: List[Wide0]) derives SchemaOf
  case class Wide2(a: Wide1, b: Wide1, c: List[Wide1]) derives SchemaOf
  case class Wide3(a: Wide2, b: Wide2, c: List[Wide2]) derives SchemaOf
  case class Wide4(a: Wide3, b: Wide3, c: List[Wide3]) derives SchemaOf
  case class Wide5(a: Wide4, b: Wide4, c: List[Wide4]) derives SchemaOf
  case class Wide6(a: Wide5, b: Wide5, c: List[Wide5]) derives SchemaOf
  case class Wide7(a: Wide6, b: Wide6, c: List[Wide6]) derives SchemaOf
  case class Wide8(a: Wide7, b: Wide7, c: List[Wide7]) derives SchemaOf
  case class Wide9(a: Wide8, b: Wide8, c: List[Wide8]) derives SchemaOf
  case class Wide10(a: Wide9, b: Wide9, c: List[Wide9]) derives SchemaOf
  case class Wide11(a: Wide10, b: Wide10, c: List[Wide10]) derives SchemaOf
  case class Wide12(a: Wide11, b: Wide11, c: List[Wide11]) derives SchemaOf
}
