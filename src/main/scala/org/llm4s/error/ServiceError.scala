package org.llm4s.error

/**
 * Service-level errors from LLM providers
 */
final case class ServiceError private (
  override val message: String,
  httpStatus: Int,
  provider: String,
  requestId: Option[String]
) extends LLMError
    with RecoverableError {

  override val code: Option[String]     = Some(httpStatus.toString)
  override val retryDelay: Option[Long] = if (isRecoverable) Some(2000) else None

  override val context: Map[String, String] = Map(
    "httpStatus" -> httpStatus.toString,
    "provider"   -> provider
  ) ++ requestId.map("requestId" -> _).toMap
}

object ServiceError {
  def apply(httpStatus: Int, provider: String, details: String): ServiceError =
    new ServiceError(s"Service error from $provider: $details (HTTP $httpStatus)", httpStatus, provider, None)

  def apply(httpStatus: Int, provider: String, details: String, requestId: String): ServiceError =
    new ServiceError(
      s"Service error from $provider: $details (HTTP $httpStatus) (Request ID $requestId)",
      httpStatus,
      provider,
      Some(requestId)
    )

  /** Unapply extractor for pattern matching */
  def unapply(error: ServiceError): Option[(String, Int, String, Option[String])] =
    Some((error.message, error.httpStatus, error.provider, error.requestId))

  // Make ServiceError recoverable or non-recoverable based on HTTP status
  implicit class ServiceErrorOps(error: ServiceError) {
    def isRecoverableStatus: Boolean = error.httpStatus >= 500 || error.httpStatus == 429 || error.httpStatus == 408
  }
}
