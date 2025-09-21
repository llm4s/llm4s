package org.llm4s.error

/**
 * API errors for external service calls
 */
final case class APIError private (
  override val message: String,
  provider: String,
  statusCode: Option[Int],
  responseBody: Option[String]
) extends LLMError
    with RecoverableError {
  override val context: Map[String, String] = Map("provider" -> provider) ++
    statusCode.map("statusCode" -> _.toString).toMap ++
    responseBody.map("responseBody" -> _).toMap
}

object APIError {
  def apply(
    provider: String,
    message: String,
    statusCode: Option[Int] = None,
    responseBody: Option[String] = None
  ): APIError =
    new APIError(s"API call to $provider failed: $message", provider, statusCode, responseBody)

  /** Unapply extractor for pattern matching */
  def unapply(error: APIError): Option[(String, String, Option[Int], Option[String])] =
    Some((error.message, error.provider, error.statusCode, error.responseBody))
}
