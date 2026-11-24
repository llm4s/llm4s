package org.llm4s.toolapi

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default._

/**
 * A fully-defined, executable tool that can be invoked by an LLM.
 *
 * Bundles together a name, a natural-language description (shown to the model),
 * a [[SchemaDefinition]] that describes the expected JSON parameters, and a
 * handler function that performs the actual work.
 *
 * Prefer constructing instances via [[ToolBuilder]] rather than directly.
 *
 * @param name        Unique tool identifier used by the LLM when calling the tool
 * @param description Natural-language description of what the tool does and when to use it
 * @param schema      Parameter schema describing the expected JSON arguments
 * @param handler     Business logic; receives a [[SafeParameterExtractor]] and returns `Right(result)`, or
 *                    `Left` of a [[ToolHandlerError]]: a `ToolParameterError` from a getter when an argument is
 *                    at fault, or a message when the tool itself fails
 * @tparam R Return type, must have a uPickle `ReadWriter`
 */
@Stable
final case class ToolFunction[R: ReadWriter] private (
  name: String,
  description: String,
  schema: SchemaDefinition[_],
  handler: SafeParameterExtractor => Either[ToolHandlerError, R]
) {

  /** Replace the tool's name. */
  def withName(name: String): ToolFunction[R] = copy(name = name)

  /** Replace the description shown to the LLM. */
  def withDescription(description: String): ToolFunction[R] = copy(description = description)

  /** Replace the parameter schema. */
  def withSchema(schema: SchemaDefinition[_]): ToolFunction[R] = copy(schema = schema)

  /** Replace the handler. */
  def withHandler(handler: SafeParameterExtractor => Either[ToolHandlerError, R]): ToolFunction[R] =
    copy(handler = handler)

  /**
   * The definition of this tool as a model is told about it: the chat-completions function-tool JSON
   * (`{"type": "function", "function": {name, description, parameters, strict}}`), which is the format every
   * provider client in llm4s starts from, whatever its own wire format.
   *
   * @param strict `true` renders the schema for OpenAI's strict mode, where every property is listed as
   *               required; `false` keeps `required` as the schema declares it
   */
  def toolDefinition(strict: Boolean): ujson.Value =
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
  def execute(args: ujson.Value): Either[ToolCallError, ujson.Value] = run(args, None)

  /**
   * Executes the tool as the call `call`: the handler reads it as [[SafeParameterExtractor.call]], so a tool with an
   * external side effect can pass `call.idempotencyKey` on. An agent runs every `ToolFunction` this way.
   */
  def execute(args: ujson.Value, call: ToolCallInfo): Either[ToolCallError, ujson.Value] = run(args, Some(call))

  private def run(args: ujson.Value, call: Option[ToolCallInfo]): Either[ToolCallError, ujson.Value] =
    args match {
      // a tool with no required parameter takes null as no arguments
      case ujson.Null if hasRequiredParameters => Left(ToolCallError.NullArguments(name))
      case ujson.Null                          => handle(SafeParameterExtractor(ujson.Obj(), call))
      case _                                   => handle(SafeParameterExtractor(args, call))
    }

  // An argument at fault is InvalidArguments, with each parameter error listed; the tool's own message HandlerError.
  private def handle(extractor: SafeParameterExtractor): Either[ToolCallError, ujson.Value] =
    handler(extractor) match {
      case Right(result)         => Right(writeJs(result))
      case Left(message: String) => Left(ToolCallError.HandlerError(name, message))
      case Left(error: ToolParameterError) =>
        Left(ToolCallError.InvalidArguments(name, ToolCallErrorJson.flattenErrors(List(error))))
    }
}

object ToolFunction {

  /**
   * The [[ToolFunction.toolDefinition definitions]] of `tools`, in order, as the JSON array a request carries.
   *
   * @param strict as for [[ToolFunction.toolDefinition]]
   */
  def toolDefinitions(tools: Seq[ToolFunction[_]], strict: Boolean): ujson.Arr =
    ujson.Arr.from(tools.map(_.toolDefinition(strict)))

  /** Creates a [[ToolFunction]]; [[ToolBuilder]] is the usual way in. */
  def apply[R: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[_],
    handler: SafeParameterExtractor => Either[ToolHandlerError, R]
  ): ToolFunction[R] =
    new ToolFunction[R](name, description, schema, handler)
}

/**
 * Builder for [[ToolFunction]] definitions using a fluent API.
 *
 * Obtained via the companion [[ToolBuilder]] object:
 * {{{ToolBuilder[MyResult]("my_tool", "Does something", schema)
 *   .withHandler(extractor => Right(MyResult(...)))
 *   .buildSafe()
 * }}}
 *
 * @param name        Tool name
 * @param description Natural-language description for the LLM
 * @param schema      Parameter schema
 * @param handler     Optional handler; must be set before calling [[buildSafe]]
 */
@Stable
class ToolBuilder[R: ReadWriter] private (
  name: String,
  description: String,
  schema: SchemaDefinition[_],
  handler: Option[SafeParameterExtractor => Either[ToolHandlerError, R]] = None
) {

  /**
   * Set the handler function for this tool.
   *
   * @param handler Function that extracts parameters and executes the tool's logic
   * @return A new builder with the handler registered
   */
  def withHandler(handler: SafeParameterExtractor => Either[ToolHandlerError, R]): ToolBuilder[R] =
    new ToolBuilder(name, description, schema, Some(handler))

  /**
   * Build the tool function, returning a Result for safe error handling.
   *
   * @return Right(ToolFunction) if handler is defined, Left(ValidationError) otherwise
   */
  def buildSafe(): Result[ToolFunction[R]] = handler match {
    case Some(h) => Right(ToolFunction(name, description, schema, h))
    case None    => Left(ValidationError("handler", "must be defined before calling buildSafe()"))
  }
}

object ToolBuilder {

  /**
   * Create a new builder for a tool with the given name, description, and schema.
   *
   * @param name        Unique tool identifier
   * @param description Natural-language description shown to the LLM
   * @param schema      Parameter schema
   */
  def apply[R: ReadWriter](name: String, description: String, schema: SchemaDefinition[_]): ToolBuilder[R] =
    new ToolBuilder(name, description, schema)
}
