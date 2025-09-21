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
