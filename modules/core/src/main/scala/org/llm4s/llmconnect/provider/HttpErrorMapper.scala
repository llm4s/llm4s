package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.{
  AuthenticationError,
  ContextLengthError,
  LLMError,
  ModelNotFoundError,
  RateLimitError,
  ServiceError,
  TroubleshootingLinks,
  ValidationError
}
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.http.HttpHeaders
import org.llm4s.util.BoundedJson

import java.time.Clock
import scala.concurrent.duration.FiniteDuration

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
   * | Status        | Error                                                                    |
   * |---------------|--------------------------------------------------------------------------|
   * | 401, 403      | [[org.llm4s.error.AuthenticationError]]                                  |
   * | 429           | [[org.llm4s.error.RateLimitError]], with `retryAfter` from `Retry-After` |
   * | 400, 413, 422 | [[org.llm4s.error.ContextLengthError]] when the body says the request does not fit the model's context window |
   * | 400, 404      | [[org.llm4s.error.ModelNotFoundError]] when the body says the model is unknown |
   * | 400           | [[org.llm4s.error.ValidationError]]                                      |
   * | other         | [[org.llm4s.error.ServiceError]]                                         |
   *
   * Every error carries the response's facts: `httpStatus` is `statusCode`, `requestId` the id in the response's
   * headers (`x-request-id`, `request-id`, `x-amzn-requestid`, `apim-request-id`) when it has one, and
   * `retryAfter` the delay its `Retry-After` header asks for.
   *
   * A 401, 403, 429 or 404 message ends with the next step to take and a link to its entry on the
   * First-Run Troubleshooting page.
   *
   * A `Retry-After` header (delta-seconds or an HTTP-date, name matched
   * case-insensitively) becomes the error's `retryAfter` duration; an absent,
   * unparseable or negative value leaves it unset, and the retry policy chooses the wait.
   *
   * @param statusCode the HTTP status code (must be outside 2xx range)
   * @param body       the raw response body (may be JSON or plain text)
   * @param provider   short provider label used in error messages (e.g. `"gemini"`)
   * @param headers    the response headers, as carried by `org.llm4s.http.HttpResponse`; `Map.empty` only when
   *                   there is no response to take them from
   * @return the appropriate `LLMError` subtype; a client returns it as a `Left`
   */
  def toError(statusCode: Int, body: String, provider: String, headers: Map[String, Seq[String]]): LLMError =
    toErrorAt(statusCode, body, provider, headers, Clock.systemUTC())

  /** As [[toError]], with the clock an HTTP-date `Retry-After` is measured against. */
  private[provider] def toErrorAt(
    statusCode: Int,
    body: String,
    provider: String,
    headers: Map[String, Seq[String]],
    clock: Clock
  ): LLMError = {
    val details = extractErrorDetails(body, statusCode, provider)
    val mapped: LLMError = statusCode match {
      case 401 | 403 => AuthenticationError(ProviderId(provider), details, statusCode.toString)
      case 429 =>
        (retryAfter(headers, clock) match {
          case Some(delay) => RateLimitError(ProviderId(provider), delay)
          case None        => RateLimitError(ProviderId(provider))
        }).withHttpStatus(statusCode)
      case s =>
        typed(s, errorCodes(body), details, provider).getOrElse {
          if (s == 400) ValidationError("request", details).withHttpStatus(s)
          else {
            val error = ServiceError(s, ProviderId(provider), details)
            retryAfter(headers, clock).fold(error)(error.withRetryAfter)
          }
        }
    }
    // A 401/403, 429 or 404 says the next step and links its troubleshooting entry (#2285).
    TroubleshootingLinks.annotate(withRequestId(mapped, HttpHeaders.requestId(headers)))
  }

  /**
   * The typed error for a response a caller reacts to by type, if `status`, the body's error `codes` or the
   * provider's `message` say it is one: a [[org.llm4s.error.ContextLengthError]] (with the limit and the requested
   * tokens when the message states them) or a [[org.llm4s.error.ModelNotFoundError]], carrying `status`. For a
   * client that maps its SDK's exceptions itself; [[toError]] applies it already.
   *
   * @param codes the machine-readable codes of the error: an OpenAI `error.code`, an Anthropic `error.type`
   */
  private[llm4s] def typed(status: Int, codes: Set[String], message: String, provider: String): Option[LLMError] =
    if (ProviderErrorSignals.isContextLength(status, codes, message)) {
      val (limit, requested) = ProviderErrorSignals.tokens(message)
      Some(ContextLengthError(ProviderId(provider), message).withTokens(limit, requested).withHttpStatus(status))
    } else if (ProviderErrorSignals.isModelNotFound(status, codes, message))
      Some(ModelNotFoundError(ProviderId(provider), message).withHttpStatus(status))
    else None

  /** `error` naming `model` as the model the request asked for, when it is a [[org.llm4s.error.ModelNotFoundError]]. */
  private[llm4s] def naming(error: LLMError, model: String): LLMError = error match {
    case e: ModelNotFoundError if e.model.isEmpty => e.withModel(model)
    case other                                    => other
  }

  /** `error` carrying `id` as its request id, when it is a type that holds one and has none yet. */
  private[llm4s] def withRequestId(error: LLMError, id: Option[String]): LLMError =
    id.filter(_ => error.requestId.isEmpty).fold(error) { requestId =>
      error match {
        case e: AuthenticationError => e.withRequestId(requestId)
        case e: RateLimitError      => e.withRequestId(requestId)
        case e: ValidationError     => e.withRequestId(requestId)
        case e: ContextLengthError  => e.withRequestId(requestId)
        case e: ModelNotFoundError  => e.withRequestId(requestId)
        case e: ServiceError        => e.withRequestId(requestId)
        case other                  => other
      }
    }

  /**
   * The machine-readable codes of an error body, lower-cased: `code`, `type` and `status` of its `error` object
   * (OpenAI's `error.code`, Anthropic's `error.type`, Gemini's `error.status`) and of the body itself (Mistral).
   * Empty for a body that is not a JSON object.
   */
  private[llm4s] def errorCodes(body: String): Set[String] = {
    def of(obj: collection.Map[String, ujson.Value]): Seq[String] =
      Seq("code", "type", "status").flatMap(obj.get).flatMap(_.strOpt)
    BoundedJson
      .read(body)
      .toOption
      .flatMap(_.objOpt)
      .map(json => of(json) ++ json.get("error").flatMap(_.objOpt).toSeq.flatMap(of))
      .getOrElse(Seq.empty)
      .map(_.toLowerCase(java.util.Locale.ROOT))
      .toSet
  }

  /**
   * The error a provider reported inside a stream, after the 200 status (RFC 0009, rule 2): mapped as a response with
   * `status` would be, keeping `message` (redacted) in every error type, a `RateLimitError` included, so the caller
   * sees the provider's explanation.
   *
   * @param status   the HTTP status the event stands for: the one it states, or the one its kind maps to (502 for a
   *                 failure that states none)
   * @param message  the provider's explanation, as the event carried it
   * @param provider short provider label used in error messages
   */
  def mapStreamError(status: Int, message: String, provider: String): LLMError = {
    val safe = org.llm4s.util.Redaction.remoteBody(message)
    val body = ujson.Obj("error" -> ujson.Obj("message" -> safe)).render()
    toError(status, body, provider, Map.empty) match {
      case limited: RateLimitError => limited.withDetail(extractErrorDetails(body, status, provider))
      case other                   => other
    }
  }

  /** The delay a `Retry-After` header asks for; see `HttpHeaders.retryAfter`, which parses it. */
  private[provider] def retryAfter(headers: Map[String, Seq[String]], clock: Clock): Option[FiniteDuration] =
    HttpHeaders.retryAfter(headers, clock)

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
          // IBM watsonx: {"errors": [{"code": ..., "message": ...}]}
          .orElse(
            json
              .get("errors")
              .flatMap(_.arrOpt)
              .flatMap(_.headOption)
              .flatMap(_.objOpt)
              .flatMap(_.get("message"))
              .flatMap(_.strOpt)
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
