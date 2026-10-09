package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.http.HttpHeaders
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import java.time.{ Clock, Duration, ZonedDateTime }
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }
import scala.util.Try

/**
 * Shared HTTP status-code to [[org.llm4s.error.LLMError]] mapping used by all
 * HTTP-based LLM provider clients.
 *
 * Centralises the duplicated pattern of converting non-2xx responses into
 * typed `Result` errors.  Provider-specific error details are extracted from
 * the JSON response body when possible and truncated to a safe length.
 */
@Stable
object HttpErrorMapper {

  private val MaxErrorDetailLength = 256

  /**
   * Maps an HTTP error response to a typed [[org.llm4s.error.LLMError]].
   *
   * | Status      | Error                                                                 |
   * |-------------|-----------------------------------------------------------------------|
   * | 401, 403    | [[org.llm4s.error.AuthenticationError]]                               |
   * | 429         | [[org.llm4s.error.RateLimitError]], with `retryAfter` from `Retry-After` |
   * | 400         | [[org.llm4s.error.ValidationError]]                                   |
   * | other       | [[org.llm4s.error.ServiceError]]                                      |
   *
   * A 429's `Retry-After` header (delta-seconds or an HTTP-date, name matched
   * case-insensitively) becomes the error's `retryAfter` duration; an absent,
   * unparseable or negative value leaves it unset, so the error's default backoff applies.
   *
   * @param statusCode the HTTP status code (must be outside 2xx range)
   * @param body       the raw response body (may be JSON or plain text)
   * @param provider   short provider label used in error messages (e.g. `"gemini"`)
   * @param headers    the response headers, as carried by `org.llm4s.http.HttpResponse`;
   *                   pass them whenever the response is available
   * @return `Left` containing the appropriate `LLMError` subtype
   */
  def mapHttpError(
    statusCode: Int,
    body: String,
    provider: String,
    headers: Map[String, Seq[String]] = Map.empty
  ): Result[Nothing] =
    mapHttpError(statusCode, body, provider, headers, Clock.systemUTC())

  /** As the public overload, with the clock an HTTP-date `Retry-After` is measured against. */
  private[provider] def mapHttpError(
    statusCode: Int,
    body: String,
    provider: String,
    headers: Map[String, Seq[String]],
    clock: Clock
  ): Result[Nothing] = {
    val details = extractErrorDetails(body, statusCode, provider)
    statusCode match {
      case 401 | 403 => Left(AuthenticationError(provider, details))
      case 429 =>
        retryAfter(headers, clock) match {
          case Some(delay) => Left(RateLimitError(provider, delay))
          case None        => Left(RateLimitError(provider))
        }
      case 400 => Left(ValidationError("request", details))
      case s =>
        val error = ServiceError(s, provider, details)
        Left(retryAfter(headers, clock).fold(error)(error.withRetryAfter))
    }
  }

  /**
   * The delay a `Retry-After` header asks for.
   *
   * Accepts delta-seconds (`"120"`) or an RFC 1123 HTTP-date, which is measured against
   * `clock` (a date already past gives zero), to millisecond precision. Returns `None` when
   * the header is absent, unparseable, negative, or too large to represent as a
   * `FiniteDuration`.
   */
  private[provider] def retryAfter(headers: Map[String, Seq[String]], clock: Clock): Option[FiniteDuration] =
    HttpHeaders.first(headers, "Retry-After").map(_.trim).flatMap { value =>
      val millis =
        if (value.nonEmpty && value.forall(_.isDigit))
          value.toLongOption.filter(_ <= MaxRetryAfterMillis / 1000L).map(_ * 1000L)
        else
          Try(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toOption
            .flatMap(date => Try(Duration.between(clock.instant(), date.toInstant).toMillis).toOption)
            .map(math.max(0L, _))
      millis.filter(_ <= MaxRetryAfterMillis).map(FiniteDuration(_, MILLISECONDS))
    }

  /** The longest delay a `FiniteDuration` (nanosecond-backed) holds, in whole milliseconds. */
  private val MaxRetryAfterMillis = Long.MaxValue / 1000000L

  /**
   * Attempts to extract a human-readable error message from a JSON response
   * body, falling back to a generic message.
   *
   * Tries the following JSON paths in order:
   *  1. Top-level `"message"` string
   *  2. `"error"` object → `"message"` string  (OpenAI / Mistral style)
   *  3. `"error"` as a plain string  (some providers)
   *
   * The body is read with `BoundedJson`, and every step uses an accessor that
   * returns an `Option` (`objOpt`, `strOpt`), so a body that is not JSON, not an object, or nested
   * too deeply falls back to the generic message. `.obj` on a non-object throws
   * `ujson.Value.InvalidData`, whose message renders the whole value recursively: on a deeply
   * nested top-level array that was a `StackOverflowError`, which `Try` does not catch, out of
   * every provider's error path ([[https://github.com/llm4s/llm4s/issues/1658 #1658]]).
   *
   * @return a sanitised (redacted + trimmed + truncated) error detail string
   */
  private[provider] def extractErrorDetails(body: String, statusCode: Int, provider: String): String = {
    val defaultMsg = s"$provider API error (HTTP $statusCode)"
    val raw = BoundedJson
      .read(body)
      .toOption
      .flatMap(_.objOpt)
      .flatMap { json =>
        json
          .get("message")
          .flatMap(_.strOpt)
          .orElse(
            json.get("error").flatMap { error =>
              error.strOpt.orElse(error.objOpt.flatMap(_.get("message").flatMap(_.strOpt)))
            }
          )
      }
      .getOrElse(defaultMsg)
    sanitize(raw)
  }

  private def sanitize(raw: String): String = {
    val redacted = org.llm4s.util.Redaction.redact(raw)
    val trimmed  = redacted.trim
    if (trimmed.length <= MaxErrorDetailLength) trimmed
    else trimmed.take(MaxErrorDetailLength) + "…[truncated]"
  }
}
