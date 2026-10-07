package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ObjectSchema
import org.llm4s.types.{ HeadroomPercent, Result, TokenBudget }

import scala.util.Try

/**
 * Core interface for interacting with Large Language Model providers.
 *
 * Abstracts communication with various LLM APIs (OpenAI, Azure OpenAI, Anthropic, etc.),
 * providing a unified interface for completion requests, streaming responses, and token management.
 * Implementations handle provider-specific authentication, message formatting, and tool calling.
 */
@Stable
trait LLMClient extends AutoCloseable {

  /**
   * Executes a blocking completion request and returns the full response.
   *
   * Sends the conversation to the LLM and waits for the complete response. Use when you need
   * the entire response at once or when streaming is not required.
   *
   * @param conversation conversation history including system, user, assistant, and tool messages
   * @param options configuration including temperature, max tokens, tools, etc. (default: CompletionOptions())
   * @return Right(Completion) with the model's response, or Left(LLMError) on failure
   */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion]

  /**
   * Executes a streaming completion request, invoking a callback for each chunk as it arrives.
   *
   * Streams the response incrementally, calling `onChunk` for each token/chunk received. Enables
   * real-time display of responses. Returns the final accumulated completion on success.
   *
   * @param conversation conversation history including system, user, assistant, and tool messages
   * @param options configuration including temperature, max tokens, tools, etc. (default: CompletionOptions())
   * @param onChunk callback invoked for each chunk; called synchronously, avoid blocking operations
   * @return Right(Completion) with the complete accumulated response, or Left(LLMError) on failure
   */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion]

  /**
   * Sends the conversation and parses the response into a typed value using the provided schema.
   *
   * Sets `ResponseFormat.JsonSchema` on the options. OpenAI, Azure OpenAI, Requesty and Gemini
   * enforce the schema at generation time, as does Ollama 0.5 or later through its `format` field.
   * The OpenAI-compatible providers (including Cohere) send it as `response_format`, but whether it
   * is enforced is up to the server: one that ignores the field returns unconstrained text.
   * Anthropic falls back to a best-effort system-prompt instruction, which is not schema-enforced.
   * Clients that do not read `responseFormat` (watsonx, Bedrock) send no schema at all. Because models may wrap
   * JSON in markdown code fences or surround it with prose, the response is normalised
   * (fence stripped, first balanced `{...}` or `[...]` extracted) before being deserialised with
   * uPickle into the expected type `A`.
   *
   * The reply is '''not''' validated against the schema: it is only deserialised. A constraint the
   * reader does not check - an enum, a numeric or string bound, `additionalProperties = false` (extra
   * keys are ignored) - can be violated by a reply that still returns `Right(A)`. Check such
   * constraints on the result yourself.
   *
   * The schema is derived with `strict = true`, which lists '''every''' property as required,
   * including a property declared optional with `required = false`. Only `responseFormat` is
   * overridden: every other option you pass is forwarded unchanged. `name` and `strict` on the
   * format are left at their defaults (`"response"` and `true`); call `complete` with your own
   * `ResponseFormat.JsonSchema` to set them.
   *
   * @param conversation conversation history
   * @param schema       JSON-Schema description of the expected response object
   * @param options      additional completion options (default: CompletionOptions())
   * @param reader       implicit uPickle reader for deserialising the JSON into `A`
   * @tparam A target type; must have a corresponding `upickle.default.Reader[A]`
   * @return Right(A) on success. Left(ValidationError) with field `structured_output` when the
   *         reply is not JSON, is JSON `null`, or cannot be deserialised as `A`; any other Left is
   *         the provider call's own error, returned unchanged
   */
  def completeStructured[A](
    conversation: Conversation,
    schema: ObjectSchema[A],
    options: CompletionOptions = CompletionOptions()
  )(implicit reader: upickle.default.Reader[A]): Result[A] = {
    val jsonSchema = ResponseFormat.JsonSchema(schema.toJsonSchema(strict = true))
    val opts       = options.withResponseFormat(jsonSchema)
    for {
      completion <- complete(conversation, opts)
      parsed <- Try(ujson.read(LLMClient.extractJson(completion.content))).toEither.left.map(e =>
        ValidationError("structured_output", s"Response is not valid JSON: ${e.getMessage}")
      )
      // uPickle reads a JSON null into a null reference for case classes; the schema is an object
      // schema so a null document is never a valid answer and must not reach callers as Right(null)
      _ <- Either.cond(
        parsed != ujson.Null,
        (),
        ValidationError("structured_output", "Response does not match expected schema: got JSON null")
      )
      result <- Try(upickle.default.read[A](parsed)).toEither.left.map(e =>
        ValidationError("structured_output", s"Response does not match expected schema: ${e.getMessage}")
      )
    } yield result
  }

  /**
   * Returns the maximum context window size supported by this model in tokens.
   *
   * The context window is the total tokens (prompt + completion) the model can process in a
   * single request, including all conversation messages and the generated response.
   *
   * @return total context window size in tokens (e.g., 4096, 8192, 128000)
   */
  def getContextWindow(): Int

  /**
   * Returns the number of tokens reserved for the model's completion response.
   *
   * This value is subtracted from the context window when calculating available tokens for prompts.
   * Corresponds to the max_tokens or completion token limit configured for the model.
   *
   * @return number of tokens reserved for completion
   */
  def getReserveCompletion(): Int

  /**
   * Calculates available token budget for prompts after accounting for completion reserve and headroom.
   *
   * Formula: `(contextWindow - reserveCompletion) * (1 - headroom)`
   *
   * Headroom provides a safety margin for tokenization variations and message formatting overhead.
   *
   * @param headroom safety margin as percentage of prompt budget (default: HeadroomPercent.Standard ~10%)
   * @return maximum tokens available for prompt content
   */
  def getContextBudget(headroom: HeadroomPercent = HeadroomPercent.Standard): TokenBudget = {
    val promptBudget = getContextWindow() - getReserveCompletion()
    (promptBudget * (1.0 - headroom.asRatio)).toInt
  }

  /**
   * Validates client configuration and connectivity to the LLM provider.
   *
   * May perform checks such as verifying API credentials, testing connectivity, and validating
   * configuration. Default implementation returns success; override for provider-specific validation.
   *
   * @return Right(()) if validation succeeds, Left(LLMError) with details on failure
   */
  def validate(): Result[Unit] = Right(())

  /**
   * Releases resources and closes connections to the LLM provider.
   *
   * Call when the client is no longer needed. After calling close(), the client should not be used.
   * Default implementation is a no-op; override if managing resources like connections or thread pools.
   */
  def close(): Unit = ()
}

object LLMClient {

  private val FencePattern = """(?s)^```[A-Za-z0-9_-]*[ \t]*\r?\n?(.*?)\r?\n?```\s*$""".r

  /** Upper bound on how many `{`/`[` start positions are tried, keeping extraction linear in practice. */
  private val MaxCandidateStarts = 16

  /**
   * Best-effort normalisation of model output that should contain a JSON value.
   *
   * Strips a surrounding markdown code fence and, if the remainder is not itself valid JSON,
   * extracts the first balanced `{...}` or `[...]` block that parses as JSON (string and escape
   * aware). Trailing prose after the JSON, and earlier non-JSON brace pairs in the prose, are
   * skipped; at most `MaxCandidateStarts` candidate start positions are tried. Plain JSON is
   * returned trimmed; if nothing is found the trimmed text is returned unchanged so the caller
   * reports the parse error.
   */
  private[llmconnect] def extractJson(raw: String): String = {
    val trimmed = raw.trim
    val unfenced = trimmed match {
      case FencePattern(inner) => inner.trim
      case _                   => trimmed
    }
    if (isJson(unfenced)) unfenced
    else firstJsonBlock(unfenced).getOrElse(unfenced)
  }

  private def isJson(text: String): Boolean = Try(ujson.read(text)).isSuccess

  private def firstJsonBlock(text: String): Option[String] = {
    @scala.annotation.tailrec
    def tryFrom(from: Int, attempts: Int): Option[String] =
      if (attempts >= MaxCandidateStarts) None
      else {
        val start = text.indexWhere(c => c == '{' || c == '[', from)
        if (start < 0) None
        else
          balancedFrom(text, start).filter(isJson) match {
            case found @ Some(_) => found
            case None            => tryFrom(start + 1, attempts + 1)
          }
      }
    tryFrom(0, 0)
  }

  private def balancedFrom(text: String, start: Int): Option[String] = {
    @scala.annotation.tailrec
    def loop(i: Int, depth: Int, inString: Boolean, escaped: Boolean): Option[String] =
      if (i >= text.length) None
      else {
        val c = text.charAt(i)
        if (inString) {
          if (escaped) loop(i + 1, depth, inString = true, escaped = false)
          else if (c == '\\') loop(i + 1, depth, inString = true, escaped = true)
          else if (c == '"') loop(i + 1, depth, inString = false, escaped = false)
          else loop(i + 1, depth, inString = true, escaped = false)
        } else if (c == '"') loop(i + 1, depth, inString = true, escaped = false)
        else if (c == '{' || c == '[') loop(i + 1, depth + 1, inString = false, escaped = false)
        else if (c == '}' || c == ']') {
          if (depth == 1) Some(text.substring(start, i + 1))
          else loop(i + 1, depth - 1, inString = false, escaped = false)
        } else loop(i + 1, depth, inString = false, escaped = false)
      }
    loop(start, 0, inString = false, escaped = false)
  }
}
