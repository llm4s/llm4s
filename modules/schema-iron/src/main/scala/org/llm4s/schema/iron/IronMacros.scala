package org.llm4s.schema.iron

import io.github.iltotore.iron.constraint.any.{ DescribedAs, Not, StrictEqual, True }
import io.github.iltotore.iron.constraint.char.Whitespace
import io.github.iltotore.iron.constraint.collection.{ ForAll, Length }
import io.github.iltotore.iron.constraint.numeric.{ Greater, Less }

import scala.quoted.*

/**
 * Works out, at compile time, the JSON Schema bounds an Iron constraint stands for, and refuses one it cannot
 * describe with a message naming it. Iron's named constraints are aliases of a few building blocks
 * (`GreaterEqual[V]` is `DescribedAs[Greater[V] | StrictEqual[V], ...]`, `MaxLength[V]` is
 * `DescribedAs[Length[LessEqual[V]], ...]`), so the walk follows the aliases down to those blocks.
 */
private[iron] object IronMacros {

  /** The bounds `C` puts on `A`. */
  inline def bounds[A, C]: Bounds = ${ boundsImpl[A, C] }

  private enum Kind {
    case Integer(min: Double, max: Double) // the type's own range: a bound beyond it says nothing
    case Decimal
    case Text
    case Items
  }

  // A bound as worked out, before it is written: inclusive or not.
  final private case class Range(
    lower: Option[(BigDecimal, Boolean)], // (value, inclusive)
    upper: Option[(BigDecimal, Boolean)]
  ) {
    def and(other: Range): Range =
      Range(tighter(lower, other.lower, low = true), tighter(upper, other.upper, low = false))
  }

  private val Unbounded = Range(None, None)

  // Of two lower (or upper) bounds, the one that admits less; at the same value, the exclusive one
  private def tighter(
    a: Option[(BigDecimal, Boolean)],
    b: Option[(BigDecimal, Boolean)],
    low: Boolean
  ): Option[(BigDecimal, Boolean)] =
    (a, b) match {
      case (Some((x, xi)), Some((y, yi))) =>
        if (x == y) Some((x, xi && yi))
        else if ((x > y) == low) a
        else b
      case _ => a.orElse(b)
    }

  private def boundsImpl[A: Type, C: Type](using q: Quotes): Expr[Bounds] = {
    import q.reflect.*

    val refined                    = TypeRepr.of[A]
    val constraint                 = TypeRepr.of[C]
    def short(t: TypeRepr): String = t.show(using Printer.TypeReprShortCode)

    val supported =
      "llm4s-schema-iron describes Greater, GreaterEqual, Less, LessEqual, StrictEqual, Positive, Positive0, Negative, " +
        "Negative0 and Interval on Int, Long, Double and Float; MinLength, MaxLength, FixedLength and Not[Empty] on " +
        "String, List, Seq, Vector and Set; Not[Blank] on String; and & of these. Match is not described yet: " +
        "llm4s-core's StringSchema has no pattern. To describe the type yourself, define a given SchemaOf of it."

    def refuse(part: TypeRepr, why: String): Nothing =
      report.errorAndAbort(
        s"Cannot describe the Iron constraint ${short(part)} of ${short(refined)} :| ${short(constraint)}: $why. " +
          "It is refused rather than left out of the schema, which would not tell the model the bound. " + supported
      )

    val base = refined.dealias.widen
    val kind =
      if (base =:= TypeRepr.of[Int]) Kind.Integer(Int.MinValue.toDouble, Int.MaxValue.toDouble)
      else if (base =:= TypeRepr.of[Long]) Kind.Integer(Long.MinValue.toDouble, Long.MaxValue.toDouble)
      else if (base =:= TypeRepr.of[Double] || base =:= TypeRepr.of[Float]) Kind.Decimal
      else if (base =:= TypeRepr.of[String]) Kind.Text
      else if (
        List(TypeRepr.of[List[?]], TypeRepr.of[Seq[?]], TypeRepr.of[Vector[?]], TypeRepr.of[Set[?]])
          .exists(c => base.typeSymbol == c.typeSymbol)
      ) Kind.Items
      else
        refuse(constraint, s"${short(refined)} is not a number, a string or a list, so it has no JSON Schema bounds")

    val describedAs = TypeRepr.of[DescribedAs[True, ""]].typeSymbol
    val greater     = TypeRepr.of[Greater[0]].typeSymbol
    val less        = TypeRepr.of[Less[0]].typeSymbol
    val strictEqual = TypeRepr.of[StrictEqual[0]].typeSymbol
    val length      = TypeRepr.of[Length[True]].typeSymbol
    val not         = TypeRepr.of[Not[True]].typeSymbol
    val truth       = TypeRepr.of[True]
    val blank       = TypeRepr.of[ForAll[Whitespace]]

    def isA(t: TypeRepr, symbol: Symbol): Boolean = t.dealias match {
      case AppliedType(tycon, _) => tycon.typeSymbol == symbol
      case _                     => false
    }

    def argument(t: TypeRepr): TypeRepr = t.dealias match {
      case AppliedType(_, arg :: _) => arg
      case other                    => other
    }

    // `DescribedAs[C, message]` is `C`
    def undescribed(t: TypeRepr): TypeRepr =
      if (isA(t, describedAs)) undescribed(argument(t)) else t.dealias

    def number(part: TypeRepr, v: TypeRepr): BigDecimal = v.dealias match {
      case ConstantType(IntConstant(n))    => BigDecimal(n)
      case ConstantType(LongConstant(n))   => BigDecimal(n)
      case ConstantType(DoubleConstant(n)) => BigDecimal(n)
      case ConstantType(FloatConstant(n))  => BigDecimal(n.toDouble)
      case _                               => refuse(part, s"its bound ${short(v)} is not a literal number")
    }

    // The range a numeric constraint allows: `Greater`, `Less`, `StrictEqual`, the unions `GreaterEqual` and
    // `LessEqual` are made of, and conjunctions of these.
    def range(t: TypeRepr): Range = undescribed(t) match {
      case AndType(l, r)        => range(l).and(range(r))
      case u if u =:= truth     => Unbounded
      case u if isA(u, greater) => Range(Some((number(t, argument(u)), false)), None)
      case u if isA(u, less)    => Range(None, Some((number(t, argument(u)), false)))
      case u if isA(u, strictEqual) =>
        val v = number(t, argument(u))
        Range(Some((v, true)), Some((v, true)))
      case OrType(l, r) =>
        val (a, b) = (undescribed(l), undescribed(r))
        def orEqual(bound: TypeRepr, equal: TypeRepr): Option[Range] =
          if (isA(equal, strictEqual) && argument(bound) =:= argument(equal)) {
            if (isA(bound, greater)) Some(Range(Some((number(t, argument(bound)), true)), None))
            else if (isA(bound, less)) Some(Range(None, Some((number(t, argument(bound)), true))))
            else None
          } else None
        orEqual(a, b).orElse(orEqual(b, a)).getOrElse(refuse(t, "it is a union other than >= or <="))
      case _ => refuse(t, "it is not a numeric bound")
    }

    // An integer bound as the inclusive whole number it equals; a bound outside the type's range is dropped
    def wholeLower(b: Option[(BigDecimal, Boolean)], floor: Double): Option[Double] =
      b.map { case (v, inclusive) =>
        if (inclusive) v.setScale(0, BigDecimal.RoundingMode.CEILING)
        else v.setScale(0, BigDecimal.RoundingMode.FLOOR) + 1
      }.map(_.toDouble)
        .filter(_ > floor)

    def wholeUpper(b: Option[(BigDecimal, Boolean)], ceiling: Double): Option[Double] =
      b.map { case (v, inclusive) =>
        if (inclusive) v.setScale(0, BigDecimal.RoundingMode.FLOOR)
        else v.setScale(0, BigDecimal.RoundingMode.CEILING) - 1
      }.map(_.toDouble)
        .filter(_ < ceiling)

    def inclusive(b: Option[(BigDecimal, Boolean)]): Option[Double] = b.collect { case (v, true) => v.toDouble }
    def exclusive(b: Option[(BigDecimal, Boolean)]): Option[Double] = b.collect { case (v, false) => v.toDouble }
    def lengthOf(b: Option[Double]): Option[Int]                    = b.map(_.toInt)

    // A length constraint: `Length[...]` of a numeric range, `Not[Empty]`, `Not[Blank]`, and conjunctions of these.
    def lengths(t: TypeRepr): Range = undescribed(t) match {
      case AndType(l, r)       => lengths(l).and(lengths(r))
      case u if u =:= truth    => Unbounded
      case u if isA(u, length) => range(argument(u))
      case u if isA(u, not) =>
        val negated = undescribed(argument(u))
        val empty = isA(negated, length) && (range(argument(negated)) == Range(
          Some((BigDecimal(0), true)),
          Some((BigDecimal(0), true))
        ))
        if (empty || (kind == Kind.Text && negated =:= blank)) Range(Some((BigDecimal(1), true)), None)
        else refuse(t, "only Not[Empty], and Not[Blank] on a String, are described")
      case _ => refuse(t, s"it is not a length constraint, the only kind a ${short(refined)} has")
    }

    val bounds = kind match {
      case Kind.Integer(min, max) =>
        val r = range(constraint)
        Bounds(wholeLower(r.lower, min), wholeUpper(r.upper, max), None, None, None, None)
      case Kind.Decimal =>
        val r = range(constraint)
        Bounds(inclusive(r.lower), inclusive(r.upper), exclusive(r.lower), exclusive(r.upper), None, None)
      case Kind.Text | Kind.Items =>
        val r = lengths(constraint)
        Bounds(None, None, None, None, lengthOf(wholeLower(r.lower, -1)), lengthOf(wholeUpper(r.upper, Int.MaxValue)))
    }

    '{
      Bounds(
        ${ Expr(bounds.minimum) },
        ${ Expr(bounds.maximum) },
        ${ Expr(bounds.exclusiveMinimum) },
        ${ Expr(bounds.exclusiveMaximum) },
        ${ Expr(bounds.minLength) },
        ${ Expr(bounds.maxLength) }
      )
    }
  }
}
