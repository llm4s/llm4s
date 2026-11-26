package org.llm4s.toolapi

import org.llm4s.annotation.Stable

/**
 * Base trait for all JSON Schema definitions used in tool parameter specifications.
 *
 * Each concrete subclass corresponds to a JSON Schema type and can be converted
 * to a `ujson.Value` for inclusion in OpenAI-compatible tool definitions via
 * [[SchemaDefinition.toJsonSchema]]. [[AnyOfSchema]] is a union of schemas, and
 * [[RawJsonSchema]] any JSON Schema this model has no case for.
 */
@Stable
sealed trait SchemaDefinition[T] {

  /**
   * Serialise this schema to a JSON Schema `ujson.Value`.
   *
   * @param strict When `true`, all object properties are listed as required and the optional
   *               ones accept `null`, matching OpenAI strict-mode tool definitions.
   */
  def toJsonSchema(strict: Boolean): ujson.Value
}

/**
 * String schema with validation options.
 *
 * @param description  Human-readable description shown to the LLM
 * @param enumValues   Restricts valid values to this closed set
 * @param minLength    Minimum allowed string length
 * @param maxLength    Maximum allowed string length
 * @param pattern      A regular expression the string must match, written as JSON Schema `pattern`: see `withPattern`
 */
@Stable
final case class StringSchema private (
  description: String,
  enumValues: Option[Seq[String]],
  minLength: Option[Int],
  maxLength: Option[Int],
  pattern: Option[String]
) extends SchemaDefinition[String] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val base = ujson.Obj(
      "type"        -> ujson.Str("string"),
      "description" -> ujson.Str(description)
    )

    enumValues.foreach(values => base("enum") = ujson.Arr(values.map(ujson.Str(_)): _*))
    minLength.foreach(min => base("minLength") = ujson.Num(min))
    maxLength.foreach(max => base("maxLength") = ujson.Num(max))
    pattern.foreach(regex => base("pattern") = ujson.Str(regex))

    base
  }

  /**
   * Restrict valid values to the given closed enumeration.
   *
   * @param values Allowed string values
   */
  def withEnum(values: Seq[String]): StringSchema = copy(enumValues = Some(values.distinct))

  /** Set or clear the closed enumeration; `None` lifts the restriction. Duplicates are dropped. */
  def withEnum(values: Option[Seq[String]]): StringSchema = copy(enumValues = values.map(_.distinct))

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): StringSchema = copy(description = description)

  /** Set the minimum allowed string length (inclusive). */
  def withMinLength(min: Int): StringSchema = copy(minLength = Some(min))

  /** Set or clear the minimum allowed string length (inclusive). */
  def withMinLength(min: Option[Int]): StringSchema = copy(minLength = min)

  /** Set the maximum allowed string length (inclusive). */
  def withMaxLength(max: Int): StringSchema = copy(maxLength = Some(max))

  /** Set or clear the maximum allowed string length (inclusive). */
  def withMaxLength(max: Option[Int]): StringSchema = copy(maxLength = max)

  /**
   * Require the string to match a regular expression, written to the schema as JSON Schema `pattern`.
   *
   * The match is JSON Schema's: the expression may be found anywhere in the string, so `[a-z]+` accepts
   * `"Crypt Door"` (it finds `rypt`). Anchor an expression that must cover the whole string:
   * `withPattern("^[a-z0-9_-]+$")`.
   *
   * The expression is sent to the provider as written and is not compiled here, so nothing here throws. An agent
   * refuses to build (a `Left`) with a tool or an output schema whose pattern its argument validator cannot check.
   * For the default validator that is one that is not a valid `java.util.regex` expression, is empty, is longer
   * than 1000 characters or has a shape known to backtrack catastrophically, such as `(a+)+`: the rules of
   * [[org.llm4s.security.RegexSafetyManager.safeCompile]].
   *
   * On the dialect: JSON Schema names ECMA-262 regular expressions, and a provider that enforces the pattern reads
   * it in its own way, while llm4s checks it with `java.util.regex`. Keep to what they share: literals, character
   * classes, groups, alternation, quantifiers and the anchors `^` and `$`. Among the differences, `$` in Java also
   * matches before a final line terminator (`^[a-z]+$` accepts `"abc\n"`), and possessive quantifiers, inline flags
   * such as `(?i)`, `\A`, `\z` and `\Q...\E` are Java's alone.
   *
   * @param regex the regular expression
   */
  def withPattern(regex: String): StringSchema = copy(pattern = Some(regex))

  /** Set or clear the pattern (see the other `withPattern`); `None` lifts the restriction. */
  def withPattern(regex: Option[String]): StringSchema = copy(pattern = regex)
}

object StringSchema {

  /** Creates a [[StringSchema]]; [[Schema.string]] is the usual way in. */
  def apply(
    description: String,
    enumValues: Option[Seq[String]] = None,
    minLength: Option[Int] = None,
    maxLength: Option[Int] = None,
    pattern: Option[String] = None
  ): StringSchema =
    new StringSchema(description, enumValues, minLength, maxLength, pattern)

  /** The `apply` of 1.0.0, from before `pattern` (#2954): 1.x keeps its signature. */
  def apply(
    description: String,
    enumValues: Option[Seq[String]],
    minLength: Option[Int],
    maxLength: Option[Int]
  ): StringSchema =
    new StringSchema(description, enumValues, minLength, maxLength, None)
}

/**
 * Number schema (floating-point) with range and divisibility constraints.
 *
 * @param description      Human-readable description shown to the LLM
 * @param isInteger        When `true` the JSON type is `"integer"` instead of `"number"`
 * @param minimum          Inclusive lower bound
 * @param maximum          Inclusive upper bound
 * @param exclusiveMinimum Exclusive lower bound
 * @param exclusiveMaximum Exclusive upper bound
 * @param multipleOf       Value must be a multiple of this number
 */
@Stable
final case class NumberSchema private (
  description: String,
  isInteger: Boolean,
  minimum: Option[Double],
  maximum: Option[Double],
  exclusiveMinimum: Option[Double],
  exclusiveMaximum: Option[Double],
  multipleOf: Option[Double]
) extends SchemaDefinition[Double] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val base = ujson.Obj(
      "type"        -> ujson.Str(if (isInteger) "integer" else "number"),
      "description" -> ujson.Str(description)
    )

    minimum.foreach(min => base("minimum") = ujson.Num(min))
    maximum.foreach(max => base("maximum") = ujson.Num(max))
    exclusiveMinimum.foreach(min => base("exclusiveMinimum") = ujson.Num(min))
    exclusiveMaximum.foreach(max => base("exclusiveMaximum") = ujson.Num(max))
    multipleOf.foreach(multiple => base("multipleOf") = ujson.Num(multiple))

    base
  }

  /**
   * Require the value to be an integer multiple of `multiple`.
   *
   * @param multiple The divisor
   */
  def withMultipleOf(multiple: Double): NumberSchema =
    copy(multipleOf = Some(multiple))

  /** Set or clear the divisor the value must be a multiple of. */
  def withMultipleOf(multiple: Option[Double]): NumberSchema = copy(multipleOf = multiple)

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): NumberSchema = copy(description = description)

  /** When `true` the JSON type is `"integer"` instead of `"number"`. */
  def withIsInteger(isInteger: Boolean): NumberSchema = copy(isInteger = isInteger)

  /** Set the inclusive lower bound. */
  def withMinimum(min: Double): NumberSchema = copy(minimum = Some(min))

  /** Set or clear the inclusive lower bound. */
  def withMinimum(min: Option[Double]): NumberSchema = copy(minimum = min)

  /** Set the inclusive upper bound. */
  def withMaximum(max: Double): NumberSchema = copy(maximum = Some(max))

  /** Set or clear the inclusive upper bound. */
  def withMaximum(max: Option[Double]): NumberSchema = copy(maximum = max)

  /** Set the exclusive lower bound. */
  def withExclusiveMinimum(min: Double): NumberSchema = copy(exclusiveMinimum = Some(min))

  /** Set or clear the exclusive lower bound. */
  def withExclusiveMinimum(min: Option[Double]): NumberSchema = copy(exclusiveMinimum = min)

  /** Set the exclusive upper bound. */
  def withExclusiveMaximum(max: Double): NumberSchema = copy(exclusiveMaximum = Some(max))

  /** Set or clear the exclusive upper bound. */
  def withExclusiveMaximum(max: Option[Double]): NumberSchema = copy(exclusiveMaximum = max)
}

object NumberSchema {

  /** Creates a [[NumberSchema]]; [[Schema.number]] is the usual way in. */
  def apply(
    description: String,
    isInteger: Boolean = false,
    minimum: Option[Double] = None,
    maximum: Option[Double] = None,
    exclusiveMinimum: Option[Double] = None,
    exclusiveMaximum: Option[Double] = None,
    multipleOf: Option[Double] = None
  ): NumberSchema =
    new NumberSchema(description, isInteger, minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf)
}

/**
 * Integer schema with range and divisibility constraints.
 *
 * @param description      Human-readable description shown to the LLM
 * @param minimum          Inclusive lower bound
 * @param maximum          Inclusive upper bound
 * @param exclusiveMinimum Exclusive lower bound
 * @param exclusiveMaximum Exclusive upper bound
 * @param multipleOf       Value must be a multiple of this integer
 */
@Stable
final case class IntegerSchema private (
  description: String,
  minimum: Option[Int],
  maximum: Option[Int],
  exclusiveMinimum: Option[Int],
  exclusiveMaximum: Option[Int],
  multipleOf: Option[Int]
) extends SchemaDefinition[Int] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val base = ujson.Obj(
      "type"        -> ujson.Str("integer"),
      "description" -> ujson.Str(description)
    )

    minimum.foreach(min => base("minimum") = ujson.Num(min))
    maximum.foreach(max => base("maximum") = ujson.Num(max))
    exclusiveMinimum.foreach(min => base("exclusiveMinimum") = ujson.Num(min))
    exclusiveMaximum.foreach(max => base("exclusiveMaximum") = ujson.Num(max))
    multipleOf.foreach(multiple => base("multipleOf") = ujson.Num(multiple))

    base
  }

  /**
   * Require the value to be an integer multiple of `multiple`.
   *
   * @param multiple The divisor
   */
  def withMultipleOf(multiple: Int): IntegerSchema =
    copy(multipleOf = Some(multiple))

  /** Set or clear the divisor the value must be a multiple of. */
  def withMultipleOf(multiple: Option[Int]): IntegerSchema = copy(multipleOf = multiple)

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): IntegerSchema = copy(description = description)

  /** Set the inclusive lower bound. */
  def withMinimum(min: Int): IntegerSchema = copy(minimum = Some(min))

  /** Set or clear the inclusive lower bound. */
  def withMinimum(min: Option[Int]): IntegerSchema = copy(minimum = min)

  /** Set the inclusive upper bound. */
  def withMaximum(max: Int): IntegerSchema = copy(maximum = Some(max))

  /** Set or clear the inclusive upper bound. */
  def withMaximum(max: Option[Int]): IntegerSchema = copy(maximum = max)

  /** Set the exclusive lower bound. */
  def withExclusiveMinimum(min: Int): IntegerSchema = copy(exclusiveMinimum = Some(min))

  /** Set or clear the exclusive lower bound. */
  def withExclusiveMinimum(min: Option[Int]): IntegerSchema = copy(exclusiveMinimum = min)

  /** Set the exclusive upper bound. */
  def withExclusiveMaximum(max: Int): IntegerSchema = copy(exclusiveMaximum = Some(max))

  /** Set or clear the exclusive upper bound. */
  def withExclusiveMaximum(max: Option[Int]): IntegerSchema = copy(exclusiveMaximum = max)
}

object IntegerSchema {

  /** Creates an [[IntegerSchema]]; [[Schema.integer]] is the usual way in. */
  def apply(
    description: String,
    minimum: Option[Int] = None,
    maximum: Option[Int] = None,
    exclusiveMinimum: Option[Int] = None,
    exclusiveMaximum: Option[Int] = None,
    multipleOf: Option[Int] = None
  ): IntegerSchema =
    new IntegerSchema(description, minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf)
}

/**
 * Boolean schema.
 *
 * @param description Human-readable description shown to the LLM
 */
@Stable
final case class BooleanSchema private (
  description: String
) extends SchemaDefinition[Boolean] {
  def toJsonSchema(strict: Boolean): ujson.Value =
    ujson.Obj(
      "type"        -> ujson.Str("boolean"),
      "description" -> ujson.Str(description)
    )

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): BooleanSchema = copy(description = description)
}

object BooleanSchema {

  /** Creates a [[BooleanSchema]]; [[Schema.boolean]] is the usual way in. */
  def apply(description: String): BooleanSchema = new BooleanSchema(description)
}

/**
 * Array schema with item type and size constraints.
 *
 * @param description Human-readable description shown to the LLM
 * @param itemSchema  Schema applied to every element of the array
 * @param minItems    Minimum number of elements (inclusive)
 * @param maxItems    Maximum number of elements (inclusive)
 * @param uniqueItems When `true`, all elements must be distinct
 */
@Stable
final case class ArraySchema[A] private (
  description: String,
  itemSchema: SchemaDefinition[A],
  minItems: Option[Int],
  maxItems: Option[Int],
  uniqueItems: Boolean
) extends SchemaDefinition[Seq[A]] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val base = ujson.Obj(
      "type"        -> ujson.Str("array"),
      "description" -> ujson.Str(description),
      "items"       -> itemSchema.toJsonSchema(strict)
    )

    minItems.foreach(min => base("minItems") = ujson.Num(min))
    maxItems.foreach(max => base("maxItems") = ujson.Num(max))
    if (uniqueItems) base("uniqueItems") = ujson.Bool(true)

    base
  }

  /**
   * Require all array elements to be unique.
   *
   * @param unique `true` to enforce uniqueness (default)
   */
  def withUniqueItems(unique: Boolean = true): ArraySchema[A] =
    copy(uniqueItems = unique)

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): ArraySchema[A] = copy(description = description)

  /** Replace the schema every element must conform to. */
  def withItemSchema[B](itemSchema: SchemaDefinition[B]): ArraySchema[B] =
    new ArraySchema(description, itemSchema, minItems, maxItems, uniqueItems)

  /** Set the minimum number of elements (inclusive). */
  def withMinItems(min: Int): ArraySchema[A] = copy(minItems = Some(min))

  /** Set or clear the minimum number of elements (inclusive). */
  def withMinItems(min: Option[Int]): ArraySchema[A] = copy(minItems = min)

  /** Set the maximum number of elements (inclusive). */
  def withMaxItems(max: Int): ArraySchema[A] = copy(maxItems = Some(max))

  /** Set or clear the maximum number of elements (inclusive). */
  def withMaxItems(max: Option[Int]): ArraySchema[A] = copy(maxItems = max)
}

object ArraySchema {

  /** Creates an [[ArraySchema]]; [[Schema.array]] is the usual way in. */
  def apply[A](
    description: String,
    itemSchema: SchemaDefinition[A],
    minItems: Option[Int] = None,
    maxItems: Option[Int] = None,
    uniqueItems: Boolean = false
  ): ArraySchema[A] =
    new ArraySchema(description, itemSchema, minItems, maxItems, uniqueItems)
}

/**
 * A named property within an [[ObjectSchema]].
 *
 * @param name     Property key in the JSON object
 * @param schema   Schema applied to the property value
 * @param required Whether the property is required
 */
@Stable
final case class PropertyDefinition[T] private (
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean
) {

  /** Replace the property key. */
  def withName(name: String): PropertyDefinition[T] = copy(name = name)

  /** Replace the schema applied to the property value. */
  def withSchema[U](schema: SchemaDefinition[U]): PropertyDefinition[U] = new PropertyDefinition(name, schema, required)

  /** Set whether the property is required. */
  def withRequired(required: Boolean): PropertyDefinition[T] = copy(required = required)
}

object PropertyDefinition {

  /** Creates a [[PropertyDefinition]]; [[Schema.property]] is the usual way in. */
  def apply[T](name: String, schema: SchemaDefinition[T], required: Boolean = true): PropertyDefinition[T] =
    new PropertyDefinition(name, schema, required)
}

/**
 * Object schema with a fixed set of typed properties.
 *
 * In strict mode all properties are emitted as required, as OpenAI strict-mode
 * tool definitions demand, and a property whose [[PropertyDefinition.required]]
 * flag is `false` is emitted as nullable: the model sends `null` where it would
 * have left the property out, which `SafeParameterExtractor`'s `getOptional*`
 * reads as absent.
 *
 * @param description          Human-readable description shown to the LLM
 * @param properties           Ordered sequence of property definitions
 * @param additionalProperties Whether to allow extra keys beyond those listed
 */
@Stable
final case class ObjectSchema[T] private (
  description: String,
  properties: Seq[PropertyDefinition[_]],
  additionalProperties: Boolean
) extends SchemaDefinition[T] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val props = ujson.Obj()

    // in strict mode all properties are required, so an optional one is one that may be null
    val required = (if (strict) properties else properties.filter(_.required)).map(_.name).distinct

    properties.foreach { prop =>
      val rendered = prop.schema.toJsonSchema(strict)
      props(prop.name) = if (strict && !prop.required) JsonSchemaJson.nullable(rendered) else rendered
    }

    ujson.Obj(
      "type"                 -> ujson.Str("object"),
      "description"          -> ujson.Str(description),
      "properties"           -> props,
      "required"             -> ujson.Arr(required.map(ujson.Str(_)): _*),
      "additionalProperties" -> ujson.Bool(additionalProperties)
    )
  }

  /**
   * Return a copy of this schema with `property` appended to the properties list.
   *
   * @param property The property definition to add
   */
  def withProperty[P](property: PropertyDefinition[P]): ObjectSchema[T] =
    copy(properties = properties :+ property)

  /**
   * Add a required field to the object schema
   * @param name The name of the property
   * @param schema The schema definition for the property
   * @return A new ObjectSchema with the required property added
   */
  def withRequiredField[P](name: String, schema: SchemaDefinition[P]): ObjectSchema[T] =
    withProperty(PropertyDefinition(name, schema, required = true))

  /**
   * Add an optional field to the object schema
   * @param name The name of the property
   * @param schema The schema definition for the property
   * @return A new ObjectSchema with the optional property added
   */
  def withOptionalField[P](name: String, schema: SchemaDefinition[P]): ObjectSchema[T] =
    withProperty(PropertyDefinition(name, schema, required = false))

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): ObjectSchema[T] = copy(description = description)

  /** Replace the whole property list; [[withProperty]] appends one. */
  def withProperties(properties: Seq[PropertyDefinition[_]]): ObjectSchema[T] = copy(properties = properties)

  /** Set whether keys beyond the listed properties are allowed. */
  def withAdditionalProperties(allowed: Boolean): ObjectSchema[T] = copy(additionalProperties = allowed)
}

object ObjectSchema {

  /** Creates an [[ObjectSchema]]; [[Schema.`object`]] is the usual way in. */
  def apply[T](
    description: String,
    properties: Seq[PropertyDefinition[_]],
    additionalProperties: Boolean = false
  ): ObjectSchema[T] =
    new ObjectSchema[T](description, properties, additionalProperties)
}

/**
 * Nullable schema wrapper that widens an existing schema to also accept `null`.
 *
 * Emits `"type": ["<original>", "null"]` in the JSON Schema output, adds `null` to an `enum`, and adds a
 * `{"type": "null"}` alternative to an `anyOf`.
 *
 * @param underlying The non-nullable schema to wrap
 */
@Stable
case class NullableSchema[T](
  underlying: SchemaDefinition[T]
) extends SchemaDefinition[Option[T]] {
  def toJsonSchema(strict: Boolean): ujson.Value = JsonSchemaJson.nullable(underlying.toJsonSchema(strict))
}

/**
 * A union: a value that matches at least one of `alternatives` (JSON Schema `anyOf`).
 *
 * Emits `{"description": ..., "anyOf": [...]}`. There is always at least one alternative: an `anyOf` with none
 * matches nothing.
 *
 * @param description  Human-readable description shown to the LLM
 * @param alternatives The schemas a value may match, in order
 * @tparam T The Scala type the union reads as (unused at runtime)
 */
@Stable
final case class AnyOfSchema[T] private (
  description: String,
  alternatives: Seq[SchemaDefinition[_]]
) extends SchemaDefinition[T] {
  def toJsonSchema(strict: Boolean): ujson.Value =
    ujson.Obj(
      "description" -> ujson.Str(description),
      "anyOf"       -> ujson.Arr.from(alternatives.map(_.toJsonSchema(strict)))
    )

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): AnyOfSchema[T] = copy(description = description)

  /** Replace the alternatives; at least one. */
  def withAlternatives(first: SchemaDefinition[_], rest: SchemaDefinition[_]*): AnyOfSchema[T] =
    copy(alternatives = first +: rest)

  /** Append one more alternative. */
  def withAlternative(alternative: SchemaDefinition[_]): AnyOfSchema[T] =
    copy(alternatives = alternatives :+ alternative)
}

object AnyOfSchema {

  /** Creates an [[AnyOfSchema]] of at least one alternative; [[Schema.anyOf]] is the usual way in. */
  def apply[T](description: String, first: SchemaDefinition[_], rest: SchemaDefinition[_]*): AnyOfSchema[T] =
    new AnyOfSchema[T](description, first +: rest)
}

/**
 * A JSON Schema written out as JSON, for what the other cases cannot express: `$ref`, `oneOf`, `format`, a
 * free-form object, a schema received from elsewhere (an MCP server's). A string's `pattern` no longer needs it
 * (`StringSchema.withPattern`), and an agent's default argument validator checks one written here.
 *
 * It is emitted as it was written, whatever `strict` is: nothing in it is made required or nullable. Whether a
 * provider accepts it, and whether an agent's argument validator supports its keywords, is for the caller to know.
 *
 * @param json The JSON Schema, an object
 */
@Stable
final case class RawJsonSchema private (
  private val schema: ujson.Obj
) extends SchemaDefinition[ujson.Value] {

  /** The JSON Schema; a fresh copy each call, since `ujson` values are mutable. */
  def json: ujson.Obj = ujson.copy(schema).obj

  def toJsonSchema(strict: Boolean): ujson.Value = json

  /** Replace the JSON Schema. */
  def withJson(json: ujson.Obj): RawJsonSchema = RawJsonSchema(json)
}

object RawJsonSchema {

  /** Creates a [[RawJsonSchema]] from a copy of `json`; [[Schema.raw]] is the usual way in. */
  def apply(json: ujson.Obj): RawJsonSchema = new RawJsonSchema(ujson.copy(json).obj)
}

/** Operations on rendered JSON Schema that more than one schema case, and `llm4s-schema-derivation`, share. */
private[llm4s] object JsonSchemaJson {

  private val NullType: ujson.Value = ujson.Str("null")

  /**
   * `schema` widened to accept `null`: one more `anyOf` alternative, or `null` added to its `type` (and to its
   * `enum`, since a nullable enum is one of its values or null). A schema with neither, which only
   * [[RawJsonSchema]] writes, becomes an `anyOf` of itself and null; the empty schema already accepts null.
   * Widening twice changes nothing.
   */
  def nullable(schema: ujson.Value): ujson.Value = {
    val widened = ujson.Obj.from(schema.obj)
    (widened.value.get("anyOf"), widened.value.get("type")) match {
      case (Some(alternatives: ujson.Arr), _) =>
        val nullAlternative = ujson.Obj("type" -> NullType)
        if (!alternatives.value.contains(nullAlternative))
          widened("anyOf") = ujson.Arr.from(alternatives.value :+ nullAlternative)
        widened
      case (_, Some(declared)) =>
        declared match {
          case ujson.Str(name) => widened("type") = ujson.Arr(ujson.Str(name), NullType)
          case names: ujson.Arr =>
            if (!names.value.contains(NullType)) widened("type") = ujson.Arr.from(names.value :+ NullType)
          case _ => ()
        }
        // a nullable enum is one of its values, or null
        widened.value.get("enum") match {
          case Some(values: ujson.Arr) if !values.value.contains(ujson.Null) =>
            widened("enum") = ujson.Arr.from(values.value :+ ujson.Null)
          case _ => ()
        }
        widened
      case _ if widened.value.isEmpty => widened
      case _ =>
        val union = ujson.Obj()
        widened.value.get("description").foreach(d => union("description") = d)
        union("anyOf") = ujson.Arr(widened, ujson.Obj("type" -> NullType))
        union
    }
  }
}
