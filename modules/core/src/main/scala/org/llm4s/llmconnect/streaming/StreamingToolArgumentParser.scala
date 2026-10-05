package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable

import scala.util.Try

/**
 * Shared boundary parser for streaming tool-call arguments.
 *
 * - Empty input yields an empty object sentinel for accumulators.
 * - Valid JSON is parsed as-is.
 * - Invalid/partial JSON is preserved as a raw string for later assembly.
 */
@Stable
object StreamingToolArgumentParser {

  def parse(raw: String): ujson.Value =
    if (raw.isEmpty) ujson.Obj() else Try(ujson.read(raw)).getOrElse(ujson.Str(raw))
}
