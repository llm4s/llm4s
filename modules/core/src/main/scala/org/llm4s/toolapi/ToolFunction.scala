package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default._

/**
 * Core model for tool function definitions
 */
case class ToolFunction[T, R: ReadWriter](
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: SafeParameterExtractor => Either[String, R]
) {

  /**
   * Converts the tool definition to the format expected by OpenAI's API
   */
  def toOpenAITool(strict: Boolean = true): ujson.Value =
    ujson.Obj(
      "type" -> ujson.Str("function"),
      "function" -> ujson.Obj(
        "name"        -> ujson.Str(name),
        "description" -> ujson.Str(description),
        "parameters"  -> schema.toJsonSchema(strict),
        "strict"      -> ujson.Bool(strict)
      )
    )

  /**
   * Helper to check if this tool has any required parameters
   */
  private def hasRequiredParameters: Boolean = schema match {
    case objSchema: ObjectSchema[_] =>
      objSchema.properties.exists(_.required)
    case _ =>
      // Non-object schemas are considered to have required parameters
      true
  }

  /**
   * Executes the tool with the given arguments
   */
  def execute(args: ujson.Value): Either[ToolCallError, ujson.Value] =
    // Check for null arguments first
    args match {
      case ujson.Null =>
        // For zero-parameter tools, treat null as empty object
        if (!hasRequiredParameters) {
          val extractor = SafeParameterExtractor(ujson.Obj())
          handler(extractor) match {
            case Right(result) => Right(writeJs(result))
            case Left(error)   => Left(ToolCallError.HandlerError(name, error))
          }
        } else {
          Left(ToolCallError.NullArguments(name))
        }
      case _ =>
        val extractor = SafeParameterExtractor(args)
        handler(extractor) match {
          case Right(result) => Right(writeJs(result))
          case Left(error)   => Left(ToolCallError.HandlerError(name, error))
        }
    }

  /**
   * Executes the tool with enhanced error reporting.
   * Uses SafeParameterExtractor in enhanced mode for better error messages.
   *
   * @param args The arguments to pass to the tool
   * @param enhancedHandler Handler that uses enhanced extraction methods
   * @return Either an error or the result as JSON
   */
  def executeEnhanced(
    args: ujson.Value,
    enhancedHandler: SafeParameterExtractor => Either[List[ToolParameterError], R]
  ): Either[ToolCallError, ujson.Value] =
    args match {
      case ujson.Null =>
        // For zero-parameter tools, treat null as empty object
        if (!hasRequiredParameters) {
          val extractor = SafeParameterExtractor(ujson.Obj())
          enhancedHandler(extractor) match {
            case Right(result) => Right(writeJs(result))
            case Left(errors)  => Left(ToolCallError.InvalidArguments(name, errors))
          }
        } else {
          Left(ToolCallError.NullArguments(name))
        }
      case _ =>
        val extractor = SafeParameterExtractor(args)
        enhancedHandler(extractor) match {
          case Right(result) => Right(writeJs(result))
          case Left(errors)  => Left(ToolCallError.InvalidArguments(name, errors))
        }
    }
}

/**
 * Builder for tool definitions
 */
class ToolBuilder[T, R: ReadWriter] private (
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: Option[SafeParameterExtractor => Either[String, R]] = None
) {
  def withHandler(handler: SafeParameterExtractor => Either[String, R]): ToolBuilder[T, R] =
    new ToolBuilder(name, description, schema, Some(handler))

  /**
   * Build the tool function, returning a Result for safe error handling.
   *
   * @return Right(ToolFunction) if handler is defined, Left(ValidationError) otherwise
   */
  def buildSafe(): Result[ToolFunction[T, R]] = handler match {
    case Some(h) => Right(ToolFunction(name, description, schema, h))
    case None    => Left(ValidationError("handler", "must be defined before calling build()"))
  }

  /**
   * Build the tool function, throwing on failure.
   *
   * @throws IllegalStateException if handler is not defined
   */
  @deprecated("Use buildSafe() which returns Result[ToolFunction] for safe error handling", "0.2.9")
  def build(): ToolFunction[T, R] = handler match {
    case Some(h) => ToolFunction(name, description, schema, h)
    case None    => throw new IllegalStateException("Handler not defined")
  }
}

object ToolBuilder {
  def apply[T, R: ReadWriter](name: String, description: String, schema: SchemaDefinition[T]): ToolBuilder[T, R] =
    new ToolBuilder(name, description, schema)
}
