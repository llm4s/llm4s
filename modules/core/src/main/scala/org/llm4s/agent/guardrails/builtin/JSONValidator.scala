package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.{ Result, TryOps }

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
            validateRequiredFields(parsedJson, sch) match {
              case Nil => Right(value)
              case missing =>
                Left(
                  ValidationError.invalid(
                    "output",
                    s"Missing required JSON fields: ${missing.mkString(", ")}"
                  )
                )
            }
        }
    }

  /**
   * Validates that all required fields exist according to:
   *   { "required": ["field1", "field2"] }
   *
   * Returns: list of missing field names.
   */
  private def validateRequiredFields(json: ujson.Value, schema: ujson.Value): List[String] = {

    // Extract “required” keys without try/catch
    val requiredKeys: List[String] =
      Try {
        schema.obj.get("required") match {
          case Some(ujson.Arr(items)) =>
            items.collect { case ujson.Str(s) => s }.toList
          case _ =>
            List.empty
        }
      }.toResult
        .fold(_ => List.empty, identity)

    if (requiredKeys.isEmpty) {
      List.empty
    } else {
      json match {
        case obj: ujson.Obj =>
          requiredKeys.filterNot(obj.obj.contains)

        case _ =>
          // Schema expects object, but JSON root is not an object
          List("root object")
      }
    }
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
