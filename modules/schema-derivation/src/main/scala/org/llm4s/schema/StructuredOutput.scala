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
   * refused with a `Left`, since `completeStructured` takes an object schema.
   *
   * @param conversation the conversation to send
   * @param options      completion options, as for `completeStructured`
   * @tparam A the type to extract; needs a [[SchemaOf]] (`derives SchemaOf`) and a uPickle reader
   */
  def completeStructuredOf[A](
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(using schemaOf: SchemaOf[A], reader: upickle.default.Reader[A]): Result[A] =
    schemaOf.schema(schemaOf.defaultDescription) match {
      case objectSchema: ObjectSchema[?] =>
        client.completeStructured[A](conversation, objectSchema.asInstanceOf[ObjectSchema[A]], options)
      case _ =>
        Left(
          ValidationError.invalid(
            "structured_output",
            s"${schemaOf.typeName} is not a JSON object: completeStructuredOf needs a case class"
          )
        )
    }
}
