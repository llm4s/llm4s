package org.llm4s.error

/**
 * Raised when a network-level failure occurs during communication with the LLM provider.
 *
 * This is a [[RecoverableError]]: it indicates transient failures such as connection
 * drops, DNS resolution failures, or temporary unreachability. Retrying the operation
 * may succeed.
 *
 * @param message human-readable description of the network failure
 * @param cause optional underlying exception that caused the failure (e.g., java.net.ConnectException)
 * @param endpoint the URL or endpoint that was being accessed
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
