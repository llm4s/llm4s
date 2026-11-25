package org.llm4s.llmconnect.provider

import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.model.{ FinishReason, TokenUsage }
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * The chat-completions `finish_reason` as a [[FinishReason]] (RFC 0009), for this module's client and `llm4s-openai`.
 *
 * `content_filter` is not a finish but a [[org.llm4s.error.ContentFilteredError]], whether or not text came first,
 * on `complete` and on a stream alike (the rule the Gemini clients follow since #2664): [[filtered]] gives it.
 */
private[llm4s] object ChatCompletionsFinishReason {

  /** The `finish_reason` of a reply a content filter stopped. */
  val ContentFilter = "content_filter"

  /**
   * `stop`, `tool_calls` (or the older `function_call`) and `length` mapped; anything else kept as `Other`.
   * `content_filter` maps to `Other("content_filter")` here: the client turns it into an error with [[filtered]].
   */
  def apply(raw: String): FinishReason = raw match {
    case "stop"                         => FinishReason.Stop
    case "tool_calls" | "function_call" => FinishReason.ToolCalls
    case "length"                       => FinishReason.Length
    case ""                             => FinishReason.Unknown
    case other                          => FinishReason.Other(other)
  }

  /** The reason in a choice's `finish_reason`: `None` when absent, `null` or blank. */
  def of(choice: ujson.Value): Option[FinishReason] =
    choice.obj.get("finish_reason").flatMap(_.strOpt).filter(r => r.nonEmpty && r != "null").map(apply)

  /** Whether `reason` is the content filter's. */
  def isFiltered(reason: FinishReason): Boolean = reason == FinishReason.Other(ContentFilter)

  /**
   * The error for a reply whose `finish_reason` is `content_filter`, after `cutAfterChars` characters of answer
   * text, with the usage the provider reported; `None` for any other reason.
   */
  def filtered(
    provider: ProviderId,
    reason: Option[FinishReason],
    cutAfterChars: Int,
    usage: Option[TokenUsage]
  ): Option[ContentFilteredError] =
    reason
      .filter(isFiltered)
      .map(_ => ContentFilteredError.reply(provider, ContentFilter, cutAfterChars).withUsage(usage))
}
