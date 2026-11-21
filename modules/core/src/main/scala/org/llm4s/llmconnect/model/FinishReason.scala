package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import upickle.default.ReadWriter

/**
 * Why the model's turn ended, as the provider reported it, mapped to one vocabulary (RFC 0009).
 *
 * Every [[Completion]] carries one, and a [[StreamedChunk]] may. Each client maps its own provider's values; a value a
 * client has no mapping for is kept as [[FinishReason.Other]], so a new provider value never fails a call. There is no
 * error case: a reply that ended in an error is a `Left`, and so is a content-filter finish
 * ([[org.llm4s.error.ContentFilteredError]]).
 */
@Stable
enum FinishReason derives ReadWriter {

  /** The model ended its turn: a natural stop, or a stop sequence. */
  case Stop

  /** The model asked for one or more tool calls. */
  case ToolCalls

  /** An output limit was reached: max tokens, or the context window. The content is cut short. */
  case Length

  /**
   * A reason this client has no mapping for, kept as the provider sent it. `Other("")` means the client did not read
   * one: a provider that sent none, or a client not mapped yet.
   */
  case Other(raw: String)

  /** The chat-completions name (`stop`, `tool_calls`, `length`), or [[FinishReason.Other]]'s raw value. */
  def name: String = this match {
    case Stop       => "stop"
    case ToolCalls  => "tool_calls"
    case Length     => "length"
    case Other(raw) => raw
  }
}
