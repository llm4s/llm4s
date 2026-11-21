package org.llm4s.schema.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.llm4s.schema.*
import upickle.default.ReadWriter

/** The refined types the specs describe, decode and refuse. */
object IronFixtures {

  @description("A product review")
  final case class Review(
    @description("Short headline") title: String :| (Not[Blank] & MaxLength[80]),
    @description("Stars") rating: Int :| Interval.Closed[1, 5],
    tags: List[String] :| (MinLength[1] & MaxLength[10])
  ) derives SchemaOf,
        ReadWriter

  final case class Numbers(
    positive: Int :| Positive,
    positive0: Long :| Positive0,
    negative: Int :| Negative,
    negative0: Int :| Negative0,
    greater: Int :| Greater[10],
    greaterEqual: Int :| GreaterEqual[10],
    less: Int :| Less[10],
    lessEqual: Int :| LessEqual[10],
    exactly: Int :| StrictEqual[7],
    open: Double :| Interval.Open[0.0, 1.0],
    openClosed: Double :| Interval.OpenClosed[0, 1],
    closedOpen: Float :| Interval.ClosedOpen[0, 1],
    closed: Double :| Interval.Closed[-1.5, 1.5],
    above: Double :| Greater[0.5],
    wholeAbove: Int :| Greater[0.5],
    tightest: Int :| (Greater[0] & GreaterEqual[3] & Less[100] & LessEqual[50])
  ) derives SchemaOf,
        ReadWriter

  final case class Lengths(
    name: String :| MinLength[2],
    code: String :| FixedLength[3],
    note: String :| Not[Empty],
    short: String :| MaxLength[5],
    items: Vector[Int] :| Not[Empty],
    pair: Seq[String] :| FixedLength[2],
    unique: Set[String] :| MaxLength[3]
  ) derives SchemaOf,
        ReadWriter

  final case class Wrapped(
    maybe: Option[Int :| Positive],
    each: List[String :| Not[Blank]]
  ) derives SchemaOf,
        ReadWriter

  @description("A booking")
  final case class Booking(
    @description("Guest name") guest: String :| Not[Blank],
    @description("Nights to stay") nights: Int :| Interval.Closed[1, 14]
  ) derives SchemaOf,
        ReadWriter

  final case class Confirmation(id: String) derives ReadWriter

  enum Shape derives ReadWriter, SchemaOf {
    case Circle(radius: Double)
    case Square(side: Double)
  }

  // A list of a sum type, which core's schema model cannot express: its bounds are written into the JSON Schema
  final case class Drawing(shapes: List[Shape] :| MaxLength[3]) derives SchemaOf, ReadWriter
}
