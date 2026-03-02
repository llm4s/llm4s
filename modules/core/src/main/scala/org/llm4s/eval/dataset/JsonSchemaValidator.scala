package org.llm4s.eval.dataset

import scala.collection.mutable.ListBuffer

/**
 * Lightweight structural validator for JSON values against a JSON Schema subset.
 *
 * Only the following schema keywords are recognised; all others are silently ignored:
 *  - `type`       — checks the JSON type of the value (`object`, `array`, `string`,
 *                   `number`, `boolean`, `null`); unknown type strings are skipped
 *  - `required`   — when the value is a JSON object, verifies that each named key is present
 *  - `properties` — when the value is a JSON object, recursively validates each listed
 *                   property against its sub-schema
 *
 * This is intentionally '''not''' a full JSON Schema implementation (no `if/then/else`,
 * `anyOf`, `$ref`, etc.). It is sufficient for validating the structure of
 * [[Example]] inputs and outputs in evaluation datasets.
 */
object JsonSchemaValidator {

  private val SupportedTypes = Set("object", "array", "string", "number", "boolean", "null")

  /**
   * Validates `value` against `schema`, collecting all errors before returning.
   *
   * @param value  the JSON value to validate
   * @param schema a `ujson.Obj` JSON Schema fragment; non-object schemas are accepted as-is
   * @return `Right(())` if validation passes, `Left(errors)` with one message per violation
   */
  def validate(value: ujson.Value, schema: ujson.Value): Either[List[String], Unit] = {
    val errors = ListBuffer.empty[String]
    validateInternal(value, schema, errors, "")
    val errorList = errors.result()
    if (errorList.isEmpty) Right(()) else Left(errorList)
  }

  private def validateInternal(
    value: ujson.Value,
    schema: ujson.Value,
    errors: ListBuffer[String],
    path: String
  ): Unit =
    schema.objOpt.foreach { schemaObj =>
      checkType(value, schemaObj, errors, path)
      checkRequired(value, schemaObj, errors, path)
      checkProperties(value, schemaObj, errors, path)
    }

  private def checkType(
    value: ujson.Value,
    schemaObj: ujson.Obj,
    errors: ListBuffer[String],
    path: String
  ): Unit =
    schemaObj.value.get("type").foreach { typeValue =>
      typeValue.strOpt.foreach { typeName =>
        if (SupportedTypes.contains(typeName) && !typeMatches(value, typeName)) {
          val pathStr = if (path.isEmpty) "" else s"$path: "
          errors += s"${pathStr}Expected type '$typeName' but got '${jsonTypeName(value)}'"
        }
      }
    }

  private def checkRequired(
    value: ujson.Value,
    schemaObj: ujson.Obj,
    errors: ListBuffer[String],
    path: String
  ): Unit =
    value.objOpt.foreach { valueObj =>
      schemaObj.value.get("required").foreach { requiredValue =>
        requiredValue.arrOpt.foreach { requiredArr =>
          requiredArr.value.foreach { key =>
            key.strOpt.foreach { keyStr =>
              if (!valueObj.value.contains(keyStr)) {
                val pathStr = if (path.isEmpty) "" else s"$path: "
                errors += s"${pathStr}Missing required field: $keyStr"
              }
            }
          }
        }
      }
    }

  private def checkProperties(
    value: ujson.Value,
    schemaObj: ujson.Obj,
    errors: ListBuffer[String],
    path: String
  ): Unit =
    value.objOpt.foreach { valueObj =>
      schemaObj.value.get("properties").foreach { propsValue =>
        propsValue.objOpt.foreach { propsObj =>
          propsObj.value.foreach { case (key, subSchema) =>
            valueObj.value.get(key).foreach { propertyValue =>
              val newPath = if (path.isEmpty) key else s"$path.$key"
              validateInternal(propertyValue, subSchema, errors, newPath)
            }
          }
        }
      }
    }

  private def typeMatches(value: ujson.Value, typeName: String): Boolean =
    typeName match {
      case "object"  => value match { case _: ujson.Obj => true; case _ => false }
      case "array"   => value match { case _: ujson.Arr => true; case _ => false }
      case "string"  => value match { case _: ujson.Str => true; case _ => false }
      case "number"  => value match { case _: ujson.Num => true; case _ => false }
      case "boolean" => value match { case _: ujson.Bool => true; case _ => false }
      case "null"    => value.isNull
      case _         => true
    }

  private def jsonTypeName(value: ujson.Value): String = value match {
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case ujson.Null    => "null"
  }
}
