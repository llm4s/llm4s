package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.util.DurationText

import scala.concurrent.duration.FiniteDuration

/**
 * Where a [[RateLimitError]] originated. Distinguishes a request that never left the
 * process (rejected by a local token bucket) from one the provider itself rejected with
 * an HTTP 429 - the two need different treatment when a caller has already recorded a
 * metrics event for the local case, e.g. `org.llm4s.reliability.ReliableClient`'s rate limit.
 */
@Stable
enum RateLimitOrigin {
  case LocalThrottle, UpstreamProvider
}

/**
 * Raised when the LLM provider rejects the request due to rate limiting.
 *
 * It is [[LLMError.isRetryable retryable]]: the same request may succeed after waiting, and
 * `org.llm4s.reliability.ReliableClient` waits and sends it again. [[retryAfter]] is the delay the provider asked
 * for and nothing else; how long to wait when it gave none is the retry policy's decision
 * (`org.llm4s.reliability.RetryPolicy.DefaultRateLimitDelay`).
 *
 * @param message human-readable description of the rate limit
 * @param retryAfter optional delay the provider asked for before retrying (e.g. an HTTP
 *                   `Retry-After` header)
 * @param provider the name of the LLM provider (e.g., "openai", "anthropic")
 * @param origin whether this was rejected locally (never reached the provider) or by the
 *               provider itself; defaults to [[RateLimitOrigin.UpstreamProvider]] since every
 *               existing constructor call maps a provider-side rejection
 * @param httpStatus the HTTP status of the provider's response (429), when the error was mapped from one; `None`
 *                   for a local throttle
 * @param requestId the provider's id for the refused request, when the response carried one
 */
@Stable
final case class RateLimitError private (
  override val message: String,
  override val retryAfter: Option[FiniteDuration],
  provider: String,
  origin: RateLimitOrigin,
  override val httpStatus: Option[Int],
  override val requestId: Option[String]
) extends LLMError {

  override def isRetryable: Boolean = true

  /** This error, carrying the HTTP status of the response it was mapped from. */
  def withHttpStatus(status: Int): RateLimitError = copy(httpStatus = Some(status))

  /** This error, carrying the provider's id for the refused request. */
  def withRequestId(id: String): RateLimitError = copy(requestId = Some(id))

  /** This error with `sentence` and a troubleshooting link appended to its message (#2285). */
  private[llm4s] def withHint(sentence: String, entry: TroubleshootingLinks.Entry): RateLimitError =
    copy(message = TroubleshootingLinks.withAction(message, sentence, entry))

  /** This error with the provider's own explanation appended to its message. */
  private[llm4s] def withDetail(detail: String): RateLimitError =
    if (detail.trim.isEmpty) this else copy(message = s"$message: $detail")

  override val context: Map[String, String] = Map(
    "provider" -> provider
  ) ++ retryAfter.map(d => "retryAfter" -> DurationText(d)) ++
    httpStatus.map("httpStatus" -> _.toString) ++ requestId.map("requestId" -> _)
}

object RateLimitError {

  /** Create basic rate limit error */
  def apply(provider: String): RateLimitError =
    new RateLimitError(s"Rate limited by $provider", None, provider, RateLimitOrigin.UpstreamProvider, None, None)

  /** Create rate limit error with the delay the provider asked for before retrying */
  def apply(provider: String, retryAfter: FiniteDuration): RateLimitError =
    new RateLimitError(
      s"Rate limited by $provider. Retry after ${DurationText(retryAfter)}",
      Some(retryAfter),
      provider,
      RateLimitOrigin.UpstreamProvider,
      None,
      None
    )

  /**
   * Create a rate limit error for a request rejected locally - by `ReliableClient`'s own token
   * bucket - before it ever reached `provider`. Tagged with [[RateLimitOrigin.LocalThrottle]] so
   * that `ReliableClient` keeps it out of the circuit breaker and does not record its metrics
   * event twice.
   */
  def local(provider: String): RateLimitError =
    new RateLimitError("Local rate limit exceeded.", None, provider, RateLimitOrigin.LocalThrottle, None, None)

  /**
   * Extracts the message, `retryAfter` and the provider. The arity is fixed: `origin`, `httpStatus` and `requestId`, added
   * later, are read by name.
   */
  def unapply(error: RateLimitError): Option[(String, Option[FiniteDuration], String)] =
    Some((error.message, error.retryAfter, error.provider))
}
