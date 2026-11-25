package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * A message carries a content part the provider cannot send: an image or a document its API does
 * not take, from a source it cannot read, in a role it cannot carry it in, or a file uploaded to
 * another provider. The request fails before anything is sent, rather than have the part dropped
 * or sent as text. It is not [[LLMError.isRetryable retryable]]: the conversation must change before a retry.
 *
 * @param provider the client's provider id, e.g. `ollama`
 * @param part     the part's kind, e.g. `image (url, image/png)` (see `ContentPart.kind`)
 * @param role     the role of the message that carries it, e.g. `user` or `tool`
 */
@Stable
final case class UnsupportedContentPartError private (
  override val message: String,
  provider: ProviderId,
  part: String,
  role: String
) extends LLMError {
  override val context: Map[String, String] = Map("provider" -> provider.asString, "part" -> part, "role" -> role)
}

object UnsupportedContentPartError {

  /** The error for `part` in a `role` message sent to `provider`. */
  def apply(provider: ProviderId, part: String, role: String): UnsupportedContentPartError =
    new UnsupportedContentPartError(s"$provider cannot send a $part part in a $role message", provider, part, role)

  /** Extracts the message, provider, part and role. */
  def unapply(error: UnsupportedContentPartError): Option[(String, ProviderId, String, String)] =
    Some((error.message, error.provider, error.part, error.role))
}
