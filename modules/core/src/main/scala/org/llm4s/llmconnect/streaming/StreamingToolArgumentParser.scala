package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable
import org.llm4s.util.BoundedJson

/**
 * Shared boundary parser for streaming tool-call arguments.
 *
 * - Empty input yields an empty object sentinel for accumulators.
 * - Valid JSON is parsed as-is.
 * - Invalid/partial JSON is preserved as a raw string for later assembly.
 * - JSON nested more than 512 levels deep is preserved as a raw string too: the arguments are
 *   model output, and a value that deep overflows the stack of whatever renders it next (#1562).
 */
@Stable
object StreamingToolArgumentParser {

  def parse(raw: String): ujson.Value =
    if (raw.isEmpty) ujson.Obj() else BoundedJson.read(raw).getOrElse(ujson.Str(raw))
}
