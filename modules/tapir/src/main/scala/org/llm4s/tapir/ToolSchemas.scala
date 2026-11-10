package org.llm4s.tapir

import org.llm4s.toolapi.{
  ArraySchema,
  BooleanSchema,
  IntegerSchema,
  NullableSchema,
  NumberSchema,
  ObjectSchema,
  PropertyDefinition,
  SchemaDefinition,
  StringSchema
}
import sttp.tapir.{ Schema, SchemaType, Validator }

/**
 * Converts a tapir [[sttp.tapir.Schema Schema]] to the JSON schema of a tool parameter, keeping the names,
 * descriptions, optionality and the validators JSON Schema can state: enumerations, numeric bounds, string lengths
 * and collection sizes. A pattern, which the tool API has no field for, is added to the description.
 *
 * A schema that cannot be stated (a recursive reference, a map, binary data, or a sealed hierarchy that is not an
 * enumeration) is a `Left` naming its path.
 */
private[tapir] object ToolSchemas {

  /**
   * The schema of a value, and whether it may be left out. An optional property is also nullable, so that it can be
   * stated in OpenAI's strict mode, where every property is required.
   */
  def property(
    name: String,
    path: String,
    schema: Schema[?],
    description: Option[String]
  ): Either[String, PropertyDefinition[?]] =
    definition(path, schema, description.orElse(schema.description).orElse(Some(name))).map { definition =>
      if (schema.isOptional) PropertyDefinition(name, NullableSchema(definition), required = false)
      else PropertyDefinition(name, definition, required = true)
    }

  /** The schema of a value, with any optional wrapper removed. */
  def definition(path: String, schema: Schema[?], description: Option[String]): Either[String, SchemaDefinition[?]] = {
    val text       = describe(schema, description, path)
    val validators = primitives(schema.validator)
    schema.schemaType match {
      case SchemaType.SOption(element) =>
        definition(path, element, description.orElse(schema.description))
      case SchemaType.SString() =>
        Right(string(text, validators))
      case SchemaType.SDate() =>
        Right(string(s"$text (an ISO-8601 date)", validators))
      case SchemaType.SDateTime() =>
        Right(string(s"$text (an ISO-8601 date and time)", validators))
      case SchemaType.SInteger() =>
        Right(integer(withEnumeration(text, validators), validators))
      case SchemaType.SNumber() =>
        Right(number(withEnumeration(text, validators), validators))
      case SchemaType.SBoolean() =>
        Right(BooleanSchema(text))
      case SchemaType.SArray(element) =>
        definition(s"$path[]", element, element.description.orElse(Some(s"an item of $text"))).map { item =>
          ArraySchema(text, item)
            .withMinItems(validators.collectFirst { case v: Validator.MinSize[?, ?] => v.value })
            .withMaxItems(validators.collectFirst { case v: Validator.MaxSize[?, ?] => v.value })
        }
      case SchemaType.SProduct(fields) =>
        fields
          .foldLeft[Either[String, Vector[PropertyDefinition[?]]]](Right(Vector.empty)) { (acc, field) =>
            acc.flatMap { props =>
              val name = field.name.encodedName
              property(name, s"$path.$name", field.schema, None).map(props :+ _)
            }
          }
          .map(props => ObjectSchema[Any](text, props))
      case SchemaType.SCoproduct(_, _) if enumeration(validators).nonEmpty =>
        Right(string(text, validators))
      case _: SchemaType.SCoproduct[?] =>
        Left(s"$path is a sealed hierarchy, which a tool parameter cannot describe")
      case _: SchemaType.SOpenProduct[?, ?] =>
        Left(s"$path is a map, which a tool parameter cannot describe")
      case _: SchemaType.SRef[?] =>
        Left(s"$path is a recursive type, which a tool parameter cannot describe")
      case SchemaType.SBinary() =>
        Left(s"$path is binary data, which a tool parameter cannot describe")
    }
  }

  private def describe(schema: Schema[?], description: Option[String], path: String): String = {
    val base    = description.orElse(schema.description).getOrElse(path)
    val pattern = primitives(schema.validator).collectFirst { case p: Validator.Pattern[?] => p.value }
    pattern.fold(base)(p => s"$base (must match the pattern $p)")
  }

  private def string(text: String, validators: List[Validator.Primitive[?]]): StringSchema =
    StringSchema(text)
      .withEnum(Option(enumeration(validators)).filter(_.nonEmpty))
      .withMinLength(validators.collectFirst { case v: Validator.MinLength[?] => v.value })
      .withMaxLength(validators.collectFirst { case v: Validator.MaxLength[?] => v.value })

  private def integer(text: String, validators: List[Validator.Primitive[?]]): SchemaDefinition[?] = {
    val (min, max) = bounds(validators)
    val all        = min.toList.map(_._1) ++ max.toList.map(_._1)
    if (all.forall(b => b.isWhole && b >= Int.MinValue && b <= Int.MaxValue)) {
      val inclusiveMin = min.collect { case (v, false) => v.toInt }
      val exclusiveMin = min.collect { case (v, true) => v.toInt }
      val inclusiveMax = max.collect { case (v, false) => v.toInt }
      val exclusiveMax = max.collect { case (v, true) => v.toInt }
      IntegerSchema(text)
        .withMinimum(inclusiveMin)
        .withExclusiveMinimum(exclusiveMin)
        .withMaximum(inclusiveMax)
        .withExclusiveMaximum(exclusiveMax)
    } else numberWithin(NumberSchema(text).withIsInteger(true), min, max)
  }

  private def number(text: String, validators: List[Validator.Primitive[?]]): NumberSchema = {
    val (min, max) = bounds(validators)
    numberWithin(NumberSchema(text), min, max)
  }

  private def numberWithin(
    schema: NumberSchema,
    min: Option[(Double, Boolean)],
    max: Option[(Double, Boolean)]
  ): NumberSchema =
    schema
      .withMinimum(min.collect { case (v, false) => v })
      .withExclusiveMinimum(min.collect { case (v, true) => v })
      .withMaximum(max.collect { case (v, false) => v })
      .withExclusiveMaximum(max.collect { case (v, true) => v })

  /** The lower and upper bounds, each with whether it is exclusive. */
  private def bounds(validators: List[Validator.Primitive[?]]): (Option[(Double, Boolean)], Option[(Double, Boolean)]) =
    (
      validators.collectFirst { case v: Validator.Min[t] => (v.valueIsNumeric.toDouble(v.value: t), v.exclusive) },
      validators.collectFirst { case v: Validator.Max[t] => (v.valueIsNumeric.toDouble(v.value: t), v.exclusive) }
    )

  /** The JSON Schema `enum` keyword is a string list in the tool API, so a numeric enumeration is described. */
  private def withEnumeration(text: String, validators: List[Validator.Primitive[?]]): String = {
    val values = enumeration(validators)
    if (values.isEmpty) text else s"$text (one of ${values.mkString(", ")})"
  }

  /** The values of an enumeration validator, as they are sent. */
  private def enumeration(validators: List[Validator.Primitive[?]]): List[String] =
    validators
      .collectFirst { case v: Validator.Enumeration[t] =>
        v.possibleValues.map((value: t) => v.encode.flatMap(encode => encode(value)).getOrElse(value).toString)
      }
      .getOrElse(Nil)

  /** The primitive validators that every value must pass: those of an `and`, and of a mapped validator. */
  private def primitives(validator: Validator[?]): List[Validator.Primitive[?]] = validator match {
    case p: Validator.Primitive[?] => List(p)
    case Validator.All(validators) => validators.toList.flatMap(primitives)
    case m: Validator.Mapped[?, ?] => primitives(m.wrapped)
    case _: Validator.Any[?]       => Nil
  }
}
