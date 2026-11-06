package org.llm4s.prompt

import org.llm4s.annotation.Experimental

import java.time.{ Instant, LocalDate }
import java.util.UUID
import scala.annotation.implicitNotFound
import scala.compiletime.{ constValue, erasedValue, summonInline }
import scala.deriving.Mirror

/**
 * How a value of type `A` is written into a [[Prompt]].
 *
 * An interpolated value is rendered by its `Promptable`, never by `toString`, and a type without one does not
 * compile: `prompt"Summarise $client"` (an `LLMClient`) is a compile error naming the type.
 *
 * {{{
 * case class Invoice(customer: String, amount: BigDecimal, currency: String) derives Promptable
 *
 * prompt"Check ${Invoice("ACME, Inc.", BigDecimal("12.5"), "EUR")}"
 * // Check Invoice(customer: "ACME, Inc.", amount: 12.5, currency: "EUR")
 * }}}
 *
 * The rendering is deterministic: a case class is its name and labelled fields in declaration order, an enum case
 * its name, a `Seq` its elements in order, a `Set` and a `Map` sorted by their rendered elements and keys, `None`
 * is `(none)`, and a `Some` its value. A string on its own is written as it is; inside a structure it is quoted, so
 * a comma in a field cannot be read as the end of the field.
 *
 * @tparam A the type rendered.
 */
@Experimental
@implicitNotFound(
  "No Promptable[${A}]: llm4s cannot tell how to write a ${A} into a prompt. Add `derives Promptable` to a case class or enum, or define a `given Promptable[${A}] = Promptable.instance(...)`."
)
trait Promptable[-A] {

  /** `value` as it is written where it stands on its own in a prompt. */
  def render(value: A): String

  /** `value` as it is written inside a case class, collection or `Option`; the same as [[render]] unless overridden. */
  def renderNested(value: A): String = render(value)
}

/** The instances for the standard types, and `derives Promptable` for case classes and enums. */
object Promptable {

  /** The instance in scope for `A`. */
  def apply[A](using p: Promptable[A]): Promptable[A] = p

  /** An instance that renders with `f`, on its own and nested alike. */
  def instance[A](f: A => String): Promptable[A] = new Promptable[A] {
    def render(value: A): String = f(value)
  }

  given Promptable[String] with {
    def render(value: String): String                = value
    override def renderNested(value: String): String = quote(value)
  }

  given Promptable[Char]       = instance(_.toString)
  given Promptable[Boolean]    = instance(_.toString)
  given Promptable[Byte]       = instance(_.toString)
  given Promptable[Short]      = instance(_.toString)
  given Promptable[Int]        = instance(_.toString)
  given Promptable[Long]       = instance(_.toString)
  given Promptable[Float]      = instance(_.toString)
  given Promptable[Double]     = instance(_.toString)
  given Promptable[BigInt]     = instance(_.toString)
  given Promptable[BigDecimal] = instance(_.toString)

  /** ISO-8601, in UTC: `2026-11-05T10:15:30Z`. */
  given Promptable[Instant] = instance(_.toString)

  /** ISO-8601: `2026-11-05`. */
  given Promptable[LocalDate] = instance(_.toString)

  given Promptable[UUID] = instance(_.toString)

  /** A `Some` is its value, `None` is `(none)`. */
  given [A](using p: Promptable[A]): Promptable[Option[A]] = instance {
    case Some(a) => p.renderNested(a)
    case None    => "(none)"
  }

  /** The elements in order: `[a, b, c]`. */
  given [A](using p: Promptable[A]): Promptable[Seq[A]] =
    instance(_.iterator.map(p.renderNested).mkString("[", ", ", "]"))

  /** The elements sorted by their rendering, so that the prompt does not depend on the set's iteration order. */
  given [A](using p: Promptable[A]): Promptable[Set[A]] =
    instance(_.iterator.map(p.renderNested).toVector.sorted.mkString("[", ", ", "]"))

  /** The entries sorted by their rendered key: `{a: 1, b: 2}`. */
  given [K, V](using k: Promptable[K], v: Promptable[V]): Promptable[Map[K, V]] =
    instance(
      _.iterator
        .map { case (key, value) => (k.renderNested(key), v.renderNested(value)) }
        .toVector
        .sortBy(_._1)
        .map { case (key, value) => s"$key: $value" }
        .mkString("{", ", ", "}")
    )

  /**
   * The instance of a case class or enum: `derives Promptable`.
   *
   * A case class renders as `Name(field: value, ...)`, its fields by their own `Promptable` (each field's type needs
   * one, or this does not compile); a case without fields, such as an enum's singleton case, as its name. An enum
   * or sealed trait renders each value as its case does; the cases are derived with it.
   */
  inline def derived[A](using m: Mirror.Of[A]): Promptable[A] =
    inline m match {
      case p: Mirror.ProductOf[A] =>
        product[A](constValue[p.MirroredLabel], labels[p.MirroredElemLabels], fields[p.MirroredElemTypes])
      case s: Mirror.SumOf[A] =>
        sum[A](s, cases[s.MirroredElemTypes])
    }

  private inline def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].toString :: labels[t]
    }

  private inline def fields[T <: Tuple]: List[Promptable[Any]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Promptable[h]].asInstanceOf[Promptable[Any]] :: fields[t]
    }

  // A case is derived from its own mirror, never summoned: `Promptable` is contravariant, so summoning the case of
  // an enum would find the enum's own instance (the one being derived) and recurse for ever.
  private inline def cases[T <: Tuple]: List[Promptable[Any]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        derived[h](using summonInline[Mirror.Of[h]]).asInstanceOf[Promptable[Any]] :: cases[t]
    }

  private def product[A](label: String, names: List[String], instances: List[Promptable[Any]]): Promptable[A] =
    if (names.isEmpty) instance(_ => label)
    else
      instance { value =>
        val values = value.asInstanceOf[Product].productIterator
        names
          .zip(instances)
          .map { case (name, p) => s"$name: ${p.renderNested(values.next())}" }
          .mkString(s"$label(", ", ", ")")
      }

  private def sum[A](mirror: Mirror.SumOf[A], instances: List[Promptable[Any]]): Promptable[A] = {
    val byOrdinal = instances.toVector
    instance(value => byOrdinal(mirror.ordinal(value)).render(value))
  }

  /** `s` in double quotes, with `"` and `\` escaped and line breaks written as `\n`. */
  private def quote(s: String): String = {
    val b = new StringBuilder(s.length + 2)
    b.append('"')
    s.foreach {
      case '"'  => b.append("\\\"")
      case '\\' => b.append("\\\\")
      case '\n' => b.append("\\n")
      case '\r' => b.append("\\r")
      case c    => b.append(c)
    }
    b.append('"').toString
  }
}
