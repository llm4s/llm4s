package org.llm4s.gradle

import org.llm4s.error.InvalidInputError
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.Result

/**
 * Pre-built conversation templates for common use cases.
 *
 *  Gradle/Java/Kotlin callers can use these factory methods instead of
 *  constructing conversations manually. Every method returns a
 *  [[org.llm4s.types.Result]] so validation errors surface as typed values
 *  rather than exceptions — consistent with llm4s conventions.
 *
 *  All methods reject blank or whitespace-only arguments, returning a
 *  `Left(InvalidInputError)` in that case.
 */
object ConversationTemplates {

  def codeReview(code: String): Result[Conversation] =
    if (code.isBlank) Left(InvalidInputError("code", code, "must not be blank"))
    else
      Conversation.fromPrompts(
        "You are an expert code reviewer. Analyze the code for bugs, style issues, and improvements.",
        s"Please review this code:\n\n$code"
      )

  def translate(text: String, targetLanguage: String): Result[Conversation] =
    Conversation.fromPrompts(
      s"You are a professional translator. Translate text accurately to $targetLanguage.",
      text
    )

  def summarize(document: String): Result[Conversation] =
    Conversation.fromPrompts(
      "Summarize the provided document concisely, capturing all key points.",
      document
    )

  def questionAnswer(context: String, question: String): Result[Conversation] =
    if (context.isBlank) Left(InvalidInputError("context", context, "must not be blank"))
    else
      Conversation.fromPrompts(
        s"Answer questions based only on the following context:\n\n$context",
        question
      )

  def extractJson(input: String, schema: String): Result[Conversation] =
    if (schema.isBlank) Left(InvalidInputError("schema", schema, "must not be blank"))
    else
      Conversation.fromPrompts(
        s"Extract structured data from the input and return valid JSON matching this schema:\n\n$schema",
        input
      )
}
