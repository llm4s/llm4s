package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.util.DurationText

import scala.concurrent.duration.FiniteDuration

/**
 * A provider's response with an HTTP status that no more specific error covers.
 *
 * It is [[LLMError.isRetryable retryable]] when its status is 408, 429 or any 5xx: the statuses for which sending
 * the same request again may succeed. Any other status - a 404, a 409, a 422 - means the request itself is wrong.
 *
 * @param status     the HTTP status of the response; [[httpStatus]] is `Some(status)`
 * @param retryAfter the delay the provider asked for before retrying (an HTTP `Retry-After`
 *                   header, usually on a 503), if it gave one
 */
@Stable
final case class ServiceError private (
  override val message: String,
  status: Int,
  provider: String,
  override val requestId: Option[String],
  override val retryAfter: Option[FiniteDuration]
) extends LLMError {

  override val code: Option[String] = Some(status.toString)

  override def httpStatus: Option[Int] = Some(status)

  override def isRetryable: Boolean = ServiceError.isRetryableStatus(status)

  /** This error, carrying the delay the provider asked for before retrying. */
  def withRetryAfter(delay: FiniteDuration): ServiceError = copy(retryAfter = Some(delay))

  /** This error, carrying the provider's id for the failed request; the message is left as it is. */
  def withRequestId(id: String): ServiceError = copy(requestId = Some(id))

  /** This error with `sentence` and a troubleshooting link appended to its message (#2285). */
  private[llm4s] def withHint(sentence: String, entry: TroubleshootingLinks.Entry): ServiceError =
    copy(message = TroubleshootingLinks.withAction(message, sentence, entry))

  override val context: Map[String, String] = Map(
    "httpStatus" -> status.toString,
    "provider"   -> provider
  ) ++ requestId.map("requestId" -> _).toMap ++ retryAfter.map(d => "retryAfter" -> DurationText(d))
}

object ServiceError {

  /** The HTTP statuses worth retrying: any 5xx, 429 (rate limit) and 408 (request timeout). */
  private[llm4s] def isRetryableStatus(status: Int): Boolean =
    status >= 500 || status == 429 || status == 408

  def apply(status: Int, provider: String, details: String): ServiceError =
    new ServiceError(s"Service error from $provider: $details (HTTP $status)", status, provider, None, None)

  def apply(status: Int, provider: String, details: String, requestId: String): ServiceError =
    new ServiceError(
      s"Service error from $provider: $details (HTTP $status) (Request ID $requestId)",
      status,
      provider,
      Some(requestId),
      None
    )

  /**
   * `error` with `details` as its message and `provider` as its provider, keeping its status, request id and
   * `retryAfter` (the message does not repeat the request id, which `details` usually already holds).
   */
  private[llm4s] def rewrapped(error: ServiceError, provider: String, details: String): ServiceError =
    new ServiceError(
      s"Service error from $provider: $details (HTTP ${error.status})",
      error.status,
      provider,
      error.requestId,
      error.retryAfter
    )

  /**
   * Extracts the message, HTTP status, provider, request id and `retryAfter`; the arity is fixed. It always
   * matches, as the synthesized extractor it replaces did.
   */
  def unapply(error: ServiceError): Some[(String, Int, String, Option[String], Option[FiniteDuration])] =
    Some((error.message, error.status, error.provider, error.requestId, error.retryAfter))
}
