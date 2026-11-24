package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import upickle.default.ReadWriter

/**
 * Why the model's turn ended, as the provider reported it, mapped to one vocabulary (RFC 0009).
 *
 * Every [[Completion]] carries one, and a [[StreamedChunk]] may. Each client maps its own provider's values; a value a
 * client has no mapping for is kept as [[FinishReason.Other]], so a new provider value never fails a call, and a reply
 * whose reason the client did not read is [[FinishReason.Unknown]], never a `Stop` the provider did not send. There is no
 * error case: a reply that ended in an error is a `Left`, and so is a content-filter finish
 * ([[org.llm4s.error.ContentFilteredError]]).
 */
@Stable
enum FinishReason {

  /** The model ended its turn: a natural stop, or a stop sequence. */
  case Stop

  /** The model asked for one or more tool calls. */
  case ToolCalls

  /** An output limit was reached: max tokens, or the context window. The content is cut short. */
  case Length

  /** A reason this client has no mapping for, kept as the provider sent it. */
  case Other(raw: String)

  /**
   * The client read no reason: the provider sent none, or the client does not map one. It is what a [[Completion]]
   * built without a reason carries, so a client that forgets to set one never claims a natural stop.
   */
  case Unknown

  /**
   * The chat-completions name (`stop`, `tool_calls`, `length`), [[FinishReason.Other]]'s raw value, or `""` for
   * [[FinishReason.Unknown]].
   */
  def name: String = this match {
    case Stop       => "stop"
    case ToolCalls  => "tool_calls"
    case Length     => "length"
    case Other(raw) => raw
    case Unknown    => ""
  }
}

object FinishReason {

  // the derived codec: a case without fields is its name, `Other` is {"$type":"Other","raw":...}
  private val derived: ReadWriter[FinishReason] = ReadWriter.derived

  /**
   * The JSON codec, as derived: `"Stop"`, `"ToolCalls"`, `"Length"`, `"Unknown"` and
   * `{"$type":"Other","raw":"..."}`. An `Other` with an empty `raw`, which meant "none read" before
   * [[FinishReason.Unknown]] existed, is read as `Unknown`.
   */
  given ReadWriter[FinishReason] = derived.bimap[FinishReason](
    identity,
    {
      case Other("") => Unknown
      case reason    => reason
    }
  )
}
