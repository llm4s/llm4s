package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.toolapi.{ ArraySchema, NullableSchema, ObjectSchema, PropertyDefinition, Schema, SchemaDefinition }

import scala.annotation.implicitNotFound
import scala.compiletime.{ constValue, erasedValue, summonInline }
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
 * hierarchies of singletons (as a string with the case names). Not supported, and refused at compile time with a
 * message naming the type: `Map` (core's `ObjectSchema` has a fixed set of properties and cannot describe map
 * values), recursive types and sealed hierarchies with fields (both need a JSON Schema construct, `$ref` and
 * `oneOf`, that core's schema model lacks). Other types need a `given SchemaOf` of their own: see [[SchemaOf.string]]
 * and [[SchemaOf.stringEnum]].
 *
 * @tparam A the described type
 */
@Experimental
@implicitNotFound(
  "Cannot derive a JSON schema for ${A}. Supported: String, Int, Long, Double, Float, Boolean, BigDecimal, BigInt, " +
    "Option, List/Seq/Vector/Set of a supported type, case classes of supported fields (add `derives SchemaOf`), and " +
    "enums or sealed hierarchies of singletons. A Map cannot be described, because llm4s-core's ObjectSchema has a " +
    "fixed set of properties and no way to describe map values. For any other type define a `given SchemaOf[${A}]`, " +
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
   * The JSON Schema document.
   *
   * @param strict as in `SchemaDefinition.toJsonSchema`: `true` marks every property required, which is what
   *               `completeStructured` sends
   */
  final def toJsonSchema(strict: Boolean = true): ujson.Value = schema(defaultDescription).toJsonSchema(strict)
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

  given option[A](using inner: SchemaOf[A]): SchemaOf[Option[A]] = new SchemaOf[Option[A]] {
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
        product[A](
          constValue[p.MirroredLabel],
          SchemaMacros.typeDescription[A],
          labels[p.MirroredElemLabels],
          instances[p.MirroredElemTypes],
          SchemaMacros.fieldDescriptions[A],
          SchemaMacros.fieldKeys[A]
        )
      case s: Mirror.SumOf[A] =>
        enumeration[A](constValue[s.MirroredLabel], SchemaMacros.typeDescription[A], labels[s.MirroredElemLabels])
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

  // A field's description: its own annotation, else its type's, else its name.
  private def product[A](
    name: String,
    described: Option[String],
    fields: List[String],
    fieldSchemas: List[SchemaOf[?]],
    descriptions: List[Option[String]],
    keys: List[Option[String]]
  ): SchemaOf[A] = leaf[A](name, described) { d =>
    val properties = fields.zip(fieldSchemas).zip(descriptions).zip(keys).map { case (((field, of), annotated), key) =>
      val written = key.getOrElse(field)
      PropertyDefinition(written, of.schema(annotated.orElse(of.typeDescription).getOrElse(field)), !of.isOptional)
    }
    ObjectSchema[A](d, properties)
  }

  private def enumeration[A](name: String, described: Option[String], cases: List[String]): SchemaOf[A] =
    leaf[A](name, described)(Schema.string(_).withEnum(cases))
}
