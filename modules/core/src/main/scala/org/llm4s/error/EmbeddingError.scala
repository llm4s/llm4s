package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * EmbeddingError represents a structured error returned from
 * an embedding provider (e.g., OpenAI or VoyageAI) or local encoders/extractors.
 *
 * @param code Optional error code: the HTTP status of the provider's reply (e.g., "401"), as a string because
 *             it is [[LLMError.code]]. `None` for an error that is not an HTTP reply.
 * @param message Human-readable error message from the provider or client.
 * @param provider The id of the embedding provider (`openai`, `voyage`), or of the local component that failed (`encoder`,
 *                 `extractor`)
 *
 * [[httpStatus]] is `code` when it is an HTTP status. An embedding error is [[LLMError.isRetryable retryable]]
 * when that status is 408, 429 or 5xx, as a [[ServiceError]] with it would be; one with no status is not.
 */
@Stable
final case class EmbeddingError private (
  override val code: Option[String],
  override val message: String,
  provider: ProviderId
) extends LLMError {
  override val context: Map[String, String] = Map("provider" -> provider.asString)

  override def httpStatus: Option[Int] = code.flatMap(_.trim.toIntOption).filter(s => s >= 100 && s <= 599)

  override def isRetryable: Boolean = httpStatus.exists(ServiceError.isRetryableStatus)
}

object EmbeddingError {
  def apply(code: Option[String], message: String, provider: ProviderId): EmbeddingError =
    new EmbeddingError(code, message, provider)

  def unapply(error: EmbeddingError): Option[(Option[String], String, ProviderId)] =
    Some((error.code, error.message, error.provider))
}
