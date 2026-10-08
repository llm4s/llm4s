package org.llm4s.util

import java.util.Locale

import scala.annotation.{ tailrec, unused }
import scala.util.matching.Regex

/**
 * Utilities for redacting sensitive information from strings and log messages.
 *
 * Provides both simple masking (for toString representations) and pattern-based
 * redaction (for log messages that may contain API keys, auth headers, etc.).
 *
 * Pattern-based redaction automatically detects and masks:
 *  - API keys (OpenAI, Anthropic, Google, Voyage, Langfuse)
 *  - Bearer tokens and Authorization headers
 *  - URL query parameters with sensitive keys
 *  - Sensitive fields, whatever the shape: JSON (also inside a string, single-quoted, with a number value, or with
 *    an array or object value, whose every leaf - string, number or bare word - is replaced), `key=value` pairs and
 *    `x-api-key: value` header lines (api_key, password, token, client_secret, etc.)
 *
 * @example
 * {{{
 * import org.llm4s.util.Redaction
 *
 * // Simple masking for toString
 * Redaction.secret("sk-abc123") // "***"
 *
 * // Pattern-based redaction for log messages
 * Redaction.redact("Authorization: Bearer sk-abc123...")
 * // "Authorization: [REDACTED]"
 *
 * // Redact and truncate for logging
 * Redaction.redactForLogging(longResponseBody)
 * }}}
 */
private[llm4s] object Redaction {

  /**
   * Default redaction placeholder.
   */
  val RedactionPlaceholder: String = "[REDACTED]"

  // ============================================================
  // Simple masking (for toString representations)
  // ============================================================

  def secret(@unused value: String): String = "***"

  def secretOpt(value: Option[String]): String =
    value match {
      case Some(_) => "Some(***)"
      case None    => "None"
    }

  /**
   * Truncates a string for safe logging to prevent PII leaks and log flooding.
   *
   * @param body The string to potentially truncate
   * @param maxLength Maximum length before truncation (default: 2048)
   * @return The original string if within limit, otherwise truncated with metadata
   */
  def truncateForLog(body: String, maxLength: Int = 2048): String =
    if (body.length <= maxLength) body
    else body.take(maxLength) + s"... (truncated, original length: ${body.length})"

  // ============================================================
  // Pattern-based redaction (for log messages)
  // ============================================================

  /**
   * Patterns for sensitive URL query parameters.
   */
  private val SensitiveQueryParams: Set[String] = Set(
    "key",
    "api_key",
    "apikey",
    "api-key",
    "secret",
    "token",
    "access_token",
    "password",
    "passwd",
    "auth",
    "authorization",
    "credential",
    "credentials",
    "private_key",
    "secret_key"
  )

  private val QueryParamPattern: Regex = """([?&])([^=]+)=([^&\s]*)""".r

  /**
   * Names that mark a key as holding a credential, compared after lower-casing and dropping `_` and `-`, so `api_key`,
   * `apiKey` and `x-api-key` are all `apikey`.
   *
   * A key is sensitive when it is one of these words or ends with one of the suffixes, which covers compound names such
   * as `client_secret`, `refresh_token` and `db_password`. The match is on the whole normalised key, never on a
   * substring: `max_tokens`, `prompt_tokens`, `token_count` and `next_page_token` are not credentials and appear in
   * every provider exchange, so redacting them would destroy the logs.
   */
  private val SensitiveKeyWords: Set[String] = Set("token", "authorization", "credential", "credentials")

  private val SensitiveKeySuffixes: Seq[String] = Seq(
    "apikey",
    "secret",
    "secretkey",
    "password",
    "passwd",
    "privatekey",
    "accesstoken",
    "refreshtoken",
    "idtoken",
    "authtoken",
    "sessiontoken",
    "bearertoken"
  )

  /** The bare words a container under a sensitive key keeps: JSON's literals, and Python's, since a dict is a container too. */
  private val KeptLiterals: Set[String] = Set("true", "false", "null", "True", "False", "None")

  private def isSensitiveKey(key: String): Boolean = {
    val normalised = key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit)
    SensitiveKeyWords.contains(normalised) || SensitiveKeySuffixes.exists(normalised.endsWith)
  }

  // A key is a letter followed by at most 63 letters, digits, `_` or `-`. The bound keeps matching linear on long
  // runs of word characters, which a model reply or a base64 body can contain.
  private val Key: String = """[A-Za-z][A-Za-z0-9_-]{0,63}"""

  // Each pattern captures: 1 = text up to the value, 2 = the key, 3 = the value.
  //
  // The quoted shapes only match up to the opening quote of the value: `scanQuotedValue` finds the end of the value
  // with a loop. java.util.regex recurses once per iteration of a repeated group, even when the body is a single
  // alternation such as `(?:[^"\\]|\\.)*`, and a string value of a few thousand characters (a prompt, a file's
  // contents) overflows the stack. No pattern below repeats a group: each is built from character classes with
  // bounded or possessive repetition, which are matched iteratively.

  /** `"key": "` : the start of a JSON string value. */
  private val JsonStringStart: Regex = s"""("($Key)"\\s*:\\s*")""".r

  /** `\\"key\\": \\"` : the start of a string value of JSON that sits inside a string, as a prompt or a response body carries it. */
  private val EscapedJsonStringStart: Regex = s"""(\\\\"($Key)\\\\"\\s*:\\s*\\\\")""".r

  /** `'key': '` : the start of a single-quoted string value. */
  private val SingleQuotedStart: Regex = s"""('($Key)'\\s*:\\s*')""".r

  /** `"key": 12345`: a number is a credential too when the key says so (a numeric PIN or passcode). */
  private val JsonNumberField: Regex =
    s"""("($Key)"\\s*:\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?![\\w.-])""".r

  /** `\"key\": 12345`: the same number in JSON that sits inside a string. */
  private val EscapedJsonNumberField: Regex =
    s"""(\\\\"($Key)\\\\"\\s*:\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?![\\w.-])""".r

  /** `"key": [` or `"key": {`: the start of an array or object value, with the opening bracket as group 3. */
  private val JsonContainerStart: Regex = s"""("($Key)"\\s*:\\s*)([\\[{])""".r

  /** `\"key\": [` or `\"key\": {`: the start of an array or object value of JSON that sits inside a string. */
  private val EscapedJsonContainerStart: Regex = s"""(\\\\"($Key)\\\\"\\s*:\\s*)([\\[{])""".r

  /** `'key': [` or `'key': {`: the start of an array or object value under a single-quoted key. */
  private val SingleQuotedContainerStart: Regex = s"""('($Key)'\\s*:\\s*)([\\[{])""".r

  /** `key=value` outside a URL query string: form bodies, log lines, shell-style settings, `a.b.password=...`. */
  private val EqualsPair: Regex =
    s"""((?<![A-Za-z0-9_-])($Key)=)([^\\s&"',;<>]+)""".r

  private val DoubleQuotedEqualsStart: Regex = s"""((?<![A-Za-z0-9_-])($Key)=")""".r
  private val SingleQuotedEqualsStart: Regex = s"""((?<![A-Za-z0-9_-])($Key)=')""".r

  /** A header-style line, `x-api-key: value`, at the start of a line. */
  private val HeaderLine: Regex =
    s"""(?m)(^($Key):[ \\t]*)(\\S[^\\r\\n]*)""".r

  /**
   * Redact sensitive information from a string.
   *
   * Applies multiple redaction strategies:
   * 1. Authorization headers
   * 2. Sensitive URL query parameters
   * 3. Sensitive JSON fields
   * 4. Known API key patterns
   *
   * @param input The input string potentially containing sensitive data
   * @param placeholder The placeholder to use for redacted content
   * @return The input with sensitive data redacted
   */
  def redact(input: String, placeholder: String = RedactionPlaceholder): String =
    if (input == null || input.isEmpty) {
      input
    } else {
      val step1 = redactAuthHeaders(input, placeholder)
      val step2 = redactQueryParams(step1, placeholder)
      val step3 = redactJsonFields(step2, placeholder)
      val step4 = redactApiKeys(step3, placeholder)
      redactContainerLeaves(step4, placeholder)
    }

  /**
   * Redact sensitive data and truncate for logging.
   *
   * Applies pattern-based redaction first, then truncates if needed.
   *
   * @param input The input to redact
   * @param maxLength Maximum length of the output (0 = no limit)
   * @param placeholder Redaction placeholder
   * @return Redacted and potentially truncated string
   */
  def redactForLogging(
    input: String,
    maxLength: Int = 1000,
    placeholder: String = RedactionPlaceholder
  ): String = {
    val redacted = redact(input, placeholder)

    if (maxLength > 0 && redacted.length > maxLength) {
      redacted.take(maxLength) + s"... [truncated, ${redacted.length - maxLength} chars omitted]"
    } else {
      redacted
    }
  }

  /**
   * Create a safe string representation for logging.
   *
   * Designed for use with SLF4J-style logging:
   * {{{
   * logger.debug("Request body: {}", Redaction.safe(requestBody))
   * }}}
   *
   * @param value The value to make safe for logging
   * @return A safe string representation
   */
  def safe(value: Any): String =
    value match {
      case null      => "null"
      case s: String => redactForLogging(s)
      case other     => redactForLogging(other.toString)
    }

  // ============================================================
  // Private redaction helpers
  // ============================================================

  private def redactAuthHeaders(input: String, placeholder: String): String = {
    // Handle "Authorization": "..." in JSON
    val step1 = """(?i)("Authorization"\s*:\s*")([^"]+)(")""".r
      .replaceAllIn(input, m => s"${m.group(1)}$placeholder${m.group(3)}")
    // Handle Authorization: ... in headers
    val step2 = """(?i)(Authorization:\s*)([^\n\r]+)""".r
      .replaceAllIn(step1, m => s"${m.group(1)}$placeholder")
    // Handle standalone Bearer tokens
    val step3 = """(?i)\bBearer\s+([a-zA-Z0-9\-_\.]+)""".r.replaceAllIn(step2, placeholder)
    // Handle standalone Basic auth tokens
    """(?i)\bBasic\s+([a-zA-Z0-9+/=]+)""".r.replaceAllIn(step3, placeholder)
  }

  private def redactQueryParams(input: String, placeholder: String): String =
    QueryParamPattern.replaceAllIn(
      input,
      m => {
        val separator = m.group(1)
        val key       = m.group(2)

        if (SensitiveQueryParams.exists(s => key.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT)))) {
          s"$separator$key=$placeholder"
        } else {
          m.matched
        }
      }
    )

  private def redactJsonFields(input: String, placeholder: String): String = {
    // Arrays and objects first: the strings and numbers under a sensitive key are replaced in one pass, and what is
    // left for the field patterns below is already the placeholder. Then the quoted shapes, then the bare ones, so
    // that a value is never matched by a looser pattern first. The rest of each container - its other leaves, and
    // the containers under a single-quoted key - waits for `redactContainerLeaves`, after every field pass.
    val escapedContainers =
      redactContainers(EscapedJsonContainerStart, input, placeholder, ValueEnd.EscapedQuote, leaves = false)
    val containers =
      redactContainers(JsonContainerStart, escapedContainers, placeholder, ValueEnd.Quote('"'), leaves = false)
    val escaped = redactQuoted(EscapedJsonStringStart, containers, placeholder, ValueEnd.EscapedQuote)
    val double  = redactQuoted(JsonStringStart, escaped, placeholder, ValueEnd.Quote('"'))
    val single  = redactQuoted(SingleQuotedStart, double, placeholder, ValueEnd.Quote('\''))
    // A number becomes a string, so that the redacted JSON still parses.
    val quotedEquals   = redactQuoted(DoubleQuotedEqualsStart, single, placeholder, ValueEnd.Quote('"'))
    val allQuoted      = redactQuoted(SingleQuotedEqualsStart, quotedEquals, placeholder, ValueEnd.Quote('\''))
    val escapedNumbers = redactPairs(EscapedJsonNumberField, allQuoted, placeholder, wrap = "\\\"")
    val numbers        = redactPairs(JsonNumberField, escapedNumbers, placeholder, wrap = "\"")
    Seq(EqualsPair, HeaderLine).foldLeft(numbers)((acc, pattern) => redactPairs(pattern, acc, placeholder))
  }

  /**
   * Every leaf of a container under a sensitive key that the passes before left: a string in the other quote than
   * the key's, a bare word, and the whole of a container under a single-quoted key (`{'token': ['...']}`, a Python
   * dict in a prompt). It runs last, after the field passes and the API-key patterns, because what it takes for a
   * leaf is a guess - a `'` may be an apostrophe of prose, and a container that a message merely mentions runs on
   * into the text after it - and a guess made before them could swallow the key of a field they would have redacted
   * and leave its value readable. After them it can only replace more.
   */
  private def redactContainerLeaves(input: String, placeholder: String): String = {
    val escaped = redactContainers(EscapedJsonContainerStart, input, placeholder, ValueEnd.EscapedQuote, leaves = true)
    val double  = redactContainers(JsonContainerStart, escaped, placeholder, ValueEnd.Quote('"'), leaves = true)
    redactContainers(SingleQuotedContainerStart, double, placeholder, ValueEnd.Quote('\''), leaves = true)
  }

  /** How a quoted value ends. */
  private enum ValueEnd {

    /** An unescaped `quote`; a backslash escapes the next character. */
    case Quote(quote: Char)

    /**
     * An unescaped `quote` or a bare `"`: a single-quoted leaf inside a double-quoted string, which ends at its own
     * quote or where the enclosing string does. A backslash escapes the next character, so `\"` is content.
     */
    case QuoteInString(quote: Char)

    /** A backslash followed by `"`, the closing quote of a string inside a string. */
    case EscapedQuote
  }

  /** The text that opens and closes a string ending with `end`: `"`, `'` or `\"`. */
  private def quoteOf(end: ValueEnd): String =
    end match {
      case ValueEnd.EscapedQuote     => "\\\""
      case ValueEnd.Quote(q)         => q.toString
      case ValueEnd.QuoteInString(q) => q.toString
    }

  /**
   * The index of the character that ends the value starting at `from`: the closing quote, or, for a string inside a
   * string, the backslash of the closing `\"` or a bare quote (which ends the enclosing string). A value that is not
   * closed runs to the end of the input, so a payload cut off in the middle of a credential still has it redacted. A
   * loop, not a pattern, so the cost is linear and the stack does not grow with the length of the value.
   */
  @tailrec
  private def scanQuotedValue(input: String, from: Int, end: ValueEnd): Int =
    if (from >= input.length) {
      input.length
    } else {
      val c = input.charAt(from)
      end match {
        case ValueEnd.Quote(quote) =>
          if (c == quote) from
          else scanQuotedValue(input, if (c == '\\') from + 2 else from + 1, end)
        case ValueEnd.QuoteInString(quote) =>
          if (c == quote || c == '"') from
          else scanQuotedValue(input, if (c == '\\') from + 2 else from + 1, end)
        case ValueEnd.EscapedQuote =>
          if (c == '"') from
          else if (c == '\\') {
            var next = from
            while (next < input.length && input.charAt(next) == '\\') next += 1
            val slashes = next - from
            if (next < input.length && input.charAt(next) == '"' && slashes % 4 == 1) next - 1
            else if (next < input.length && input.charAt(next) == '"' && slashes % 2 == 0) next
            else scanQuotedValue(input, next + 1, end)
          } else scanQuotedValue(input, from + 1, end)
      }
    }

  /**
   * Replaces the value of every `"key": "value"` whose key is sensitive. `start` matches up to the opening quote; the
   * value runs to the end found by `scanQuotedValue`, and the closing quote is left in place. An empty value is left as it is.
   */
  private def redactQuoted(start: Regex, input: String, placeholder: String, end: ValueEnd): String = {
    val matcher = start.pattern.matcher(input)
    val out     = new java.lang.StringBuilder(input.length)

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else {
        val valueStart = matcher.end
        val valueEnd   = scanQuotedValue(input, valueStart, end)
        if (isSensitiveKey(matcher.group(2)) && valueEnd > valueStart) {
          out.append(input, copiedTo, valueStart).append(placeholder)
          loop(valueEnd, valueEnd)
        } else {
          loop(valueEnd, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.append(input, copiedTo, input.length).toString
  }

  /**
   * The string, if any, that encloses a position of the input, from one forward scan: an unescaped `"` or `'`
   * outside a string opens one, the same unescaped quote closes it, a backslash escapes the character after it, and
   * the other quote inside a string is an ordinary character, as `'` in `"it's"` and `"` in `'say "hi"'` are. A `'`
   * between two letters or digits is an apostrophe, never a quote, so `User's config: {...}` opens no string.
   *
   * `enclosingAt` takes its positions in increasing order, as `redactContainers` finds its matches, and the scan
   * reads each character once, so the cost is linear in the input however many containers it has.
   */
  final private class EnclosingQuotes(input: String) {
    private var pos: Int   = 0
    private var open: Char = EnclosingQuotes.NoQuote

    /** The quote of the string that is open just before `index`, or `NoQuote`. */
    def enclosingAt(index: Int): Char = {
      while (pos < index) {
        val c = input.charAt(pos)
        if (c == '\\') pos += 1
        else if (open != EnclosingQuotes.NoQuote) {
          if (c == open && !isApostrophe(pos)) open = EnclosingQuotes.NoQuote
        } else if ((c == '"' || c == '\'') && !isApostrophe(pos)) {
          open = c
        }
        pos += 1
      }
      open
    }

    private def isApostrophe(i: Int): Boolean =
      input.charAt(i) == '\'' && i > 0 && i + 1 < input.length &&
        input.charAt(i - 1).isLetterOrDigit && input.charAt(i + 1).isLetterOrDigit
  }

  private object EnclosingQuotes {
    val NoQuote: Char = '\u0000'
  }

  /**
   * What a container walk takes a quote for. A leaf opens on the key's own quote, and on the other quote only where
   * that quote cannot be the end of a string enclosing the container: taking the end of the enclosing string for a
   * leaf opener desynchronises the quotes, and the walk runs on into the fields after the string. `EnclosingQuotes`
   * decides which walk from the quotes before the container, and a stray quote before it (an unpaired `"` in a log
   * prefix) can mislead it; that the `Plain` and `InSingleQuotes` walks replace every bare word they pass, not only
   * the quoted leaves and numbers, is what keeps a credential unreadable even then. The `InDoubleQuotes` walk cannot
   * pass the `"` that ends its string, so it keeps prose.
   */
  private enum Walk {

    /** No enclosing string: `"` and `'` each open a leaf, scanned to its own quote. */
    case Plain

    /**
     * Inside a double-quoted string: `'` opens a leaf, which its own quote or a bare `"` ends; a bare `"` ends the
     * enclosing string, and the container with it. `\"` opens a leaf under an escaped key, whose own quote it is,
     * and ends the container under a single-quoted key, whose syntax has no `"` at all - it may be a double-quoted
     * leaf or the end of a string inside the string, and a walk cannot tell which.
     */
    case InDoubleQuotes

    /**
     * Inside a single-quoted string: only `"` opens a leaf, and `'` is an ordinary character, as it was before
     * #1647. A `'` may be the end of the enclosing string, but an apostrophe in prose makes a `'` too uncertain to
     * end the container on.
     */
    case InSingleQuotes
  }

  /**
   * Replaces the leaves of each `"key": [...]` or `"key": {...}` whose key is sensitive, and leaves the brackets, the
   * keys of nested objects, `true`, `false` and `null`, so that the redacted JSON still parses and keeps its shape.
   * `start` matches up to and including the opening bracket (group 3). A container whose key is not sensitive is
   * entered, not skipped, so a credential inside it is still found.
   *
   * With `leaves = false` this is the walk that runs before the field passes, as it always has: a `'` is an ordinary
   * character, and only the strings in the key's quote and the numbers are replaced. The field passes after it read
   * the quotes it leaves, so it may not take a `'` of prose for a leaf (and swallow the key of the next
   * `password='...'`), nor write a quote where there was none. With `leaves = true` it is the walk that runs after
   * every other pass, when nothing is left to read its output: every leaf is replaced, whatever its quote, and so is
   * every bare word outside a double-quoted string. The string that encloses a `"key"` or `'key'` match, if any,
   * picks that walk; an escaped key is inside a double-quoted string by construction.
   */
  private def redactContainers(
    start: Regex,
    input: String,
    placeholder: String,
    end: ValueEnd,
    leaves: Boolean
  ): String = {
    val matcher   = start.pattern.matcher(input)
    val out       = new java.lang.StringBuilder(input.length)
    val enclosing = new EnclosingQuotes(input)

    def walkAt(keyStart: Int): Walk =
      end match {
        case ValueEnd.Quote(keyQuote) =>
          enclosing.enclosingAt(keyStart) match {
            case '"' if keyQuote == '\'' => Walk.InDoubleQuotes
            case '\'' if keyQuote == '"' => Walk.InSingleQuotes
            case _                       => Walk.Plain // no string, or the key's own quote, which closes one
          }
        case ValueEnd.EscapedQuote | ValueEnd.QuoteInString(_) => Walk.InDoubleQuotes
      }

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else {
        val open = matcher.start(3)
        if (isSensitiveKey(matcher.group(2))) {
          out.append(input, copiedTo, open)
          val valueEnd = redactLeaves(input, open, out, placeholder, end, walkAt(matcher.start(1)), leaves)
          loop(valueEnd, valueEnd)
        } else {
          loop(open + 1, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.append(input, copiedTo, input.length).toString
  }

  /**
   * Appends to `out` the array or object that opens at `from`, with its leaves replaced, and returns the index just
   * after it. Brackets are counted on an explicit stack, so the depth of the value does not grow the call stack, and
   * each character is visited a bounded number of times. The value ends at its closing bracket, at the end of the
   * input (a payload cut off in the middle of it) or, inside a string, at the `"` that ends the enclosing string,
   * which is left for the caller.
   *
   * Which quotes open a leaf, and which end the container, is `walk`'s (see [[Walk]]); without `leaves` the walk is
   * the one before #1647 (see [[redactContainers]]). A bare leaf is written back as the placeholder in the key's
   * quote, `end`: a number, a word with a digit or `-` in it, and, with `leaves` and outside a double-quoted string,
   * any other word, bar the literals of `KeptLiterals` and a word before `:`.
   */
  private def redactLeaves(
    input: String,
    from: Int,
    out: java.lang.StringBuilder,
    placeholder: String,
    end: ValueEnd,
    walk: Walk,
    leaves: Boolean
  ): Int = {
    val length         = input.length
    val quote          = quoteOf(end)
    val inDoubleQuotes = walk == Walk.InDoubleQuotes
    val openers        = new java.lang.StringBuilder

    def inObject: Boolean = openers.length > 0 && openers.charAt(openers.length - 1) == '{'

    def isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    // A string of an object that is followed by `:` is a key, not a value.
    def isKey(after: Int): Boolean = {
      var i = after
      while (i < length && isSpace(input.charAt(i))) i += 1
      inObject && i < length && input.charAt(i) == ':'
    }

    // Whether a `:` follows `after`, past whitespace and the quotes and backslashes that close a key: what ends there
    // is a key (`user` of `{user: ...}`, or `'token'` of `{'token': ...}`), not a value. The run skipped is kept, so
    // that a long run of quotes, each of which asks, is read once.
    var skippedFrom = -1
    var skippedTo   = -1
    def keyFollows(after: Int): Boolean = {
      if (after < skippedFrom || after > skippedTo) {
        var j = after
        while (j < length && (isSpace(input.charAt(j)) || "\"'\\".indexOf(input.charAt(j).toInt) >= 0)) j += 1
        skippedFrom = after
        skippedTo = j
      }
      skippedTo < length && input.charAt(skippedTo) == ':'
    }

    // The string whose opening quote (`"`, `'` or `\"`, as `stringEnd` says) starts at `open`; returns the index
    // after it.
    def emitString(open: Int, stringEnd: ValueEnd): Int = {
      val stringQuote  = quoteOf(stringEnd)
      val contentStart = open + stringQuote.length
      val contentEnd   = scanQuotedValue(input, contentStart, stringEnd)
      // Inside a string, `scanQuotedValue` may stop at a bare quote: the enclosing string ends there.
      val closed = contentEnd < length && (stringEnd match {
        case ValueEnd.Quote(_)         => true
        case ValueEnd.QuoteInString(q) => input.charAt(contentEnd) == q
        case ValueEnd.EscapedQuote     => input.charAt(contentEnd) == '\\'
      })
      val next = if (closed) contentEnd + stringQuote.length else contentEnd
      if (closed && isKey(next)) {
        out.append(input, open, next)
      } else {
        out.append(stringQuote)
        if (contentEnd > contentStart) out.append(placeholder)
        if (closed) out.append(stringQuote)
      }
      next
    }

    // A string in the other quote than the key's (`'abc'` under `"token"`, `"abc"` under `'token'`), or in the key's
    // own `'`: a key before `:` is kept whole, and a string that cannot be a value - prose, whose `'` is an apostrophe
    // run on by a letter, or text with a `:` or `=`, which is no leaf but a field the passes before have seen to - is
    // not taken for one: its quote is an ordinary character and the walk goes on inside it.
    def emitOtherString(open: Int, stringEnd: ValueEnd): Int = {
      val contentStart = open + 1
      val contentEnd   = scanQuotedValue(input, contentStart, stringEnd)
      val closed = contentEnd < length && (stringEnd match {
        case ValueEnd.QuoteInString(q) => input.charAt(contentEnd) == q
        case _                         => true
      })
      val next = if (closed) contentEnd + 1 else contentEnd
      if (closed && keyFollows(next)) {
        out.append(input, open, next)
        next
      } else if (
        (closed && inDoubleQuotes && next < length && input.charAt(next).isLetterOrDigit) ||
        !isLeafText(input, contentStart, contentEnd)
      ) {
        out.append(input.charAt(open))
        open + 1
      } else {
        out.append(input.charAt(open))
        if (contentEnd > contentStart) out.append(placeholder)
        if (closed) out.append(input.charAt(contentEnd))
        next
      }
    }

    // What ends a bare leaf - a number, or a value that is not quoted - and separates leaves: whitespace, `,`, `:`,
    // a bracket, a quote and a backslash.
    def separates(c: Char): Boolean =
      isSpace(c) || c == ',' || c == ':' || c == '[' || c == ']' || c == '{' || c == '}' || c == '"' ||
        c == '\'' || c == '\\'

    // A word as the walk before #1647 wrote it: every character as it is, bar a number, which is replaced.
    def copyWord(start: Int, stop: Int): Unit = {
      var j = start
      while (j < stop) {
        val c = input.charAt(j)
        if (c == '-' || (c >= '0' && c <= '9')) {
          j += 1
          while (j < stop && isNumberChar(input.charAt(j))) j += 1
          out.append(quote).append(placeholder).append(quote)
        } else {
          out.append(c)
          j += 1
        }
      }
    }

    var i    = from
    var done = false
    while (i < length && !done) {
      val c = input.charAt(i)
      if (leaves && placeholder.nonEmpty && c == placeholder.charAt(0) && input.startsWith(placeholder, i)) {
        // A value a pass before has replaced: kept as it is, brackets and all.
        out.append(placeholder)
        i += placeholder.length
      } else if (c == '[' || c == '{') {
        openers.append(c)
        out.append(c)
        i += 1
      } else if (c == ']' || c == '}') {
        if (openers.length > 0) openers.setLength(openers.length - 1)
        out.append(c)
        i += 1
        done = openers.length == 0
      } else if (c == '"') {
        if (inDoubleQuotes) done = true // the enclosing string ends, and the container with it
        else if (end == ValueEnd.Quote('"')) i = emitString(i, ValueEnd.Quote('"'))
        else i = emitOtherString(i, ValueEnd.Quote('"'))
      } else if (c == '\'' && leaves && walk != Walk.InSingleQuotes) {
        i = emitOtherString(i, if (inDoubleQuotes) ValueEnd.QuoteInString('\'') else ValueEnd.Quote('\''))
      } else if (c == '\\' && inDoubleQuotes) {
        var next = i
        while (next < length && input.charAt(next) == '\\') next += 1
        val slashes     = next - i
        val beforeQuote = next < length && input.charAt(next) == '"'
        if (beforeQuote && end != ValueEnd.EscapedQuote) {
          // Any `"` under a single-quoted key ends the container; the backslashes are left with it for the caller.
          done = true
        } else if (beforeQuote && slashes % 4 == 1) {
          out.append(input, i, next - 1)
          i = emitString(next - 1, ValueEnd.EscapedQuote)
        } else if (beforeQuote && slashes % 2 == 0) {
          out.append(input, i, next)
          i = next
          done = true
        } else {
          // An escape between values (`\n` of a pretty-printed document), copied with the character it escapes.
          val stop = math.min(length, next + 1)
          out.append(input, i, stop)
          i = stop
        }
      } else if (c == '\\' && leaves) {
        // A backslash escapes the character after it, as it does for the scan of a string, so that a string whose
        // quote was not taken for a leaf is walked as that scan read it, and each quote opens at most one scan.
        val stop = math.min(length, i + 2)
        out.append(input, i, stop)
        i = stop
      } else if (separates(c)) {
        out.append(c)
        i += 1
      } else {
        // A bare leaf: a number, or a word that is not quoted, as `token: [abc]` has. A word with a digit or `-` is
        // replaced whole, where the walk before #1647 replaced only its number (`abc"[REDACTED]"`); the quote it writes
        // is where that walk wrote one, a few characters on, so the passes after read the text after it as they did.
        var next   = i + 1
        var digits = c == '-' || (c >= '0' && c <= '9')
        var eq     = c == '='
        while (next < length && !separates(input.charAt(next))) {
          val d = input.charAt(next)
          if (d == '-' || (d >= '0' && d <= '9')) digits = true
          if (d == '=') eq = true
          next += 1
        }
        val number = c == '-' || (c >= '0' && c <= '9')
        val replace =
          if (!leaves) digits && !eq && !keyFollows(next)
          else number || !(inDoubleQuotes || keyFollows(next) || KeptLiterals.contains(input.substring(i, next)))
        if (replace) out.append(quote).append(placeholder).append(quote)
        else if (leaves) out.append(input, i, next)
        else copyWord(i, next)
        i = next
      }
    }
    i
  }

  private def isNumberChar(c: Char): Boolean =
    (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

  /**
   * Whether `input(from until to)`, the content of a string that the last container walk found, can be a leaf: it
   * holds no `:` and no `=`. Text with either is a field (`password='...'`, `'api_key': '...'`) that a quote of prose
   * before it runs into, not a value; the walk goes on inside it, and the prose around the field is kept.
   */
  private def isLeafText(input: String, from: Int, to: Int): Boolean = {
    var j    = from
    var leaf = true
    while (leaf && j < to) {
      val c = input.charAt(j)
      leaf = c != ':' && c != '='
      j += 1
    }
    leaf
  }

  /**
   * Replaces the value of every match whose key is sensitive. Redacting a value that is already the
   * placeholder gives the placeholder, so redacting twice gives the same result as redacting once.
   */
  private def redactPairs(
    pattern: Regex,
    input: String,
    placeholder: String,
    wrap: String = ""
  ): String =
    pattern.replaceAllIn(
      input,
      m => {
        val text =
          if (isSensitiveKey(m.group(2))) m.group(1) + wrap + placeholder + wrap
          else m.matched
        Regex.quoteReplacement(text)
      }
    )

  private def redactApiKeys(input: String, placeholder: String): String =
    // Delegate to the canonical patterns in SecretPatterns so there is a
    // single source of truth for credential regexes across the codebase.
    SecretPatterns.redactAllWithPlaceholder(input, placeholder)
}
