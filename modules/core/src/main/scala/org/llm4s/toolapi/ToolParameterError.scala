package org.llm4s.toolapi

import org.llm4s.annotation.Stable

/**
 * What a tool's handler returns when it cannot do what was asked: a [[ToolParameterError]] when the call's arguments
 * are at fault, which is what [[SafeParameterExtractor]]'s getters return, or a [[ToolHandlerError.Failed]] when the
 * tool itself says no. A handler reads its arguments and reports its own failures in one `for` comprehension:
 * {{{
 * .withHandler { args =>
 *   for {
 *     city    <- args.getString("city")                    // Left(ToolParameterError) when missing or mistyped
 *     _       <- Either.cond(city.nonEmpty, (), ToolHandlerError.failed("city is empty"))
 *     weather <- lookup(city).toRight(ToolHandlerError.failed(s"no such city: $city")) // the tool's own failure
 *   } yield weather
 * }
 * }}}
 * [[ToolFunction.execute]] turns the first into [[ToolCallError.InvalidArguments]], with every parameter error in
 * its `parameterErrors`, and the second into [[ToolCallError.HandlerError]].
 *
 * A `String` is not a `ToolHandlerError`: write `Left(ToolHandlerError.failed("message"))`, and turn a helper's
 * `Either[String, A]` into a handler's with `.left.map(ToolHandlerError.failed)`.
 *
 * It is a sealed trait and not a union of `String` and `ToolParameterError`, so that Java and Scala 2.13 callers can
 * read the signatures that name it ([[ToolFunction]], and through it `CompletionOptions`).
 */
@Stable
sealed trait ToolHandlerError {

  /** The error as text: the handler's own message, or what is wrong with the arguments. */
  def message: String
}

object ToolHandlerError {

  /**
   * The tool itself refused or failed, the arguments being readable: [[ToolFunction.execute]] reports it as a
   * [[ToolCallError.HandlerError]] carrying `message`.
   *
   * @param message what went wrong, as the model is told it
   */
  final case class Failed private (message: String) extends ToolHandlerError {

    /** Replace the message. */
    def withMessage(message: String): Failed = copy(message = message)
  }

  object Failed {

    /** Creates a [[Failed]]. */
    def apply(message: String): Failed = new Failed(message)
  }

  /**
   * The tool's own failure, typed as a [[ToolHandlerError]] so that it sits beside a getter's
   * [[ToolParameterError]] in one `for` comprehension without a type annotation.
   */
  def failed(message: String): ToolHandlerError = Failed(message)
}

/**
 * Structured error information for tool parameter validation.
 *
 * Represents specific validation failures when parsing tool call arguments,
 * including missing parameters, type mismatches, null values, and invalid nesting.
 */
@Stable
sealed trait ToolParameterError extends ToolHandlerError {
  def parameterName: String
  def message: String
}

object ToolParameterError {

  /**
   * A required parameter is completely missing from the arguments.
   *
   * @param parameterName name of the missing parameter
   * @param expectedType the expected type of the parameter
   * @param availableParameters list of parameter names that were provided (hint for self-correction)
   */
  final case class MissingParameter private (
    parameterName: String,
    expectedType: String,
    availableParameters: Seq[String]
  ) extends ToolParameterError {
    def message: String = {
      val available = if (availableParameters.nonEmpty) {
        s" (available: ${availableParameters.mkString(", ")})"
      } else ""
      s"required parameter '$parameterName' (type: $expectedType) is missing$available"
    }

    /** Replace the name of the missing parameter. */
    def withParameterName(parameterName: String): MissingParameter = copy(parameterName = parameterName)

    /** Replace the expected type of the missing parameter. */
    def withExpectedType(expectedType: String): MissingParameter = copy(expectedType = expectedType)

    /** Replace the names of the parameters that were provided (a hint for self-correction). */
    def withAvailableParameters(availableParameters: Seq[String]): MissingParameter =
      copy(availableParameters = availableParameters)
  }

  object MissingParameter {

    /** Creates a [[MissingParameter]]; `availableParameters` defaults to none. */
    def apply(
      parameterName: String,
      expectedType: String,
      availableParameters: Seq[String] = Nil
    ): MissingParameter =
      new MissingParameter(parameterName, expectedType, availableParameters)
  }

  /**
   * A required parameter is present but has a null value.
   *
   * @param parameterName name of the null parameter
   * @param expectedType the expected non-null type
   */
  case class NullParameter(
    parameterName: String,
    expectedType: String
  ) extends ToolParameterError {
    def message: String =
      s"parameter '$parameterName' (type: $expectedType) is required but value was null"
  }

  /**
   * A parameter has the wrong type.
   *
   * @param parameterName name of the mistyped parameter
   * @param expectedType the type that was expected
   * @param actualType the type that was received
   */
  case class TypeMismatch(
    parameterName: String,
    expectedType: String,
    actualType: String
  ) extends ToolParameterError {
    def message: String =
      s"parameter '$parameterName' has wrong type - expected $expectedType but got $actualType"
  }

  /**
   * Cannot access a nested property because parent is not an object.
   *
   * @param parameterName name of the parameter being accessed
   * @param parentPath JSON path to the parent element
   * @param parentType actual type of the parent element (e.g. "string", "array")
   */
  case class InvalidNesting(
    parameterName: String,
    parentPath: String,
    parentType: String
  ) extends ToolParameterError {
    def message: String =
      s"cannot access parameter '$parameterName' because parent '$parentPath' is $parentType, not an object"
  }

  /**
   * Aggregation of multiple parameter errors from a single tool call.
   *
   * @param errors the individual parameter errors
   */
  case class MultipleErrors(
    errors: Seq[ToolParameterError]
  ) extends ToolParameterError {
    def parameterName: String = errors.map(_.parameterName).mkString(", ")
    def message: String =
      if (errors.size == 1) {
        errors.head.message
      } else {
        s"multiple parameter issues:\n${errors.map(e => s"  - ${e.message}").mkString("\n")}"
      }
  }
}

/**
 * Why a tool call did not return a result.
 *
 * Covers the full lifecycle of a tool invocation: unknown function, null arguments,
 * invalid arguments, handler errors, execution exceptions, a timeout and a cancellation.
 *
 * A `ToolCallError` is not an [[org.llm4s.error.LLMError]], on purpose. An `LLMError` is a call into llm4s that
 * failed, for the program to handle. A tool call's failure is a result: it is sent back to the model, which reads
 * it and tries again ([[formatted]] as text, [[ToolCallErrorJson.toJson]] as JSON), and the conversation goes on.
 * Code that must stop on one maps it at that point, for example
 * `result.left.map(e => ProcessingError("tool", e.formatted))`.
 */
@Stable
sealed trait ToolCallError {
  def toolName: String
  def message: String
  def formatted: String = s"Tool call '$toolName' $message"
}

object ToolCallError {

  /**
   * Whether this error is considered retryable (e.g. timeout, transient execution failure).
   * UnknownFunction, NullArguments, InvalidArguments, HandlerError and Cancelled are not retryable.
   * ExecutionError is retryable when the cause is IOException or TimeoutException (transient).
   */
  def isRetryable(error: ToolCallError): Boolean = error match {
    case _: UnknownFunction | _: NullArguments | _: InvalidArguments | _: HandlerError | _: Cancelled =>
      false
    case _: Timeout =>
      true
    case ExecutionError(_, cause) =>
      import java.io.IOException
      import java.util.concurrent.TimeoutException
      cause.isInstanceOf[IOException] || cause.isInstanceOf[TimeoutException]
  }

  /**
   * Tool function doesn't exist
   */
  final case class UnknownFunction private (
    toolName: String,
    availableTools: Seq[String]
  ) extends ToolCallError {
    def message: String = {
      val toolsInfo = if (availableTools.nonEmpty) s" Available tools: [${availableTools.mkString(", ")}]." else ""
      s"is not a recognized tool.$toolsInfo Check the tool name matches exactly (case-sensitive)."
    }

    /** Replace the name of the tool that was not found. */
    def withToolName(toolName: String): UnknownFunction = copy(toolName = toolName)

    /** Replace the names of the tools that are registered (a hint for self-correction). */
    def withAvailableTools(availableTools: Seq[String]): UnknownFunction = copy(availableTools = availableTools)
  }

  object UnknownFunction {

    /** Creates an [[UnknownFunction]]; `availableTools` defaults to none. */
    def apply(toolName: String, availableTools: Seq[String] = Seq.empty): UnknownFunction =
      new UnknownFunction(toolName, availableTools)
  }

  /**
   * Tool received null arguments when it expected an object
   */
  case class NullArguments(toolName: String) extends ToolCallError {
    def message: String = "received null arguments - expected an object with required parameters"
  }

  /**
   * Tool has parameter validation errors
   */
  case class InvalidArguments(
    toolName: String,
    parameterErrors: Seq[ToolParameterError]
  ) extends ToolCallError {
    def message: String = {
      val errorMessages = parameterErrors match {
        case Seq(single) => single.message
        case multiple =>
          s"has parameter issues:\n${multiple.map(e => s"  - ${e.message}").mkString("\n")}"
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
    def message: String = s"failed during execution: ${cause.getMessage}"
  }

  /**
   * Tool handler returned an error (business logic failure)
   */
  case class HandlerError(
    toolName: String,
    error: String
  ) extends ToolCallError {
    def message: String = s"failed with error: $error"
  }

  /**
   * Tool execution did not complete within the configured timeout.
   *
   * @param toolName  Name of the tool that timed out
   * @param duration Timeout duration that was exceeded
   */
  case class Timeout(
    toolName: String,
    duration: scala.concurrent.duration.FiniteDuration
  ) extends ToolCallError {
    def message: String = s"$toolName timed out after $duration"
  }

  /** The call was cancelled by interrupting its thread; the interrupt flag is kept. */
  case class Cancelled(toolName: String) extends ToolCallError {
    def message: String = "was cancelled"
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
 *   "errorType": "<unknown_function|null_arguments|invalid_arguments|handler_error|execution_error|timeout|cancelled>",
 *   "message": "<human readable summary>",
 *   "parameterErrors": [   // optional: only present for invalid_arguments
 *     {
 *       "parameterName": "<string>",
 *       "kind": "<missing_parameter|null_parameter|type_mismatch|invalid_nesting>",
 *       "expectedType": "<string|null>",
 *       "receivedType": "<string|null>",
 *       "availableParameters": ["<string>", ...]  // optional hint
 *     }
 *   ]
 * }
 * }}}
 */
@Stable
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
  def flattenErrors(errors: Seq[ToolParameterError]): Seq[ToolParameterError] =
    errors.flatMap {
      case ToolParameterError.MultipleErrors(nested) => flattenErrors(nested)
      case single                                    => Seq(single)
    }

  /**
   * Convert a ToolCallError to structured JSON for ToolMessage.content.
   *
   * @param error The tool call error to serialize
   * @return ujson.Obj containing structured error information
   */
  def toJson(error: ToolCallError): ujson.Obj = {
    val base = ujson.Obj(
      "isError"  -> true,
      "toolName" -> error.toolName,
      "message"  -> error.message
    )

    error match {
      case ToolCallError.UnknownFunction(_, _) =>
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

      case ToolCallError.Timeout(_, duration) =>
        base("errorType") = "timeout"
        base("code") = "timeout"
        base("duration") = duration.toString
        base

      case ToolCallError.Cancelled(_) =>
        base("errorType") = "cancelled"
        base
    }
  }
}
