package org.llm4s.jev

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.http.{ HttpHeaders, HttpResponse }
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.util.BoundedJson

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

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
 * the body before anything is read from it, in the forms [[mask]] lists; a partial or otherwise transformed echo
 * of the key is not recognised.
 */
private[jev] object JevErrors {

  private val Provider = "jev"

  private val MaxDelayMillis = Long.MaxValue / 1000000L

  /**
   * The deepest error body read as JSON. An error body is a flat object or one wrapped in another (`message`,
   * `error.message`), or a FastAPI-style `detail` list of validation errors (`{"detail":[{"loc":["body",...]}]}`,
   * four levels): a handful of levels, so 32 leaves ample headroom.
   * The limit is far below [[org.llm4s.util.BoundedJson.MaxDepth]] because the body is not only parsed but walked
   * and re-encoded here, then parsed again by the mapper: at 512 levels those recursions overflow a 256 KiB thread
   * stack, and a `StackOverflowError` is not caught by `Try` or any `Result`.
   */
  private[jev] val MaxErrorDepth: Int = 32

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
   * `text` with the API key replaced by `***` wherever it appears verbatim or percent-encoded as
   * `java.net.URLEncoder` writes it in UTF-8 (`tsk/live+0` as `tsk%2Flive%2B0`, upper-case hex). A key holds no
   * space (the config refuses one), so there is no `+`-for-space variant to cover. Nothing else is recognised: not
   * a prefix or a fragment of the key, nor another encoding.
   */
  private[jev] def mask(text: String, apiKey: String): String =
    if (apiKey.isEmpty) text
    else {
      val encoded = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
      text.replace(apiKey, "***").replace(encoded, "***")
    }

  /** Whether `text` holds the API key in a form [[mask]] removes. */
  private[jev] def reveals(text: String, apiKey: String): Boolean =
    apiKey.nonEmpty && mask(text, apiKey) != text

  /**
   * `body` with the API key removed as [[mask]] does. A JSON body is decoded first and every string in it (keys
   * and values) masked, then re-encoded, so the key is caught however the server escaped it (`\/`, `\"`,
   * `\uXXXX`): the mapper decodes those escapes, so masking only the raw text would let the decoded key through.
   * Any other body, and a JSON body nested more than [[MaxErrorDepth]] levels deep, is masked as text: walking or
   * re-encoding a deeply nested value can overflow the stack, and a `StackOverflowError` is not caught by `Try` or
   * any `Result`.
   */
  private[jev] def redactBody(body: String, apiKey: String): String =
    if (apiKey.isEmpty) body
    else
      BoundedJson
        .read(body, MaxErrorDepth)
        .toOption
        .map(json => ujson.write(redactJson(json, apiKey)))
        .getOrElse(mask(body, apiKey))

  private def redactJson(json: ujson.Value, apiKey: String): ujson.Value = json match {
    case ujson.Str(s)     => ujson.Str(mask(s, apiKey))
    case ujson.Arr(items) => ujson.Arr.from(items.map(redactJson(_, apiKey)))
    case ujson.Obj(fields) =>
      ujson.Obj.from(fields.map { case (k, v) => mask(k, apiKey) -> redactJson(v, apiKey) })
    case other => other
  }

  /**
   * The body handed to the mapper. A body nested more than [[MaxErrorDepth]] levels deep, too deep to redact as
   * JSON, is not passed on at all: its
   * text-masked form could still carry a JSON-escaped key, which the mapper would decode from a top-level
   * `message`. The mapper then reports its default message, with the status.
   */
  private def mapperBody(body: String, apiKey: String): String =
    if (BoundedJson.exceedsDepth(body, MaxErrorDepth)) "" else redactBody(body, apiKey)

  /** The error for `response`, which has a non-2xx status. */
  def fromResponse(response: HttpResponse, apiKey: String): LLMError = {
    val body = mapperBody(response.body, apiKey)
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
