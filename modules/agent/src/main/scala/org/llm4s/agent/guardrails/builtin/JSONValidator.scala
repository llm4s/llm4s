package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.util.Try

/**
 * Validates that output is valid JSON matching an optional schema.
 *
 * This guardrail ensures that LLM output is properly formatted JSON,
 * which is useful when requesting structured data from the agent.
 *
 * The schema is a deliberately small subset of JSON Schema:
 *
 *  - `required`: the listed fields must be present on the top-level object.
 *  - `properties.<field>.type`: when a top-level field is present, its value must have that type. The
 *    types are `string`, `number`, `integer` (a number with no fractional part), `boolean`, `object`,
 *    `array` and `null`; a list such as `["string", "null"]` accepts any of its types. A field the
 *    document does not contain is not checked (list it under `required` to demand it).
 *
 * Everything else in a schema is ignored: nested `properties`, `items`, `enum`, `format`, and a `type`
 * that is not one of the names above, so a schema this validator cannot judge never rejects output.
 * Every field whose type does not match is reported, in the order the schema lists them.
 *
 * @param schema Optional JSON schema to validate against (minimal subset)
 */
class JSONValidator(schema: Option[ujson.Value] = None) extends OutputGuardrail {

  override def validate(value: String): Result[String] =
    // 1) Parse JSON. The output is model text: nested more than 512 levels deep it is refused
    //    before it is parsed, since a value that deep overflows the stack of whatever renders it
    //    (#1562), and is reported as not valid JSON like any other unparseable output
    BoundedJson.read(value) match {
      case Left(error) =>
        Left(
          ValidationError.invalid(
            "output",
            s"Output is not valid JSON: ${error.message}"
          )
        )

      case Right(parsedJson) =>
        // 2) Validate using schema if provided
        schema match {
          case None => Right(value)

          case Some(sch) =>
            validateAgainstSchema(parsedJson, sch) match {
              case None        => Right(value)
              case Some(error) => Left(ValidationError.invalid("output", error))
            }
        }
    }

  /**
   * Validates JSON against schema, returning an error message if validation fails.
   */
  private def validateAgainstSchema(json: ujson.Value, schema: ujson.Value): Option[String] = {
    val requiredKeys = extractRequiredKeys(schema)

    json match {
      case obj: ujson.Obj =>
        val missing = requiredKeys.filterNot(obj.obj.contains)
        if (missing.nonEmpty) Some(s"Missing required JSON fields: ${missing.mkString(", ")}")
        else {
          val mismatches = typeMismatches(obj, extractPropertyTypes(schema))
          if (mismatches.isEmpty) None else Some(mismatches.mkString("; "))
        }

      case _ if requiredKeys.isEmpty =>
        // `properties` constrains an object and says nothing about other values, as in JSON Schema.
        None

      case _ =>
        Some(s"Schema requires an object with fields [${requiredKeys.mkString(", ")}], but got a non-object value")
    }
  }

  /** One message per top-level field whose value is not of any of its declared types, in schema order. */
  private def typeMismatches(obj: ujson.Obj, propertyTypes: Seq[(String, Seq[String])]): Seq[String] =
    propertyTypes.flatMap { case (field, expected) =>
      obj.obj.get(field).filterNot(value => expected.exists(matchesType(value, _))).map { value =>
        s"Field '$field' has type '${typeName(value)}', expected ${expected.map(t => s"'$t'").mkString(" or ")}"
      }
    }

  /**
   * Extracts the list of required field names from a JSON schema.
   * Looks for: { "required": ["field1", "field2"] }
   */
  private def extractRequiredKeys(schema: ujson.Value): List[String] =
    Try(schema.obj).toOption
      .flatMap(_.get("required"))
      .collect { case ujson.Arr(items) => items.collect { case ujson.Str(s) => s }.toList }
      .getOrElse(List.empty)

  /**
   * Extracts the declared type(s) of each property, in the order the schema lists them.
   * Looks for: { "properties": { "field1": { "type": "string" }, "field2": { "type": ["string", "null"] } } }
   *
   * A property is left out when it declares no type, or any type name this validator does not know,
   * because a type that cannot be judged must not reject output.
   */
  private def extractPropertyTypes(schema: ujson.Value): Seq[(String, Seq[String])] =
    Try(schema.obj).toOption
      .flatMap(_.get("properties"))
      .collect { case ujson.Obj(props) =>
        props.iterator.flatMap { case (field, fieldSchema) =>
          declaredTypes(fieldSchema).map(field -> _)
        }.toList
      }
      .getOrElse(List.empty)

  private def declaredTypes(fieldSchema: ujson.Value): Option[Seq[String]] =
    Try(fieldSchema.obj).toOption
      .flatMap(_.get("type"))
      .flatMap {
        case ujson.Str(t)  => Some(Seq(t))
        case ujson.Arr(ts) => Some(ts.toSeq.collect { case ujson.Str(t) => t }).filter(_.size == ts.size)
        case _             => None
      }
      .filter(types => types.nonEmpty && types.forall(JSONValidator.SUPPORTED_TYPES.contains))

  private def matchesType(value: ujson.Value, expected: String): Boolean =
    (value, expected) match {
      case (_: ujson.Str, "string")   => true
      case (_: ujson.Num, "number")   => true
      case (ujson.Num(n), "integer")  => n.isWhole
      case (_: ujson.Bool, "boolean") => true
      case (_: ujson.Obj, "object")   => true
      case (_: ujson.Arr, "array")    => true
      case (ujson.Null, "null")       => true
      case _                          => false
    }

  /** The JSON type name of a value; every number is a `number`, whole or not. */
  private def typeName(value: ujson.Value): String = value match {
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case ujson.Null    => "null"
  }

  val name: String = "JSONValidator"

  override val description: Option[String] = Some(
    schema match {
      case Some(_) => "Validates output is valid JSON matching schema"
      case None    => "Validates output is valid JSON"
    }
  )
}

object JSONValidator {

  /** The `type` names a schema's properties may declare; see the class Scaladoc. */
  private val SUPPORTED_TYPES: Set[String] =
    Set("string", "number", "integer", "boolean", "object", "array", "null")

  /**
   * Create a JSON validator without schema validation.
   */
  def apply(): JSONValidator = new JSONValidator()

  /**
   * Create a JSON validator with schema validation.
   */
  def withSchema(schema: ujson.Value): JSONValidator =
    new JSONValidator(Some(schema))
}
