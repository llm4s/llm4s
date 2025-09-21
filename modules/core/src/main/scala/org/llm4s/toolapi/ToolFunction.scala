package org.llm4s.toolapi

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
   * Executes the tool with the given arguments
   */
  def execute(args: ujson.Value): Either[ToolCallError, ujson.Value] =
    // Check for null arguments first
    args match {
      case ujson.Null =>
        Left(ToolCallError.NullArguments(name))
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
        Left(ToolCallError.NullArguments(name))
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

  def build(): ToolFunction[T, R] = handler match {
    case Some(h) => ToolFunction(name, description, schema, h)
    case None    => throw new IllegalStateException("Handler not defined")
  }
}

object ToolBuilder {
  def apply[T, R: ReadWriter](name: String, description: String, schema: SchemaDefinition[T]): ToolBuilder[T, R] =
    new ToolBuilder(name, description, schema)
}
