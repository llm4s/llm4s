package org.llm4s.toolapi

/**
 * Base trait for all schema definitions
 */
sealed trait SchemaDefinition[T] {
  def toJsonSchema(strict: Boolean): ujson.Value
}

/**
 * String schema with validation options
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

  def withEnum(values: Seq[String]): StringSchema = copy(enumValues = Some(values))
  def withLengthConstraints(min: Option[Int] = None, max: Option[Int] = None): StringSchema =
    copy(minLength = min, maxLength = max)
}

/**
 * Number schema with validation options
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

  def withRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema =
    copy(minimum = min, maximum = max)

  def withExclusiveRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema =
    copy(exclusiveMinimum = min, exclusiveMaximum = max)

  def withMultipleOf(multiple: Double): NumberSchema =
    copy(multipleOf = Some(multiple))
}

/**
 * Integer schema with validation options
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

  def withRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema =
    copy(minimum = min, maximum = max)

  def withExclusiveRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema =
    copy(exclusiveMinimum = min, exclusiveMaximum = max)

  def withMultipleOf(multiple: Int): IntegerSchema =
    copy(multipleOf = Some(multiple))
}

/**
 * Boolean schema
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
 * Array schema with validation options
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

  def withSizeConstraints(min: Option[Int] = None, max: Option[Int] = None): ArraySchema[A] =
    copy(minItems = min, maxItems = max)

  def withUniqueItems(unique: Boolean = true): ArraySchema[A] =
    copy(uniqueItems = unique)
}

/**
 * Property definition for object schemas
 */
case class PropertyDefinition[T](
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean = true
)

/**
 * Object schema with properties
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

  def withProperty[P](property: PropertyDefinition[P]): ObjectSchema[T] =
    copy(properties = properties :+ property)
}

/**
 * Nullable schema wrapper
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
