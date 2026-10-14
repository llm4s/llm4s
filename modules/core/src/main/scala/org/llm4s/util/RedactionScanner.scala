package org.llm4s.util

import java.util.Locale

/**
 * The engine of [[Redaction.redact]]: one forward pass over a small tokenizer, linear in the input by construction
 * (#1932).
 *
 * '''Token model.''' The input is read a ''level'' at a time ([[RedactionScanner.Text]]): a sequence of characters,
 * each mapped to the span of the raw input it was decoded from. The lexer finds the level's string literals in one
 * forward scan - a `"` or `'` opens one unless a letter, a digit or a backslash comes right before it (bar a one- or
 * two-letter Python prefix such as `b'`); a backslash escapes the character after it; `"` closes at the next
 * unescaped `"`, `'` at the next unescaped `'` that is not between two letters or digits; no string runs over a line
 * break. Everything else is plain text, which the policy scanner ([[RedactionScanner.Scan]]) walks a character at a
 * time, taking each string as one token.
 *
 * '''Escape depth.''' A string's content is a level of its own, decoded once (`\"`, `\'`, `\\`, `\/`, `\b`, `\f`,
 * `\n`, `\r`, `\t`, `\uXXXX`; an invalid escape is kept as written), so JSON inside a string - and JSON inside that -
 * is lexed with bare quotes at its own level. An escape in plain text is a fragment of escaped text whose outer quotes
 * are not there (a log written into a JSON string, Gson's `&`): it is decoded in place and the level lexed again,
 * until no escape is left in plain text. A string's content is a level deeper than the level that holds it, and a
 * decode one more, so a document JSON-string-encoded `k` times is `2k` levels down. At [[RedactionScanner.MaxDepth]]
 * nothing is decoded any more, and a level there that still holds an escape is ''unresolved'': its structure - a key
 * whose quotes are still escaped (`\"password\":\"x\"`), a `=` still written `\u003d` - cannot be read, so the whole
 * level is replaced, which is the rest of the string literal it is the content of (or the whole input, at the top).
 * It fails closed: nothing past the limit is shown undecoded.
 *
 * '''Work bound.''' Each level is built and scanned once; the contents of the strings at one depth are disjoint and
 * no longer than the level that holds them, so the work is at most the input's length times
 * [[RedactionScanner.MaxDepth]]. The depth is capped because decodes need not shrink the text much: each round of a
 * chain such as `\u005cu005cu0022` uncovers one more escape for five fewer characters. Every rule
 * reads forward from the scanner's position, with a lookahead of bounded length - a key, a separator, a container's
 * first element, a header name - or consumes what it reads. No regular expression is used.
 *
 * '''Policy.''' See [[RedactionScanner.Scan]].
 */
private[util] object RedactionScanner {

  /**
   * The most levels - a string's content, a decode of a string's escapes, or a round of escapes in plain text - between
   * a level and the input: 16 JSON string encodings, each a content and a decode. A level at the limit that still holds
   * an escape is replaced whole (`Text.unresolved`).
   */
  val MaxDepth: Int = 32

  /**
   * `input` with every credential the policy finds replaced by `placeholder`. With `truncated`, the input is a prefix
   * of a longer text, and nothing still open at its end is decided in a value's favour (see [[Redaction.safeBody]]).
   */
  def redact(input: String, placeholder: String, truncated: Boolean): String = {
    val edits = new Edits(placeholder)
    new Scan(Text.top(input), input, placeholder, edits, truncated, atInputEnd = true, carried = null).run()
    edits.applyTo(input)
  }

  // ============================================================
  // Levels
  // ============================================================

  /**
   * One level of the input: the characters `chars(off until off + n)`, each the decoding of the raw span
   * `rawStart(i) until rawEnd(i)` of the input. `strEnd(i)` is, where a string opens at `i`, the index after it -
   * after its closing quote, or where it stopped unclosed - and -1 elsewhere; `strClosed` marks the strings that
   * closed; `plainEscapes`, whether an escape is left in its plain text.
   */
  final class Text(
    val chars: Array[Char],
    val off: Int,
    val n: Int,
    starts: Array[Int],
    ends: Array[Int],
    mapOff: Int,
    base: Int,
    val depth: Int
  ) {
    private[RedactionScanner] var strEnd: Array[Int]          = null
    private[RedactionScanner] var strClosed: java.util.BitSet = null
    private[RedactionScanner] var plainEscapes: Boolean       = false

    /** Whether this level is at [[MaxDepth]] and still holds an escape, so that it cannot be read: it is replaced whole. */
    private[RedactionScanner] var unresolved: Boolean = false
    def ch(i: Int): Char                              = chars(off + i)
    def rawStart(i: Int): Int                         = if (starts == null) base + i else starts(mapOff + i)
    def rawEnd(i: Int): Int                           = if (ends == null) base + i + 1 else ends(mapOff + i)
    def stringEnd(i: Int): Int                        = if (strEnd == null) -1 else strEnd(i)
    def closed(i: Int): Boolean                       = strClosed.get(i)
    private[RedactionScanner] def view(from: Int, to: Int): Text =
      new Text(chars, off + from, to - from, starts, ends, mapOff + from, base + from, depth + 1)

    /** Whether the character at `i` stands for an escape: its raw span is longer than one character. */
    def escaped(i: Int): Boolean = rawEnd(i) - rawStart(i) > 1

    /** The raw index where the character at `i` starts, or where this level ends for `i == n`. */
    def rawAt(i: Int): Int = if (i < n) rawStart(i) else if (n > 0) rawEnd(n - 1) else base
  }

  object Text {

    def top(input: String): Text = settle(new Text(input.toCharArray, 0, input.length, null, null, 0, 0, 0))

    /** The content of the string that opens at `s`, as a level: decoded once, then settled. */
    def content(t: Text, s: Int): Text = {
      val end       = t.stringEnd(s)
      val ce        = if (t.closed(s)) end - 1 else end
      val v         = t.view(s + 1, ce)
      var backslash = false
      var i         = 0
      while (!backslash && i < v.n) { backslash = v.ch(i) == '\\'; i += 1 }
      if (!backslash) settle(v)
      else if (v.depth < MaxDepth) settle(decode(v, plain = false))
      else {
        // too deep to decode: unresolved where an escape is left in it, inside a string of its own or not
        var k = 0
        while (!v.unresolved && k < v.n) { if (v.ch(k) == '\\' && escapeLength(v, k) > 0) v.unresolved = true; k += 1 }
        if (v.unresolved) v else settle(v)
      }
    }

    /** `t` lexed, and, while it holds an escape in plain text, decoded there and lexed again. */
    private def settle(start: Text): Text = {
      var t = start
      lex(t)
      while (t.plainEscapes && t.depth < MaxDepth) {
        t = decode(t, plain = true)
        lex(t)
      }
      // at the limit with an escape left in plain text: what it stands for cannot be read
      if (t.plainEscapes) t.unresolved = true
      t
    }

    /**
     * `t` with its escapes decoded: all of them, or, with `plain`, those outside its strings. Each decoded character
     * keeps the raw span of its escape (`Text.escaped`).
     */
    private def decode(t: Text, plain: Boolean): Text = {
      val n      = t.n
      val chars  = new Array[Char](n)
      val starts = new Array[Int](n)
      val ends   = new Array[Int](n)
      var i      = 0
      var o      = 0
      def copy(k: Int): Unit = {
        chars(o) = t.ch(k); starts(o) = t.rawStart(k); ends(o) = t.rawEnd(k)
        o += 1
      }
      while (i < n)
        if (plain && t.stringEnd(i) >= 0) {
          val e = t.stringEnd(i)
          while (i < e) { copy(i); i += 1 }
        } else if (t.ch(i) == '\\') {
          val len = escapeLength(t, i)
          if (len == 0) { copy(i); i += 1 }
          else {
            chars(o) = escapedChar(t, i, len); starts(o) = t.rawStart(i); ends(o) = t.rawEnd(i + len - 1)
            o += 1
            i += len
          }
        } else { copy(i); i += 1 }
      new Text(chars, 0, o, starts, ends, 0, 0, t.depth + 1)
    }

    /** The length of the escape the backslash at `i` starts - 2, or 6 for `\uXXXX` - or 0 where it starts none. */
    private[RedactionScanner] def escapeLength(t: Text, i: Int): Int =
      if (i + 1 >= t.n) 0
      else
        t.ch(i + 1) match {
          case '"' | '\'' | '\\' | '/' | 'b' | 'f' | 'n' | 'r' | 't'                             => 2
          case 'u' if i + 5 < t.n && (2 to 5).forall(k => Character.digit(t.ch(i + k), 16) >= 0) => 6
          case _                                                                                 => 0
        }

    private def escapedChar(t: Text, i: Int, len: Int): Char =
      if (len == 6) Integer.parseInt(new String(t.chars, t.off + i + 2, 4), 16).toChar
      else
        t.ch(i + 1) match {
          case 'b' => '\b'
          case 'f' => '\f'
          case 'n' => '\n'
          case 'r' => '\r'
          case 't' => '\t'
          case c   => c
        }

    /**
     * Finds the strings of `t` in one forward scan, and whether any escape is left in its plain text. A string opens
     * at a `"` or `'` with no letter, digit or backslash right before it - or a Python prefix of one or two of
     * `bBrRuUfF` that none comes before - and runs to its closing quote: the next unescaped `"`, or the next unescaped
     * `'` that is not between two letters or digits. A line break, or the end of the level, stops a string unclosed.
     */
    private def lex(t: Text): Unit = {
      val n      = t.n
      var ends   = null.asInstanceOf[Array[Int]]
      val closed = new java.util.BitSet()
      var plainE = false
      var i      = 0
      while (i < n) {
        val c = t.ch(i)
        if (c == '\\') {
          if (!plainE && escapeLength(t, i) > 0) plainE = true
          i += 2
        } else if ((c == '"' || c == '\'') && opens(t, i)) {
          var j     = i + 1
          var close = -1
          while (close < 0 && j < n && t.ch(j) != '\n' && t.ch(j) != '\r') {
            val d = t.ch(j)
            if (d == '\\') j += 2
            else if (d == c && (c == '"' || !between(t, j))) close = j
            else j += 1
          }
          if (ends == null) { ends = new Array[Int](n); java.util.Arrays.fill(ends, -1) }
          if (close >= 0) { ends(i) = close + 1; closed.set(i); i = close + 1 }
          else { ends(i) = math.min(j, n); i = math.min(j, n) }
        } else i += 1
      }
      t.strEnd = ends
      t.strClosed = closed
      t.plainEscapes = plainE
    }

    private def isAlnum(c: Char): Boolean = Character.isLetterOrDigit(c)

    /** Whether the `'` at `j` sits between two letters or digits, as the apostrophe of `it's` does. */
    private[RedactionScanner] def between(t: Text, j: Int): Boolean =
      j > 0 && j + 1 < t.n && isAlnum(t.ch(j - 1)) && isAlnum(t.ch(j + 1))

    /** Whether the quote at `i` opens a string (see [[lex]]). */
    private[RedactionScanner] def opens(t: Text, i: Int): Boolean =
      i == 0 || {
        val p = t.ch(i - 1)
        if (p == '\\') false
        else if (!isAlnum(p)) true
        else {
          var k = i - 1
          while (k >= 0 && i - k <= 2 && "bBrRuUfF".indexOf(t.ch(k).toInt) >= 0) k -= 1
          k < i - 1 && i - k <= 3 && (k < 0 || !(isAlnum(t.ch(k)) || t.ch(k) == '\\'))
        }
      }
  }

  // ============================================================
  // Edits
  // ============================================================

  /**
   * The replacements made, in raw coordinates, kept sorted and free of overlaps as they are added: the scan adds
   * them in increasing order of their start, and one that overlaps the last is joined with it into one placeholder.
   */
  final class Edits(placeholder: String) {
    private var starts = new Array[Int](16)
    private var ends   = new Array[Int](16)
    private var reps   = new Array[String](16)
    private var count  = 0
    private var sorted = true

    def add(start: Int, end: Int, rep: String): Unit =
      if (end > start) {
        if (count > 0 && start < starts(count - 1)) sorted = false
        if (count > 0 && sorted && start < ends(count - 1)) {
          if (end > ends(count - 1)) { ends(count - 1) = end; reps(count - 1) = placeholder }
        } else {
          if (count == starts.length) {
            starts = java.util.Arrays.copyOf(starts, count * 2)
            ends = java.util.Arrays.copyOf(ends, count * 2)
            reps = java.util.Arrays.copyOf(reps, count * 2)
          }
          starts(count) = start; ends(count) = end; reps(count) = rep
          count += 1
        }
      }

    /**
     * `input` with the edits made. Where one was added out of order, they are sorted first; edits that overlap are
     * written as one placeholder, the first one's where one covers the others.
     */
    def applyTo(input: String): String =
      if (count == 0) input
      else {
        val order: Seq[Int] =
          if (sorted) 0 until count else (0 until count).sortBy(k => (starts(k), -ends(k)))
        val out = new java.lang.StringBuilder(input.length)
        var at  = 0
        order.foreach { k =>
          if (ends(k) > at) {
            val s = math.max(starts(k), at)
            out.append(input, at, s)
            out.append(if (s > starts(k)) "" else reps(k))
            at = ends(k)
          }
        }
        out.append(input, at, input.length).toString
      }
  }

  // ============================================================
  // The policy
  // ============================================================

  /** An array, object, tuple or call whose leaves are being replaced, and the quote a bare leaf is written in. */
  final class Walk(val stack: java.lang.StringBuilder, val quote: String)

  /** What a value left open at the end of a level was: a header's, a quoted one's, or a pair's unquoted one. */
  private val OpenHeader = 0
  private val OpenQuoted = 1
  private val OpenPair   = 2

  /** The bare words a container's leaves and a field's value keep: JSON's literals, and Python's. */
  private val KeptLiterals: Set[String] = Set("true", "false", "null", "True", "False", "None")

  private def isKeyChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'

  private def isWordStart(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'

  /** The characters `\s` matches. */
  private def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'

  private def isAlnum(c: Char): Boolean = Character.isLetterOrDigit(c)

  /** The escape letters whose characters may stand right before a key: `\b`, `\f`, `\n`, `\r`, `\t`. */
  private val EscapedControls: String = "\b\f\n\r\t"

  /** The HTML escapes of `&` and `?` that may come before a query parameter's key, in lower case. */
  private val HtmlQuerySeparators: Seq[String] = Seq("&amp;", "&#38;", "&#x26;", "&#63;", "&#x3f;")

  /**
   * The policy over one level (`t`): the scanner that walks its plain text and takes each string as one token. In
   * order of precedence at each position:
   *
   *  1. A string followed by `:` is a key, and one followed by `=` a pair's (`"password" = "x"`). A sensitive one has
   *     its value replaced (`readValue`): a string's content,
   *     a container's or a call's leaves (`walk`), or a number or bare word, which is written as the placeholder in
   *     the key's own quote. Any other string is a level of its own, scanned in turn (`scanString`). A key whose
   *     quotes a stray quote paired otherwise (`""apikey":"x"`) is read as a key all the same (`misalignedField`).
   *  1. `Bearer` or `Basic` and its token, both replaced, unless the token is the key of a sensitive pair.
   *  1. A word followed by `=` is the key of a pair (`key=value`, `key = value`): a sensitive one has its value
   *     replaced - a string's content, a container's or call's leaves, or the text up to whitespace or one of `,;<>`,
   *     or an `&` that another pair follows.
   *  1. A word followed by `:` is a header: `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie`
   *     anywhere in a line, and any other sensitive name at the start of a line (after indentation and a YAML `- `).
   *     Its value runs to the end of the line, or over the indented lines after it where it is empty on its own;
   *     inside a bracket, also to a closing bracket it did not open, or a `,` before another key. A sensitive name
   *     inside a line is a field where a separator or a bracket comes before it, or where its value looks like a
   *     credential (`credentialLike`), up to whitespace, `,` or `;`; so is one inside a bracket whose `:` or value is
   *     on another line.
   *  1. After `?`, `&`, a URL's `#`, `;jsessionid` or an HTML escape of `&` or `?`, a query parameter: its key runs
   *     to `=`, and a sensitive one (by substring, as a credential name, or a short-lived credential's name) has its
   *     value replaced, up to `&` that another parameter follows, whitespace, `#` or a `,` before JSON.
   *  1. `scheme://` and its authority: the password of its userinfo is replaced.
   *  1. The vendor key shapes of [[SecretPatterns.SecretType.default]], matched without a regex.
   *
   * A value still open where this level ends is replaced to that end - at the top level, the end of the input - and
   * reported (`openFrom`, `openKind`) to the level around, which carries it on past a string that closed where it
   * cannot have (`extendOpenValue`): a quote glued to the next word, or to closing brackets and a word, is a quote of
   * the value, not the string's end. A string value whose own close is glued so (`" here | token="x"`) runs on the
   * same way (`stringValue`).
   *
   * @param atInputEnd whether this level runs to the end of the input unclosed
   * @param carried    a container left open by the string before, which this level's text goes on (a notebook's
   *                   `text/plain` line array), or null
   */
  final class Scan(
    t: Text,
    input: String,
    ph: String,
    edits: Edits,
    truncated: Boolean,
    atInputEnd: Boolean,
    carried: Walk
  ) {
    private val n = t.n

    /** The raw start of a value still open where this level ends, or -1; and the quote it opened with, or 0. */
    var openFrom: Int         = -1
    var openQuote: Char       = 0
    var openKind: Int         = OpenHeader
    var openNested            = false
    private var depth         = 0 // brackets open in this level's plain text
    var openWalk: Walk        = null
    private var pending: Walk = null

    def run(): Unit =
      if (t.unresolved) mask(0, n) // fails closed: see `Text.unresolved`
      else scan()

    private def scan(): Unit = {
      var i         = if (carried != null) walk(carried, 0) else 0
      var lineStart = carried == null
      while (i < n)
        if (t.stringEnd(i) >= 0) { lineStart = false; i = onString(i) }
        else {
          val c = t.ch(i)
          if (c == '\n' || c == '\r') { lineStart = true; i += 1 }
          else if (c == ' ' || c == '\t') i += 1
          else {
            val atLineStart = lineStart
            lineStart = false
            if (c == ',') i += 1 // a container's or an array's separator: a walk carried over it is kept
            else {
              pending = null
              if (placeholderAt(i)) i += ph.length
              else if (isWordStart(c)) i = onWord(i, atLineStart)
              else
                c match {
                  case '{' | '[' | '('       => depth += 1; i += 1
                  case '}' | ']' | ')'       => if (depth > 0) depth -= 1; i += 1
                  case '?' | '&' | '#' | ';' => i = onSeparator(i)
                  case ':'                   => i = onColon(i)
                  case '-' if atLineStart && i + 1 < n && t.ch(i + 1) == ' ' =>
                    lineStart = true; i += 1
                  case _ => i += 1
                }
            }
          }
        }
    }

    // ---------------------------------------------------------------- helpers

    private def mask(from: Int, to: Int): Unit = if (to > from) edits.add(t.rawStart(from), t.rawEnd(to - 1), ph)

    private def maskWith(from: Int, to: Int, rep: String): Unit =
      if (to > from) edits.add(t.rawStart(from), t.rawEnd(to - 1), rep)

    private def placeholderAt(i: Int): Boolean =
      ph.nonEmpty && i + ph.length <= n && t.ch(i) == ph.charAt(0) && {
        var k = 1
        while (k < ph.length && t.ch(i + k) == ph.charAt(k)) k += 1
        k == ph.length
      }

    private def placeholderOnly(from: Int, to: Int): Boolean = to - from == ph.length && placeholderAt(from)

    private def text(from: Int, to: Int): String = new String(t.chars, t.off + from, to - from)

    private def raw(i: Int): String = input.substring(t.rawStart(i), t.rawEnd(i))

    /*
     * Whitespace and structure. Two skips decide where the scanner looks past whitespace, and each site uses the one
     * its shape needs:
     *
     *  - `skipBlanks` (spaces and tabs) where a line break ends the shape: a prose or header `key: value` line (its
     *    value runs to the end of the line, or folds over indented lines - `onHeader`), a `key=value` line outside any
     *    bracket (logfmt; `token=\nnext=1` has an empty value), `Bearer` and its token, a leaf and a closing quote
     *    on the same line (`endsValueAt`, `endsJsonStringAt`), and the next header after an escaped line break.
     *  - `skipSpace` (any whitespace, `\n`, `\r\n` and tabs among it) wherever the shape is a container's or a
     *    call's: after a quoted key's `:` and before it, after `[`, `{` and `(` (`plausible`, `callArgs`), between
     *    elements and before a closing bracket (`walk`, which also skips a `pytest` line's prefix), after a key inside
     *    a container (`walk`, `inlinePair`), and around a bare key's `=` or `:`, a quoted key's `=` and between a
     *    call's name and its `(`, inside a bracket this level opened (`afterKey`): pretty-printed JSON, a Python repr
     *    and keyword arguments one per line put any of these on a line of its own. Outside a bracket those take
     *    blanks (`afterKey` again).
     *
     * No rule asks for a key and its delimiter to touch: each finds the delimiter past the whitespace its site allows.
     * The adjacency checks left are those where touching is the stricter reading, and failing it masks more, not
     * less: `Bearer` and a sensitive header name glued to its `:` (not a token), a call's name glued to its `(` (kept
     * where it reads as a type's, where a name apart from its `(` is replaced too), `key=` after an `&` (else the value
     * runs on), and a quote glued to a key whose quotes the lexer paired otherwise. One reads less on purpose: outside
     * a bracket, a blank after a glued `=` before a lone word that does not look like a credential is prose (`token=
     * see the docs`).
     *
     * Every early exit - the placeholder already there, the next field's key, the end of a value, prose - skips a value
     * only on positive evidence that what it skips is no part of it, and otherwise replaces it
     * (`RedactionEarlyExitsSpec` pins each kept boundary).
     */
    private def skipBlanks(from: Int): Int = {
      var k = from
      while (k < n && (t.ch(k) == ' ' || t.ch(k) == '\t')) k += 1
      k
    }

    private def skipSpace(from: Int): Int = {
      var k = from
      while (k < n && Character.isWhitespace(t.ch(k))) k += 1
      k
    }

    /** The letter of a `\b`, `\f`, `\n`, `\r` or `\t` escape right before `i`, or 0. */
    private def escapeLetterBefore(i: Int): Char =
      if (i > 0 && t.escaped(i - 1) && EscapedControls.indexOf(t.ch(i - 1).toInt) >= 0)
        input.charAt(t.rawEnd(i - 1) - 1)
      else 0

    /** Whether the word `from until to` names a credential, also with the letter of an escape before it. */
    private def credentialWord(from: Int, to: Int): Boolean = {
      val word = text(from, to)
      Redaction.isCredentialName(word) || {
        val l = escapeLetterBefore(from)
        l != 0 && Redaction.isCredentialName(s"$l$word")
      }
    }

    private def isProse(key: String, from: Int, to: Int): Boolean =
      Redaction.isBareAssertion(key) && !(truncated && atInputEnd && to >= n) && Redaction.isProseText(
        t.chars,
        t.off + from,
        t.off + to
      )

    /** Records a value still open at the end of this level, from `from`, opened by `quote` (or 0). */
    private def open(from: Int, kind: Int, quote: Char): Unit = {
      openFrom = t.rawAt(from)
      openKind = kind
      openQuote = quote
      openNested = depth > 0
    }

    // ---------------------------------------------------------------- strings

    /** The decoded text of the string at `s`, for a key's name. */
    private def keyName(s: Int): String = {
      val end = t.stringEnd(s) - 1
      var bs  = false
      var k   = s + 1
      while (!bs && k < end) { bs = t.ch(k) == '\\'; k += 1 }
      if (!bs) text(s + 1, end)
      else {
        val sb = new java.lang.StringBuilder
        k = s + 1
        while (k < end)
          if (t.ch(k) == '\\') {
            val len = Text.escapeLength(t, k)
            if (len == 6) sb.append(Integer.parseInt(text(k + 2, k + 6), 16).toChar)
            else if (len == 2) sb.append(t.ch(k + 1))
            else sb.append('\\')
            k += math.max(len, 1)
          } else { sb.append(t.ch(k)); k += 1 }
        sb.toString
      }
    }

    private def onString(s: Int): Int = {
      val end = t.stringEnd(s)
      if (t.closed(s)) {
        val k = skipSpace(end)
        if (k < n && t.ch(k) == ':') {
          pending = null
          val key = keyName(s)
          if (Redaction.isCredentialName(key)) return readValue(skipSpace(k + 1), field = true, raw(s), key)
          return scanString(s, null)
        }
        // a quoted key's `=` (`"password" = "x"`, TOML's quoted keys): read as a pair's, with the whitespace of one
        val e = afterKey(end)
        if (e < n && t.ch(e) == '=') {
          val key = keyName(s)
          if (Redaction.isCredentialName(key)) {
            pending = null
            // `"password" == "x"` too: a comparison's right side is the credential as much
            val eq = if (e + 1 < n && t.ch(e + 1) == '=') e + 1 else e
            return readValue(afterKey(eq + 1), field = false, null, key)
          }
        }
      }
      val carry = pending
      pending = null
      scanString(s, carry)
    }

    /** Scans the content of the string at `s` as a level of its own; the index to go on from. */
    private def scanString(s: Int, carry: Walk): Int = {
      val end    = t.stringEnd(s)
      val closed = t.closed(s)
      if (end - s <= (if (closed) 2 else 1) && carry == null) return end
      val inner = new Scan(
        Text.content(t, s),
        input,
        ph,
        edits,
        truncated,
        atInputEnd = !closed && end == n && atInputEnd,
        carried = carry
      )
      inner.run()
      if (inner.openWalk != null) {
        // A container left open where the string closed right before more of it - a stray apostrophe opened that
        // string (`'tis Settings(token=SecretStr('x'))`) - goes on after the quote.
        if (closed && glued(end) && inner.openFrom < 0) {
          val close = closerOf(t.ch(s), end, lineEnd(end))
          if (close < 0) return walk(inner.openWalk, end)
          mask(end, close)
          return walk(inner.openWalk, close + 1)
        }
        val k = skipSpace(end)
        if (k < n && t.ch(k) == ',') {
          val k2 = skipSpace(k + 1)
          if (k2 < n && t.stringEnd(k2) >= 0) pending = inner.openWalk
        }
        if (!closed && end == n) openWalk = inner.openWalk
      }
      if (inner.openFrom >= 0) {
        if (closed) extendOpenValue(s, inner)
        else {
          if (end == n) {
            openFrom = inner.openFrom; openKind = inner.openKind; openQuote = inner.openQuote
            openNested = inner.openNested
          }
          end
        }
      } else end
    }

    private var lineEndFrom = -1
    private var lineEndAt   = -1

    /** The index of the line break at or after `from`, or `n`. */
    private def lineEnd(from: Int): Int =
      if (from >= lineEndFrom && from <= lineEndAt) lineEndAt
      else {
        var k = from
        while (k < n && t.ch(k) != '\n' && t.ch(k) != '\r') k += 1
        lineEndFrom = from
        lineEndAt = k
        k
      }

    private var closerQuote: Char = 0
    private var closerFrom        = -1
    private var closerTo          = -1
    private var closerAt          = -1

    /**
     * The first quote `q` in `from until to` that can close a string - an unescaped `"`, or an unescaped `'` not
     * between two letters or digits - or -1. Cached, so that the strings of one line that ask read it once.
     */
    private def closerOf(q: Char, from: Int, to: Int): Int =
      if (q == closerQuote && from >= closerFrom && to <= closerTo && (closerAt < 0 || closerAt >= from)) closerAt
      else {
        var k     = from
        var found = -1
        while (found < 0 && k < to) {
          val c = t.ch(k)
          if (c == '\\') k += 2
          else if (c == q && (q == '"' || !Text.between(t, k))) found = k
          else k += 1
        }
        closerQuote = q
        closerFrom = from
        closerTo = to
        closerAt = found
        found
      }

    /**
     * A value left open at the end of the string at `s` (`inner` reports it): the string may have closed on a quote of
     * the value's own, written without an escape. The index to go on from.
     *
     *  - A header's value (a cookie's), where the string closed where a JSON string cannot (`endsJsonStringAt`) -
     *    `msg="Cookie: a="x"; sid=..."` - runs on to the end of the line.
     *  - A pair's unquoted value, where the string closed right before more of it (`password=\\"abc`), runs on as
     *    an unquoted value does.
     *  - A quoted value of the other quote than the string's, inside a bracket left open (`"{'password': 'Qx"]]9x', 'u': 1}"`),
     *    runs on to the next quote of its own on the line that ends a value there - before `,`, a closing bracket or
     *    the end of the line - as a credential may hold the string's quote; where none follows on a line the input
     *    was cut in, to the end. Otherwise, where the string closed where a JSON string cannot, to the next quote of
     *    the string's kind after which one can, or to the end of the line.
     */
    private def extendOpenValue(s: Int, inner: Scan): Int = {
      val from      = inner.openFrom
      val quote     = inner.openQuote
      val end       = t.stringEnd(s)
      val q         = t.ch(s)
      val le        = lineEnd(end)
      val malformed = !endsJsonStringAt(end)
      def extendTo(stop: Int): Int = {
        edits.add(from, t.rawAt(stop), ph)
        if (stop == n) {
          openFrom = from; openKind = inner.openKind; openQuote = quote; openNested = inner.openNested
        }
        stop
      }
      inner.openKind match {
        case OpenHeader => if (malformed) extendTo(le) else end
        case OpenPair =>
          if (!glued(end)) end
          else {
            // the quote that closed the string opened the pair's value (`'tis, token='Kv x' ...`): to its own close
            val close = closerOf(q, end, le)
            if (close >= 0) { mask(end, close); close + 1 }
            else extendTo(pairValueEnd(end))
          }
        case _ =>
          val own =
            if (inner.openNested && quote != q) {
              var c     = closerOf(quote, end, le)
              var found = -1
              while (c >= 0 && found < 0)
                if (endsValueAt(c + 1)) found = c else c = closerOf(quote, c + 1, le)
              if (found >= 0) { edits.add(from, t.rawStart(found), ph); found + 1 }
              else if (truncated && atInputEnd && le == n) extendTo(n)
              else -1
            } else -1
          if (own >= 0) own
          else if (!malformed) end
          else {
            var k = -1
            var c = closerOf(q, end, le)
            while (c >= 0 && k < 0)
              if (endsJsonStringAt(c + 1)) k = c else c = closerOf(q, c + 1, le)
            extendTo(if (k >= 0) k else le)
          }
      }
    }

    /** Whether a value can end right before `k`: past blanks, the end of the line or level, `,` or a closing bracket. */
    private def endsValueAt(k: Int): Boolean = {
      val j = skipBlanks(k)
      j >= n || isBreak(j) || ",}])".indexOf(t.ch(j).toInt) >= 0
    }

    /**
     * Whether what comes from `from` on is what follows the end of a JSON string: past whitespace, the end of the
     * level, a `,` or `:` before a value, or a run of `}` and `]` followed by the end of the level, a line break or such
     * a `,`. Whitespace, closing brackets and at most six characters after a `,` or `:` are read.
     */
    private def endsJsonStringAt(from: Int): Boolean = {
      def valueAfter(sep: Int): Boolean = {
        val j = skipSpace(sep + 1)
        def literalAt(word: String): Boolean =
          j + word.length <= n && text(j, j + word.length) == word && (j + word.length == n || !isAlnum(
            t.ch(j + word.length)
          ))
        j < n && ("\"'{[-0123456789".indexOf(t.ch(j).toInt) >= 0 || literalAt("true") || literalAt("false") ||
          literalAt("null"))
      }
      val i = skipBlanks(from)
      i == n || (t.ch(i) match {
        case '\n' | '\r' => true
        case '}' | ']' =>
          var j = i
          while (j < n && "}] \t".indexOf(t.ch(j).toInt) >= 0) j += 1
          j == n || t.ch(j) == '\n' || t.ch(j) == '\r' || (t.ch(j) == ',' && valueAfter(j))
        case ',' | ':' => valueAfter(i)
        case _         => false
      })
    }

    // ---------------------------------------------------------------- values

    /**
     * The value of a sensitive key, from `v`: a field's (`field`, after `:`, with the raw text of its key's quote) or
     * a pair's (after `=`). Returns the index after it.
     */
    private def readValue(v: Int, field: Boolean, quote: String, key: String): Int =
      // nothing after the key but the end of this level: the value may follow the string's close (`"x={'k': "v"}"`)
      if (v >= n) { open(v, OpenPair, 0); v }
      // already masked: only where the placeholder is the whole value (not `password=[REDACTED]hunter2`)
      else if (placeholderAt(v)) {
        val p = v + ph.length
        // a name already replaced, before its bracket (`[REDACTED](['x'])`): the bracket is walked as a call's
        if (p < n && "([{".indexOf(t.ch(p).toInt) >= 0) walk(new Walk(new java.lang.StringBuilder, quote), p)
        else {
          val e = placeholderValueEnd(p, field)
          if (e > p) mask(v, e)
          e
        }
      }
      // A pair whose `=` ends its line, inside a bracket, is empty where a key follows on the next line
      // (`{password=\n"user": 1}`). On the same line a quoted value before a `:` is the value (`password="x": failed`).
      else if (t.stringEnd(v) >= 0 && !field && t.closed(v) && colonAfter(t.stringEnd(v)) && breakBefore(v)) v
      else if (t.stringEnd(v) >= 0 && (field || !gluedString(v))) stringValue(v, key, field)
      else {
        val c = t.ch(v)
        if (c == '[' || c == '{' || c == '(') container(v, quote, field, key)
        else {
          val call = callAt(v)
          if (call >= 0) {
            // a name apart from its `(` may be no call's but a value before a bracket of prose: replaced too
            // and so is a name that reads as no type's (`password=hunter2(x)`): a value with a `(` in it
            if (isSpace(t.ch(call - 1)) || !wrapperName(v, callNameEnd(v))) mask(v, callNameEnd(v))
            walk(new Walk(new java.lang.StringBuilder, quote), call)
          } else if (field) bareValue(v, quote)
          else pairValue(v, key)
        }
      }

    /**
     * The end of a value that starts with the placeholder, from `from`, right after it: `from` itself where the
     * placeholder is the whole value - whitespace, the end, or what ends a value follows it - else the end of the rest,
     * read as an unquoted value is (`[REDACTED]hunter2`, `[REDACTED]"x"`), to be replaced with it.
     */
    private def placeholderValueEnd(from: Int, field: Boolean): Int =
      if (from >= n || isSpace(t.ch(from)) || ",;<>)]}".indexOf(t.ch(from).toInt) >= 0) from
      else if (t.ch(from) == '&' && parameterAfter(from)) from
      else if (!field) pairValueEnd(from)
      else {
        var e = from
        while (e < n && !isSpace(t.ch(e)) && ",;)]}".indexOf(t.ch(e).toInt) < 0)
          e = if (t.stringEnd(e) > e) t.stringEnd(e) else e + 1
        e
      }

    /** Whether a line break is among the whitespace right before `v`. */
    private def breakBefore(v: Int): Boolean = {
      var k     = v - 1
      var found = false
      while (!found && k >= 0 && isSpace(t.ch(k))) { found = isBreak(k); k -= 1 }
      found
    }

    /** The string value at `s`, replaced in full: to the end of this level where it does not close. */
    private def stringValue(s: Int, key: String, field: Boolean): Int = {
      val end = t.stringEnd(s)
      val cs  = s + 1
      if (!t.closed(s)) {
        // A field's string never runs over a line, so one left open was cut off: replaced to the end of this level.
        // A pair's value ends with its line, as a logfmt line does.
        val to = if (field) n else end
        if (!placeholderOnly(cs, to)) mask(cs, to)
        if (to == n) open(cs, OpenQuoted, t.ch(s))
        to
      } else {
        val ce = end - 1
        if (ce > cs && !placeholderOnly(cs, ce)) {
          if (isProse(key, cs, ce)) return scanString(s, null)
          mask(cs, ce)
        }
        // A close glued to more text (`"password": " here | token="Kv,x" ...`) was no close but the opening quote of the
        // value of a key the string swallowed: that value is replaced too, to its own close or, where none follows on
        // the line, as an unquoted value. Not the rest of a sensitive quoted key (`" here {"x-api-key": ...`), which is
        // read so and has its own value replaced; a key that names no credential may be the value's tail, and goes.
        if (glued(end) && !sensitiveQuotedKeyAt(end, t.ch(s))) {
          val close = closerOf(t.ch(s), end, lineEnd(end))
          val e     = if (close >= 0) close else pairValueEnd(end)
          mask(end, e)
          return if (close >= 0) close + 1 else e
        }
        if (bracketGlued(end)) {
          // a close right before closing brackets glued to a word (`"Kv"]]x'`), which no string ends at: the value goes
          // on, to a quote of its kind after which a string can end, or to the end of the line
          val le = lineEnd(end)
          var k  = -1
          var c  = closerOf(t.ch(s), end, le)
          while (c >= 0 && k < 0) if (endsJsonStringAt(c + 1)) k = c else c = closerOf(t.ch(s), c + 1, le)
          val stop = if (k >= 0) k else le
          mask(end, stop)
          return stop
        }
        var after = end
        var more  = field
        while (more) {
          // Python joins the strings written right after a string, as `pprint` splits a long one
          // A string before a `:` is the next field's key where a line break comes before it (`'x'\n 'user': 1`); on
          // the same line, with no `,` between, it is still the value's (`'abc' 'hunter2': 1`) and goes too.
          val k = skipSpace(after)
          if (k < n && t.stringEnd(k) >= 0 && t.closed(k) && !(colonAfter(t.stringEnd(k)) && hasBreak(after, k))) {
            val e = t.stringEnd(k)
            if (e - 1 > k + 1 && !placeholderOnly(k + 1, e - 1)) mask(k + 1, e - 1)
            after = e
            if (colonAfter(e)) more = false
          } else more = false
        }
        after
      }
    }

    /**
     * Whether the string at `s` closes right before more of an unquoted value (`?password=''Xk9`, `token='a'b`): the
     * value is then read unquoted, over the string.
     */
    private def gluedString(s: Int): Boolean = {
      val e = t.stringEnd(s)
      t.closed(s) && e < n && {
        val c = t.ch(e)
        !(isSpace(c) || ",;:<>&)]}".indexOf(c.toInt) >= 0)
      }
    }

    /**
     * Whether a key that names a credential, its closing `q` and `:` follow `at`: the rest of a quoted key whose opening
     * quote was paired otherwise, which `onWord` reads as a field (`misalignedField`).
     */
    private def sensitiveQuotedKeyAt(at: Int, q: Char): Boolean = {
      var j = at
      while (j < n && isKeyChar(t.ch(j))) j += 1
      j > at && j < n && t.ch(j) == q && colonAfter(j + 1) && credentialWord(at, j)
    }

    /** Whether a run of `]`, `}` or `)` at `at` is glued to a letter, a digit or `_`: `"]]x`, no string's end. */
    private def bracketGlued(at: Int): Boolean = {
      var k = at
      while (k < n && "]})".indexOf(t.ch(k).toInt) >= 0) k += 1
      k > at && glued(k)
    }

    /** Whether a letter, a digit or `_` is right at `at`: text a closing quote is glued to. */
    private def glued(at: Int): Boolean = at < n && (isAlnum(t.ch(at)) || t.ch(at) == '_')

    private def colonAfter(from: Int): Boolean = {
      val k = skipSpace(from)
      k < n && t.ch(k) == ':'
    }

    /** A number or a bare word under a field's key: written as the placeholder in the key's quote. */
    private def bareValue(v: Int, quote: String): Int = {
      var w    = leafEnd(v, inCall = false)
      val word = text(v, w)
      if (word.equalsIgnoreCase("bearer") || word.equalsIgnoreCase("basic")) {
        // a scheme and its token, one value
        val k = skipBlanks(w)
        if (k > w && k < n) w = math.max(w, leafEnd(k, inCall = false))
      }
      if (w > v && !KeptLiterals.contains(word)) maskWith(v, w, quote + ph + quote)
      w
    }

    /** An unquoted pair value: up to whitespace, one of `,;<>`, or an `&` that another pair follows. */
    private def pairValue(v: Int, key: String): Int = {
      val e =
        if (v < n && t.ch(v) == '<') {
          // a value in angle brackets (`password=<hunter2>`), not an empty one before a tag
          val c = pairValueEnd(v + 1)
          if (c < n && t.ch(c) == '>') c + 1 else c
        } else pairValueEnd(v)
      if (e == v) { if (e == n) open(v, OpenPair, 0); e } // `token=` right before a string's close: see extendOpenValue
      else if (placeholderOnly(v, e)) e
      else if (isProse(key, v, e)) v
      else {
        mask(v, e)
        if (e == n) open(v, OpenPair, 0)
        e
      }
    }

    private def pairValueEnd(v: Int): Int = {
      var e    = v
      var done = false
      while (!done && e < n) {
        val se = t.stringEnd(e)
        if (se >= 0) e = se
        else {
          val c = t.ch(e)
          if (isSpace(c) || c == ',' || c == ';' || c == '<' || c == '>') done = true
          else if (c == '&' && parameterAfter(e)) done = true
          else e += 1
        }
      }
      e
    }

    /** Whether another `key=` follows the `&` at `e`, right after it or after an HTML escape (`&amp;key=`). */
    private def parameterAfter(e: Int): Boolean =
      pairAt(e + 1) || HtmlQuerySeparators.exists(h =>
        e + h.length <= n && text(e, e + h.length).equalsIgnoreCase(h) && pairAt(e + h.length)
      )

    /** Whether a `key=` (a letter, then key characters, then `=`) starts at `k`. */
    private def pairAt(k: Int): Boolean =
      k < n && Character.isLetter(t.ch(k)) && {
        var j = k + 1
        while (j < n && isKeyChar(t.ch(j))) j += 1
        j < n && t.ch(j) == '='
      }

    /**
     * Where a call's `(` is, after a dotted name that starts at `v` (`SecretStr(`, `pydantic.SecretStr(`), or -1. The
     * `(` may follow the name after whitespace as a bare key's `=` may (`afterKey`): `SecretStr ('x')`.
     */
    private def callAt(v: Int): Int =
      if (!(Character.isLetter(t.ch(v)) || t.ch(v) == '_')) -1
      else {
        val k = afterKey(callNameEnd(v))
        if (k < n && t.ch(k) == '(' && callArgs(k)) k else -1
      }

    /**
     * Whether the dotted name `from until to` reads as a type's, which a value is wrapped in (`SecretStr`,
     * `pydantic.SecretStr`, `Some`): its last part starts with an upper-case letter and holds no digit. Any other name
     * glued to a `(` under a sensitive key may be the credential itself (`hunter2(x)`, `Pa55(word)`) and is replaced.
     */
    private def wrapperName(from: Int, to: Int): Boolean = {
      var p = to - 1
      while (p > from && t.ch(p - 1) != '.') p -= 1
      var digit = false
      var k     = p
      while (k < to) { if (t.ch(k) >= '0' && t.ch(k) <= '9') digit = true; k += 1 }
      p < to && Character.isUpperCase(t.ch(p)) && !digit
    }

    /** The end of the dotted name that starts at `v`. */
    private def callNameEnd(v: Int): Int = {
      var k = v + 1
      while (k < n && (isWordStart(t.ch(k)) || t.ch(k) == '.')) k += 1
      k
    }

    /** Whether what follows the `(` at `k` can start a call's arguments: a value, a name, or `)`. */
    private def callArgs(k: Int): Boolean = {
      // a call's arguments may start on the next line (`SecretStr(\n'x'\n)`): any whitespace
      val a = skipSpace(k + 1)
      a >= n || t.stringEnd(a) >= 0 || {
        val c = t.ch(a)
        "[{)-'\"".indexOf(c.toInt) >= 0 || isWordStart(c)
      }
    }

    /** A container under a sensitive key at `v`: its leaves are replaced, unless it is no container but prose. */
    private def container(v: Int, quote: String, field: Boolean, key: String): Int =
      // A bracket glued to a bare first word is a container too, as Go prints a slice (`password=[abc hunter2]`);
      // prose puts a blank after a bracket it mentions (`'token': [ for details`)
      if (plausible(v) || (v + 1 < n && isWordStart(t.ch(v + 1))))
        walk(new Walk(new java.lang.StringBuilder, quote), v)
      else if (!field) pairValue(v, key)
      else {
        val g = gluedElement(v)
        if (g >= 0) g else v + 1
      }

    /**
     * Whether the bracket at `open` starts a container rather than prose that mentions one (`'token': [ for
     * details`): its first element - a string, a word, a bracket - is followed by what follows an element (`,`, `:`,
     * `=`, a closing bracket, another string) or by nothing: a container cut off there is one.
     */
    private def plausible(open: Int): Boolean = {
      val k = skipSpace(open + 1)
      if (k >= n || placeholderAt(k)) true
      else if (t.stringEnd(k) >= 0) {
        if (!t.closed(k)) true
        else {
          val a = skipSpace(t.stringEnd(k))
          a >= n || ",:]})=".indexOf(t.ch(a).toInt) >= 0 || t.stringEnd(a) >= 0
        }
      } else if ("]})[{(".indexOf(t.ch(k).toInt) >= 0) true
      else {
        val from = if (t.ch(k) == '\\') k + 1 else k
        val w    = leafEnd(from, inCall = true) // `=` ends an element in any container, as in `walk`
        if (w == from) true
        else {
          val a = skipSpace(w)
          a >= n || ",:]})=(".indexOf(t.ch(a).toInt) >= 0
        }
      }
    }

    /**
     * Under a key whose container is no container, its first element where it is glued to what follows it, as a
     * stray quote leaves a document (`{"token": {'"'hunter2:`): replaced to the next whitespace. The index after it,
     * or -1 where the element is prose.
     */
    private def gluedElement(open: Int): Int = {
      val k = skipSpace(open + 1)
      if (k >= n) -1
      else {
        val e = if (t.stringEnd(k) >= 0) t.stringEnd(k) else leafEnd(k, inCall = false)
        if (
          e > k && e < n && !Character.isWhitespace(t.ch(e)) && ",:]})=(".indexOf(t.ch(e).toInt) < 0 &&
          inlinePair(k) < 0
        ) {
          var z = e
          while (z < n && !Character.isWhitespace(t.ch(z))) z += 1
          mask(k, z)
          z
        } else -1
      }
    }

    /** Where a container's leaf, a bare value or a call's name that starts at `i` ends. */
    private def leafEnd(i: Int, inCall: Boolean): Int = {
      var k = i
      while (
        k < n && t.stringEnd(k) < 0 && {
          val c = t.ch(k)
          !(Character.isWhitespace(c) || ",:[]{}()\\".indexOf(c.toInt) >= 0 || (inCall && c == '='))
        }
      ) k += 1
      k
    }

    /**
     * The `=` of a `key=` with a sensitive key that starts at `i` inside a container, or -1: any whitespace may come
     * between the key and its `=`, as everywhere in a container (not `key == x`).
     */
    private def inlinePair(i: Int): Int = {
      var j = i
      while (j < n && isKeyChar(t.ch(j))) j += 1
      val k = skipSpace(j)
      if (
        j > i && k < n && t.ch(k) == '=' && !(k + 1 < n && t.ch(k + 1) == '=') && Redaction.isCredentialName(text(i, j))
      ) k
      else -1
    }

    /** Whether an odd run of backslashes ends right before `i`, so that the character at `i` is escaped. */
    private def escapedBefore(i: Int): Boolean = {
      var j = i - 1
      while (j >= 0 && t.ch(j) == '\\') j -= 1
      (i - 1 - j) % 2 == 1
    }

    /**
     * Replaces the leaves of the container `w`, from `start`: every string that is not a key (followed by `:`, or by
     * `=` in a call) and every bare word that is not a key, a call's name or a literal (`true`, `None`, ...); a bare
     * word is written as the placeholder in `w`'s quote, its escaped first character kept. The walk ends where its
     * outermost bracket closes; at a value right after a value, where the text is no container any more - bar a bare
     * word after a bare word, or a container after a container, on the same line, as Go prints a slice (`[a b]`,
     * `[[a b] [c d]]`); or at a sensitive `key=` inside it, whose value it replaces (`[password=:a]x`). Left open at the end of this level, it is
     * reported (`openWalk`) and may go on in the next string. Brackets are counted on `w`'s own stack, so the depth of
     * the container does not grow the call stack. The index to go on from.
     */
    private def walk(w: Walk, start: Int): Int = {
      val stack     = w.stack
      var i         = start
      var lastValue = false
      var lastStr   = false
      var done      = false
      var lastBare  = false // the last value was a bare word
      var lastClose = false // the last value was a container, closed
      var broke     = false // a line break since the last value
      while (!done && i < n) {
        val inCall = stack.length > 0 && stack.charAt(stack.length - 1) == '('
        val se     = t.stringEnd(i)
        if (placeholderAt(i)) {
          // already masked where the placeholder is the whole leaf, or a call's name before its bracket; a leaf that
          // only starts with it goes whole
          val p = i + ph.length
          if (p < n && "([{".indexOf(t.ch(p).toInt) >= 0) { i = p; lastValue = false }
          else {
            val e = placeholderValueEnd(p, field = true)
            if (e > p) mask(i, e)
            i = e; lastValue = true; lastBare = true; lastClose = false; broke = false
          }
          lastStr = false
        } else if (se >= 0) {
          if (lastValue && !lastStr) done = true
          else {
            val closed = t.closed(i)
            val k      = skipSpace(se)
            if (closed && k < n && (t.ch(k) == ':' || (inCall && t.ch(k) == '='))) { i = se; lastValue = false }
            else {
              val ce     = if (closed) se - 1 else se
              val masked = placeholderOnly(i + 1, ce)
              if (ce > i + 1 && !masked) mask(i + 1, ce)
              if (!closed && se == n) { open(i + 1, OpenQuoted, t.ch(i)); openNested = true }
              i = se
              lastValue = true
              lastBare = false
              lastClose = false; broke = false
              // a leaf written as the placeholder in quotes may have been a bare word: a string after it is no
              // string joined to it, as it was not after the word
              lastStr = !masked
            }
          }
        } else {
          val c = t.ch(i)
          if (c == '[' || c == '{' || c == '(') {
            // a container right after a container, on the same line, is another of Go's (`[[a b] [c d]]`)
            if (lastValue && !(lastClose && !broke)) done = true
            else { stack.append(c); i += 1; lastValue = false; lastClose = false }
          } else if (c == ']' || c == '}' || c == ')') {
            if (stack.length > 0) stack.setLength(stack.length - 1)
            i += 1
            if (stack.length == 0) done = true
            lastValue = true
            lastStr = false
            lastBare = false
            lastClose = true
            broke = false
          } else if (c == ',' || c == ':' || c == '=') { i += 1; lastValue = false; lastStr = false }
          else if (c == '\n' || c == '\r') { broke = true; i = skipLinePrefix(i + 1) }
          else if (Character.isWhitespace(c) || c == '\\') i += 1
          // A value right after a value ends the walk, where the text is no container any more; but a bare word after a
          // bare word on the same line is a leaf of a slice Go prints without commas (`password=[abc hunter2]`).
          else if (lastValue && !(lastBare && !broke)) done = true
          else {
            val pair = if (inCall) -1 else inlinePair(i)
            if (pair >= 0) {
              val v  = skipSpace(pair + 1)
              val se = if (v < n) t.stringEnd(v) else -1
              // a quoted value keeps its quotes (`[x, password='a']`), as a pair's does outside a container
              val e = if (se > v + 1 && t.closed(v) && !gluedString(v)) se else pairValueEnd(v)
              if (e == se) mask(v + 1, se - 1) else mask(v, e)
              i = e
              // a value that ran over a closing bracket (`[password=:a]x`) ends the container; else the walk goes on
              var k = v
              while (k < e && "]})".indexOf(t.ch(k).toInt) < 0) k = if (t.stringEnd(k) > k) t.stringEnd(k) else k + 1
              if (k < e || e == v) { done = true; stack.setLength(0) }
              else { lastValue = true; lastBare = false; lastClose = false; broke = false }
            } else {
              // a leaf ends at `=` in any container, as `=` separates there (`[note= x]`, `[a=b]`)
              val e    = leafEnd(i, inCall = true)
              val k    = skipSpace(e) // inside a container, a key's `:` or `=` may follow on the next line
              val next = if (k < n) t.ch(k) else 0.toChar
              // a key where one can stand, not glued on after a value (`[a hunter2: x]`)
              if (
                !lastValue && (next == ':' || (inCall && next == '=') || (k == e && next == '(' && wrapperName(i, e)))
              )
                lastValue = false
              else if (next == '(') {
                // a name apart from its `(` (`SecretStr ('x')`, `hunter2 (old)`), or one that reads as no type's
                // (`hunter2(x)`): replaced, and the bracket walked
                if (!KeptLiterals.contains(text(i, e))) {
                  val from = if (escapedBefore(i)) i + 1 else i
                  if (e > from) { if (w.quote == null) mask(from, e) else maskWith(from, e, w.quote + ph + w.quote) }
                }
                lastValue = false
              } else if (e < n && t.stringEnd(e) >= 0 && e - i <= 2 && Text.opens(t, e))
                () // a string's prefix: `b'...'`
              else {
                if (!KeptLiterals.contains(text(i, e))) {
                  val from = if (escapedBefore(i)) i + 1 else i
                  if (e > from) { if (w.quote == null) mask(from, e) else maskWith(from, e, w.quote + ph + w.quote) }
                }
                lastValue = true
                lastBare = true
                lastClose = false; broke = false
              }
              lastStr = false
              i = math.max(e, i + 1)
            }
          }
        }
      }
      if (!done && stack.length > 0) openWalk = w
      i
    }

    /** After a line break in a container: indentation, and a `pytest` line's `E` and diff marker (`+`, `-`, `?`). */
    private def skipLinePrefix(from: Int): Int = {
      def marker(at: Int, chars: String): Int =
        if (at + 1 < n && chars.indexOf(t.ch(at).toInt) >= 0 && (t.ch(at + 1) == ' ' || t.ch(at + 1) == '\t'))
          skipBlanks(at + 1)
        else at
      marker(marker(skipBlanks(from), "E"), "+-?")
    }

    // ---------------------------------------------------------------- words

    private def onWord(i: Int, atLineStart: Boolean): Int = {
      var j = i
      while (j < n && isKeyChar(t.ch(j))) j += 1
      // A quoted key whose quotes the lexer paired otherwise, after a stray quote: `""apikey":"...`.
      if (i > 0 && j < n) {
        val q = t.ch(i - 1)
        if ((q == '"' || q == '\'') && t.ch(j) == q && colonAfter(j + 1) && credentialWord(i, j))
          return misalignedField(skipSpace(skipSpace(j + 1) + 1), text(i, j), raw(i - 1))
      }
      val lower = text(i, j).toLowerCase(Locale.ROOT)
      if (lower == "bearer" || lower == "basic") {
        val r = onAuthScheme(i, j, bearer = lower == "bearer")
        if (r >= 0) return r
      }
      val k = afterKey(j)
      if (k < n) {
        val c                   = t.ch(k)
        def spansLines: Boolean = depth > 0 && (hasBreak(j, k) || hasBreak(k + 1, afterKey(k + 1)))
        // `key=value`, `key = value`, `key==value`, and the comparison `password == 'x'`, whose right side is as much
        // the credential (a test's source line, a `pytest` report)
        if (c == '=') return onPair(i, j, if (k > j && k + 1 < n && t.ch(k + 1) == '=') k + 1 else k)
        // `key:`, `key :` and, inside a bracket, `key\n:` (`afterKey`); not a URL's `scheme://`
        if (c == ':' && !(k + 2 < n && t.ch(k + 1) == '/' && t.ch(k + 2) == '/')) {
          // a key whose `:` or value is on another line, inside a bracket, is code (`{\n  password\n  :\n  'x'}`): a field
          if (spansLines && (anywhereHeader(i, j) || credentialWord(i, j)))
            return inlineField(afterKey(k + 1), text(i, j))
          if (anywhereHeader(i, j) || (atLineStart && credentialWord(i, j))) return onHeader(i, j, k)
          // `{password: x}`, `a=1, token: x`: a sensitive key inside a line, after a separator or an opening bracket,
          // whose value is read as a pair's, to whitespace or one of `,;<>` (not "Reset your password: the link")
          if (credentialWord(i, j)) {
            val v = afterKey(k + 1)
            if (afterSeparator(i) || credentialLike(v)) return inlineField(v, text(i, j))
          }
        }
      }
      vendorScan(i, j)
    }

    /**
     * Whether the unquoted value at `v` reads as a credential rather than prose: a run of six or more characters with a
     * digit in it, as `token: Zq81x9` has and `password: the link` has not. A sensitive key inside a line of prose is
     * taken for a field only before such a value.
     */
    private def credentialLike(v: Int): Boolean = {
      val e = inlineValueEnd(v)
      e - v >= 6 && {
        var digit = false
        var k     = v
        while (!digit && k < e) { digit = t.ch(k) >= '0' && t.ch(k) <= '9'; k += 1 }
        digit
      }
    }

    /**
     * The value of a sensitive `key:` inside a line, from `v`: a string or container as any value, `Bearer` or `Basic`
     * and its token as such, else one token.
     */
    private def inlineField(v: Int, key: String): Int =
      if (v < n && (t.stringEnd(v) >= 0 || "[{(".indexOf(t.ch(v).toInt) >= 0)) readValue(v, field = false, null, key)
      else if (authSchemeAt(v)) v // read by `onWord` next
      else {
        val e = inlineValueEnd(v)
        if (e > v && !placeholderOnly(v, e) && !isProse(key, v, e)) mask(v, e)
        e
      }

    /** Whether `Bearer` or `Basic` starts at `v`, a word of its own. */
    private def authSchemeAt(v: Int): Boolean = {
      var e = v
      while (e < n && isKeyChar(t.ch(e))) e += 1
      val w = text(v, e)
      (w.equalsIgnoreCase("bearer") || w.equalsIgnoreCase("basic")) && (e == n || isSpace(t.ch(e)))
    }

    /** Whether a line break is in `from until to`. */
    private def hasBreak(from: Int, to: Int): Boolean = {
      var k = from
      while (k < to && !isBreak(k)) k += 1
      k < to
    }

    /** Where an unquoted value inside a line ends: at whitespace, `,` or `;`. */
    private def inlineValueEnd(v: Int): Int = {
      var e = v
      while (e < n && !isSpace(t.ch(e)) && t.ch(e) != ',' && t.ch(e) != ';')
        e = if (t.stringEnd(e) > e) t.stringEnd(e) else e + 1
      e
    }

    /** Whether, past blanks, one of `,;|{([` comes right before `i`. */
    private def afterSeparator(i: Int): Boolean = {
      var k = i - 1
      while (k >= 0 && (t.ch(k) == ' ' || t.ch(k) == '\t')) k -= 1
      k >= 0 && ",;|{([".indexOf(t.ch(k).toInt) >= 0
    }

    /** A field whose key's quotes the lexer did not pair: its value, read from `v` in the raw text. */
    private def misalignedField(v: Int, key: String, quote: String): Int =
      if (v < n && (t.ch(v) == '"' || t.ch(v) == '\'')) {
        val qq    = t.ch(v)
        var k     = v + 1
        var close = -1
        while (close < 0 && k < n && t.ch(k) != '\n' && t.ch(k) != '\r')
          if (t.ch(k) == '\\') k += 2 else if (t.ch(k) == qq) close = k else k += 1
        if (close >= 0) { if (close > v + 1 && !placeholderOnly(v + 1, close)) mask(v + 1, close); close + 1 }
        else {
          if (!placeholderOnly(v + 1, n)) mask(v + 1, n)
          open(v + 1, OpenQuoted, qq)
          n
        }
      } else readValue(v, field = true, quote, key)

    /**
     * Past the whitespace at `from` around a bare key's `=` or `:`: blanks, and line breaks too inside a bracket this
     * level opened (`Settings(\n  token=\n    'x')`, `{password:\n  'x'}`), where the text is code. Outside one, a
     * line break ends a `key=` or `key:` line with an empty value, and the next line is a line of its own.
     */
    private def afterKey(from: Int): Int = if (depth > 0) skipSpace(from) else skipBlanks(from)

    private def onPair(i: Int, j: Int, eq: Int): Int = {
      val spaced = eq > j
      val v      = afterKey(eq + 1)
      // `key= value`, a blank after a glued `=`, is prose outside a bracket (`token= see the docs`) where the value is a
      // word that does not look like a credential (`credentialLike`) - not a string, a container or a call; a keyword
      // argument inside one (`f(token= 'x')`) is not
      def proseAfter: Boolean =
        v < n && t.stringEnd(v) < 0 && "[{(".indexOf(t.ch(v).toInt) < 0 && callAt(v) < 0 && !credentialLike(v)
      if (!credentialWord(i, j)) v
      else if (!spaced && depth == 0 && v > eq + 1 && proseAfter) v
      else readValue(v, field = false, null, text(i, j))
    }

    private def anywhereHeader(i: Int, j: Int): Boolean = {
      val lower = text(i, j).toLowerCase(Locale.ROOT)
      lower == "cookie" || lower.endsWith("-cookie") || lower.filter(isAlnum).endsWith("authorization")
    }

    /**
     * A header's value, to the end of its line: of this level, where a line break that stands for an escape in plain
     * text, or in a string that never closed, ends it only where the next header follows (`\nX-Header:`), as a value
     * may hold `\n` itself. A value empty on its own line is folded onto the indented lines after it, as an HTTP
     * header's `obs-fold` and a YAML block are (`credentials:\n  user: bob\n  pass: x`). Where it runs to the end of
     * this level - or there is nothing after the `:` but that end - it is reported as open, for the level around to
     * carry on past a string that closed where none can.
     */
    private def onHeader(i: Int, j: Int, colon: Int): Int = {
      var v = skipBlanks(colon + 1)
      val folded = v < n && isBreak(v) && {
        val k = afterBreak(v)
        k < n && (t.ch(k) == ' ' || t.ch(k) == '\t') && { v = skipBlanks(k); true }
      }
      if (v < n && isBreak(v)) v
      else {
        var e        = v
        var d        = false
        val brackets = new java.lang.StringBuilder
        while (!d && e < n)
          if (isBreak(e) && (!t.escaped(e) || headerFollows(e + 1))) {
            val k = afterBreak(e)
            if (folded && k < n && (t.ch(k) == ' ' || t.ch(k) == '\t')) e = k else d = true
          } else if (t.stringEnd(e) > e + 1 && t.stringEnd(e) <= n && !isBreak(t.stringEnd(e) - 1)) e = t.stringEnd(e)
          else {
            val c = t.ch(e)
            if (c == '[' || c == '{' || c == '(') { brackets.append(c); e += 1 }
            else if (c == ']' || c == '}' || c == ')') {
              if (brackets.length > 0) { brackets.setLength(brackets.length - 1); e += 1 }
              // Inside a bracket, the value is an element of a container: a closing bracket it did not open, not glued
              // to more of it (`ab}cd`), closes the container the key is in (`{\n  token: x } {'k':\n  'v'}`) ...
              else if (depth > 0 && !glued(e + 1)) d = true
              else e += 1
            } else if (c == ',' && depth > 0 && brackets.length == 0 && keyAt(e + 1))
              d = true // ... as a `,` and a key do
            else e += 1
          }
        // Opening brackets and blanks the value ends with, before the line break that ends it, hold nothing: they are
        // left to the scanner, as the lines after them are inside those brackets (`Bearer x\n, {\npassword\n: y}`).
        var m = e
        if (e < n && brackets.length > 0) {
          while (m > v && "{[( \t".indexOf(t.ch(m - 1).toInt) >= 0 && t.stringEnd(m - 1) < 0) m -= 1
          while (m < e && (t.ch(m) == ' ' || t.ch(m) == '\t')) m += 1 // from the first bracket: blanks stay masked
        }
        if (m == v) { if (e == n) open(v, OpenHeader, 0); m }
        else if (placeholderOnly(v, m)) m
        else if (isProse(text(i, j), v, m)) v
        else {
          mask(v, m)
          if (e == n) {
            open(v, OpenHeader, 0)
            // a container the value opened and did not close may go on in the next string (a notebook's lines)
            if (brackets.length > 0) openWalk = new Walk(brackets, null)
          }
          m
        }
      }
    }

    /**
     * Whether a key starts at `k`, past whitespace: a string and `:`, or a word and `:` or `=` (not `==` or `://`).
     * The lookahead ends at the key's delimiter.
     */
    private def keyAt(k: Int): Boolean = {
      val j = skipSpace(k)
      if (j >= n) false
      else if (t.stringEnd(j) >= 0) t.closed(j) && colonAfter(t.stringEnd(j))
      else {
        var w = j
        while (w < n && isKeyChar(t.ch(w))) w += 1
        val d = afterKey(w)
        w > j && d < n && (t.ch(d) match {
          case ':' => !(d + 2 < n && t.ch(d + 1) == '/' && t.ch(d + 2) == '/')
          case '=' => !(d + 1 < n && t.ch(d + 1) == '=')
          case _   => false
        })
      }
    }

    private def isBreak(k: Int): Boolean = t.ch(k) == '\n' || t.ch(k) == '\r'

    /** The index after the line break at `k`: `\r\n` is one. */
    private def afterBreak(k: Int): Int = if (t.ch(k) == '\r' && k + 1 < n && t.ch(k + 1) == '\n') k + 2 else k + 1

    /**
     * Whether the next line starts at `k`, after at most one more escaped line break (`\r\n`): a header name or a YAML
     * key, indented or not, and `:`.
     */
    private def headerFollows(k: Int): Boolean = {
      val s = skipBlanks(if (k < n && isBreak(k) && t.escaped(k)) k + 1 else k)
      var e = s
      while (e < n && e - s < 64 && (isAlnum(t.ch(e)) || t.ch(e) == '-' || t.ch(e) == '_')) e += 1
      e > s && e < n && t.ch(e) == ':'
    }

    /**
     * `Bearer` or `Basic` at `i` and its token, after whitespace: both replaced. A token that is the key of a sensitive
     * pair (`Bearer password=x`) is left to the pair. -1 where there is no token.
     */
    private def onAuthScheme(i: Int, j: Int, bearer: Boolean): Int = {
      val k = skipBlanks(j)
      if (k == j || k >= n) -1
      else if (t.stringEnd(k) >= 0) {
        // a quoted token (`Bearer "x"`): its content is replaced, as a string value's is; not a key after the scheme
        val se = t.stringEnd(k)
        if (t.closed(k) && colonAfter(se)) -1
        else {
          val ce = if (t.closed(k)) se - 1 else se
          if (ce > k + 1 && !placeholderOnly(k + 1, ce)) mask(k + 1, ce)
          se
        }
      } else {
        var e = k
        while (
          e < n && {
            val c = t.ch(e)
            (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            (if (bearer) "-_.~+/=".indexOf(c.toInt) >= 0 else c == '+' || c == '/' || c == '=')
          }
        ) e += 1
        if (e == k) -1
        else {
          var kk = k
          while (kk < n && isKeyChar(t.ch(kk))) kk += 1
          val eq = skipBlanks(kk)
          if (kk > k && eq < n && t.ch(eq) == '=' && Redaction.isCredentialName(text(k, kk))) -1
          // a sensitive header's name (`Bearer Authorization: ...`), whose own value is replaced, or a URL's scheme,
          // whose password is; not a token that only goes on with a `:` (`Basic alice:hunter2`, `Bearer hunter2: x`)
          else if (
            e < n && t.ch(e) == ':' &&
            (anywhereHeader(k, e) || credentialWord(k, e) || (e + 2 < n && t.ch(e + 1) == '/' && t.ch(e + 2) == '/'))
          ) -1
          else {
            val end = if (e < n && t.ch(e) == ':') math.max(e, inlineValueEnd(e)) else e
            mask(i, end); end
          }
        }
      }
    }

    // ---------------------------------------------------------------- queries

    private def onSeparator(i: Int): Int =
      t.ch(i) match {
        case '?' => onQuery(i + 1, matrix = false)
        case '&' =>
          val entity = HtmlQuerySeparators.find(e => i + e.length <= n && text(i, i + e.length).equalsIgnoreCase(e))
          onQuery(i + entity.fold(1)(_.length), matrix = false)
        case '#' =>
          if (i > 0 && !Character.isWhitespace(t.ch(i - 1)) && "\"'`<>()[]{},;".indexOf(t.ch(i - 1).toInt) < 0)
            onQuery(i + 1, matrix = false)
          else i + 1
        case _ =>
          val m = "jsessionid="
          if (i + 1 + m.length <= n && text(i + 1, i + 1 + m.length).equalsIgnoreCase(m)) onQuery(i + 1, matrix = true)
          else i + 1
      }

    /** A query parameter whose key starts at `ks`; the index to go on from. */
    private def onQuery(ks: Int, matrix: Boolean): Int = {
      var k = ks
      while (
        k < n && t.stringEnd(k) < 0 && !placeholderAt(k) && {
          val c = t.ch(k)
          !(c == '=' || (c == '&' && !t.escaped(k)) || c == '?' || c == '#' || c == ';' || c == '"' || c == '\'' ||
            isSpace(c))
        }
      ) k += 1
      if (k >= n || k == ks || t.ch(k) != '=') ks
      else {
        val key = text(ks, k)
        val v   = k + 1
        if (!(matrix || Redaction.isSensitiveQueryParam(key))) v
        else if (v < n && t.stringEnd(v) >= 0 && !gluedString(v)) stringValue(v, key, field = false)
        else {
          val e = queryValueEnd(v, matrix)
          if (e > v && !placeholderOnly(v, e) && !isProse(key, v, e)) mask(v, e)
          e
        }
      }
    }

    /**
     * Where a query value from `v` ends: at `&`, whitespace, `#`, a `,` before JSON or, for `;jsessionid`, at `?`, `;`
     * or `/`. An `&` that stands for an escape is the value's after a quote, and any `&` after the escape of a quote:
     * a serialiser that escapes quotes escapes `&` too, so a quoted credential holding one (`password='p&ss'`) reads
     * so (#1676).
     */
    private def queryValueEnd(v: Int, matrix: Boolean): Int = {
      var e         = v
      var quoted    = false
      var escQuoted = false
      var done      = false
      while (!done && e < n) {
        val se = t.stringEnd(e)
        if (se >= 0) { quoted = true; e = se }
        else {
          val c = t.ch(e)
          if (isSpace(c) || c == '#') done = true
          else if (c == '&') {
            if (escQuoted || (quoted && t.escaped(e)) || !parameterAfter(e)) e += 1 else done = true
          } else if (matrix && (c == '?' || c == ';' || c == '/')) done = true
          else if (c == ',' && e + 1 < n && "{[\"'".indexOf(t.ch(e + 1).toInt) >= 0) done = true
          else {
            if (c == '"' || c == '\'') { quoted = true; if (t.escaped(e)) escQuoted = true }
            e += 1
          }
        }
      }
      e
    }

    // ---------------------------------------------------------------- URLs

    private def schemeChar(c: Char): Boolean = isAlnum(c) || c == '+' || c == '.' || c == '-'

    private def onColon(i: Int): Int =
      if (i > 0 && schemeChar(t.ch(i - 1)) && i + 2 < n && t.ch(i + 1) == '/' && t.ch(i + 2) == '/') authority(i + 3)
      else i + 1

    /**
     * The authority of a URL from `start`: the password of its userinfo, from its first `:` to its last `@`, is
     * replaced (#1816). It runs to whitespace, a string, or one of `/?#\"<>[]{}|^` and backtick, or to the scheme of
     * another URL (`redis://u:p1@h1,redis://u:p2@h2`); a `,` or `;` starts another item of a list, read the same way
     * without a scheme, where it follows a password and an `@` follows it, or where it follows a host and its port.
     * With `truncated`, an authority open at the end of the input has all after its `:` replaced, as its `@` may lie
     * past the cut. The index after it.
     */
    private def authority(start: Int): Int = {
      // A password may hold `"`, `'`, `\\`, brackets and braces that no URL should hold unencoded but a log line does:
      // they do not end the authority; its `@` does, or a character no URL holds before one.
      def endsAuthority(c: Char): Boolean = Character.isWhitespace(c) || "/?#<>|^`".indexOf(c.toInt) >= 0
      def schemeAfter(j: Int): Boolean    = j + 2 < n && t.ch(j + 1) == '/' && t.ch(j + 2) == '/'
      var j                               = start
      var end                             = -1
      var colon                           = -1
      var lastAt                          = -1
      var hostDot                         = false
      var portOnly                        = true
      var pending                         = -1
      var nextColon                       = -1
      var nextHostDot                     = false
      var nextPortOnly                    = true
      var run                             = start
      var maskedTo                        = -1
      def emit(): Unit =
        if (colon >= 0 && lastAt > colon + 1) { mask(colon + 1, lastAt); maskedTo = lastAt + 1 }
      def startItem(firstColon: Int, dot: Boolean, port: Boolean): Unit = {
        colon = firstColon; lastAt = -1; hostDot = dot; portOnly = port; pending = -1
      }
      var hashAt = -1
      while (j < n && end < 0) {
        val c = t.ch(j)
        // a `#` after the userinfo's `:` and before its `@` is the password's, written unencoded in a log line
        if (c == '#' && colon >= 0 && lastAt < 0 && pending < 0) { if (hashAt < 0) hashAt = j; j += 1 }
        else if (endsAuthority(c) || t.stringEnd(j) >= 0) end = j
        else {
          if (c == ':' && j > run && schemeAfter(j)) end = run
          else {
            if (c == '@') {
              if (pending >= 0) { emit(); startItem(nextColon, nextHostDot, nextPortOnly) }
              lastAt = j
            } else if (c == ':') {
              if (pending >= 0) { if (nextColon < 0) nextColon = j }
              else if (colon < 0) colon = j
            } else if ((c == ',' || c == ';') && pending < 0) {
              if (colon >= 0 && lastAt > colon + 1) {
                pending = j; nextColon = -1; nextHostDot = false; nextPortOnly = true
              } else if (colon >= 0 && lastAt < 0 && hostDot && portOnly && j > colon + 1) startItem(-1, false, true)
            }
            if (pending >= 0) {
              if (c == '.' && nextColon < 0) nextHostDot = true
              if (nextColon >= 0 && j > nextColon && !c.isDigit) nextPortOnly = false
            } else {
              if (c == '.' && colon < 0) hostDot = true
              if (colon >= 0 && j > colon && !c.isDigit) portOnly = false
            }
            if (!schemeChar(c)) run = j + 1
          }
          if (end < 0) j += 1
        }
      }
      if (end < 0) end = n
      if (hashAt >= 0 && lastAt < hashAt) end = hashAt // no `@` after it: the `#` started the fragment after all
      emit()
      if (truncated && atInputEnd && end == n && colon >= 0 && lastAt < colon && pending < 0) { mask(colon + 1, n); n }
      else math.max(start, maskedTo) // the host and what follows are scanned as any text is
    }

    // ---------------------------------------------------------------- vendor keys

    /** The end of the run of `cls`'s characters from `p`, cached so that each character is read once per call site. */
    final private class Run(cls: Char => Boolean) {
      private var from = -1
      private var end  = -1
      def apply(p: Int): Int =
        if (from >= 0 && p >= from && p <= end) end
        else {
          var e = p
          while (e < n && cls(t.ch(e))) e += 1
          from = p
          end = e
          e
        }
    }

    private def alnumAscii(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')

    private lazy val skRun      = new Run(alnumAscii)
    private lazy val projRun    = new Run(alnumAscii)
    private lazy val antRun     = new Run(c => alnumAscii(c) || c == '-')
    private lazy val lfRun      = new Run(alnumAscii)
    private lazy val pkLfRun    = new Run(alnumAscii)
    private lazy val paRun      = new Run(alnumAscii)
    private lazy val googleRun  = new Run(c => alnumAscii(c) || c == '-' || c == '_')
    private lazy val awsRun     = new Run(c => (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
    private lazy val jwtHeader  = new Run(c => alnumAscii(c) || c == '-' || c == '_')
    private lazy val jwtPayload = new Run(c => alnumAscii(c) || c == '-' || c == '_')
    private lazy val jwtSig     = new Run(c => alnumAscii(c) || c == '-' || c == '_')

    private def startsWith(p: Int, s: String): Boolean =
      p + s.length <= n && {
        var k = 0
        while (k < s.length && t.ch(p + k) == s.charAt(k)) k += 1
        k == s.length
      }

    /**
     * The end of a vendor key that starts at `p`, or -1: the shapes of [[SecretPatterns.SecretType.default]] - OpenAI
     * (`sk-`, `sk-proj-`), Anthropic (`sk-ant-`), Google (`AIza`), Voyage (`pa-`), Langfuse (`pk-lf-`, `sk-lf-`), an
     * AWS access key id (`AKIA`) and a JWT - matched from cached run ends.
     */
    private def vendorAt(p: Int): Int =
      t.ch(p) match {
        case 's' =>
          if (startsWith(p, "sk-ant-") && antRun(p + 7) - (p + 7) >= 20) antRun(p + 7)
          else if (startsWith(p, "sk-proj-") && projRun(p + 8) - (p + 8) >= 20) projRun(p + 8)
          else if (startsWith(p, "sk-lf-") && lfRun(p + 6) - (p + 6) >= 10) lfRun(p + 6)
          else if (startsWith(p, "sk-") && skRun(p + 3) - (p + 3) >= 20) skRun(p + 3)
          else -1
        case 'p' =>
          if (startsWith(p, "pa-") && paRun(p + 3) - (p + 3) >= 20) paRun(p + 3)
          else if (startsWith(p, "pk-lf-") && pkLfRun(p + 6) - (p + 6) >= 10) pkLfRun(p + 6)
          else -1
        case 'A' =>
          if (startsWith(p, "AIza") && googleRun(p + 4) - (p + 4) >= 35) googleRun(p + 4)
          else if (startsWith(p, "AKIA") && awsRun(p + 4) - (p + 4) >= 16) p + 20
          else -1
        case 'e' if startsWith(p, "eyJ") =>
          val a = jwtHeader(p + 3)
          if (a > p + 3 && a < n && t.ch(a) == '.' && startsWith(a + 1, "eyJ")) {
            val b = jwtPayload(a + 4)
            if (b > a + 4 && b < n && t.ch(b) == '.') {
              val c = jwtSig(b + 1)
              if (c > b + 1) c else -1
            } else -1
          } else -1
        case _ => -1
      }

    /** The word `i until j` checked for a vendor key at each position; the index to go on from. */
    private def vendorScan(i: Int, j: Int): Int = {
      var p = i
      while (p < j) {
        val c = t.ch(p)
        if (c == 's' || c == 'p' || c == 'A' || c == 'e') {
          val e = vendorAt(p)
          if (e > p) { mask(p, e); return e }
        }
        p += 1
      }
      j
    }
  }
}
