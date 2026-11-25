package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.error.{ LLMError, NetworkError }

/**
 * How a Gemini or Vertex AI stream ends when it does not finish (RFC 0009, Part 2):
 *
 *  - A chunk carrying an `error` object (`{"error":{"code":503,"message":...,"status":"UNAVAILABLE"}}`), which the
 *    service sends once the 200 status is out, fails the stream with that error, and nothing after it is read
 *    ([[streamError]]).
 *  - A stream whose candidate never stated a `finishReason` was cut off: a retryable
 *    [[org.llm4s.error.NetworkError]] ([[unfinished]]).
 *  - A `data:` line that is not JSON fails the stream, a 502 like a bad gateway, and nothing after it is read
 *    ([[unreadable]]). Blank lines and SSE `:` comment lines, which is how a keep-alive is sent, are not data lines
 *    and are still ignored, as are the other SSE fields (RFC 0009, open question 5).
 *
 * Either way the text already delivered stays delivered.
 */
private[provider] object GeminiStreamEnd {

  /**
   * The failure a chunk reports in its `error` object, mapped as an HTTP response with its `code` would be, so a
   * 429 is a `RateLimitError` and a 503 a retryable `ServiceError`. A `code` that is not an HTTP failure status is a
   * 502, retried like a bad gateway.
   */
  def streamError(json: ujson.Value, provider: ProviderId): Option[LLMError] =
    json.objOpt
      .flatMap(_.get("error"))
      .filter(error => error != ujson.Null && error != ujson.Obj() && error != ujson.Str(""))
      .map { error =>
        val message =
          error.objOpt.flatMap(_.get("message")).flatMap(_.strOpt).orElse(error.strOpt).getOrElse(error.render())
        val status = error.objOpt
          .flatMap(_.get("code"))
          .flatMap(_.numOpt)
          .map(_.toInt)
          .filter(code => code >= 400 && code <= 599)
          .getOrElse(502)
        HttpErrorMapper.mapStreamError(status, message, provider)
      }

  /**
   * A `data:` line that is not JSON: the service sent something that is not a chunk, so the reply cannot be trusted
   * to be whole. A 502, as a bad gateway is, so `ReliableClient` may retry it while no chunk has been delivered.
   * `reason` is the parser's, never the line itself.
   */
  def unreadable(reason: String, provider: ProviderId): LLMError =
    HttpErrorMapper.mapStreamError(502, s"the stream sent a data line that is not JSON: $reason", provider)

  /** A stream whose candidate stated no `finishReason`: cut off, so a retry may succeed. */
  def unfinished(endpoint: String): NetworkError =
    NetworkError("stream ended without a finishReason; the reply is incomplete", None, endpoint)
}
