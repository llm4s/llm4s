package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.toolapi.{ ArraySchema, NullableSchema, ObjectSchema, PropertyDefinition, Schema, SchemaDefinition }

import scala.annotation.implicitNotFound
import scala.compiletime.{ constValue, erasedValue, summonInline }
import scala.util.NotGiven
import scala.deriving.Mirror

/**
 * The JSON schema of `A`, derived from the type itself so that it cannot drift from it.
 *
 * {{{
 * case class Invoice(vendor: String, amount: Double, lines: List[String], note: Option[String]) derives SchemaOf
 *
 * SchemaOf[Invoice].toJsonSchema()    // the schema `completeStructured` sends
 * }}}
 *
 * The schema describes what uPickle's derived `ReadWriter` reads and writes (field names, `Option` as the value
 * or `null`, an enum as its case name, a `BigDecimal` as a string), so a reply that matches the schema reads back
 * into `A`. The module's tests check that round trip for every supported shape.
 *
 * Supported: `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`, `BigDecimal`, `BigInt`; `Option`; `List`,
 * `Seq`, `Vector` and `Set` of a supported type; case classes whose fields are supported; enums and sealed
 * hierarchies of singletons (as a string with the case names, or a case's `@upickle.implicits.key`). Not supported,
 * and refused at compile time with a message naming the type: `Map` (core's `ObjectSchema` has a fixed set of
 * properties and cannot describe map values), recursive types and sealed hierarchies with fields (both need a JSON
 * Schema construct, `$ref` and `oneOf`, that core's schema model lacks), `Option` directly inside `Option` and
 * tuples (uPickle writes both as JSON arrays), and named tuples. Other types need a `given SchemaOf` of their own:
 * see [[SchemaOf.string]] and [[SchemaOf.stringEnum]]. A hand-written given is used as it is, so it can also
 * describe a type the derivation would refuse.
 *
 * A recursion that runs through hand-written givens which themselves call `SchemaOf.derived` (for mutually
 * recursive `P` and `Q`, `given SchemaOf[P] = SchemaOf.derived[P]` and `given SchemaOf[Q] = SchemaOf.derived[Q]`)
 * cannot be seen at compile time, because the check does not look inside a hand-written given. Building that
 * schema (`schema`, `definition`, `toJsonSchema`) throws an `IllegalStateException` naming the recursive type
 * instead, on first use; it never hangs.
 *
 * @tparam A the described type
 */
@Experimental
@implicitNotFound(
  "Cannot derive a JSON schema for ${A}. Supported: String, Int, Long, Double, Float, Boolean, BigDecimal, BigInt, " +
    "Option, List/Seq/Vector/Set of a supported type, case classes of supported fields (add `derives SchemaOf`), and " +
    "enums or sealed hierarchies of singletons. A Map cannot be described, because llm4s-core's ObjectSchema has a " +
    "fixed set of properties and no way to describe map values. An Option directly inside an Option cannot be " +
    "described either: uPickle writes it as a JSON array ([], [null] or [value]), not as the value or null, and a tuple as an " +
    "array of mixed types: use a case class. For any other type define a `given SchemaOf[${A}]`, " +
    "for example with SchemaOf.string."
)
trait SchemaOf[A] {

  /** The name of the type, used to describe it when it has no [[description]]. */
  def typeName: String

  /** The type's own [[description]], if it has one. */
  def typeDescription: Option[String] = None

  /** Whether a field of this type may be absent or `null`: true for `Option`. */
  def isOptional: Boolean = false

  /** The schema, described to the model by `description`. */
  def schema(description: String): SchemaDefinition[A]

  /** The description of the type when nothing more specific is given. */
  final def defaultDescription: String = typeDescription.getOrElse(typeName)

  /**
   * The schema of `A` as core's schema model, described by the type's [[description]] or name: what
   * `completeStructuredOf` sends, and what a tool takes as its parameters
   * (`ToolBuilder[A, R](name, text, SchemaOf[A].definition)`).
   */
  final def definition: SchemaDefinition[A] = schema(defaultDescription)

  /**
   * The JSON Schema document.
   *
   * @param strict as in `SchemaDefinition.toJsonSchema`: `true` marks every property required, which is what
   *               `completeStructured` sends
   */
  final def toJsonSchema(strict: Boolean = true): ujson.Value = definition.toJsonSchema(strict)
}

@Experimental
object SchemaOf {

  /** The instance of `A` in scope. */
  def apply[A](using schemaOf: SchemaOf[A]): SchemaOf[A] = schemaOf

  /**
   * A type that is written as a JSON string, such as a date or an identifier whose `ReadWriter` you define
   * yourself: its schema is a plain string.
   *
   * @param description what the model sees for a field of this type that has no [[description]] of its own, and
   *                    for the type itself when it is the schema on its own
   */
  def string[A](description: String): SchemaOf[A] = leaf(description, Some(description))(Schema.string(_))

  /**
   * A type that is written as one of a fixed set of strings.
   *
   * @param description as for [[string]]
   * @param values      the strings the model may answer with
   */
  def stringEnum[A](description: String, values: Seq[String]): SchemaOf[A] =
    leaf(description, Some(description))(Schema.string(_).withEnum(values))

  // `SchemaDefinition` is sealed and its type parameter is the Scala type it reads, which these leaves widen
  // (a `Long` is described by `IntegerSchema`, which is a `SchemaDefinition[Int]`; a `BigDecimal` by a
  // string). The parameter is not used when the JSON Schema is written, so the cast is confined to this method.
  private def leaf[A](name: String, described: Option[String] = None, optional: Boolean = false)(
    build: String => SchemaDefinition[?]
  ): SchemaOf[A] = new SchemaOf[A] {
    def typeName: String                       = name
    override def typeDescription               = described
    override def isOptional: Boolean           = optional
    def schema(d: String): SchemaDefinition[A] = build(d).asInstanceOf[SchemaDefinition[A]]
  }

  given SchemaOf[String]  = leaf("string")(Schema.string(_))
  given SchemaOf[Int]     = leaf("integer")(Schema.integer(_))
  given SchemaOf[Long]    = leaf("integer")(Schema.integer(_))
  given SchemaOf[Double]  = leaf("number")(Schema.number(_))
  given SchemaOf[Float]   = leaf("number")(Schema.number(_))
  given SchemaOf[Boolean] = leaf("boolean")(Schema.boolean(_))
  // uPickle writes a BigDecimal and a BigInt as JSON strings and reads only strings back
  given SchemaOf[BigDecimal] = leaf("decimal number, as a string")(Schema.string(_))
  given SchemaOf[BigInt]     = leaf("integer, as a string")(Schema.string(_))

  // uPickle writes an `Option` directly inside an `Option` as an array (`[]`, `[null]`, `[1]`), not as the value
  // or `null`, so the nullable schema would be wrong for it: it has no instance, and the message says why.
  given option[A](using inner: SchemaOf[A], notNested: NotGiven[A <:< Option[?]]): SchemaOf[Option[A]] =
    new SchemaOf[Option[A]] {
      def typeName: String                               = inner.typeName
      override def typeDescription: Option[String]       = inner.typeDescription
      override def isOptional: Boolean                   = true
      def schema(d: String): SchemaDefinition[Option[A]] = NullableSchema(inner.schema(d))
    }

  given list[A](using item: SchemaOf[A]): SchemaOf[List[A]]     = array(item)
  given vector[A](using item: SchemaOf[A]): SchemaOf[Vector[A]] = array(item)
  given seq[A](using item: SchemaOf[A]): SchemaOf[Seq[A]]       = array(item)
  given set[A](using item: SchemaOf[A]): SchemaOf[Set[A]]       = array(item)

  private def array[C, A](item: SchemaOf[A]): SchemaOf[C] =
    leaf[C](s"list of ${item.typeName}")(d => ArraySchema(d, item.schema(item.defaultDescription)))

  /** Derives the schema of a case class, an enum or a sealed hierarchy of singletons: `derives SchemaOf`. */
  inline def derived[A](using m: Mirror.Of[A]): SchemaOf[A] = {
    SchemaMacros.check[A]
    inline m match {
      case p: Mirror.ProductOf[A] =>
        // `instances` is passed by name: see `product`
        product[A](
          constValue[p.MirroredLabel],
          SchemaMacros.typeDescription[A],
          labels[p.MirroredElemLabels],
          instances[p.MirroredElemTypes],
          SchemaMacros.fieldDescriptions[A],
          SchemaMacros.fieldKeys[A]
        )
      case s: Mirror.SumOf[A] =>
        enumeration[A](
          constValue[s.MirroredLabel],
          SchemaMacros.typeDescription[A],
          labels[s.MirroredElemLabels],
          SchemaMacros.caseKeys[A]
        )
    }
  }

  private inline def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: labels[t]
    }

  private inline def instances[T <: Tuple]: List[SchemaOf[?]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[SchemaOf[h]] :: instances[t]
    }

  // How many products of one name may be under construction inside each other, on one thread, before the
  // schema counts as recursive: the compile-time check's bound on nesting one class inside itself.
  private val MaxSameNameDepth = 16

  // The product schemas being built on this thread, innermost first, so that a recursion the compile-time check
  // cannot see (a hand-written given that derives a type which reaches back to the one being built) fails with
  // a message instead of recursing until the stack overflows.
  private val building: ThreadLocal[List[(SchemaOf[?], String)]] = ThreadLocal.withInitial(() => Nil)

  private def recursiveType(name: String): Nothing =
    throw new IllegalStateException(
      s"recursive type $name: llm4s-schema-derivation cannot describe recursive types; core's schema model has " +
        s"no $$ref. Describe $name, or the type that refers back to it, with a hand-written given that does not " +
        "derive it, for example SchemaOf.string."
    )

  // A field's description: its own annotation, else its type's, else its name.
  //
  // `fieldSchemas` is by name and evaluated only when the schema is built, never when the instance is made.
  // Summoning a field's instance can initialise another lazy `given`; were that done while this instance is
  // itself being initialised, two hand-written givens of mutually recursive types (each deriving its type) would
  // each wait for the other's initialisation on the same thread, and the program would hang. Deferred, making an
  // instance touches no other given, and building the schema of a recursive type is refused by the guard below.
  private def product[A](
    name: String,
    described: Option[String],
    fields: List[String],
    fieldSchemas: => List[SchemaOf[?]],
    descriptions: List[Option[String]],
    keys: List[Option[String]]
  ): SchemaOf[A] = new SchemaOf[A] { self =>
    def typeName: String                         = name
    override def typeDescription: Option[String] = described
    def schema(d: String): SchemaDefinition[A] = {
      val outer = building.get
      if (outer.exists(_._1 eq self) || outer.count(_._2 == name) >= MaxSameNameDepth) recursiveType(name)
      building.set((self, name) :: outer)
      val built = scala.util.Try {
        val properties =
          fields.zip(fieldSchemas).zip(descriptions).zip(keys).map { case (((field, of), annotated), key) =>
            val written = key.getOrElse(field)
            PropertyDefinition(
              written,
              of.schema(annotated.orElse(of.typeDescription).getOrElse(field)),
              !of.isOptional
            )
          }
        ObjectSchema[A](d, properties)
      }
      building.set(outer)
      built.get
    }
  }

  // A case is written as its name, or as its `@upickle.implicits.key` when it has one.
  private def enumeration[A](
    name: String,
    described: Option[String],
    cases: List[String],
    keys: Map[String, String]
  ): SchemaOf[A] =
    leaf[A](name, described)(Schema.string(_).withEnum(cases.map(c => keys.getOrElse(c, c))))
}
