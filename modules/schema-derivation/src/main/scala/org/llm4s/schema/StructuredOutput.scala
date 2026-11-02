package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ResponseFormat }
import org.llm4s.toolapi.ObjectSchema
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.util.{ Success, Try }

extension (client: LLMClient) {

  /**
   * [[org.llm4s.llmconnect.LLMClient.completeStructured]] with the schema derived from `A` instead of written by
   * hand: `client.completeStructuredOf[Invoice](conversation)`.
   *
   * `A` is a case class (the model answers with a JSON object) or a sum type: an enum or sealed trait whose cases
   * have fields, which `SchemaOf` describes as a JSON Schema `anyOf`. A case class whose schema core's model can
   * express goes through `completeStructured` unchanged. One that holds a sum type is sent as its JSON Schema in a
   * `ResponseFormat.JsonSchema`. A sum type itself is wrapped, since OpenAI's strict mode refuses an `anyOf` at the
   * root: the model is asked for `{"value": <the sum>}`, and the reply's `value` is read into `A`.
   *
   * Any other derived type, such as an enum of singletons, is refused with a `Left`, since the reply must be a
   * JSON object. So is a schema that cannot be built, such as a recursion through a hand-written given that
   * derives its type, which `SchemaOf.toJsonSchema` throws for: the model is not called.
   *
   * @param conversation the conversation to send
   * @param options      completion options, as for `completeStructured`
   * @tparam A the type to extract; needs a [[SchemaOf]] (`derives SchemaOf`) and a uPickle reader
   */
  def completeStructuredOf[A](
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(using schemaOf: SchemaOf[A], reader: upickle.default.Reader[A]): Result[A] =
    Try(schemaOf.toJsonSchema(strict = true)).toEither.left
      .map(e => ValidationError.invalid("structured_output", e.getMessage))
      .flatMap { schema =>
        if (schema.obj.contains("anyOf")) {
          StructuredOutput.readWrapped[A](client, conversation, options, schema)
        } else if (schema.obj.get("type").contains(ujson.Str("object"))) {
          Try(schemaOf.definition) match {
            case Success(objectSchema: ObjectSchema[?]) =>
              client.completeStructured[A](conversation, objectSchema.asInstanceOf[ObjectSchema[A]], options)
            // a case class that holds a sum type: core's model cannot express it, so its JSON Schema is sent
            case _ => StructuredOutput.readText[A](client, conversation, options, schema)
          }
        } else
          Left(
            ValidationError.invalid(
              "structured_output",
              s"${schemaOf.typeName} is not a JSON object: completeStructuredOf needs a case class or a sum type " +
                "whose cases have fields"
            )
          )
      }
}

/** The part of `completeStructuredOf` that sends a JSON Schema core's `ObjectSchema` cannot express. */
private[schema] object StructuredOutput {

  /** The property a sum type is wrapped in, since OpenAI's strict mode refuses an `anyOf` at the root. */
  val WrapperKey = "value"

  /** `{"type": "object", "properties": {"value": <schema>}, "required": ["value"], "additionalProperties": false}` */
  def wrap(schema: ujson.Value): ujson.Value =
    ujson.Obj(
      "type"                 -> ujson.Str("object"),
      "properties"           -> ujson.Obj(WrapperKey -> schema),
      "required"             -> ujson.Arr(ujson.Str(WrapperKey)),
      "additionalProperties" -> ujson.False
    )

  /**
   * Sends `schema` as the response format and reads the reply into `A`, as `completeStructured` does: the JSON is
   * taken out of a fence or prose, refused when it is not JSON, nested too deep or `null`, and a reader's failure
   * is a `ValidationError`. `A` is read from the reply's text, so that a `Long` beyond 2^53 is read exactly.
   */
  def readText[A](client: LLMClient, conversation: Conversation, options: CompletionOptions, schema: ujson.Value)(using
    reader: upickle.default.Reader[A]
  ): Result[A] =
    reply(client, conversation, options, schema).flatMap { case (json, _) => readWith(upickle.default.read[A](json)) }

  /** As [[readText]] for a sum type: `schema` is sent [[wrap]]ped, and the reply's `value` is read into `A`. */
  def readWrapped[A](client: LLMClient, conversation: Conversation, options: CompletionOptions, schema: ujson.Value)(
    using reader: upickle.default.Reader[A]
  ): Result[A] =
    reply(client, conversation, options, wrap(schema)).flatMap {
      case (_, ujson.Obj(fields)) if fields.contains(WrapperKey) =>
        readWith(upickle.default.read[A](fields(WrapperKey)))
      case _ =>
        Left(
          ValidationError(
            "structured_output",
            s"Response does not match expected schema: expected an object with the property '$WrapperKey'"
          )
        )
    }

  private def reply(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions,
    schema: ujson.Value
  ): Result[(String, ujson.Value)] =
    for {
      completion <- client.complete(conversation, options.withResponseFormat(ResponseFormat.JsonSchema(schema)))
      json = LLMClient.extractJson(completion.content)
      parsed <- BoundedJson
        .read(json)
        .left
        .map(e => ValidationError("structured_output", s"Response is not valid JSON: ${e.message}"))
      _ <- Either.cond(
        parsed != ujson.Null,
        (),
        ValidationError("structured_output", "Response does not match expected schema: got JSON null")
      )
    } yield (json, parsed)

  private def readWith[A](read: => A): Result[A] =
    Try(read).toEither.left.map(e =>
      ValidationError("structured_output", s"Response does not match expected schema: ${e.getMessage}")
    )
}
