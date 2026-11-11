package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ResponseFormat, StreamedChunk }
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
   * express is sent and read exactly as `completeStructured` sends and reads it. One that holds a sum type is sent as its JSON Schema in a
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
    StructuredOutput.plan[A].flatMap { plan =>
      client
        .complete(conversation, options.withResponseFormat(ResponseFormat.JsonSchema(plan.schema)))
        .flatMap(completion => plan.read(completion.content))
    }

  /**
   * [[completeStructuredOf]], streamed: `onPartial` sees the value take shape while the model writes it, and the
   * result is the finished value.
   *
   * The request is the one `completeStructuredOf` sends (the same response format, through `streamComplete`), and
   * the result is the reply read exactly as `completeStructuredOf` reads it, with the same errors. As text arrives,
   * it is parsed with [[PartialJson]] and read into `A`'s partial type ([[PartialOf]]): a named tuple with `A`'s
   * field names, each `None` until it starts to arrive. `onPartial` is called, on the thread that streams, each
   * time that value changes, and each value it sees extends the one before: a string grows, a list gains elements,
   * a field once present stays present. A number, an enum or any other value with no partial form is shown only once
   * complete. Thinking deltas are not parsed, and nor is text before the first `{` (a fence or a preamble).
   *
   * {{{
   * client.streamStructuredOf[Invoice](conversation) { p =>
   *   p.vendor.foreach(ui.setTitle)                  // p.vendor: Option[String]
   *   ui.setRows(p.items.getOrElse(Nil).size)        // p.items: Option[List[(sku: Option[String], ...)]]
   * }
   * }}}
   *
   * The whole reply is parsed again on each chunk, so the cost grows with the square of its length: fine for the
   * replies structured output is used for, slow for one of many megabytes.
   *
   * @param conversation the conversation to send
   * @param options      completion options, as for `completeStructuredOf`
   * @param onPartial    called with each new partial value; an exception it throws is the stream's
   * @tparam A the case class to extract; needs a [[SchemaOf]] and a uPickle reader, and, for each field type with
   *           no partial form, a uPickle reader of that type
   */
  def streamStructuredOf[A](
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(using schemaOf: SchemaOf[A], reader: upickle.default.Reader[A], partial: PartialOf[A])(
    onPartial: partial.Out => Unit
  ): Result[A] =
    StructuredOutput.plan[A].flatMap { plan =>
      val text                      = new StringBuilder
      var last: Option[partial.Out] = None
      def onChunk(chunk: StreamedChunk): Unit =
        chunk.content.filter(_.nonEmpty).foreach { delta =>
          text.append(delta)
          StructuredOutput.partialValue(partial, text).filterNot(last.contains).foreach { value =>
            last = Some(value)
            onPartial(value)
          }
        }
      client
        .streamComplete(conversation, options.withResponseFormat(ResponseFormat.JsonSchema(plan.schema)), onChunk)
        .flatMap(completion => plan.read(completion.content))
    }
}

/** The request `completeStructuredOf` and `streamStructuredOf` send, and how each reads the reply. */
private[schema] object StructuredOutput {

  /** The response format's schema, and how the reply's text is read into `A`. */
  final case class Plan[A](schema: ujson.Value, read: String => Result[A])

  /**
   * The schema to send for `A`, and how to read the reply. A case class whose schema core's model can express is sent
   * as `completeStructured` sends its `ObjectSchema`; one that holds a sum type is sent as its JSON Schema; a sum type
   * is [[wrap]]ped, and the reply's `value` read into `A`. Any other type, or a schema that cannot be built, is a
   * `ValidationError`.
   */
  def plan[A](using schemaOf: SchemaOf[A], reader: upickle.default.Reader[A]): Result[Plan[A]] =
    Try(schemaOf.toJsonSchema(strict = true)).toEither.left
      .map(e => ValidationError.invalid("structured_output", e.getMessage))
      .flatMap { schema =>
        if (schema.obj.contains("anyOf")) Right(Plan(wrap(schema), readWrapped[A]))
        else if (schema.obj.get("type").contains(ujson.Str("object"))) {
          Try(schemaOf.definition) match {
            case Success(objectSchema: ObjectSchema[?]) =>
              Right(Plan(objectSchema.toJsonSchema(strict = true), readText[A]))
            // a case class that holds a sum type: core's model cannot express it, so its JSON Schema is sent
            case _ => Right(Plan(schema, readText[A]))
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
   * The partial value of the reply so far: the JSON from its first `{`, parsed with `PartialJson` and read into
   * `PartialOf[A]`'s type. `None` before the object has started.
   */
  def partialValue[A](partial: PartialOf[A], text: StringBuilder): Option[partial.Out] = {
    val start = text.indexOf("{")
    if (start < 0) None else PartialJson.parseTree(text.substring(start)).flatMap(partial.read)
  }

  /**
   * Reads the reply into `A`, as `completeStructured` does: the JSON is taken out of a fence or prose, refused when
   * it is not JSON, nested too deep or `null`, and a reader's failure is a `ValidationError`. `A` is read from the
   * reply's text, so that a `Long` beyond 2^53 is read exactly.
   */
  private def readText[A](content: String)(using reader: upickle.default.Reader[A]): Result[A] =
    parsed(content).flatMap { case (json, _) => readWith(upickle.default.read[A](json)) }

  /** As [[readText]] for a sum type sent [[wrap]]ped: the reply's `value` is read into `A`. */
  private def readWrapped[A](content: String)(using reader: upickle.default.Reader[A]): Result[A] =
    parsed(content).flatMap {
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

  private def parsed(content: String): Result[(String, ujson.Value)] = {
    val json = LLMClient.extractJson(content)
    for {
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
  }

  private def readWith[A](read: => A): Result[A] =
    Try(read).toEither.left.map(e =>
      ValidationError("structured_output", s"Response does not match expected schema: ${LLMClient.readFailure(e)}")
    )
}
