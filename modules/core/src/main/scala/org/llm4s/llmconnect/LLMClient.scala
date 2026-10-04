package org.llm4s.llmconnect

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
   * Sets `ResponseFormat.JsonSchema` on the options so providers that support native structured
   * output (OpenAI, Gemini) enforce the schema at generation time. Anthropic falls back to a
   * best-effort system-prompt instruction, which is not schema-enforced. Because models may wrap
   * JSON in markdown code fences or surround it with prose, the response is normalised
   * (fence stripped, first balanced `{...}` or `[...]` extracted) before being deserialised with
   * uPickle into the expected type `A`.
   *
   * @param conversation conversation history
   * @param schema       JSON-Schema description of the expected response object
   * @param options      additional completion options (default: CompletionOptions())
   * @param reader       implicit uPickle reader for deserialising the JSON into `A`
   * @tparam A target type; must have a corresponding `upickle.default.Reader[A]`
   * @return Right(A) on success, or Left(LLMError) when the provider call fails or the JSON cannot be parsed
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

  /**
   * Best-effort normalisation of model output that should contain a JSON value.
   *
   * Strips a surrounding markdown code fence and, if the remainder still does not start with
   * `{` or `[`, extracts the first balanced `{...}` or `[...]` block (string and escape aware).
   * Plain JSON is returned trimmed; if nothing is found the trimmed text is returned unchanged so
   * the caller reports the parse error.
   */
  private[llmconnect] def extractJson(raw: String): String = {
    val trimmed = raw.trim
    val unfenced = trimmed match {
      case FencePattern(inner) => inner.trim
      case _                   => trimmed
    }
    if (unfenced.startsWith("{") || unfenced.startsWith("[")) unfenced
    else
      unfenced.indexWhere(c => c == '{' || c == '[') match {
        case -1    => unfenced
        case start => balancedFrom(unfenced, start).getOrElse(unfenced)
      }
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
