package org.llm4s.agent.graph.tool

import java.nio.charset.StandardCharsets

/**
 * A tool whose messages the loop bounds: every error the loop records for one of its calls - its own, a wrapper's, an
 * admission refusal (invalid arguments, a lock change), a reviewer's rejection - is cut so that the stored tool message,
 * `{"error": ...}`, is at most `maxErrorBytes` in UTF-8, and a successful result a wrapper left larger than that is
 * recorded as an error instead. The `task` tools are (design 4.44): rule 12 bounds
 * what a call puts in the parent's checkpoint without relying on the model's arguments.
 */
private[agent] trait BoundedErrors:
  def maxErrorBytes: Int

/** Cutting a tool error to fit its stored message. */
private[agent] object ToolErrors:

  private val Cut = " ... (truncated)"

  private def utf8(text: String): Int = text.getBytes(StandardCharsets.UTF_8).length

  /** The bytes of `text` as the tool message the loop stores for an error: `{"error": text}`. */
  def wrapped(text: String): Int = utf8(ujson.Obj("error" -> ujson.Str(text)).render())

  /** `text`, cut on a code point and marked, so its stored error message is at most `max` bytes; empty if no cut fits. */
  def bounded(text: String, max: Int): String =
    if wrapped(text) <= max then text
    else
      val points            = text.codePoints().toArray
      def candidate(n: Int) = new String(points, 0, n) + Cut
      def fits(n: Int)      = wrapped(candidate(n)) <= max
      @scala.annotation.tailrec
      def largest(lo: Int, hi: Int): Int =
        if lo >= hi then lo
        else
          val mid = (lo + hi + 1) / 2
          if fits(mid) then largest(mid, hi) else largest(lo, mid - 1)
      val n = largest(0, points.length)
      if fits(n) then candidate(n) else ""

  /** Why `tool`'s successful result `text` is not stored: a [[BoundedErrors]] tool's result over its bound. */
  def overBound(tool: AgentTool[?], text: String): Option[String] =
    tool match
      case b: BoundedErrors if utf8(text) > b.maxErrorBytes =>
        Some(s"Tool '${tool.spec.name}' result over ${b.maxErrorBytes} bytes (${utf8(text)})")
      case _ => None

  /** `content` as the loop records it for a call of `tool`: an error of a [[BoundedErrors]] tool cut to its bound. */
  def recorded(tool: Option[AgentTool[?]], content: String, isError: Boolean): String =
    tool match
      case Some(b: BoundedErrors) if isError => bounded(content, b.maxErrorBytes)
      case _                                 => content
