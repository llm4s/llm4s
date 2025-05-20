package org.llm4s.toolapi

import upickle.default._
import ujson._

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
  def execute(args: ujson.Value): Either[ToolCallError, ujson.Value] = {
    val extractor = SafeParameterExtractor(args)
    handler(extractor) match {
      case Right(result) => Right(ujson.read(upickle.default.write(result)))
      case Left(error)   => Left(ToolCallError.InvalidArguments(List(error)))
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
