package org.llm4s.llmconnect.provider

import java.util.Locale
import java.util.regex.Pattern

/**
 * How a provider's error reply says one of the two things a caller reacts to by type: the request does not fit the
 * model's context window, or the model is unknown.
 *
 * The providers agree on neither a status nor a code, so this reads the code where there is one (OpenAI's
 * `context_length_exceeded` and `model_not_found`, which the OpenAI-compatible services copy; Anthropic's
 * `not_found_error`; Mistral's `invalid_model`) and the message where there is not. Only the first
 * [[ScanLength]] characters of a message are read, and every check is a substring test or a pattern without
 * nested quantifiers, so a large body costs nothing.
 */
private[llm4s] object ProviderErrorSignals {

  private val ScanLength = 2048

  private val ContextLengthCodes = Set("context_length_exceeded", "context_window_exceeded")

  // OpenAI: "This model's maximum context length is 8192 tokens"; Anthropic: "prompt is too long: 213462 tokens >
  // 200000 maximum", "input length and `max_tokens` exceed context limit"; Gemini: "The input token count (N)
  // exceeds the maximum number of tokens allowed (M)"; Bedrock: "Input is too long for requested model"; Cohere:
  // "too many tokens"; Mistral, DeepSeek, xAI, OpenRouter and llama.cpp servers: "... context length ...",
  // "... context window ..."
  private val ContextLengthPhrases = Seq(
    "maximum context length",
    "context length",
    "context window",
    "context limit",
    "prompt is too long",
    "input is too long",
    "exceeds the maximum number of tokens",
    "too many tokens"
  )

  private val ModelNotFoundCodes = Set("model_not_found", "invalid_model")

  private val ModelMissingPhrases = Seq("not found", "does not exist", "not exist")

  private val InvalidModelPhrases = Seq("invalid model", "not a valid model")

  /** The statuses a refusal for length arrives with: 400 almost everywhere, 413 and 422 from some gateways. */
  private val RefusedRequest = Set(400, 413, 422)

  private def scanned(message: String): String = message.take(ScanLength).toLowerCase(Locale.ROOT)

  /** Whether a reply with `status`, the error `codes` (lower-cased) and `message` refuses the request as too long. */
  def isContextLength(status: Int, codes: Set[String], message: String): Boolean =
    RefusedRequest.contains(status) && {
      val text = scanned(message)
      codes.exists(ContextLengthCodes.contains) || ContextLengthPhrases.exists(text.contains)
    }

  /**
   * Whether a reply with `status`, the error `codes` (lower-cased) and `message` says the model is unknown. A 404
   * that does not say so is not one: a wrong base URL answers 404 too.
   */
  def isModelNotFound(status: Int, codes: Set[String], message: String): Boolean =
    (status == 404 || status == 400) && {
      val text = scanned(message)
      codes.exists(ModelNotFoundCodes.contains) ||
      // Anthropic: {"type":"not_found_error","message":"model: claude-x"}
      (codes.contains("not_found_error") && text.contains("model:")) ||
      (text.contains("model") && ModelMissingPhrases.exists(text.contains)) ||
      InvalidModelPhrases.exists(text.contains)
    }

  // Each pattern captures (requested, limit) or (limit, requested), as its flag says. No nested quantifiers.
  private val TokenPatterns: Seq[(Pattern, Boolean)] = Seq(
    // Anthropic: "prompt is too long: 213462 tokens > 200000 maximum"
    """(\d{1,12}) tokens > (\d{1,12}) maximum""" -> true,
    // Gemini: "The input token count (1200000) exceeds the maximum number of tokens allowed (1048576)"
    """token count \((\d{1,12})\) exceeds the maximum number of tokens allowed \((\d{1,12})\)""" -> true,
    // OpenAI: "maximum context length is 8192 tokens. However, your messages resulted in 9000 tokens",
    // "... However, you requested 9000 tokens (8000 in the messages, 1000 in the completion)"
    """maximum context length is (\d{1,12}) tokens\D{0,60}(?:resulted in|requested) (\d{1,12}) tokens""" -> false
  ).map { case (regex, requestedFirst) => Pattern.compile(regex, Pattern.CASE_INSENSITIVE) -> requestedFirst }

  private val LimitOnly = Pattern.compile("""maximum context length is (\d{1,12}) tokens""", Pattern.CASE_INSENSITIVE)

  /** The model's context window and the tokens the request needed, as `message` states them: `(limit, requested)`. */
  def tokens(message: String): (Option[Int], Option[Int]) = {
    val text = message.take(ScanLength)
    def int(s: String): Option[Int] = s.toIntOption
    TokenPatterns.iterator
      .map { case (pattern, requestedFirst) =>
        val m = pattern.matcher(text)
        if (m.find()) Some(if (requestedFirst) (int(m.group(2)), int(m.group(1))) else (int(m.group(1)), int(m.group(2))))
        else None
      }
      .collectFirst { case Some(found) => found }
      .getOrElse {
        val m = LimitOnly.matcher(text)
        (if (m.find()) int(m.group(1)) else None, None)
      }
  }
}
