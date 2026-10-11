package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }
import org.llm4s.toolapi.ObjectSchema
import org.llm4s.types.Result

extension (client: LLMClient) {

  /**
   * [[org.llm4s.llmconnect.LLMClient.completeStructured]] with the schema derived from `A` instead of written by
   * hand: `client.completeStructuredOf[Invoice](conversation)`.
   *
   * `A` must be a case class (the model answers with a JSON object). Any other derived type, such as an enum, is
   * refused with a `Left`, since `completeStructured` takes an object schema. So is a schema that cannot be built,
   * such as a recursion through a hand-written given that derives its type, which `SchemaOf.definition` throws
   * for: the model is not called.
   *
   * @param conversation the conversation to send
   * @param options      completion options, as for `completeStructured`
   * @tparam A the type to extract; needs a [[SchemaOf]] (`derives SchemaOf`) and a uPickle reader
   */
  def completeStructuredOf[A](
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(using schemaOf: SchemaOf[A], reader: upickle.default.Reader[A]): Result[A] =
    scala.util
      .Try(schemaOf.definition)
      .toEither
      .left
      .map(e => ValidationError.invalid("structured_output", e.getMessage))
      .flatMap(definition => objectOf(definition, schemaOf.typeName))
      .flatMap(objectSchema => client.completeStructured[A](conversation, objectSchema, options))
}

private def objectOf[A](definition: org.llm4s.toolapi.SchemaDefinition[A], typeName: String): Result[ObjectSchema[A]] =
  definition match {
    case objectSchema: ObjectSchema[?] => Right(objectSchema.asInstanceOf[ObjectSchema[A]])
    case _ =>
      Left(
        ValidationError.invalid(
          "structured_output",
          s"$typeName is not a JSON object: completeStructuredOf needs a case class"
        )
      )
  }
