package org.llm4s.toolapi

/**
 * Base trait for all JSON Schema definitions used in tool parameter specifications.
 *
 * Each concrete subclass corresponds to a JSON Schema type and can be converted
 * to a `ujson.Value` for inclusion in OpenAI-compatible tool definitions via
 * [[SchemaDefinition.toJsonSchema]].
 */
sealed trait SchemaDefinition[T] {

  /**
   * Serialise this schema to a JSON Schema `ujson.Value`.
   *
   * @param strict When `true`, all object properties are treated as required,
   *               matching OpenAI strict-mode tool definitions.
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
 */
case class StringSchema(
  description: String,
  enumValues: Option[Seq[String]] = None,
  minLength: Option[Int] = None,
  maxLength: Option[Int] = None
) extends SchemaDefinition[String] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val base = ujson.Obj(
      "type"        -> ujson.Str("string"),
      "description" -> ujson.Str(description)
    )

    enumValues.foreach(values => base("enum") = ujson.Arr(values.map(ujson.Str(_)): _*))
    minLength.foreach(min => base("minLength") = ujson.Num(min))
    maxLength.foreach(max => base("maxLength") = ujson.Num(max))

    base
  }

  /**
   * Restrict valid values to the given closed enumeration.
   *
   * @param values Allowed string values
   */
  def withEnum(values: Seq[String]): StringSchema = copy(enumValues = Some(values))

  /**
   * Add minimum and/or maximum length constraints.
   *
   * @param min Optional minimum length (inclusive)
   * @param max Optional maximum length (inclusive)
   */
  def withLengthConstraints(min: Option[Int] = None, max: Option[Int] = None): StringSchema =
    copy(minLength = min, maxLength = max)
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
case class NumberSchema(
  description: String,
  isInteger: Boolean = false,
  minimum: Option[Double] = None,
  maximum: Option[Double] = None,
  exclusiveMinimum: Option[Double] = None,
  exclusiveMaximum: Option[Double] = None,
  multipleOf: Option[Double] = None
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
   * Add inclusive minimum and/or maximum range constraints.
   *
   * @param min Optional inclusive lower bound
   * @param max Optional inclusive upper bound
   */
  def withRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema =
    copy(minimum = min, maximum = max)

  /**
   * Add exclusive minimum and/or maximum range constraints.
   *
   * @param min Optional exclusive lower bound
   * @param max Optional exclusive upper bound
   */
  def withExclusiveRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema =
    copy(exclusiveMinimum = min, exclusiveMaximum = max)

  /**
   * Require the value to be an integer multiple of `multiple`.
   *
   * @param multiple The divisor
   */
  def withMultipleOf(multiple: Double): NumberSchema =
    copy(multipleOf = Some(multiple))
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
case class IntegerSchema(
  description: String,
  minimum: Option[Int] = None,
  maximum: Option[Int] = None,
  exclusiveMinimum: Option[Int] = None,
  exclusiveMaximum: Option[Int] = None,
  multipleOf: Option[Int] = None
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
   * Add inclusive minimum and/or maximum range constraints.
   *
   * @param min Optional inclusive lower bound
   * @param max Optional inclusive upper bound
   */
  def withRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema =
    copy(minimum = min, maximum = max)

  /**
   * Add exclusive minimum and/or maximum range constraints.
   *
   * @param min Optional exclusive lower bound
   * @param max Optional exclusive upper bound
   */
  def withExclusiveRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema =
    copy(exclusiveMinimum = min, exclusiveMaximum = max)

  /**
   * Require the value to be an integer multiple of `multiple`.
   *
   * @param multiple The divisor
   */
  def withMultipleOf(multiple: Int): IntegerSchema =
    copy(multipleOf = Some(multiple))
}

/**
 * Boolean schema.
 *
 * @param description Human-readable description shown to the LLM
 */
case class BooleanSchema(
  description: String
) extends SchemaDefinition[Boolean] {
  def toJsonSchema(strict: Boolean): ujson.Value =
    ujson.Obj(
      "type"        -> ujson.Str("boolean"),
      "description" -> ujson.Str(description)
    )
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
case class ArraySchema[A](
  description: String,
  itemSchema: SchemaDefinition[A],
  minItems: Option[Int] = None,
  maxItems: Option[Int] = None,
  uniqueItems: Boolean = false
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
   * Add minimum and/or maximum array size constraints.
   *
   * @param min Optional minimum number of items
   * @param max Optional maximum number of items
   */
  def withSizeConstraints(min: Option[Int] = None, max: Option[Int] = None): ArraySchema[A] =
    copy(minItems = min, maxItems = max)

  /**
   * Require all array elements to be unique.
   *
   * @param unique `true` to enforce uniqueness (default)
   */
  def withUniqueItems(unique: Boolean = true): ArraySchema[A] =
    copy(uniqueItems = unique)
}

/**
 * A named property within an [[ObjectSchema]].
 *
 * @param name     Property key in the JSON object
 * @param schema   Schema applied to the property value
 * @param required Whether the property is required
 */
case class PropertyDefinition[T](
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean = true
)

/**
 * Object schema with a fixed set of typed properties.
 *
 * In strict mode all properties are emitted as required regardless of
 * the individual [[PropertyDefinition.required]] flag, matching the
 * behaviour expected by OpenAI strict-mode tool definitions.
 *
 * @param description          Human-readable description shown to the LLM
 * @param properties           Ordered sequence of property definitions
 * @param additionalProperties Whether to allow extra keys beyond those listed
 */
case class ObjectSchema[T](
  description: String,
  properties: Seq[PropertyDefinition[_]],
  additionalProperties: Boolean = false
) extends SchemaDefinition[T] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val props = ujson.Obj()

    // in strict mode all properties are required
    val required = (if (strict) properties else properties.filter(_.required)).map(_.name)

    properties.foreach(prop => props(prop.name) = prop.schema.toJsonSchema(strict))

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
}

/**
 * Nullable schema wrapper that widens an existing schema to also accept `null`.
 *
 * Emits `"type": ["<original>", "null"]` in the JSON Schema output.
 *
 * @param underlying The non-nullable schema to wrap
 */
case class NullableSchema[T](
  underlying: SchemaDefinition[T]
) extends SchemaDefinition[Option[T]] {
  def toJsonSchema(strict: Boolean): ujson.Value = {
    val schema    = underlying.toJsonSchema(strict).obj
    val typeField = schema.get("type")

    typeField match {
      case Some(ujson.Str(typeValue)) =>
        // Replace type field with array of types
        schema("type") = ujson.Arr(ujson.Str(typeValue), ujson.Str("null"))
      case Some(arr: ujson.Arr) =>
        // Add null to existing type array
        schema("type") = ujson.Arr.from(arr.value :+ ujson.Str("null"))
      case _ =>
        // Create new type array if none exists
        schema("type") = ujson.Arr(ujson.Str("null"))
    }

    ujson.Obj.from(schema)
  }
}
