package org.llm4s.error

/**
 * Rate limiting errors - typically recoverable with exponential backoff
 */

final case class RateLimitError private (
  override val message: String,
  retryAfter: Option[Long],
  provider: String,
  requestsRemaining: Option[Int] = None,
  resetTime: Option[Long] = None
) extends LLMError
    with RecoverableError {

  override val maxRetries: Int = 5

  // Intelligent retry delay calculation
  override def retryDelay: Option[Long] = retryAfter.orElse {
    Some(Math.min(30000, 1000 * Math.pow(2, maxRetries).toLong)) // Exponential backoff, max 30s
  }

  override val context: Map[String, String] = Map(
    "provider" -> provider
  ) ++ retryAfter.map("retryAfter" -> _.toString) ++
    requestsRemaining.map("requestsRemaining" -> _.toString) ++
    resetTime.map("resetTime" -> _.toString)
}

object RateLimitError {

  /** Create basic rate limit error */
  def apply(provider: String): RateLimitError =
    RateLimitError(s"Rate limited by $provider", None, provider)

  /** Create rate limit error with retry delay */
  def apply(provider: String, retryAfter: Long): RateLimitError =
    RateLimitError(s"Rate limited by $provider. Retry after $retryAfter seconds", Some(retryAfter), provider)

  /** Unapply extractor for pattern matching */
  def unapply(error: RateLimitError): Option[(String, Option[Long], String)] =
    Some((error.message, error.retryAfter, error.provider))
}
