package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.util.DurationText

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Service-level errors from LLM providers
 *
 * @param retryAfter the delay the provider asked for before retrying (an HTTP `Retry-After`
 *                   header, usually on a 503), if it gave one
 */
@Stable
final case class ServiceError private (
  override val message: String,
  httpStatus: Int,
  provider: String,
  requestId: Option[String],
  retryAfter: Option[FiniteDuration]
) extends LLMError
    with RecoverableError {

  override val code: Option[String] = Some(httpStatus.toString)

  /** The provider's `retryAfter` hint when it gave one, else [[ServiceError.DefaultRetryDelay]]. */
  override val retryDelay: Option[FiniteDuration] = retryAfter.orElse(Some(ServiceError.DefaultRetryDelay))

  /**
   * Whether `httpStatus` is one a retry may fix: 5xx, 429 and 408 - the status check of
   * [[org.llm4s.reliability.RetryPolicy.isRetryableStatus RetryPolicy.isRetryableStatus]]. The error itself is
   * always a `RecoverableError`; this says whether this particular status is.
   */
  def isRecoverableStatus: Boolean = org.llm4s.reliability.RetryPolicy.isRetryableStatus(httpStatus)

  /** This error, carrying the delay the provider asked for before retrying. */
  def withRetryAfter(delay: FiniteDuration): ServiceError = copy(retryAfter = Some(delay))

  /** This error with `sentence` and a troubleshooting link appended to its message (#2285). */
  private[llm4s] def withHint(sentence: String, entry: TroubleshootingLinks.Entry): ServiceError =
    copy(message = TroubleshootingLinks.withAction(message, sentence, entry))

  override val context: Map[String, String] = Map(
    "httpStatus" -> httpStatus.toString,
    "provider"   -> provider
  ) ++ requestId.map("requestId" -> _).toMap ++ retryAfter.map(d => "retryAfter" -> DurationText(d))
}

object ServiceError {

  /** The delay [[ServiceError.retryDelay]] suggests when the provider gave no `retryAfter` hint. */
  val DefaultRetryDelay: FiniteDuration = 2.seconds

  def apply(httpStatus: Int, provider: String, details: String): ServiceError =
    new ServiceError(s"Service error from $provider: $details (HTTP $httpStatus)", httpStatus, provider, None, None)

  def apply(httpStatus: Int, provider: String, details: String, requestId: String): ServiceError =
    new ServiceError(
      s"Service error from $provider: $details (HTTP $httpStatus) (Request ID $requestId)",
      httpStatus,
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
      s"Service error from $provider: $details (HTTP ${error.httpStatus})",
      error.httpStatus,
      provider,
      error.requestId,
      error.retryAfter
    )
}
