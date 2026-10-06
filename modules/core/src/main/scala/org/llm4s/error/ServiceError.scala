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

  /** This error, carrying the delay the provider asked for before retrying. */
  def withRetryAfter(delay: FiniteDuration): ServiceError = copy(retryAfter = Some(delay))

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

  /** Unapply extractor for pattern matching */
  def unapply(error: ServiceError): Option[(String, Int, String, Option[String])] =
    Some((error.message, error.httpStatus, error.provider, error.requestId))

  // Make ServiceError recoverable or non-recoverable based on HTTP status
  implicit class ServiceErrorOps(error: ServiceError) {
    def isRecoverableStatus: Boolean = org.llm4s.reliability.RetryPolicy.isRetryableStatus(error.httpStatus)
  }
}
