package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * Validates that output is valid JSON matching an optional schema.
 *
 * This guardrail ensures that LLM output is properly formatted JSON,
 * which is useful when requesting structured data from the agent.
 *
 * @param schema Optional JSON schema to validate against (minimal subset)
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
  private def validateAgainstSchema(json: ujson.Value, schema: ujson.Value): Option[String] = {
    val requiredKeys = extractRequiredKeys(schema)

    if (requiredKeys.isEmpty) {
      None
    } else {
      json match {
        case obj: ujson.Obj =>
          val missing = requiredKeys.filterNot(obj.obj.contains)
          if (missing.isEmpty) None
          else Some(s"Missing required JSON fields: ${missing.mkString(", ")}")

        case _ =>
          Some(s"Schema requires an object with fields [${requiredKeys.mkString(", ")}], but got a non-object value")
      }
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

  val name: String = "JSONValidator"

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
