package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.provider.ToolCallRules
import org.llm4s.types.Result

/**
 * Shared boundary parser for a tool call's arguments, as the text a provider streamed or sent (RFC 0009, Part 3).
 *
 * - Empty or blank input is `{}`, a call with no arguments. Whether that stands depends on how the reply ended:
 *   [[StreamingAccumulator.toCompletion]] accepts it only after a `ToolCalls` or `Stop` finish.
 * - A JSON object is parsed as-is.
 * - Anything else is a `Left`, the malformed-tool-call [[org.llm4s.error.ProcessingError ProcessingError]]: JSON that
 *   is cut or invalid, JSON that is not an object, and JSON nested more than 512 levels deep, which is refused before
 *   it is parsed: the arguments are model output, and a value that deep overflows the stack of whatever renders it
 *   next (#1562). A broken call is never `{}` and never a raw string.
 */
@Stable
object StreamingToolArgumentParser {

  def parse(raw: String): Result[ujson.Value] = ToolCallRules.parseArguments(raw)
}
