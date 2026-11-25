package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.TokenUsage

/**
 * The provider's content filter stopped the call: it blocked the prompt, or stopped the reply, before or after some
 * text. Every chat client raises it for its provider's filter finish (RFC 0009): Gemini's
 * `promptFeedback.blockReason` or a `finishReason` such as `SAFETY`, `RECITATION`, `PROHIBITED_CONTENT`, `BLOCKLIST` or
 * `SPII`; the chat-completions `finish_reason` `content_filter` (OpenAI, Azure OpenAI and the OpenAI-compatible
 * clients); Anthropic's `stop_reason` `refusal`; Bedrock's `stopReason` `guardrail_intervened` or `content_filtered`.
 * A reply the filter cut short is not a finished answer; a stream has already delivered the text before the stop,
 * and the message says how much there was.
 *
 * The provider is healthy, so a `ReliableClient`'s circuit breaker counts this as neither a success nor a failure.
 * It is not [[LLMError.isRetryable retryable]]: sending the same request again would be filtered the same way: the conversation
 * must change before a retry.
 *
 * @param provider      the client's provider name, e.g. `Gemini`
 * @param reason        the provider's reason, as it named it, e.g. `SAFETY`
 * @param promptBlocked whether the prompt itself was blocked (`blockReason`), rather than the reply (`finishReason`)
 * @param usage         the tokens the provider reported for the filtered call, which it bills, when it reported them
 */
@Stable
final case class ContentFilteredError private (
  override val message: String,
  provider: String,
  reason: String,
  promptBlocked: Boolean,
  usage: Option[TokenUsage]
) extends LLMError {
  override val context: Map[String, String] =
    Map("provider" -> provider, "reason" -> reason, "promptBlocked" -> promptBlocked.toString)

  /** This error with the token usage the provider reported for the call. */
  def withUsage(usage: Option[TokenUsage]): ContentFilteredError =
    new ContentFilteredError(message, provider, reason, promptBlocked, usage)

  /** This error with the token usage the provider reported for the call. */
  def withUsage(usage: TokenUsage): ContentFilteredError = withUsage(Some(usage))
}

object ContentFilteredError {

  /** The error for a filtered call; [[prompt]] and [[reply]] name each case. */
  def apply(provider: String, reason: String, promptBlocked: Boolean): ContentFilteredError = {
    val message =
      if (promptBlocked) s"$provider blocked the prompt: blockReason $reason"
      else s"$provider returned no content: finishReason $reason"
    new ContentFilteredError(message, provider, reason, promptBlocked, None)
  }

  /** `provider` blocked the prompt for `blockReason`. */
  def prompt(provider: String, blockReason: String): ContentFilteredError =
    apply(provider, blockReason, promptBlocked = true)

  /** `provider` withheld the reply and sent no content, finishing for `finishReason`. */
  def reply(provider: String, finishReason: String): ContentFilteredError =
    apply(provider, finishReason, promptBlocked = false)

  /**
   * `provider`'s filter stopped the reply for `finishReason` after `cutAfterChars` characters of answer text; the
   * message says how many, and is [[reply]]'s when there were none. A stream has already delivered that text.
   */
  def reply(provider: String, finishReason: String, cutAfterChars: Int): ContentFilteredError =
    if (cutAfterChars <= 0) reply(provider, finishReason)
    else
      new ContentFilteredError(
        s"$provider stopped the reply after $cutAfterChars characters of text: finishReason $finishReason",
        provider,
        finishReason,
        promptBlocked = false,
        usage = None
      )

  /**
   * Extracts the message, provider, reason and whether the prompt was blocked. The arity is fixed: `usage`, added
   * later, is read by name.
   */
  def unapply(error: ContentFilteredError): Option[(String, String, String, Boolean)] =
    Some((error.message, error.provider, error.reason, error.promptBlocked))
}
