package org.llm4s.util

import org.llm4s.error.ValidationError
import org.llm4s.types.{ Result, TryOps }

import scala.annotation.tailrec
import scala.util.Try

/**
 * Reads JSON that a model or a tool produced, refusing a document nested too deeply to parse.
 *
 * `ujson.read` recurses once per nesting level, so a reply such as 100,000 `[` overflows the stack,
 * and a `StackOverflowError` is not an `Exception`: `Try` does not catch it, and it escapes every
 * `Result`-returning caller (#1562). The depth is therefore measured first, on the raw text, with an
 * iterative scan that ignores brackets inside string literals - the scan `JSONTool` in
 * `llm4s-agent-tools` uses for the same reason (#1510, #1564) - so a document that would overflow
 * is never handed to the parser. Malformed text is not judged by the scan: the parser reports it.
 *
 * Use it wherever text a model wrote (a structured reply, tool-call arguments, a guardrail's input)
 * or a tool returned is parsed. JSON the library wrote itself, configuration and provider response
 * envelopes - in which the model's text sits inside string literals - do not need it.
 */
private[llm4s] object BoundedJson {

  /**
   * Deepest nesting (arrays and objects together) [[read]] accepts by default. 512 is the limit
   * `JSONTool` settled on (#1564): deep enough for any reply a model means, and parsed comfortably on
   * the 1 MB thread stack its specs run on. No structured output or tool call the library handles
   * comes anywhere near it.
   */
  val MaxDepth: Int = 512

  /**
   * Parses `text`, or returns a `Left`: a [[ValidationError]] on field `json` naming the limit when
   * `text` is nested more than `maxDepth` levels deep, otherwise the parser's error exactly as
   * `Try(ujson.read(text)).toResult` reports it.
   */
  def read(text: String, maxDepth: Int = MaxDepth): Result[ujson.Value] =
    if (exceedsDepth(text, maxDepth)) Left(tooDeep(maxDepth))
    else Try(ujson.read(text)).toResult

  /** Whether `text` is nested more than `maxDepth` levels deep (brackets inside strings not counted). */
  def exceedsDepth(text: String, maxDepth: Int = MaxDepth): Boolean = maxNesting(text, maxDepth) > maxDepth

  /** The error [[read]] returns for a document nested more than `maxDepth` levels deep. */
  def tooDeep(maxDepth: Int = MaxDepth): ValidationError =
    ValidationError("json", s"JSON is nested more than $maxDepth levels deep")

  /**
   * The deepest nesting in `text`, stopping as soon as it passes `limit`, so a very long document
   * costs no more to refuse than its first `limit` opening brackets. Adapted from
   * `JSONTool.maxNesting` in `llm4s-agent-tools`.
   */
  private def maxNesting(text: String, limit: Int): Int = {
    @tailrec
    def scan(i: Int, depth: Int, deepest: Int, inString: Boolean, escaped: Boolean): Int =
      if (i >= text.length || deepest > limit) deepest
      else {
        val c = text.charAt(i)
        if (inString) {
          if (escaped) scan(i + 1, depth, deepest, inString = true, escaped = false)
          else if (c == '\\') scan(i + 1, depth, deepest, inString = true, escaped = true)
          else if (c == '"') scan(i + 1, depth, deepest, inString = false, escaped = false)
          else scan(i + 1, depth, deepest, inString = true, escaped = false)
        } else if (c == '"') scan(i + 1, depth, deepest, inString = true, escaped = false)
        else if (c == '[' || c == '{')
          scan(i + 1, depth + 1, math.max(deepest, depth + 1), inString = false, escaped = false)
        else if (c == ']' || c == '}') scan(i + 1, math.max(0, depth - 1), deepest, inString = false, escaped = false)
        else scan(i + 1, depth, deepest, inString = false, escaped = false)
      }
    scan(0, 0, 0, inString = false, escaped = false)
  }
}
