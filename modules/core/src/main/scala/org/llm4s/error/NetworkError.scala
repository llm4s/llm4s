package org.llm4s.error

/**
 * Network-related errors
 * @param message
 * @param cause
 * @param endpoint
 */
final case class NetworkError private (
  override val message: String,
  cause: Option[Throwable],
  endpoint: String
) extends LLMError
    with RecoverableError {
  override val context: Map[String, String] = Map("endpoint" -> endpoint) ++
    cause.map(c => Map("exceptionType" -> c.getClass.getSimpleName)).getOrElse(Map.empty)
}

object NetworkError {
  def apply(message: String, cause: Option[Throwable], endpoint: String): NetworkError =
    new NetworkError(message, cause, endpoint)

  /** Unapply extractor for pattern matching */
  def unapply(error: NetworkError): Option[(String, Option[Throwable], String)] =
    Some((error.message, error.cause, error.endpoint))
}
