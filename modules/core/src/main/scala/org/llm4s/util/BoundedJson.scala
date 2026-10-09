package org.llm4s.util

import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.types.Result

import scala.annotation.tailrec
import scala.util.Try

/**
 * Reads JSON that a model or a tool produced, refusing a document nested too deeply to handle.
 *
 * `ujson.read` itself is iterative: on a 1 MB thread stack it parses a reply of 1,000,000 nested `[`
 * without complaint. What overflows is every traversal of the value it builds - rendering it back to a
 * provider, into a log line or an exception message, `upickle.default.read[A]` (`transform`), equality -
 * each of which recurses once per nesting level; and a `StackOverflowError` is not an `Exception`: `Try`
 * does not catch it, and it escapes every `Result`-returning caller (#1562). So the depth is measured
 * before parsing, on the raw text, with an iterative scan that ignores brackets inside string literals -
 * the scan `JSONTool` in `llm4s-agent-tools` measures its documents with, for the same reason (#1510,
 * #1564) - and a document over the limit is never parsed, so no value that deep exists to be traversed.
 * Malformed text is not judged by the scan: the parser reports it.
 *
 * Use it wherever text a model wrote (a structured reply, tool-call arguments, a guardrail's input)
 * or a tool returned is parsed. JSON the library wrote itself, configuration and provider response
 * envelopes - in which the model's text sits inside string literals - do not need it, unless the
 * model's JSON is a native value in the envelope, as Gemini's `functionCall.args` is (#1643): then
 * the envelope parse is the boundary the model's JSON crosses, and it is the parse to bound.
 */
private[llm4s] object BoundedJson {

  /**
   * Deepest nesting (arrays and objects together) [[read]] accepts by default. 512 is the limit
   * `JSONTool` settled on (#1564): deep enough for any reply a model means, and parsed, rendered and
   * `transform`ed comfortably on the 1 MB thread stack its specs run on. No structured output or tool
   * call the library handles comes anywhere near it.
   */
  val MaxDepth: Int = 512

  /**
   * Parses `text`, or returns a `Left`: a [[ValidationError]] on field `json` naming the limit when
   * `text` is nested more than `maxDepth` levels deep, otherwise a [[ValidationError]] on field `json`
   * carrying the parser's own message (why it stopped, and the JSON path where). The parser's exception
   * is mapped here, never through `DefaultErrorMapper`: a malformed reply is a validation failure, and
   * that mapper used to classify any exception whose message contained `401` or `429` (#1668) - the
   * parser's message is a JSON path such as `$[429]`, so a malformed reply came back as a `RateLimitError`.
   */
  def read(text: String, maxDepth: Int = MaxDepth): Result[ujson.Value] =
    if (exceedsDepth(text, maxDepth)) Left(tooDeep(maxDepth))
    else Try(ujson.read(text)).toEither.left.map(malformed)

  /** Whether `text` is nested more than `maxDepth` levels deep (brackets inside strings not counted). */
  def exceedsDepth(text: String, maxDepth: Int = MaxDepth): Boolean = maxNesting(text, maxDepth) > maxDepth

  /** The field every error of [[read]] is on. */
  private val Field = "json"

  /**
   * What a too-deep error's violation starts with; the limit that refused the document follows. [[tooDeep]]
   * writes it and [[TooDeep]] reads it, so the two cannot disagree on the wording, and the limit is the one
   * thing the recognition ignores.
   */
  private val TooDeepPrefix = "JSON is nested more than"

  /** The error [[read]] returns for a document nested more than `maxDepth` levels deep, naming that limit. */
  def tooDeep(maxDepth: Int = MaxDepth): ValidationError =
    ValidationError(Field, s"$TooDeepPrefix $maxDepth levels deep")

  /**
   * Recognises an error [[read]] returned because the document was too deep, whatever limit refused it:
   * `case Left(BoundedJson.TooDeep()) =>`. A site telling a too-deep refusal from a parse failure must use
   * this, never `e == tooDeep()`: that equality holds only for the error of the default limit with its
   * exact wording, and a read with another limit, or a reworded message, would then fall through to the
   * site's generic arm - in a stream, the arm that skips the chunk (#1651). The recognition is structural:
   * a [[ValidationError]] on the same field, with a violation that starts with the prefix [[tooDeep]]
   * writes - the reason as given, not the message, so `ValidationError`'s own `Invalid <field>: ` framing
   * is not relied on either.
   */
  object TooDeep {
    def unapply(e: LLMError): Boolean = e match {
      case v: ValidationError => v.field == Field && v.violations.exists(_.startsWith(TooDeepPrefix))
      case _                  => false
    }
  }

  /**
   * The error [[read]] returns for text the parser rejects. upickle reports a failure as a
   * `TraceException` whose message is the JSON path it stopped at (`$[3].name`) and whose cause says
   * why (`expected json value got x at index 12`); both are kept.
   */
  private def malformed(e: Throwable): ValidationError = {
    val path   = Option(e.getMessage).map(_.trim).filter(_.nonEmpty)
    val reason = Option(e.getCause).flatMap(c => Option(c.getMessage)).map(_.trim).filter(_.nonEmpty)
    val detail = (reason, path) match {
      case (Some(r), Some(p)) => s"$r (at $p)"
      case (Some(r), None)    => r
      case (None, Some(p))    => s"not valid JSON at $p"
      case (None, None)       => s"not valid JSON (${e.getClass.getSimpleName})"
    }
    ValidationError(Field, detail)
  }

  /**
   * The deepest nesting in `text`, stopping as soon as it passes `limit`, so a very long document
   * costs no more to refuse than its first `limit` opening brackets.
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
