package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.FinishReason

/**
 * The chat-completions `finish_reason` as a [[FinishReason]] (RFC 0009), for this module's client and `llm4s-openai`.
 * `content_filter` stays `Other` until RFC 0009 PR 2 makes it a `ContentFilteredError`.
 */
private[llm4s] object ChatCompletionsFinishReason {

  /** `stop`, `tool_calls` (or the older `function_call`) and `length` mapped; anything else kept as `Other`. */
  def apply(raw: String): FinishReason = raw match {
    case "stop"                         => FinishReason.Stop
    case "tool_calls" | "function_call" => FinishReason.ToolCalls
    case "length"                       => FinishReason.Length
    case other                          => FinishReason.Other(other)
  }

  /** The reason in a choice's `finish_reason`: `None` when absent, `null` or blank. */
  def of(choice: ujson.Value): Option[FinishReason] =
    choice.obj.get("finish_reason").flatMap(_.strOpt).filter(r => r.nonEmpty && r != "null").map(apply)
}
