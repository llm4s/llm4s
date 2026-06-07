package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * Validates that output is valid JSON matching an optional schema.
 *
 * Supported JSON Schema subset:
 *
 * {
 *   "required": ["name"],
 *   "properties": {
 *     "name": {
 *       "type": "string"
 *     }
 *   }
 * }
 *
 * Supported types:
 * string, number, boolean, object, array, null
 *
 * @param schema Optional JSON schema to validate against.
 *               Supports only:
 *               - required
 *               - properties.<field>.type
 */
class JSONValidator(schema: Option[ujson.Value] = None) extends OutputGuardrail {

  override def validate(value: String): Result[String] =
    // 1) Parse JSON
    Try(ujson.read(value)) match {
      case Failure(ex) =>
        Left(
          ValidationError.invalid(
            "output",
            s"Output is not valid JSON: ${ex.getMessage}"
          )
        )

      case Success(parsedJson) =>
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
  private def validateAgainstSchema(
    json: ujson.Value,
    schema: ujson.Value
  ): Option[String] = {

    val requiredKeys = extractRequiredKeys(schema)
    val properties   = extractProperties(schema)

    json match {
      case obj: ujson.Obj =>
        val missing = requiredKeys.filterNot(obj.obj.contains)

        if (missing.nonEmpty) {
          Some(s"Missing required JSON fields: ${missing.mkString(", ")}")
        } else {

          properties.collectFirst {
            case (fieldName, expectedType)
                if obj.obj.get(fieldName).exists(value => getJsonType(value) != expectedType) =>
              val actualType =
                getJsonType(obj.obj(fieldName))

              s"Field '$fieldName' has type '$actualType', expected '$expectedType'"
          }
        }

      case _ =>
        if (requiredKeys.nonEmpty || properties.nonEmpty)
          Some(
            s"Schema requires an object, but got a non-object value"
          )
        else
          None
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
   * Extracts property type definitions from a JSON schema.
   * Looks for: { "properties": { "field1": { "type": "string" } } }
   */
  private def extractProperties(schema: ujson.Value): Map[String, String] =
    Try(schema.obj).toOption
      .flatMap(_.get("properties"))
      .collect { case ujson.Obj(props) =>
        props.collect {
          case (fieldName, ujson.Obj(fieldSchema)) if fieldSchema.get("type").exists(_.isInstanceOf[ujson.Str]) =>
            fieldName -> fieldSchema("type").str
        }.toMap
      }
      .getOrElse(Map.empty)

  /**
   * Gets the JSON type name for a given ujson.Value.
   */
  private def getJsonType(value: ujson.Value): String = value match {
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case ujson.Null    => "null"
  }

  override val name: String = "JSONValidator"

  override val description: Option[String] = Some(
    schema match {
      case Some(_) => "Validates output is valid JSON matching schema"
      case None    => "Validates output is valid JSON"
    }
  )
}

object JSONValidator {

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
