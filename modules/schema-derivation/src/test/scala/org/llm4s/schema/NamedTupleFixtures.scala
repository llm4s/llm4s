package org.llm4s.schema

import org.llm4s.schema.Fixtures.*
import upickle.default.ReadWriter

import scala.annotation.nowarn

/**
 * Named tuples (#2328), each beside the case class with the same fields in the same order, whose schema and uPickle
 * encoding the named tuple's must equal.
 */
// `derives ReadWriter` on a case class with a named tuple field uses the given of `namedtuples` (it does not compile
// without it), but -Wunused reports the import as unused, since it is only used inside uPickle's derivation macro.
@nowarn("msg=unused import")
object NamedTupleFixtures {
  import org.llm4s.schema.namedtuples.given

  type Review = (sentiment: Color, confidence: Double, quote: Option[String])
  case class ReviewClass(sentiment: Color, confidence: Double, quote: Option[String]) derives SchemaOf, ReadWriter

  /** Elements of every kind: scalars, a case class, an enum, a described type, `Option` and collections. */
  type Mixed = (
    name: String,
    count: Int,
    big: Long,
    ratio: Double,
    flag: Boolean,
    amount: BigDecimal,
    inner: Inner,
    color: Color,
    described: Described,
    items: List[Inner],
    tags: Vector[String],
    maybe: Option[Color],
    maybeInner: Option[Inner],
    options: List[Option[Int]]
  )
  case class MixedClass(
    name: String,
    count: Int,
    big: Long,
    ratio: Double,
    flag: Boolean,
    amount: BigDecimal,
    inner: Inner,
    color: Color,
    described: Described,
    items: List[Inner],
    tags: Vector[String],
    maybe: Option[Color],
    maybeInner: Option[Inner],
    options: List[Option[Int]]
  ) derives SchemaOf,
        ReadWriter

  /** A named tuple inside a named tuple, a list and an option of one. */
  type Plot = (title: String, point: (x: Int, y: Int), points: List[(x: Int, y: Int)], label: Option[(text: String)])
  case class Point(x: Int, y: Int) derives SchemaOf, ReadWriter
  case class Label(text: String) derives SchemaOf, ReadWriter
  case class PlotClass(title: String, point: Point, points: List[Point], label: Option[Label])
      derives SchemaOf,
        ReadWriter

  /** Named tuples as case class fields. */
  case class HoldsTuples(review: Review, points: List[(x: Int, y: Int)], maybe: Option[(text: String)])
      derives SchemaOf,
        ReadWriter
  case class HoldsClasses(review: ReviewClass, points: List[Point], maybe: Option[Label]) derives SchemaOf, ReadWriter

  /** A sum type with fields as an element. */
  type WithSum = (action: SumFixtures.Action, note: String)
  case class WithSumClass(action: SumFixtures.Action, note: String) derives SchemaOf, ReadWriter

  /** A tool's arguments and result. */
  type ForecastArgs = (city: String, days: Int, units: Option[String])
  type Forecast     = (high: Double, low: Double)
  case class ForecastArgsClass(city: String, days: Int, units: Option[String]) derives SchemaOf, ReadWriter
}
