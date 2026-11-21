package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.schema.PartialJson.Node

import scala.quoted.*
import scala.util.Try

/**
 * The type of a partly written `A`, and how to read one from the JSON a model has streamed so far: what
 * `client.streamStructuredOf[A]` hands its callback.
 *
 * For a case class `A`, `Out` is a named tuple with `A`'s field names, each an `Option` of the field's partial
 * value, `None` until the field has started to arrive:
 *
 * {{{
 * case class LineItem(sku: String, quantity: Int)
 * case class Invoice(vendor: String, items: List[LineItem], status: Status)
 *
 * // the Out of PartialOf[Invoice] is
 * // (vendor: Option[String], items: Option[List[(sku: Option[String], quantity: Option[Int])]], status: Option[Status])
 * }}}
 *
 * A field's partial value follows its type:
 *  - `String`: the text so far;
 *  - a case class with fields: its own partial named tuple, recursively;
 *  - `List`, `Vector` and `Seq`: the elements so far, the last one partial when its type has partial values;
 *  - `Option[T]`: `Some(None)` once the model has written `null`, else `T`'s partial value in a `Some`;
 *  - anything else (numbers, `Boolean`, enums, sum types, `Set`, `BigDecimal`): the value, only once complete,
 *    read with its uPickle `Reader`. A number is never shown while more digits may follow.
 *
 * An instance is derived for any case class with fields (no `derives` clause), from the same field names, and the
 * same `@upickle.implicits.key`s, that its `SchemaOf` and uPickle use. The type is computed at compile time rather
 * than as a match type, since a match type cannot tell a case class from an enum or a sealed trait. To name it in
 * a signature, take it from the instance: `val invoices = summon[PartialOf[Invoice]]` and `def show(p: invoices.Out)`.
 *
 * @tparam A the case class being streamed
 */
@Experimental
sealed trait PartialOf[A] {

  /** The partial value of `A`: a named tuple of `Option`s, one per field of `A`. */
  type Out

  /** The partial value of the JSON object in `node`, or `None` when it is not an object. */
  private[schema] def read(node: Node): Option[Out]
}

@Experimental
object PartialOf {

  /** The instance for the case class `A`, with `Out` worked out from `A`'s fields. */
  transparent inline given derived[A]: PartialOf[A] = ${ PartialMacros.derivedImpl[A] }

  final private[schema] class Derived[A, O](shape: Shape.Fields) extends PartialOf[A] {
    type Out = O
    private[schema] def read(node: Node): Option[O] = Shape.decode(shape, node).map(_.asInstanceOf[O])
  }

  private[schema] def derive[A, O](shape: Shape.Fields): PartialOf[A] { type Out = O } = new Derived[A, O](shape)
}

/** How the partial value of one type is read from a parsed prefix: built by `PartialMacros` for each field. */
sealed private[schema] trait Shape

private[schema] object Shape {

  /** A `String`: its prefix. */
  case object Text extends Shape

  /** A type with no partial value: read whole, once complete, with its reader. */
  final case class Whole(reader: upickle.default.Reader[?]) extends Shape

  /** An `Option`: `null` is `Some(None)`, anything else the inner partial value in a `Some`. */
  final case class Optional(inner: Shape) extends Shape

  /** A `List`, `Vector` or `Seq`: the leading elements that have a partial value. */
  final case class Many(item: Shape, vector: Boolean) extends Shape

  /** A case class: a tuple of the fields' partial values, each `None` until its key has arrived. */
  final case class Fields(keys: List[String], shapes: List[Shape]) extends Shape

  def decode(shape: Shape, node: Node): Option[Any] = (shape, node) match {
    case (Text, Node.Str(value, _)) => Some(value)
    case (Whole(reader), whole) if whole.isComplete =>
      Try(upickle.default.read(whole.render)(using reader.asInstanceOf[upickle.default.Reader[Any]])).toOption
    case (Optional(_), Node.Null)                 => Some(None)
    case (Optional(inner), value)                 => decode(inner, value).map(Some(_))
    case (Many(item, vector), Node.Arr(items, _)) =>
      // an element with no partial value yet ends the list, so that later elements never shift into its place
      val read = items.iterator.map(decode(item, _)).takeWhile(_.isDefined).flatten.toList
      Some(if (vector) read.toVector else read)
    case (Fields(keys, shapes), obj: Node.Obj) =>
      val values = keys.zip(shapes).map { case (key, field) => obj.field(key).flatMap(decode(field, _)) }
      Some(Tuple.fromArray(values.toArray[Any]))
    case _ => None
  }
}

/** The compile-time half of `PartialOf`: the partial type of `A` and the `Shape` that reads it. */
private[schema] object PartialMacros {

  def derivedImpl[A: Type](using Quotes): Expr[PartialOf[A]] = {
    import quotes.reflect.*

    def isCaseClass(symbol: Symbol): Boolean =
      symbol.isClassDef && symbol.flags.is(Flags.Case) && !symbol.flags.is(Flags.Module) &&
        !symbol.flags.is(Flags.Abstract)

    val root = TypeRepr.of[A].dealias
    if (!isCaseClass(root.typeSymbol) || root.typeSymbol.caseFields.isEmpty)
      report.errorAndAbort(
        s"No PartialOf[${root.show}]: streamStructuredOf streams a case class with fields, and ${root.show} is not " +
          "one. Stream a sum type by wrapping it in a case class field: its value is surfaced once complete."
      )

    val optionSym = TypeRepr.of[Option[Any]].typeSymbol
    val listSym   = TypeRepr.of[List[Any]].typeSymbol
    val vectorSym = TypeRepr.of[Vector[Any]].typeSymbol
    val seqSym    = TypeRepr.of[Seq[Any]].typeSymbol
    val keySym    = Symbol.requiredClass("upickle.implicits.key")
    val namedTuple = TypeRepr.of[NamedTuple.NamedTuple[EmptyTuple, EmptyTuple]] match {
      case AppliedType(constructor, _) => constructor
      case other                       => report.errorAndAbort(s"Unexpected named tuple type: ${other.show}")
    }

    def tupleOf(types: List[TypeRepr]): TypeRepr =
      types.foldRight(TypeRepr.of[EmptyTuple])((head, tail) => TypeRepr.of[*:].appliedTo(List(head, tail)))

    def fieldKey(owner: Symbol, field: Symbol): String =
      owner.primaryConstructor.paramSymss.flatten
        .find(_.name == field.name)
        .flatMap(_.annotations.collectFirst {
          case Apply(Select(New(tpt), _), List(Literal(StringConstant(key)))) if tpt.tpe.typeSymbol == keySym => key
        })
        .getOrElse(field.name)

    // The partial type of a value of type `tpe`, and the shape that reads it. `seen` stops a recursive type, which
    // SchemaOf refuses anyway, from unfolding forever: inside itself it is read whole.
    def partial(tpe: TypeRepr, seen: Set[Symbol]): (TypeRepr, Expr[Shape]) = {
      val t = tpe.dealias
      t match {
        case _ if t =:= TypeRepr.of[String] => (t, '{ Shape.Text })
        case AppliedType(constructor, List(inner)) if constructor.typeSymbol == optionSym =>
          val (innerType, innerShape) = partial(inner, seen)
          (TypeRepr.of[Option].appliedTo(innerType), '{ Shape.Optional($innerShape) })
        case AppliedType(constructor, List(item)) if Set(listSym, vectorSym, seqSym).contains(constructor.typeSymbol) =>
          val (itemType, itemShape) = partial(item, seen)
          val vector                = constructor.typeSymbol == vectorSym
          (constructor.appliedTo(itemType), '{ Shape.Many($itemShape, ${ Expr(vector) }) })
        case _ if isCaseClass(t.typeSymbol) && t.typeSymbol.caseFields.nonEmpty && !seen(t.typeSymbol) =>
          product(t, seen + t.typeSymbol)
        case _ => (t, whole(t))
      }
    }

    def whole(t: TypeRepr): Expr[Shape] = t.asType match {
      case '[w] =>
        Expr.summon[upickle.default.Reader[w]] match {
          case Some(reader) => '{ Shape.Whole($reader) }
          case None =>
            report.errorAndAbort(
              s"No PartialOf[${root.show}]: its field type ${t.show} has no uPickle Reader in scope, which reads " +
                "its value once complete. Derive one (`derives ReadWriter`) as for completeStructuredOf."
            )
        }
    }

    def product(t: TypeRepr, seen: Set[Symbol]): (TypeRepr, Expr[Shape.Fields]) = {
      val fields   = t.typeSymbol.caseFields
      val partials = fields.map(field => partial(t.memberType(field), seen))
      val names    = tupleOf(fields.map(field => ConstantType(StringConstant(field.name))))
      val values   = tupleOf(partials.map { case (fieldType, _) => TypeRepr.of[Option].appliedTo(fieldType) })
      val keys     = Expr(fields.map(fieldKey(t.typeSymbol, _)))
      val shapes   = Expr.ofList(partials.map { case (_, shape) => shape })
      (namedTuple.appliedTo(List(names, values)), '{ Shape.Fields($keys, $shapes) })
    }

    val (out, shape) = product(root, Set(root.typeSymbol))
    out.asType match {
      case '[o] => '{ PartialOf.derive[A, o]($shape) }
    }
  }

}
