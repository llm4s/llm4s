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
 *  All methods reject blank or whitespace-only arguments (Unicode whitespace included),
 *  returning a `Left(InvalidInputError)` that names the offending argument. Arguments are
 *  otherwise passed through verbatim (Unicode, newlines, dollar signs and braces included):
 *  they are interpolated into a prompt, not into code, and an interpolation placeholder inside
 *  an argument is never re-expanded. A caller that passes untrusted text is still exposed to
 *  prompt injection, as with any LLM call.
 */
object ConversationTemplates {

  /** Rejects a blank argument here, since [[Conversation.fromPrompts]] only trims ASCII whitespace. */
  private def nonBlank(field: String, value: String): Result[String] =
    if (value.isBlank) Left(InvalidInputError(field, value, "must not be blank")) else Right(value)

  def codeReview(code: String): Result[Conversation] =
    for {
      c <- nonBlank("code", code)
      conversation <- Conversation.fromPrompts(
        "You are an expert code reviewer. Analyze the code for bugs, style issues, and improvements.",
        s"Please review this code:\n\n$c"
      )
    } yield conversation

  def translate(text: String, targetLanguage: String): Result[Conversation] =
    for {
      t    <- nonBlank("text", text)
      lang <- nonBlank("targetLanguage", targetLanguage)
      conversation <- Conversation.fromPrompts(
        s"You are a professional translator. Translate text accurately to $lang.",
        t
      )
    } yield conversation

  def summarize(document: String): Result[Conversation] =
    for {
      d <- nonBlank("document", document)
      conversation <- Conversation.fromPrompts(
        "Summarize the provided document concisely, capturing all key points.",
        d
      )
    } yield conversation

  def questionAnswer(context: String, question: String): Result[Conversation] =
    for {
      c <- nonBlank("context", context)
      q <- nonBlank("question", question)
      conversation <- Conversation.fromPrompts(
        s"Answer questions based only on the following context:\n\n$c",
        q
      )
    } yield conversation

  def extractJson(input: String, schema: String): Result[Conversation] =
    for {
      i <- nonBlank("input", input)
      s <- nonBlank("schema", schema)
      conversation <- Conversation.fromPrompts(
        s"Extract structured data from the input and return valid JSON matching this schema:\n\n$s",
        i
      )
    } yield conversation
}
