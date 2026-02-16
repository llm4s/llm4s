package org.llm4s.toolapi

/**
 * Structured error information for tool parameter validation
 */
sealed trait ToolParameterError {
  def parameterName: String
  def getMessage: String
}

object ToolParameterError {

  /**
   * A required parameter is completely missing from the arguments
   */
  case class MissingParameter(
    parameterName: String,
    expectedType: String,
    availableParameters: List[String] = Nil
  ) extends ToolParameterError {
    def getMessage: String = {
      val available = if (availableParameters.nonEmpty) {
        s" (available: ${availableParameters.mkString(", ")})"
      } else ""
      s"required parameter '$parameterName' (type: $expectedType) is missing$available"
    }
  }

  /**
   * A required parameter is present but has a null value
   */
  case class NullParameter(
    parameterName: String,
    expectedType: String
  ) extends ToolParameterError {
    def getMessage: String =
      s"parameter '$parameterName' (type: $expectedType) is required but value was null"
  }

  /**
   * A parameter has the wrong type
   */
  case class TypeMismatch(
    parameterName: String,
    expectedType: String,
    actualType: String
  ) extends ToolParameterError {
    def getMessage: String =
      s"parameter '$parameterName' has wrong type - expected $expectedType but got $actualType"
  }

  /**
   * Cannot access a nested property because parent is not an object
   */
  case class InvalidNesting(
    parameterName: String,
    parentPath: String,
    parentType: String
  ) extends ToolParameterError {
    def getMessage: String =
      s"cannot access parameter '$parameterName' because parent '$parentPath' is $parentType, not an object"
  }

  /**
   * Multiple parameter errors
   */
  case class MultipleErrors(
    errors: List[ToolParameterError]
  ) extends ToolParameterError {
    def parameterName: String = errors.map(_.parameterName).mkString(", ")
    def getMessage: String =
      if (errors.size == 1) {
        errors.head.getMessage
      } else {
        s"multiple parameter issues:\n${errors.map(e => s"  - ${e.getMessage}").mkString("\n")}"
      }
  }
}

/**
 * Enhanced tool call errors with consistent formatting
 */
sealed trait ToolCallError {
  def toolName: String
  def getMessage: String
  def getFormattedMessage: String = s"Tool call '$toolName' ${getMessage}"
}

object ToolCallError {

  /**
   * Tool function doesn't exist
   */
  case class UnknownFunction(toolName: String) extends ToolCallError {
    def getMessage: String = "is not a recognized tool"
  }

  /**
   * Tool received null arguments when it expected an object
   */
  case class NullArguments(toolName: String) extends ToolCallError {
    def getMessage: String = "received null arguments - expected an object with required parameters"
  }

  /**
   * Tool has parameter validation errors
   */
  case class InvalidArguments(
    toolName: String,
    parameterErrors: List[ToolParameterError]
  ) extends ToolCallError {
    def getMessage: String = {
      val errorMessages = parameterErrors match {
        case single :: Nil => single.getMessage
        case multiple =>
          s"has parameter issues:\n${multiple.map(e => s"  - ${e.getMessage}").mkString("\n")}"
      }
      errorMessages
    }
  }

  /**
   * Tool execution failed (after parameters were validated)
   */
  case class ExecutionError(
    toolName: String,
    cause: Throwable
  ) extends ToolCallError {
    def getMessage: String = s"failed during execution: ${cause.getMessage}"
  }

  /**
   * Tool handler returned an error (business logic failure)
   */
  case class HandlerError(
    toolName: String,
    error: String
  ) extends ToolCallError {
    def getMessage: String = s"failed with error: $error"
  }
}

/**
 * JSON serialization helpers for structured tool error payloads.
 *
 * Converts ToolCallError and ToolParameterError instances to machine-readable JSON
 * format for ToolMessage.content, enabling LLMs and consumers to programmatically
 * parse validation errors and self-correct tool arguments.
 *
 * Output schema:
 * {{{
 * {
 *   "isError": true,
 *   "toolName": "<string>",
 *   "errorType": "<unknown_function|null_arguments|invalid_arguments|handler_error|execution_error>",
 *   "message": "<human readable summary>",
 *   "parameterErrors": [   // optional: only present for invalid_arguments
 *     {
 *       "parameterName": "<string>",
 *       "kind": "<missing_parameter|null_parameter|type_mismatch|invalid_nesting>",
 *       "expectedType": "<string|null>",
 *       "receivedType": "<string|null>",
 *       "availableParameters": ["<string>", ...]  // optional hint
 *     }
 *   ],
 *   "error": "<legacy error string for older consumers>"
 * }
 * }}}
 */
object ToolCallErrorJson {

  /**
   * Convert a ToolParameterError to structured JSON for parameterErrors array
   */
  def parameterErrorToJson(error: ToolParameterError): ujson.Obj = error match {
    case ToolParameterError.MissingParameter(name, expectedType, available) =>
      val obj = ujson.Obj(
        "parameterName" -> name,
        "kind"          -> "missing_parameter",
        "expectedType"  -> expectedType,
        "receivedType"  -> ujson.Null
      )
      if (available.nonEmpty) obj("availableParameters") = ujson.Arr.from(available.map(ujson.Str(_)))
      obj

    case ToolParameterError.NullParameter(name, expectedType) =>
      ujson.Obj(
        "parameterName" -> name,
        "kind"          -> "null_parameter",
        "expectedType"  -> expectedType,
        "receivedType"  -> "null"
      )

    case ToolParameterError.TypeMismatch(name, expectedType, actualType) =>
      ujson.Obj(
        "parameterName" -> name,
        "kind"          -> "type_mismatch",
        "expectedType"  -> expectedType,
        "receivedType"  -> actualType
      )

    case ToolParameterError.InvalidNesting(name, parentPath, parentType) =>
      ujson.Obj(
        "parameterName" -> name,
        "kind"          -> "invalid_nesting",
        "expectedType"  -> "object",
        "receivedType"  -> parentType,
        "parentPath"    -> parentPath
      )

    case ToolParameterError.MultipleErrors(errors) =>
      // This case shouldn't occur in parameterErrors array (flattened by caller)
      // but handle gracefully
      ujson.Obj(
        "parameterName" -> errors.map(_.parameterName).mkString(", "),
        "kind"          -> "multiple_errors"
      )
  }

  /**
   * Flatten MultipleErrors into a list of individual errors
   */
  def flattenErrors(errors: List[ToolParameterError]): List[ToolParameterError] =
    errors.flatMap {
      case ToolParameterError.MultipleErrors(nested) => flattenErrors(nested)
      case single                                    => List(single)
    }

  /**
   * Convert a ToolCallError to structured JSON for ToolMessage.content.
   *
   * Always includes the legacy "error" field for backward compatibility
   * with older consumers that expect the escaped string format.
   *
   * @param error The tool call error to serialize
   * @return ujson.Obj containing structured error information
   */
  def toJson(error: ToolCallError): ujson.Obj = {
    val legacyError = error.getFormattedMessage

    val base = ujson.Obj(
      "isError"  -> true,
      "toolName" -> error.toolName,
      "message"  -> error.getMessage,
      "error"    -> legacyError // Legacy field for backward compatibility
    )

    error match {
      case ToolCallError.UnknownFunction(_) =>
        base("errorType") = "unknown_function"
        base

      case ToolCallError.NullArguments(_) =>
        base("errorType") = "null_arguments"
        base

      case ToolCallError.InvalidArguments(_, parameterErrors) =>
        base("errorType") = "invalid_arguments"
        val flattened = flattenErrors(parameterErrors)
        base("parameterErrors") = ujson.Arr.from(flattened.map(parameterErrorToJson))
        base

      case ToolCallError.HandlerError(_, _) =>
        base("errorType") = "handler_error"
        base

      case ToolCallError.ExecutionError(_, cause) =>
        base("errorType") = "execution_error"
        // Optionally include exception class name for debugging (safe, no stack trace)
        base("exceptionType") = cause.getClass.getSimpleName
        base
    }
  }
}
