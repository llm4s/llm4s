package org.llm4s.runner

import java.util.regex.Pattern
import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

/**
 * The structural check that makes the step budget of [[WorkspaceRegexSafetyManager]] a sound bound (#3051).
 *
 * The budget counts reads of the input, so it bounds a match only if the engine cannot do much work between two
 * reads. Two kinds of pattern can:
 *   - a repetition whose body can match the empty string and whose count the engine must reach:
 *     `(){2000000000}b` iterates two billion times without reading a character;
 *   - many ways to match the empty string, tried one after another where every atom fails without a read (at the end
 *     of the input, or before a `$`): `(?:|)` repeated 40 times then `b` tries 2^40 paths on an empty line, and
 *     `(?:a?)*` repeated so does too.
 *
 * So the check estimates, for each position, how many steps the engine can take there without reading, and refuses a
 * pattern whose estimate exceeds [[MaxSilentSteps]]. Each part has two numbers: `e`, the ways it can match the empty
 * string, and `f`, the steps it can take at one position without reading (each atom, assertion or alternative tried
 * counts one). A sequence multiplies: what follows a part is tried once for each way that part matched empty. An
 * alternation adds. A repetition over a body that cannot match empty adds at most one way (skipping it), since every
 * iteration reads. Over a body that can, the required iterations are counted one by one, and each optional iteration
 * (a `?`, the rest of a bounded `{n,m}`, and an unbounded `*`, `+`, `{n,}`, which the JDK ends on an iteration that
 * matched empty, so it counts once) adds a way: the body's empty match and the skip are both tried when what follows
 * fails. Measured on JDK 21 with a failing `(?!)` after the chain and an empty input: `(?:a?)?`, `(\s*)?`, `(a|)?`,
 * `(?:a?)*` and `(?:a?){0,2}` each double the time per copy, while `(?:a|)`, `(?:a?)` and `(?:a?){3}` stay flat.
 * The estimate is checked at every point a match can resume after a read: each suffix of every sequence.
 *
 * The threshold is 1000 steps, the size of the longest pattern accepted: so between two counted reads the engine does
 * at most about one more walk over a pattern of the longest size, and the step budget stays a bound on the time up to
 * that factor. An ordinary pattern, even one with `(\s*)?` or `(a|)?` in it, estimates a few steps; what is refused
 * grows exponentially or with a large required count.
 *
 * It also refuses:
 *   - a lookbehind whose body can be longer than [[MaxLookbehindLength]] characters, has no maximum length, or holds
 *     another lookaround: the JDK tries a lookbehind's body from each earlier position its length allows, moving back
 *     without reading, and nested lookarounds multiply those scans (Codex on #3135: four nested
 *     `(?<!...(?!)a{0,1000})` ran past 12 s on a 1000-character line with almost no reads). An accepted lookbehind
 *     counts its body's steps once per position it is tried from;
 *   - a counted repetition above `maxRepetition` (the workspace runner's cap; core passes `Int.MaxValue`);
 *   - nesting deeper than [[MaxNesting]], so the parser's own stack stays small.
 *
 * The check's own work is bounded by the pattern's length, never by a number written in it: a repetition's cost is
 * built a step at a time only until it stops changing or passes the limit, which takes a few thousand steps at most
 * (`(?i){2147483647}` is a fixed point at once).
 *   - comments mode (`x`) and canonical equivalence (`c`, `CANON_EQ`), whose syntax this check does not follow.
 *
 * The pattern is parsed after `Pattern.compile` accepted it, so its syntax is valid; anything this parser does not
 * follow is refused rather than guessed at.
 *
 * NOTE: kept in step with `org.llm4s.security.RegexShape` in `core`, which this module cannot depend on.
 */
private[runner] object RegexShape {

  /** The most steps a pattern may take at one position without reading the input. */
  val MaxSilentSteps = 1000L

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

  private val TooManySilentSteps =
    s"The pattern can take more than $MaxSilentSteps steps at one position without reading the input, which the " +
      "step budget cannot bound: a repetition of something that can match the empty string, such as `(){5000}`, or " +
      "many ways to match it in a row, such as `(?:|)` or `(?:a?)*` repeated"

  /** The longest body a lookbehind may have, in characters: the JDK tries it from each of that many earlier positions. */
  val MaxLookbehindLength = 64L

  /**
   * The deepest nesting of groups and classes accepted. The parser recurses once per level, so this bounds its stack;
   * ordinary patterns nest a few levels.
   */
  val MaxNesting = 64

  private val TooDeep = s"A pattern nested more than $MaxNesting groups or classes deep is not supported"

  private val LookbehindRefused =
    s"A lookbehind whose body can be longer than $MaxLookbehindLength characters, or has no maximum length, or " +
      "holds another lookaround, is not supported: the engine tries it from each earlier position without reading"

  /**
   * Ways to match the empty string (`e`) and steps without a read (`f`), both saturating just above the limit; the
   * most characters it can consume (`len`, [[Unbounded]] when it has no maximum); and whether it holds a lookaround.
   */
  final private case class Cost(e: Long, f: Long, len: Long = 0, look: Boolean = false) {
    def nullable: Boolean = e > 0
  }

  private val Unbounded = Long.MaxValue / 4

  private def addLen(a: Long, b: Long): Long = math.min(Unbounded, a + b)
  private def mulLen(a: Long, b: Long): Long = if (a == 0 || b == 0) 0 else if (a > Unbounded / b) Unbounded else a * b

  private val Cap = MaxSilentSteps + 1

  private def add(a: Long, b: Long): Long = math.min(Cap, a + b)
  private def mul(a: Long, b: Long): Long = if (a == 0 || b == 0) 0 else math.min(Cap, a * b)

  /** `first` then `rest`: what follows is tried once for each way `first` matched empty. */
  private def andThen(first: Cost, rest: Cost): Cost =
    Cost(mul(first.e, rest.e), add(first.f, mul(first.e, rest.f)), addLen(first.len, rest.len), first.look || rest.look)

  /** One character of the pattern's own, which matches one `char`. */
  private val Literal = Cost(0, 1, 1)

  /** A class, `.` or escape, which may match a supplementary code point: two `char`s. */
  private val Consuming = Cost(0, 1, 2)
  private val ZeroWidth = Cost(1, 1)

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

  /** A recursive-descent reading of `src` that gives each part its [[Cost]]. */
  final private class Parser(src: String, maxRepetition: Int)(using boundary.Label[Option[String]]) {
    private var pos          = 0
    private var groupsOpened = 0
    private var depth        = 0
    private val nullable     = mutable.Map.empty[Int, Boolean]
    private val named        = mutable.Map.empty[String, Int]

    private def refuse(reason: String): Nothing = break(Some(reason))

    private def checked(c: Cost): Cost = if (c.f > MaxSilentSteps) refuse(TooManySilentSteps) else c

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

    /** Alternatives separated by `|`: each is tried, a step even when empty, so their ways and steps add. */
    private def alternation(): Cost = {
      def tried(c: Cost) = c.copy(f = add(c.f, 1))
      var cost           = tried(sequence())
      while (peek == '|') {
        pos += 1
        val alternative = tried(sequence())
        cost = Cost(
          add(cost.e, alternative.e),
          add(cost.f, alternative.f),
          math.max(cost.len, alternative.len),
          cost.look || alternative.look
        )
      }
      checked(cost)
    }

    /** Parts in a row; every suffix is checked, since a match can resume at any part after a read. */
    private def sequence(): Cost = {
      val parts = mutable.ArrayBuffer.empty[Cost]
      while (peek != -1 && peek != '|' && peek != ')') parts += quantified()
      parts.foldRight(Cost(1, 0))((part, rest) => checked(andThen(part, rest)))
    }

    private def quantified(): Cost = {
      var cost = atom()
      while (peek == '?' || peek == '*' || peek == '+' || peek == '{') {
        val (min, max) = quantifier()
        cost = checked(repeated(cost, min, max))
        if (peek == '?' || peek == '+') pos += 1 // reluctant or possessive
      }
      cost
    }

    /** `body{min,max}` (`max` None when unbounded). */
    private def repeated(body: Cost, min: Long, max: Option[Long]): Cost = {
      val len = max.fold(if (body.len == 0) 0L else Unbounded)(m => mulLen(body.len, m))
      silent(body, min, max).copy(len = len, look = body.look)
    }

    private def silent(body: Cost, min: Long, max: Option[Long]): Cost =
      if (!body.nullable)
        // every iteration reads; at one position only the first is tried, and the loop can be skipped when min is 0
        Cost(if (min == 0) 1 else 0, add(body.f, 1))
      else {
        // each required iteration in turn, then each optional one (a `?` is one), which may be taken or skipped: two
        // more ways, the body's empty match and the skip, both tried by the JDK when what follows fails
        val optional = Cost(add(body.e, 1), add(body.f, 1))
        val extra    = max.fold(1L)(m => m - min) // an unbounded loop ends on an iteration that matched empty
        times(body, min, times(optional, extra, Cost(1, 0)))
      }

    /**
     * `part` repeated `n` times before `rest`, without taking `n` steps: the costs saturate at the limit, so the result
     * stops changing (a fixed point) or passes the limit within a few thousand repetitions whatever `n` the pattern
     * writes. `(?i){2147483647}` is a fixed point at once (#3135).
     */
    private def times(part: Cost, n: Long, rest: Cost): Cost = {
      var cost = rest
      var i    = 0L
      var done = false
      while (!done && i < n) {
        val next = andThen(part, cost)
        done = (next.e == cost.e && next.f == cost.f) || next.f > MaxSilentSteps // len is set by the caller
        cost = next
        i += 1
      }
      cost
    }

    /** One quantifier: its minimum and its maximum, None when unbounded. */
    private def quantifier(): (Long, Option[Long]) =
      next() match {
        case '?' => (0L, Some(1L))
        case '*' => (0L, None)
        case '+' => (1L, None)
        case _ =>
          val bounds = takeUntil('}').split(",", -1).toList
          val parsed = bounds.map(b => if (b.isEmpty) None else b.toLongOption.orElse(refuse(s"Bad repetition {$b}")))
          parsed.flatten.find(_ > maxRepetition).foreach { n =>
            refuse(s"A counted repetition above $maxRepetition, such as {$n}, is not supported")
          }
          val min = parsed.headOption.flatten.getOrElse(refuse("A counted repetition needs a minimum"))
          (min, if (parsed.size == 1) Some(min) else parsed(1))
      }

    /** One atom and its cost. */
    private def atom(): Cost =
      next() match {
        case '('       => group()
        case '['       => charClass(); Consuming
        case '.'       => Consuming
        case '\\'      => escape()
        case '^' | '$' => ZeroWidth
        case c if c.isHighSurrogate && peek != -1 && peek.toChar.isLowSurrogate =>
          pos += 1; Consuming
        case _ => Literal
      }

    /** One level deeper, within [[MaxNesting]]: this parser recurses once per level, so its stack stays small. */
    private def nested[A](body: => A): A = {
      depth += 1
      if (depth > MaxNesting) refuse(TooDeep)
      val result = body
      depth -= 1
      result
    }

    private def group(): Cost = nested(groupBody())

    private def groupBody(): Cost =
      if (peek != '?') capturing(None)
      else {
        pos += 1
        next() match {
          case ':' | '>' => closed(alternation())
          case '=' | '!' => lookahead()
          case '<' if peek == '=' || peek == '!' =>
            pos += 1; lookbehind()
          case '<' => capturing(Some(takeUntil('>')))
          case _ =>
            pos -= 1
            inlineFlags()
        }
      }

    /** Zero-width; its body is tried each time it is reached. */
    private def lookahead(): Cost = {
      val body = closed(alternation())
      checked(Cost(1, add(body.f, 1), 0, look = true))
    }

    /** Zero-width; its body is tried from each earlier position within its maximum length. */
    private def lookbehind(): Cost = {
      val body = closed(alternation())
      if (body.look || body.len > MaxLookbehindLength) refuse(LookbehindRefused)
      checked(Cost(1, add(1, mul(body.len + 1, body.f)), 0, look = true))
    }

    /** `(?flags)`, zero-width, or `(?flags:...)`, a group. */
    private def inlineFlags(): Cost = {
      val flags = new StringBuilder
      while (peek != -1 && "idmsuxUc-".indexOf(peek) >= 0) flags.append(next())
      if (flags.indexOf("x") >= 0) refuse(CommentsMode)
      if (flags.indexOf("c") >= 0) refuse(CanonEq)
      next() match {
        case ')' => Cost(1, 0)
        case ':' => closed(alternation())
        case c   => refuse(s"Unsupported group construct '(?${flags}$c'")
      }
    }

    private def capturing(name: Option[String]): Cost = {
      groupsOpened += 1
      val n = groupsOpened
      name.foreach(named(_) = n)
      val cost = closed(alternation())
      nullable(n) = cost.nullable
      cost
    }

    private def closed(cost: Cost): Cost = { expect(')'); cost }

    /** A backreference can match the empty string unless its group is closed and cannot; its length is the group's. */
    private def backreference(group: Option[Int]): Cost =
      Cost(if (group.flatMap(nullable.get).getOrElse(true)) 1 else 0, 1, Unbounded)

    private def escape(): Cost =
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
          Consuming
        case 'k' =>
          expect('<')
          backreference(named.get(takeUntil('>')))
        case 'x' =>
          if (peek == '{') skipPast('}') else skip(2)
          Consuming
        case 'u' => skip(4); Consuming
        case 'c' => skip(1); Consuming
        case 'N' => skipPast('}'); Consuming
        case 'p' | 'P' =>
          if (peek == '{') skipPast('}') else skip(1)
          Consuming
        case 'b' =>
          if (peek == '{') skipPast('}')
          ZeroWidth
        case 'B' | 'A' | 'G' | 'Z' | 'z' => ZeroWidth
        case 'X'                         => Consuming.copy(len = Unbounded) // a grapheme cluster has no maximum length
        case _ => Consuming // a class such as \d or \R, or an escaped character
      }

    /** A character class, after its `[`: a `]` first in it (after any `^`) is a literal, as `Pattern` reads it. */
    private def charClass(): Unit = nested(classBody())

    private def classBody(): Unit = {
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
