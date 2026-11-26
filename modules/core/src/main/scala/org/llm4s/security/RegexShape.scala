package org.llm4s.security

import java.util.regex.Pattern
import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

/**
 * The structural check that makes the step budget of [[RegexSafetyManager]] a sound bound (#3051).
 *
 * The budget counts reads of the input, so it bounds a match only if the engine cannot do unbounded work between two
 * reads. It can when a loop's body can match the empty string: `(){2000000000}b` iterates two billion times without
 * reading a character. It can also at the end of the input, where every atom fails without a read: there
 * `(?:|)(?:|)...(?:|)b` tries 2^n empty paths on an empty line. This check refuses both, so every iteration of every
 * loop consumes, and so reads, at least one character, and every part of the pattern has at most one way to match
 * the empty string. The work between two reads is then bounded by the pattern's size.
 *
 * It refuses:
 *   - a quantifier (`?`, `*`, `+`, `{n,m}`) applied to something that can match the empty string;
 *   - an alternation with more than one alternative that can match the empty string;
 *   - a counted repetition above `maxRepetition`;
 *   - comments mode (`x`) and canonical equivalence (`c`, `CANON_EQ`), whose syntax this check does not follow.
 *
 * Something that can match the empty string is an empty group, an anchor or boundary (`^`, `$`, `\b`, `\A`, ...), a
 * lookaround, a quantified item whose minimum is 0, a backreference to a group that can, and anything made only of
 * those. The pattern is parsed after `Pattern.compile` accepted it, so its syntax is valid; anything this parser does
 * not follow is refused rather than guessed at.
 *
 * NOTE: kept in step with `org.llm4s.runner.RegexShape` in the workspace runner, which cannot depend on `core`.
 */
private[security] object RegexShape {

  /** `None` when `pattern`, compiled with `flags`, has a bounded shape; otherwise the reason it is refused. */
  def refusal(pattern: String, flags: Int, maxRepetition: Int): Option[String] =
    if ((flags & Pattern.LITERAL) != 0) None
    else if ((flags & Pattern.COMMENTS) != 0) Some(CommentsMode)
    else if ((flags & Pattern.CANON_EQ) != 0) Some(CanonEq)
    else
      boundary {
        new Parser(unquoted(pattern), maxRepetition).pattern()
        None
      }

  private val CommentsMode = "Comments mode (the `x` flag) is not supported in a pattern"
  private val CanonEq      = "Canonical equivalence (the `c` flag, CANON_EQ) is not supported in a pattern"

  /**
   * `pattern` with each `\Q...\E` quotation replaced by the escaped characters it stands for, as `Pattern` itself does
   * before parsing: `(a?)\Q\E*` is `(a?)*`.
   */
  private def unquoted(pattern: String): String =
    if (!pattern.contains("\\Q")) pattern
    else {
      val out = new StringBuilder
      var i   = 0
      while (i < pattern.length) {
        val c = pattern.charAt(i)
        if (c == '\\' && i + 1 < pattern.length && pattern.charAt(i + 1) == 'Q') {
          val end = pattern.indexOf("\\E", i + 2) match {
            case -1 => pattern.length
            case e  => e
          }
          pattern.substring(i + 2, end).foreach { q =>
            if (q < 0x80 && !Character.isLetterOrDigit(q)) out.append('\\')
            out.append(q)
          }
          i = math.min(pattern.length, end + 2)
        } else if (c == '\\' && i + 1 < pattern.length) {
          out.append(c).append(pattern.charAt(i + 1))
          i += 2
        } else {
          out.append(c)
          i += 1
        }
      }
      out.toString
    }

  /** A recursive-descent reading of `src` that answers, for each part, whether it can match the empty string. */
  final private class Parser(src: String, maxRepetition: Int)(using boundary.Label[Option[String]]) {
    private var pos          = 0
    private var groupsOpened = 0
    private val nullable     = mutable.Map.empty[Int, Boolean]
    private val named        = mutable.Map.empty[String, Int]

    private def refuse(reason: String): Nothing = break(Some(reason))

    private def peek: Int = if (pos < src.length) src.charAt(pos).toInt else -1

    private def next(): Char =
      if (pos < src.length) { pos += 1; src.charAt(pos - 1) }
      else refuse("The pattern ends inside a construct")

    private def expect(c: Char): Unit = if (next() != c) refuse(s"Expected '$c' at index ${pos - 1}")

    private def skip(n: Int): Unit = pos = math.min(src.length, pos + n)

    private def skipPast(c: Char): Unit = {
      while (peek != -1 && peek != c) pos += 1
      expect(c)
    }

    private def takeUntil(c: Char): String = {
      val start = pos
      while (peek != -1 && peek != c) pos += 1
      val taken = src.substring(start, pos)
      expect(c)
      taken
    }

    def pattern(): Unit = {
      alternation(): Unit
      if (pos < src.length) refuse(s"Unexpected '${src.charAt(pos)}' at index $pos")
    }

    /** Alternatives separated by `|`; at most one of them may match the empty string. */
    private def alternation(): Boolean = {
      var empty = if (sequence()) 1 else 0
      while (peek == '|') {
        pos += 1
        if (sequence()) empty += 1
      }
      if (empty > 1)
        refuse(
          "An alternation with more than one alternative that can match the empty string, such as `(a?|b?)` or " +
            "`(|x|)`, is not supported"
        )
      empty > 0
    }

    private def sequence(): Boolean = {
      var empty = true
      while (peek != -1 && peek != '|' && peek != ')')
        if (!quantified()) empty = false
      empty
    }

    private def quantified(): Boolean = {
      var empty = atom()
      while (peek == '?' || peek == '*' || peek == '+' || peek == '{') {
        if (empty)
          refuse(
            "A quantifier applied to something that can match the empty string, such as `(a?)*` or `(){1000}`, " +
              "is not supported"
          )
        empty = quantifier() == 0
        if (peek == '?' || peek == '+') pos += 1 // reluctant or possessive
      }
      empty
    }

    /** One quantifier; its minimum. */
    private def quantifier(): Long =
      next() match {
        case '?' | '*' => 0L
        case '+'       => 1L
        case _ =>
          val bounds = takeUntil('}').split(",", -1).toList
          val parsed = bounds.map(b => if (b.isEmpty) None else b.toLongOption.orElse(refuse(s"Bad repetition {$b}")))
          parsed.flatten.find(_ > maxRepetition).foreach { n =>
            refuse(s"A counted repetition above $maxRepetition, such as {$n}, is not supported")
          }
          parsed.headOption.flatten.getOrElse(refuse("A counted repetition needs a minimum"))
      }

    /** One atom; whether it can match the empty string. */
    private def atom(): Boolean =
      next() match {
        case '('       => group()
        case '['       => charClass(); false
        case '\\'      => escape()
        case '^' | '$' => true
        case c if c.isHighSurrogate && peek != -1 && peek.toChar.isLowSurrogate =>
          pos += 1; false
        case _ => false
      }

    private def group(): Boolean =
      if (peek != '?') capturing(None)
      else {
        pos += 1
        next() match {
          case ':' | '>' => closed(alternation())
          case '=' | '!' => closed(alternation()); true
          case '<' if peek == '=' || peek == '!' =>
            pos += 1; closed(alternation()); true
          case '<' => capturing(Some(takeUntil('>')))
          case _ =>
            pos -= 1
            inlineFlags()
        }
      }

    /** `(?flags)`, zero-width, or `(?flags:...)`, a group. */
    private def inlineFlags(): Boolean = {
      val flags = new StringBuilder
      while (peek != -1 && "idmsuxUc-".indexOf(peek) >= 0) flags.append(next())
      if (flags.indexOf("x") >= 0) refuse(CommentsMode)
      if (flags.indexOf("c") >= 0) refuse(CanonEq)
      next() match {
        case ')' => true
        case ':' => closed(alternation())
        case c   => refuse(s"Unsupported group construct '(?${flags}$c'")
      }
    }

    private def capturing(name: Option[String]): Boolean = {
      groupsOpened += 1
      val n = groupsOpened
      name.foreach(named(_) = n)
      val empty = closed(alternation())
      nullable(n) = empty
      empty
    }

    private def closed(empty: Boolean): Boolean = { expect(')'); empty }

    /** A backreference can match the empty string unless its group is closed and cannot. */
    private def backreference(group: Option[Int]): Boolean = group.flatMap(nullable.get).getOrElse(true)

    private def escape(): Boolean =
      next() match {
        case d if d >= '1' && d <= '9' =>
          // as `Pattern` reads it: further digits while the number still names a group opened so far
          var ref = d - '0'
          while (peek >= '0' && peek <= '9' && ref * 10 + (peek - '0') <= groupsOpened) {
            ref = ref * 10 + (peek - '0')
            pos += 1
          }
          backreference(Some(ref))
        case '0' =>
          var digits = 0
          while (digits < 3 && peek >= '0' && peek <= '7') { pos += 1; digits += 1 }
          false
        case 'k' =>
          expect('<')
          backreference(named.get(takeUntil('>')))
        case 'x' =>
          if (peek == '{') skipPast('}') else skip(2)
          false
        case 'u' => skip(4); false
        case 'c' => skip(1); false
        case 'N' => skipPast('}'); false
        case 'p' | 'P' =>
          if (peek == '{') skipPast('}') else skip(1)
          false
        case 'b' =>
          if (peek == '{') skipPast('}')
          true
        case 'B' | 'A' | 'G' | 'Z' | 'z' => true
        case _                           => false // a class such as \d or \R, or an escaped character
      }

    /** A character class, after its `[`: a `]` first in it (after any `^`) is a literal, as `Pattern` reads it. */
    private def charClass(): Unit = {
      if (peek == '^') pos += 1
      if (peek == ']') pos += 1
      while (peek != ']')
        next() match {
          case '[' => charClass()
          case '\\' =>
            next() match {
              case 'p' | 'P' | 'x' | 'N' if peek == '{' => skipPast('}')
              case 'c'                                  => skip(1)
              case _                                    => ()
            }
          case _ => ()
        }
      pos += 1
    }
  }
}
