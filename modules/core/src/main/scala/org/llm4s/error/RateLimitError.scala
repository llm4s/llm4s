package org.llm4s.error

import org.llm4s.util.DurationText

import java.time.Instant
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Where a [[RateLimitError]] originated. Distinguishes a request that never left the
 * process (rejected by a local token bucket) from one the provider itself rejected with
 * an HTTP 429 - the two need different treatment when a caller has already recorded a
 * metrics event for the local case, e.g. `org.llm4s.reliability.ReliableClient`'s rate limit.
 */
enum RateLimitOrigin {
  case LocalThrottle, UpstreamProvider
}

/**
 * Raised when the LLM provider rejects the request due to rate limiting.
 *
 * This is a [[RecoverableError]]: it is safe to retry after waiting.
 * The client can handle this automatically when configured with a retry policy.
 * It provides intelligent retry delays, utilizing provider hints when available.
 *
 * @param message human-readable description of the rate limit
 * @param retryAfter optional delay the provider asked for before retrying (e.g. an HTTP
 *                   `Retry-After` header)
 * @param provider the name of the LLM provider (e.g., "openai", "anthropic")
 * @param requestsRemaining optional number of requests remaining in the current window
 * @param resetTime optional instant at which the rate limit window resets
 * @param origin whether this was rejected locally (never reached the provider) or by the
 *               provider itself; defaults to [[RateLimitOrigin.UpstreamProvider]] since every
 *               existing constructor call maps a provider-side rejection
 */
final case class RateLimitError private (
  override val message: String,
  retryAfter: Option[FiniteDuration],
  provider: String,
  requestsRemaining: Option[Int] = None,
  resetTime: Option[Instant] = None,
  origin: RateLimitOrigin = RateLimitOrigin.UpstreamProvider
) extends LLMError
    with RecoverableError {

  override val maxRetries: Int = 5

  /** The provider's `retryAfter` hint when it gave one, else [[RateLimitError.DefaultRetryDelay]]. */
  override def retryDelay: Option[FiniteDuration] = retryAfter.orElse(Some(RateLimitError.DefaultRetryDelay))

  override val context: Map[String, String] = Map(
    "provider" -> provider
  ) ++ retryAfter.map(d => "retryAfter" -> DurationText(d)) ++
    requestsRemaining.map("requestsRemaining" -> _.toString) ++
    resetTime.map("resetTime" -> _.toString)
}

object RateLimitError {

  /** The delay [[RateLimitError.retryDelay]] suggests when the provider gave no `retryAfter` hint. */
  val DefaultRetryDelay: FiniteDuration = 30.seconds

  /** Create basic rate limit error */
  def apply(provider: String): RateLimitError =
    RateLimitError(s"Rate limited by $provider", None, provider)

  /** Create rate limit error with the delay the provider asked for before retrying */
  def apply(provider: String, retryAfter: FiniteDuration): RateLimitError =
    RateLimitError(s"Rate limited by $provider. Retry after ${DurationText(retryAfter)}", Some(retryAfter), provider)

  /**
   * Create a rate limit error for a request rejected locally (e.g. by a token-bucket
   * middleware) before it ever reached `provider`. Tagged with [[RateLimitOrigin.LocalThrottle]]
   * so a wrapping component that already recorded a metrics event for the rejection -
   * such as `ReliableClient` composed around a rate-limiting middleware - can recognize
   * that and avoid recording it again.
   */
  def local(provider: String): RateLimitError =
    RateLimitError(
      message = "Local rate limit exceeded.",
      retryAfter = None,
      provider = provider,
      origin = RateLimitOrigin.LocalThrottle
    )

  /** Unapply extractor for pattern matching */
  def unapply(error: RateLimitError): Option[(String, Option[FiniteDuration], String)] =
    Some((error.message, error.retryAfter, error.provider))
}
