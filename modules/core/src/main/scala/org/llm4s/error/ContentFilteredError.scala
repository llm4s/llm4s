package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * The provider's content filter stopped the call: it blocked the prompt, or withheld the reply and sent no content
 * (Gemini's `promptFeedback.blockReason`, or a candidate with no content and a `finishReason` such as `SAFETY`,
 * `RECITATION`, `PROHIBITED_CONTENT`, `BLOCKLIST` or `SPII`).
 *
 * The provider is healthy, so a `ReliableClient`'s circuit breaker counts this as neither a success nor a failure.
 * As a [[NonRecoverableError]], sending the same request again would be filtered the same way: the conversation
 * must change before a retry.
 *
 * @param provider      the client's provider name, e.g. `Gemini`
 * @param reason        the provider's reason, as it named it, e.g. `SAFETY`
 * @param promptBlocked whether the prompt itself was blocked (`blockReason`), rather than the reply (`finishReason`)
 */
@Stable
final case class ContentFilteredError private (
  override val message: String,
  provider: String,
  reason: String,
  promptBlocked: Boolean
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] =
    Map("provider" -> provider, "reason" -> reason, "promptBlocked" -> promptBlocked.toString)
}

object ContentFilteredError {

  /** The error for a filtered call; [[prompt]] and [[reply]] name each case. */
  def apply(provider: String, reason: String, promptBlocked: Boolean): ContentFilteredError = {
    val message =
      if (promptBlocked) s"$provider blocked the prompt: blockReason $reason"
      else s"$provider returned no content: finishReason $reason"
    new ContentFilteredError(message, provider, reason, promptBlocked)
  }

  /** `provider` blocked the prompt for `blockReason`. */
  def prompt(provider: String, blockReason: String): ContentFilteredError =
    apply(provider, blockReason, promptBlocked = true)

  /** `provider` withheld the reply and sent no content, finishing for `finishReason`. */
  def reply(provider: String, finishReason: String): ContentFilteredError =
    apply(provider, finishReason, promptBlocked = false)

  /** Extracts the message, provider, reason and whether the prompt was blocked. */
  def unapply(error: ContentFilteredError): Option[(String, String, String, Boolean)] =
    Some((error.message, error.provider, error.reason, error.promptBlocked))
}
