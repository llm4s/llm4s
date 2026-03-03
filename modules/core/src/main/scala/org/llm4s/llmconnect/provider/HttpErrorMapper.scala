package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Shared HTTP status-code to [[org.llm4s.error.LLMError]] mapping used by all
 * HTTP-based LLM provider clients.
 *
 * Centralises the duplicated pattern of converting non-2xx responses into
 * typed `Result` errors.  Provider-specific error details are extracted from
 * the JSON response body when possible and truncated to a safe length.
 */
object HttpErrorMapper {

  private val MaxErrorDetailLength = 256

  /**
   * Maps an HTTP error response to a typed [[org.llm4s.error.LLMError]].
   *
   * @param statusCode the HTTP status code (must be outside 2xx range)
   * @param body       the raw response body (may be JSON or plain text)
   * @param provider   short provider label used in error messages (e.g. `"gemini"`)
   * @return `Left` containing the appropriate `LLMError` subtype
   */
  def mapHttpError(statusCode: Int, body: String, provider: String): Result[Nothing] = {
    val details = extractErrorDetails(body, statusCode, provider)
    statusCode match {
      case 401 | 403 => Left(AuthenticationError(provider, details))
      case 429       => Left(RateLimitError(provider))
      case 400       => Left(ValidationError("request", details))
      case s         => Left(ServiceError(s, provider, details))
    }
  }

  /**
   * Attempts to extract a human-readable error message from a JSON response
   * body, falling back to a generic message.
   *
   * Tries the following JSON paths in order:
   *  1. Top-level `"message"` string
   *  2. `"error"` object → `"message"` string  (OpenAI / Mistral style)
   *  3. `"error"` as a plain string  (some providers)
   *
   * @return a sanitised (trimmed + truncated) error detail string
   */
  private[provider] def extractErrorDetails(body: String, statusCode: Int, provider: String): String = {
    val defaultMsg = s"$provider API error (HTTP $statusCode)"
    val raw = Try {
      val json = ujson.read(body)
      json.obj
        .get("message")
        .flatMap(_.strOpt)
        .orElse(
          json.obj.get("error").flatMap { error =>
            // Try string first (avoids exception when error is not an object)
            error.strOpt.orElse(
              error.objOpt.flatMap(_.get("message").flatMap(_.strOpt))
            )
          }
        )
        .getOrElse(defaultMsg)
    }.getOrElse(defaultMsg)
    sanitize(raw)
  }

  private def sanitize(raw: String): String = {
    val trimmed = raw.trim
    if (trimmed.length <= MaxErrorDetailLength) trimmed
    else trimmed.take(MaxErrorDetailLength) + "…[truncated]"
  }
}
