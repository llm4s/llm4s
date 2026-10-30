package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.toolapi.{
  ArraySchema,
  NullableSchema,
  NumberSchema,
  ObjectSchema,
  PropertyDefinition,
  Schema,
  SchemaDefinition
}

import scala.annotation.implicitNotFound
import scala.compiletime.{ constValue, erasedValue, summonFrom, summonInline }
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
 * hierarchies of singletons (as a string with the case names, or a case's `@upickle.implicits.key`); and sum types,
 * enums and sealed traits whose cases have fields (as a JSON Schema `anyOf` of one object per case, each with the
 * discriminator uPickle writes: `"$type"`, or the trait's `@upickle.implicits.key`, holding the case's tag). Not
 * supported, and refused at compile time with a message naming the type: `Map` (core's `ObjectSchema` has a fixed
 * set of properties and cannot describe map values), recursive types (they need a JSON Schema `$ref`), a sealed
 * hierarchy nested in another or with a generic case, `Option` directly inside `Option` and
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
 * A generic case class may be nested in itself at most 16 deep: `W[W[...[Int]]]` with 16 `W`s is described, with 17
 * it is taken for a recursion. Spelled out in a field's type, the deeper nesting is refused at compile time; reached
 * through the generic `derives SchemaOf` instance (`SchemaOf[W[...[Int]]]`) it is refused when the schema is built,
 * with the same `IllegalStateException`.
 *
 * A sum type with fields, and a type that holds one, has no schema in core's model, which has no union: its
 * [[toJsonSchema]] is the JSON Schema, while [[schema]] and [[definition]] throw an `UnsupportedOperationException`
 * saying so. `completeStructuredOf` sends such a schema as it is, and a tool refuses it.
 *
 * @tparam A the described type
 */
@Experimental
@implicitNotFound(
  "Cannot derive a JSON schema for ${A}. Supported: String, Int, Long, Double, Float, Boolean, BigDecimal, BigInt, " +
    "Option, List/Seq/Vector/Set of a supported type, case classes of supported fields (add `derives SchemaOf`), and " +
    "enums or sealed traits, with or without fields. A Map cannot be described, because llm4s-core's ObjectSchema has a " +
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

  /**
   * The schema, described to the model by `description`.
   *
   * @throws java.lang.IllegalStateException for a derived instance whose type turns out to be recursive, or nests a
   *                                         generic case class in itself more than 16 deep (see [[SchemaOf]])
   * @throws java.lang.UnsupportedOperationException for a sum type with fields, or a type that holds one: core's
   *                                                 schema model has no union (use [[jsonSchema]])
   */
  def schema(description: String): SchemaDefinition[A]

  /**
   * The JSON Schema, described to the model by `description`: `schema(description).toJsonSchema(strict)`, except
   * for a sum type with fields (and a type that holds one), whose `anyOf` core's schema model cannot express.
   *
   * @param strict as in [[toJsonSchema]]
   * @throws java.lang.IllegalStateException as [[schema]] does
   */
  def jsonSchema(description: String, strict: Boolean): ujson.Value = schema(description).toJsonSchema(strict)

  /** The description of the type when nothing more specific is given. */
  final def defaultDescription: String = typeDescription.getOrElse(typeName)

  /**
   * The schema of `A` as core's schema model, described by the type's [[description]] or name: what
   * `completeStructuredOf` sends, and what a tool takes as its parameters
   * (`ToolBuilder[A, R](name, text, SchemaOf[A].definition)`).
   *
   * @throws java.lang.IllegalStateException as [[schema]] does
   * @throws java.lang.UnsupportedOperationException as [[schema]] does
   */
  final def definition: SchemaDefinition[A] = schema(defaultDescription)

  /**
   * The JSON Schema document, for every derived type including sum types with fields.
   *
   * @param strict as in `SchemaDefinition.toJsonSchema`: `true` marks every property required, which is what
   *               `completeStructured` sends
   * @throws java.lang.IllegalStateException as [[schema]] does
   */
  final def toJsonSchema(strict: Boolean = true): ujson.Value = jsonSchema(defaultDescription, strict)
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

  given SchemaOf[String] = leaf("string")(Schema.string(_))
  given SchemaOf[Int]    = leaf("integer")(Schema.integer(_))
  // `NumberSchema(isInteger = true)` writes the same `{"type": "integer"}` as `IntegerSchema`, but tells a typed
  // tool's argument check (`Tool`) that the field is a `Long`, so it accepts whole numbers beyond the `Int` range.
  given SchemaOf[Long]    = leaf("integer")(NumberSchema(_, isInteger = true))
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
      override def jsonSchema(d: String, strict: Boolean): ujson.Value =
        JsonSchemas.nullable(inner.jsonSchema(d, strict))
    }

  given list[A](using item: SchemaOf[A]): SchemaOf[List[A]]     = array(item)
  given vector[A](using item: SchemaOf[A]): SchemaOf[Vector[A]] = array(item)
  given seq[A](using item: SchemaOf[A]): SchemaOf[Seq[A]]       = array(item)
  given set[A](using item: SchemaOf[A]): SchemaOf[Set[A]]       = array(item)

  private def array[C, A](item: SchemaOf[A]): SchemaOf[C] = new SchemaOf[C] {
    def typeName: String = s"list of ${item.typeName}"
    // see `leaf` for the cast
    def schema(d: String): SchemaDefinition[C] =
      ArraySchema(d, item.schema(item.defaultDescription)).asInstanceOf[SchemaDefinition[C]]
    override def jsonSchema(d: String, strict: Boolean): ujson.Value =
      JsonSchemas.array(d, item.jsonSchema(item.defaultDescription, strict))
  }

  /**
   * Derives the schema of a case class, an enum or a sealed hierarchy: `derives SchemaOf`. An enum or sealed trait
   * whose cases are all singletons is a string; one with a case that has fields is an `anyOf` (see [[SchemaOf]]).
   */
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
        inline if (SchemaMacros.singletons[A])
          enumeration[A](
            constValue[s.MirroredLabel],
            SchemaMacros.typeDescription[A],
            labels[s.MirroredElemLabels],
            SchemaMacros.caseKeys[A]
          )
        else
          // `cases` is passed by name, like a product's fields
          union[A](
            constValue[s.MirroredLabel],
            SchemaMacros.typeDescription[A],
            SchemaMacros.discriminator[A],
            labels[s.MirroredElemLabels],
            cases[s.MirroredElemTypes],
            SchemaMacros.caseKeys[A],
            SchemaMacros.caseDescriptions[A]
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

  // Each case of a sum: `None` for a singleton, else the case's product schema, derived from its own Mirror (as
  // uPickle derives the case's writer from the sum's), with its fields' instances summoned as for any product.
  private inline def cases[T <: Tuple]: List[Option[SchemaOf[?]]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        summonFrom {
          case _: ValueOf[`h`] => None
          case p: Mirror.ProductOf[`h`] =>
            Some(
              product[h](
                constValue[p.MirroredLabel],
                SchemaMacros.typeDescription[h],
                labels[p.MirroredElemLabels],
                instances[p.MirroredElemTypes],
                SchemaMacros.fieldDescriptions[h],
                SchemaMacros.fieldKeys[h]
              )
            )
        } :: cases[t]
    }

  // How many products of one name may be under construction inside each other, on one thread, before the
  // schema counts as recursive: the compile-time check's bound on nesting one class inside itself.
  private val MaxSameNameDepth = 16

  // The product schemas being built on this thread, innermost first, so that a recursion the compile-time check
  // cannot see (a hand-written given that derives a type which reaches back to the one being built) fails with
  // a message instead of recursing until the stack overflows.
  private val building: ThreadLocal[List[(SchemaOf[?], String)]] = ThreadLocal.withInitial(() => Nil)

  /** How many product schemas are being built on this thread: zero outside a build. For the module's tests. */
  private[schema] def buildsInProgress: Int = building.get.size

  private def recursiveType(name: String): Nothing =
    throw new IllegalStateException(
      s"recursive type $name: llm4s-schema-derivation cannot describe recursive types; core's schema model has " +
        s"no $$ref. Describe $name, or the type that refers back to it, with a hand-written given that does not " +
        "derive it, for example SchemaOf.string."
    )

  // Builds `body` with `self` (a product named `name`) marked as under construction on this thread.
  private def guarded[T](self: SchemaOf[?], name: String)(body: => T): T = {
    val outer = building.get
    if (outer.exists(_._1 eq self) || outer.count(_._2 == name) >= MaxSameNameDepth) recursiveType(name)
    building.set((self, name) :: outer)
    // scalafix:off DisableSyntax.NoKeywordFinally
    // The guard must be restored whatever a field's instance throws, fatal errors included: `scala.util.Try`
    // rethrows an `ExceptionInInitializerError`, a `LinkageError`, an `InterruptedException` or a
    // `ControlThrowable` without running anything after it, which would leave this entry on the thread and make
    // every later build of the type on it fail as "recursive". The throwable itself is never caught.
    try body
    finally building.set(outer)
    // scalafix:on DisableSyntax.NoKeywordFinally
  }

  // A field's description: its own annotation, else its type's, else its name.
  //
  // `fieldSchemas` is by name and evaluated only when the schema is built, never when the instance is made.
  // Summoning a field's instance can initialise another lazy `given`; were that done while this instance is
  // itself being initialised, two hand-written givens of mutually recursive types (each deriving its type) would
  // each wait for the other's initialisation on the same thread, and the program would hang. Deferred, making an
  // instance touches no other given, and building the schema of a recursive type is refused by the guard above.
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

    // Each field: the name it is written under, its instance, its description, and whether it is required.
    private def properties: List[(String, SchemaOf[?], String, Boolean)] =
      fields.zip(fieldSchemas).zip(descriptions).zip(keys).map { case (((field, of), annotated), key) =>
        (key.getOrElse(field), of, annotated.orElse(of.typeDescription).getOrElse(field), !of.isOptional)
      }

    def schema(d: String): SchemaDefinition[A] = guarded(self, name) {
      ObjectSchema[A](
        d,
        properties.map { case (written, of, described, required) =>
          PropertyDefinition(written, of.schema(described), required)
        }
      )
    }

    override def jsonSchema(d: String, strict: Boolean): ujson.Value = guarded(self, name) {
      JsonSchemas.obj(
        d,
        properties.map { case (written, of, described, required) =>
          (written, of.jsonSchema(described, strict), required)
        },
        strict
      )
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

  // A sum with a case that has fields: an `anyOf` of one schema per case. uPickle writes a singleton case as its
  // tag, a bare string, and a case with fields as an object whose `discriminator` property holds the tag, so a
  // singleton is a string with that one value and any other case its product schema with the discriminator added.
  private def union[A](
    name: String,
    described: Option[String],
    discriminator: String,
    labels: List[String],
    caseSchemas: => List[Option[SchemaOf[?]]],
    keys: Map[String, String],
    caseDescriptions: Map[String, String]
  ): SchemaOf[A] = new SchemaOf[A] {
    def typeName: String                         = name
    override def typeDescription: Option[String] = described

    def schema(d: String): SchemaDefinition[A] =
      throw new UnsupportedOperationException(
        s"$name is a sum type with fields: its schema is a JSON Schema anyOf, which llm4s-core's schema model " +
          "(and so a tool's parameters) cannot express. SchemaOf.toJsonSchema and completeStructuredOf describe it; " +
          "a tool's arguments cannot hold a sum type yet."
      )

    override def jsonSchema(d: String, strict: Boolean): ujson.Value =
      JsonSchemas.anyOf(
        d,
        labels.zip(caseSchemas).map { case (label, of) =>
          val tag         = keys.getOrElse(label, label)
          val description = caseDescriptions.getOrElse(label, label)
          of match {
            case None     => JsonSchemas.constant(description, tag)
            case Some(of) => JsonSchemas.tagged(of.jsonSchema(description, strict), discriminator, tag)
          }
        }
      )
  }
}

/**
 * The JSON that core's schema model writes, built directly so that a sum type's `anyOf` can sit anywhere in it.
 * Each builder writes exactly what its core counterpart (`ObjectSchema`, `ArraySchema`, `NullableSchema`) does, so a
 * type without a sum gets the same schema whichever way it is built.
 */
private[schema] object JsonSchemas {

  def obj(description: String, properties: List[(String, ujson.Value, Boolean)], strict: Boolean): ujson.Value = {
    val props = ujson.Obj()
    properties.foreach { case (name, schema, _) => props(name) = schema }
    val required = (if (strict) properties else properties.filter(_._3)).map(_._1).distinct
    ujson.Obj(
      "type"                 -> ujson.Str("object"),
      "description"          -> ujson.Str(description),
      "properties"           -> props,
      "required"             -> ujson.Arr.from(required.map(ujson.Str(_))),
      "additionalProperties" -> ujson.False
    )
  }

  def array(description: String, items: ujson.Value): ujson.Value =
    ujson.Obj("type" -> ujson.Str("array"), "description" -> ujson.Str(description), "items" -> items)

  // `null` added: to an `anyOf` as one more alternative, otherwise to the type (and the `enum`, if any)
  def nullable(schema: ujson.Value): ujson.Value = {
    val widened  = ujson.Obj.from(schema.obj)
    val nullType = ujson.Str("null")
    widened.value.get("anyOf") match {
      case Some(alternatives) =>
        if (!alternatives.arr.contains(ujson.Obj("type" -> nullType)))
          widened("anyOf") = ujson.Arr.from(alternatives.arr :+ ujson.Obj("type" -> nullType))
      case None =>
        widened.value.get("type") match {
          case Some(ujson.Str(name)) => widened("type") = ujson.Arr(ujson.Str(name), nullType)
          case Some(names: ujson.Arr) =>
            if (!names.value.contains(nullType)) widened("type") = ujson.Arr.from(names.value :+ nullType)
          case _ => widened("type") = ujson.Arr(nullType)
        }
        widened.value.get("enum") match {
          case Some(values: ujson.Arr) if !values.value.contains(ujson.Null) =>
            widened("enum") = ujson.Arr.from(values.value :+ ujson.Null)
          case _ => ()
        }
    }
    widened
  }

  def anyOf(description: String, alternatives: List[ujson.Value]): ujson.Value =
    ujson.Obj("description" -> ujson.Str(description), "anyOf" -> ujson.Arr.from(alternatives))

  // A string that can only be `value`: a single-value `enum`, which OpenAI strict mode and Gemini both accept
  def constant(description: String, value: String): ujson.Value =
    ujson.Obj(
      "type"        -> ujson.Str("string"),
      "description" -> ujson.Str(description),
      "enum"        -> ujson.Arr(ujson.Str(value))
    )

  // An object schema with the discriminator added as its first, required property
  def tagged(objectSchema: ujson.Value, discriminator: String, tag: String): ujson.Value = {
    val tagged     = ujson.Obj.from(objectSchema.obj)
    val properties = ujson.Obj(discriminator -> constant(s"Always \"$tag\"", tag))
    objectSchema("properties").obj.foreach { case (k, v) => if (k != discriminator) properties(k) = v }
    tagged("properties") = properties
    tagged("required") =
      ujson.Arr.from(ujson.Str(discriminator) +: objectSchema("required").arr.filterNot(_ == ujson.Str(discriminator)))
    tagged
  }
}
