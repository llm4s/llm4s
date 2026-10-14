package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental

import java.util.regex.Pattern
import scala.annotation.tailrec

/**
 * A path pattern, matched against a whole target string: a tool name, a `/`-separated path, or a URL.
 *
 *  - `*` matches any run of characters other than `/`, so it stays within one segment;
 *  - `?` matches one character other than `/`;
 *  - `**` matches any run of characters, `/` included; as a whole segment it also matches zero
 *    segments, so `src/&#42;&#42;/a.scala` matches `src/a.scala`, and `src/&#42;&#42;` matches `src`.
 *
 * Every other character is literal: there are no escapes, character classes or braces, so every
 * string is a valid pattern. Two globs are equal when their patterns are.
 */
@Experimental
final class Glob private (val pattern: String):
  private val regex: Pattern = Glob.compile(pattern)

  /** Whether `target` matches this pattern as a whole. */
  def matches(target: String): Boolean = regex.matcher(target).matches()

  override def equals(other: Any): Boolean = other match
    case g: Glob => g.pattern == pattern
    case _       => false
  override def hashCode: Int    = pattern.hashCode
  override def toString: String = s"Glob($pattern)"

object Glob:
  def apply(pattern: String): Glob = new Glob(pattern)

  private def compile(pattern: String): Pattern =
    val n                    = pattern.length
    def segmentStart(i: Int) = i == 0 || pattern.charAt(i - 1) == '/'
    @tailrec def loop(i: Int, out: StringBuilder): String =
      if i >= n then out.toString
      // a trailing "/**": the segment before it, or anything beneath it
      else if pattern.startsWith("/**", i) && i + 3 == n then out.append("(?:/.*)?").toString
      // a whole "**" segment followed by more: zero or more segments
      else if pattern.startsWith("**/", i) && segmentStart(i) then loop(i + 3, out.append("(?:.*/)?"))
      else if pattern.startsWith("**", i) then loop(i + 2, out.append(".*"))
      else
        val token = pattern.charAt(i) match
          case '*' => "[^/]*"
          case '?' => "[^/]"
          case c   => Pattern.quote(c.toString)
        loop(i + 1, out.append(token))
    Pattern.compile(loop(0, new StringBuilder), Pattern.DOTALL)
