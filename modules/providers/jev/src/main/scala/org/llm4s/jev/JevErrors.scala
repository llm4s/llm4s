package org.llm4s.jev

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.http.{ HttpHeaders, HttpResponse }
import org.llm4s.llmconnect.provider.HttpErrorMapper

import scala.concurrent.duration.*
import scala.util.Try

/**
 * Turns a failed Jev HTTP response into an [[org.llm4s.error.LLMError]].
 *
 * What TypeSafe documents (https://docs.typesafe.ai/api.md#errors): 401 for a missing or invalid key, 422 for a
 * request body that failed validation, 429 when the rate limit is exceeded and 529 when it is overloaded. The SDK
 * references add 400, 403, 404 and any 5xx. The mapping is [[org.llm4s.llmconnect.provider.HttpErrorMapper]]'s, so
 * Jev errors are the ones every other client returns:
 *
 *  - 401, 403: [[org.llm4s.error.AuthenticationError]]
 *  - 400, 422: [[org.llm4s.error.ValidationError]] (422 is Jev's own status for it; the mapper only knows 400)
 *  - 429: [[org.llm4s.error.RateLimitError]], carrying the delay the server asked for
 *  - anything else (404, 408, 5xx including 529): [[org.llm4s.error.ServiceError]], carrying that delay too
 *
 * The documentation does not give the shape of the error body, so the text in the error is the mapper's best
 * effort (a `message`, an `error.message`, ...), truncated, and never the whole body. The API key is removed from
 * the body before anything is read from it, raw or JSON-escaped, so a server that echoed the key cannot put it in
 * an error.
 */
private[jev] object JevErrors {

  private val Provider = "jev"

  private val MaxDelayMillis = Long.MaxValue / 1000000L

  /** The delay `retry-after-ms` asks for, if the header is a non-negative whole number of milliseconds. */
  private def retryAfterMs(headers: Map[String, Seq[String]]): Option[FiniteDuration] =
    HttpHeaders
      .first(headers, "retry-after-ms")
      .map(_.trim)
      .filter(v => v.nonEmpty && v.length <= 15 && v.forall(_.isDigit))
      .flatMap(_.toLongOption)
      .filter(_ <= MaxDelayMillis)
      .map(FiniteDuration(_, MILLISECONDS))

  /**
   * `body` with every occurrence of `apiKey` replaced by `***`. A JSON body is decoded first and every string in it
   * (keys and values) redacted, then re-encoded, so the key is caught however the server escaped it (`\/`, `\"`,
   * `\uXXXX`): the mapper decodes those escapes, so redacting only the raw text would let the decoded key through.
   * Any other body is redacted as text.
   */
  private[jev] def redactBody(body: String, apiKey: String): String =
    if (apiKey.isEmpty) body
    else
      Try(ujson.read(body)).toOption
        .map(json => ujson.write(redactJson(json, apiKey)))
        .getOrElse(body.replace(apiKey, "***"))

  private def redactJson(json: ujson.Value, apiKey: String): ujson.Value = json match {
    case ujson.Str(s)     => ujson.Str(s.replace(apiKey, "***"))
    case ujson.Arr(items) => ujson.Arr.from(items.map(redactJson(_, apiKey)))
    case ujson.Obj(fields) =>
      ujson.Obj.from(fields.map { case (k, v) => k.replace(apiKey, "***") -> redactJson(v, apiKey) })
    case other => other
  }

  /** The error for `response`, which has a non-2xx status. */
  def fromResponse(response: HttpResponse, apiKey: String): LLMError = {
    val body = redactBody(response.body, apiKey)
    // The mapper handles 400 as a validation error; Jev's 422 is the same thing.
    val status = if (response.statusCode == 422) 400 else response.statusCode
    val mapped: LLMError =
      HttpErrorMapper.mapHttpError(status, body, Provider, response.headers).fold(identity, identity)
    // `retry-after-ms` is the more precise of the two headers the SDKs honour, so it wins when both are sent.
    (mapped, retryAfterMs(response.headers)) match {
      case (_: RateLimitError, Some(delay))     => RateLimitError(Provider, delay)
      case (service: ServiceError, Some(delay)) => service.withRetryAfter(delay)
      case (other, _)                           => other
    }
  }
}
