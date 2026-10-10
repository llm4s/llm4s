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
 *  - Bearer tokens and credential headers: Authorization, Proxy-Authorization, Cookie and Set-Cookie
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

  /**
   * A provider's response body (or any text from outside the process) made fit for a log line or an error message:
   * redacted in full, then truncated with `truncateForLog`.
   *
   * Redaction always runs on the whole text before the cut. Cutting first can leave a credential straddling the cut
   * point, or strip the closing quote or key that a redaction pattern needs, so the fragment that survives is no longer
   * recognised and is written in the clear. Use this, never `truncateForLog` alone, for any text that reaches a log, an
   * `LLMError` message, a trace or the provider exchange sink.
   *
   * It differs from [[redactForLogging]] only in its defaults and its suffix: 2048 characters, a `null`-safe input
   * and `truncateForLog`'s `(truncated, original length: N)`, the format the error messages and log lines it
   * replaced already used; `redactForLogging` keeps its 1000-character default, `0` for no limit, a caller's
   * placeholder and its `[truncated, N chars omitted]` suffix, which `safe` and the MCP payload log rely on.
   *
   * @param body The text to redact and truncate
   * @param maxLength Maximum length of the redacted text before truncation (default: 2048)
   * @return The redacted text, truncated with metadata when it is longer than `maxLength`
   */
  def safeBody(body: String, maxLength: Int = 2048): String =
    if (body == null) "null" else truncateForLog(redact(body), maxLength)

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

  /**
   * `?key=` or `&key=`, the start of a parameter of a URL or a query string; the value after it is found by
   * `queryValue`. The key is one run of the characters a query key can hold: no whitespace, quote, `?`, `&` or `=`.
   * So a `?` of prose, a question in a chat message, cannot start a key that runs across quotes, braces and lines to
   * a later `=` and take the text after that for a value (#1667). The run is possessive and cannot hold a `?` or `&`,
   * so each character is read by at most one attempt. `escapedQueryParamValues` reads the same parameters, and those
   * whose separators are JSON escapes (#1676).
   */
  private val QueryParamStart: Regex = """([?&])([^=&?\s"']++)=""".r

  /**
   * Names that mark a key as holding a credential, compared after lower-casing and dropping `_` and `-`, so `api_key`,
   * `apiKey` and `x-api-key` are all `apikey`.
   *
   * A key is sensitive when it is one of these words or ends with one of the suffixes, which covers compound names such
   * as `client_secret`, `refresh_token` and `db_password`. The match is on the whole normalised key, never on a
   * substring: `max_tokens`, `prompt_tokens`, `token_count` and `next_page_token` are not credentials and appear in
   * every provider exchange, so redacting them would destroy the logs.
   *
   * The credential-bearing HTTP headers are keys too, read by every shape the passes handle (a header line, a JSON or
   * escaped-JSON field, a header map in JSON, `key=value`): `Authorization` and `Proxy-Authorization`, which carries
   * the same `Basic`, `Bearer`, `Digest` or `Negotiate` credential for a proxy (#1686); `Cookie` and `Set-Cookie`,
   * whose whole value is replaced, since a cookie holds a session token; `X-Api-Key`, `Api-Key` and `X-Goog-Api-Key`
   * (suffix `apikey`), `X-Auth-Token` (`authtoken`) and `X-Amz-Security-Token` (`securitytoken`). `cookie` is a whole
   * word, never a suffix or a substring: `cookie_policy`, `cookie_consent` and `max_cookie_age` name settings, not
   * cookies. With `SensitiveKeySuffixes`, this is core's one list of sensitive key names for fields, header lines and
   * `key=value` pairs; URL query parameters are also matched, by substring, against `SensitiveQueryParams`. A list of
   * headers to withhold (the HTTP tool's redirect stripping) should read the same names.
   */
  private val SensitiveKeyWords: Set[String] =
    Set("token", "authorization", "proxyauthorization", "credential", "credentials", "cookie", "cookies", "setcookie")

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
    "securitytoken",
    "bearertoken"
  )

  /** The bare words a container under a sensitive key keeps: JSON's literals, and Python's, since a dict is a container too. */
  private val KeptLiterals: Set[String] = Set("true", "false", "null", "True", "False", "None")

  /**
   * Whether `key` (a field, header or parameter name) names a credential, by `SensitiveKeyWords` and
   * `SensitiveKeySuffixes`. `llm4s-agent-tools`' HTTP tool reads it to withhold such headers on a cross-origin
   * redirect even when they are allowlisted, so the two lists stay one.
   */
  private[llm4s] def isSensitiveKey(key: String): Boolean = {
    val normalised = key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit)
    SensitiveKeyWords.contains(normalised) || SensitiveKeySuffixes.exists(normalised.endsWith)
  }

  /**
   * Whether a header line's name is sensitive and not one `CookieHeaderStart` reads (`Cookie`, `Set-Cookie`): the
   * header-line field pass leaves a cookie header to `redactCookieHeaders`, so that, like every pass but that one, it
   * reads and writes the text as it would were cookies not sensitive (#1686). `Cookies:` and `Set_Cookie:`, which
   * the cookie pass does not read, are the field pass's.
   */
  private def isSensitiveHeaderNotCookie(name: String): Boolean = {
    val lower = name.toLowerCase(Locale.ROOT)
    val cookieHeader = lower.endsWith("cookie") && (lower.length == 6 || {
      val before = lower.charAt(lower.length - 7)
      !(before.isLetterOrDigit || before == '_')
    })
    isSensitiveKey(name) && !cookieHeader
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

  /** `'key': 12345`: the same number under a single-quoted key, a Python dict or a JavaScript literal. */
  private val SingleQuotedNumberField: Regex =
    s"""('($Key)'\\s*:\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?![\\w.-])""".r

  /**
   * `'key': "` : the start of a double-quoted value under a single-quoted key, as Python's `repr` writes a string
   * that holds a `'` (`{'password': "it's"}`).
   */
  private val SingleQuotedKeyStringStart: Regex = s"""('($Key)'\\s*:\\s*")""".r

  /** `'key': \"` : the same value inside a JSON string, where its quotes are escaped. */
  private val SingleQuotedKeyEscapedStringStart: Regex = s"""('($Key)'\\s*:\\s*\\\\")""".r

  /** The longest key `endsAsValue` reads after a value, as `Key` bounds the keys the patterns match. */
  private val MaxKeyLength: Int = 64

  /** `"key": [` or `"key": {`: the start of an array or object value, with the opening bracket as group 3. */
  private val JsonContainerStart: Regex = s"""("($Key)"\\s*:\\s*)([\\[{])""".r

  /** `\"key\": [` or `\"key\": {`: the start of an array or object value of JSON that sits inside a string. */
  private val EscapedJsonContainerStart: Regex = s"""(\\\\"($Key)\\\\"\\s*:\\s*)([\\[{])""".r

  /** `'key': [` or `'key': {`: the start of an array or object value under a single-quoted key. */
  private val SingleQuotedContainerStart: Regex = s"""('($Key)'\\s*:\\s*)([\\[{])""".r

  /**
   * What may come before the key of a `key=value` pair: not a letter, a digit, `_` or `-`, which would make it the end
   * of a longer word.
   */
  private val PlainKeyStart: String = """(?<![A-Za-z0-9_-])"""

  /**
   * The four hex digits of a JSON escape (backslashes, `u` and the digits) of a character a key may hold: a letter, a
   * digit, `_` or `-`.
   */
  private val KeyCharHex: String = """00(?:2[dD]|3[0-9]|4[1-9a-fA-F]|5[0-9aAfF]|6[1-9a-fA-F]|7[0-9aA])"""

  /**
   * Where the key of a `key=value` pair may start in an input that holds the JSON escape of a separator or a quote
   * (see [[redact]]): where it starts in the text the escapes stand for. That is after a character that is not a
   * letter, a digit, `_` or `-` (`PlainKeyStart`), but not at the `u` of an escape - backslashes, `u` and four hex
   * digits - that stands for such a character, or right after such an escape, which ends in a digit or a letter but
   * stands for a separator: a form body in JSON that Go's `encoding/json` wrote has every `&` escaped (#1676). Read as
   * a key, an escape takes the pair after it for its value: after the escape of `'`, the key `u0027` and the escape
   * of `=` would hold `password=...`, and no pass read the credential. The escape of a letter, a digit, `_` or
   * `-` is a character of the key, as it is in the text it stands for. `[u]` keeps the compiler from reading an
   * escape in the pattern as one.
   */
  private val EscapedKeyStart: String =
    s"""(?:$PlainKeyStart(?!(?<=\\\\)[u](?!$KeyCharHex)[0-9a-fA-F]{4})|(?<=\\\\[u][0-9a-fA-F]{4})(?<!\\\\[u]$KeyCharHex))"""

  /**
   * The `=` between the key and the value of a `key=value` pair, or its JSON escape (backslashes and `u003d`, the hex
   * digits in either case), which Gson and other HTML-safe serialisers write: `password`, the escape and a value is
   * read as `password=...` is, as the query pass reads it (#1676).
   */
  private val PairEquals: String = """(?:=|\\+[u]003[dD])"""

  /**
   * The patterns that find the start of a `key=value` pair, with the key starting where `keyStart` allows: one set
   * for an input that holds no JSON escape of a separator or a quote, read as it always was, and one for an input
   * that does (`EscapedKeyStart`).
   *
   *  - `equals`: `key=` before the value of a pair outside a URL query string: form bodies, log lines, shell-style
   *    settings, `a.b.password=...`. Group 2 is the key; the value, at least one character, is read by
   *    `redactEqualsPairs`.
   *  - `doubleQuoted`, `singleQuoted`: `key="` and `key='`.
   *  - `escapedQuote`: `key=` and the opening quote of its value, written as a JSON escape: the escaped form of
   *    `key='...'` and `key="..."`. Group 1 is the key, group 2 the backslashes of the escape, group 3 the hex digits
   *    of the quote.
   */
  final private class PairStarts(keyStart: String) {
    val equals: Regex       = s"""($keyStart($Key)$PairEquals)(?=[^\\s&"',;<>])""".r
    val doubleQuoted: Regex = s"""($keyStart($Key)$PairEquals")""".r
    val singleQuoted: Regex = s"""($keyStart($Key)$PairEquals')""".r
    val escapedQuote: Regex = s"""$keyStart($Key)$PairEquals(\\\\++)u00(2[27])""".r
  }

  private val PlainPairStarts: PairStarts   = new PairStarts(PlainKeyStart)
  private val EscapedPairStarts: PairStarts = new PairStarts(EscapedKeyStart)

  private def pairStarts(escaped: Boolean): PairStarts = if (escaped) EscapedPairStarts else PlainPairStarts

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
   * 5. Cookie and Set-Cookie headers
   *
   * The passes run in that order, each over the text the one before left. Where the input holds the JSON escape of
   * `&`, `=`, `?`, `'` or `"` (backslashes, `u00` and the hex digits), which Go's `encoding/json`, System.Text.Json,
   * Gson and other HTML-safe serialisers write, some passes read an escape as the character it stands for (#1676),
   * and a value read so may run further than the same value written plainly: a query value that holds a quote runs
   * over an escaped `&`, into the key of the field after it. So there every pass also reads the input as it was given,
   * and what any pass replaces, in sequence or alone, is replaced: the edits are merged where they overlap or meet
   * and made once, and no pass can take the key or the quote of a field from the pass that reads it. An input that
   * holds no such escape is redacted by the passes in sequence alone, exactly as before.
   *
   * Redacting the output a second time usually changes nothing, but that is not guaranteed: where quotes are
   * unbalanced or stray, the text a first pass replaced can change how a second pass pairs the quotes, and the
   * second pass may then replace more. It never makes readable what the first pass replaced, since that text is no
   * longer in its input.
   *
   * @param input The input string potentially containing sensitive data
   * @param placeholder The placeholder to use for redacted content
   * @return The input with sensitive data redacted
   */
  def redact(input: String, placeholder: String = RedactionPlaceholder): String =
    if (input == null || input.isEmpty) {
      input
    } else {
      val escaped = holdsSeparatorOrQuoteEscape(input)
      val passes  = redactionPasses(placeholder, input.count(_ == '\''), escaped)
      if (escaped) {
        // Every pass also reads the input as it was given, and what any of them replaces is merged with what the
        // passes in sequence replace, and written once: no pass can take the key or the quote of a field from
        // another pass's reading (#1676). Each pass runs twice and the edits are sorted once.
        val alone = passes.flatMap(pass => pass(input).edits)
        applyEdits(input, mergeEdits(sequenceEdits(input, passes) ++ alone, placeholder))
      } else {
        passes.foldLeft(input)((text, pass) => pass(text).result)
      }
    }

  /**
   * What the passes replace when each reads the text the one before left, as edits of the input. Each character of
   * a text is traced to the character of the input it was copied from, or to none where a pass wrote it (a
   * placeholder, or a quote around one); the edits are the runs of the input that no character of the last text is
   * traced to, with the text written in their place.
   */
  private def sequenceEdits(input: String, passes: Vector[String => Rewrite]): Vector[Edit] = {
    var text   = input
    var origin = Array.range(0, input.length)
    passes.foreach { pass =>
      val rewrite = pass(text)
      origin = traced(text, origin, rewrite.edits)
      text = rewrite.result
    }
    val found     = Vector.newBuilder[Edit]
    var next      = 0  // the next character of the input that is not yet copied or replaced
    var runFrom   = -1 // where in `text` the characters a pass wrote start
    var maskStart = -1
    var maskEnd   = -1
    def record(upTo: Int, at: Int): Unit =
      if (runFrom >= 0 || upTo > next) {
        val from         = if (runFrom < 0) at else runFrom
        val (start, end) = if (maskStart < 0) (-1, -1) else (maskStart - from, maskEnd - from)
        found += Edit(next, upTo, text.substring(from, at), start, end)
        runFrom = -1
        maskStart = -1
        maskEnd = -1
      }
    var i = 0
    while (i < text.length) {
      val o = origin(i)
      if (o >= 0) {
        record(o, i)
        next = o + 1
      } else {
        if (runFrom < 0) runFrom = i
        if (o == Masked) {
          if (maskStart < 0) maskStart = i
          maskEnd = i + 1
        }
      }
      i += 1
    }
    record(input.length, text.length)
    found.result()
  }

  /** What a character of a text is traced to when a pass wrote it: a placeholder, or the text around one. */
  private val Masked: Int  = -2
  private val Written: Int = -1

  /** The trace of each character of the text `edits` make of `text`, whose characters are traced by `origin`. */
  private def traced(text: String, origin: Array[Int], edits: Vector[Edit]): Array[Int] = {
    val out  = Array.newBuilder[Int]
    var from = 0
    edits.foreach { edit =>
      out.addAll(origin, from, edit.start - from)
      var k = 0
      while (k < edit.replacement.length) {
        out += (if (edit.masked && k >= edit.maskStart && k < edit.maskEnd) Masked else Written)
        k += 1
      }
      from = edit.end
    }
    out.addAll(origin, from, text.length - from)
    out.result()
  }

  /**
   * The passes of [[redact]], in the order they run over an input that holds no JSON escape of a separator or a
   * quote: the authorization patterns, the query parameters, the fields (containers, quoted values, numbers,
   * `key=value` pairs and header lines), the API-key patterns, the leaves of containers, and last the cookie headers.
   *
   * The cookie pass is last so that it can only add to what the others replace (#1686). Its value runs over quotes
   * in a malformed line (`msg="GET /" Cookie: theme="dark"; sid=abc`), and a pass after it would pair the quotes
   * left on the line differently, and could read past the key of a field on a later line and leave its value
   * readable. Run last, it changes no text another pass reads: every other pass reads what it would were cookie
   * headers not redacted at all (the header-line pass leaves `Cookie:` and `Set-Cookie:` lines to it), and the cookie
   * pass replaces only more of what they left. A value it reads may hold their placeholders already; it replaces them
   * with the rest of the value, and never writes back text they replaced.
   */
  private def redactionPasses(placeholder: String, inputQuotes: Int, escaped: Boolean): Vector[String => Rewrite] =
    authHeaderPasses(placeholder) ++
      Vector((text: String) => redactQueryParams(text, placeholder)) ++
      jsonFieldPasses(placeholder, inputQuotes, escaped) ++
      SecretPatterns.SecretType.default.toVector.map(t => (text: String) => regexPass(t.pattern, text, placeholder)) ++
      containerLeafPasses(placeholder) :+
      ((text: String) => redactCookieHeaders(text, placeholder))

  /**
   * Whether the input holds the JSON escape of `&`, `=`, `?`, `'` or `"` (backslashes, `u00` and `26`, `3d`, `3f`,
   * `27` or `22`, the hex digits in either case), which the passes read as the characters they stand for (#1676).
   */
  private def holdsSeparatorOrQuoteEscape(input: String): Boolean = {
    var i     = input.indexOf('\\')
    var found = false
    while (!found && i >= 0) {
      found = separatorEscapeEnd(input, i) >= 0 || quoteEscapeEnd(input, i) >= 0
      i = input.indexOf('\\', afterBackslashes(input, i))
    }
    found
  }

  /**
   * One replacement a pass makes: the input from `start` to `end` is written as `replacement`, whose placeholder,
   * if it holds one, runs from `maskStart` to `maskEnd`.
   */
  final private case class Edit(start: Int, end: Int, replacement: String, maskStart: Int, maskEnd: Int) {
    def masked: Boolean = maskStart >= 0
    def before: String  = if (masked) replacement.substring(0, maskStart) else replacement
    def after: String   = if (masked) replacement.substring(maskEnd) else ""
  }

  /**
   * The output of one pass over `input`, built as the pass writes it - the text it keeps (`copy`), the text it adds
   * (`text`) and the placeholder (`mask`) - and kept both as the text and as the edits that turn the input into it.
   * The pass copies the input in order, so each character is read once more at most.
   */
  final private class Rewrite(input: String, placeholder: String) {
    private val out         = new java.lang.StringBuilder(input.length)
    private val found       = Vector.newBuilder[Edit]
    private var cursor      = 0  // the input before this is copied or replaced
    private var pendingFrom = -1 // where in `out` the replacement not yet recorded starts
    private var maskStart   = -1
    private var maskEnd     = -1

    /** Keeps `input(from until to)`; the input between the last text kept and `from` is replaced by what was added. */
    def copy(from: Int, to: Int): Unit = {
      val start = math.max(from, cursor)
      record(start)
      if (to > start) out.append(input, start, to)
      cursor = math.max(cursor, to)
    }

    def text(s: String): Unit = {
      if (pendingFrom < 0) pendingFrom = out.length
      out.append(s)
    }

    def mask(): Unit = {
      if (pendingFrom < 0) pendingFrom = out.length
      if (maskStart < 0) maskStart = out.length - pendingFrom
      out.append(placeholder)
      maskEnd = out.length - pendingFrom
    }

    private def record(upTo: Int): Unit =
      if (pendingFrom >= 0 || upTo > cursor) {
        val replacement = if (pendingFrom < 0) "" else out.substring(pendingFrom)
        if (!input.regionMatches(cursor, replacement, 0, replacement.length) || upTo - cursor != replacement.length)
          found += Edit(cursor, upTo, replacement, maskStart, maskEnd)
        cursor = upTo
        pendingFrom = -1
        maskStart = -1
        maskEnd = -1
      }

    /** Copies the rest of the input from `from`, after the last replacement. */
    def finish(from: Int): Rewrite = {
      copy(from, input.length)
      this
    }

    def result: String      = out.toString
    def edits: Vector[Edit] = found.result()
  }

  /**
   * The edits of every pass, sorted, with those that overlap joined into one: it spans them all and writes one
   * placeholder, with the text the first of them adds before its placeholder (an opening quote) and the text the last
   * adds after it. Sort and one sweep, so linear in the input bar the sort.
   */
  private def mergeEdits(edits: Vector[Edit], placeholder: String): Vector[Edit] = {
    val sorted = edits.distinct.sortBy(e => (e.start, -e.end))
    val merged = Vector.newBuilder[Edit]
    var i      = 0
    while (i < sorted.length) {
      val first = sorted(i)
      var last  = first
      var end   = first.end
      var j     = i + 1
      // Two placeholders that meet, with nothing a pass wrote between them, are one.
      def meets(next: Edit): Boolean =
        next.start == end && last.masked && last.after.isEmpty && next.masked && next.before.isEmpty
      while (j < sorted.length && (sorted(j).start < end || meets(sorted(j)))) {
        if (sorted(j).end > end || (sorted(j).end == end && sorted(j).start == end)) {
          end = sorted(j).end
          last = sorted(j)
        }
        j += 1
      }
      if (j == i + 1) merged += first
      else {
        val before = first.before
        val after  = last.after
        merged += Edit(
          first.start,
          end,
          before + placeholder + after,
          before.length,
          before.length + placeholder.length
        )
      }
      i = j
    }
    merged.result()
  }

  /** The input with the merged edits made. */
  private def applyEdits(input: String, edits: Vector[Edit]): String = {
    val out = new java.lang.StringBuilder(input.length)
    val copiedTo = edits.foldLeft(0) { (from, edit) =>
      out.append(input, from, edit.start).append(edit.replacement)
      edit.end
    }
    out.append(input, copiedTo, input.length).toString
  }

  /**
   * Redact sensitive data and truncate for logging.
   *
   * Applies pattern-based redaction first, then truncates if needed. For a provider's response body in an error
   * message or a log line, prefer [[safeBody]], which differs only in its defaults and truncation suffix.
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

  /**
   * `"Authorization": "`, in any case, up to the opening quote of the value, with the key as group 2. The value is
   * read by `redactQuoted`, as the body of a JSON string: a backslash escapes the character after it, so an escaped
   * quote (`\"`) is part of the value and a quote after an escaped backslash (`\\"`) ends it. Stopping at the first
   * `"` instead redacted only the text before an escaped quote and left the rest readable (#1672). A value that is
   * not closed runs to the end of the input, so a payload cut off in the middle of a credential still has it
   * redacted; one whose key a single-quoted string holds ends where that string does too (#1697).
   */
  private val JsonAuthorizationStart: Regex = """(?i)("(Authorization)"\s*:\s*")""".r

  /**
   * `Authorization: ...` (`Proxy-Authorization: ...` too) in a header, anywhere in a line, the value running to the
   * end of the line: the header-line field pass reads only a header at the start of a line, and a log line often
   * writes the headers after a prefix. Unlike a cookie's (`CookieHeaderStart`), the value does not stop where a JSON
   * string around the header ends: a Digest or other auth-param credential holds quoted strings and commas
   * (`username="u", response="..."`), and where the quotes around the header are not escaped, a quote followed by
   * `,` is no sure end of a string, so stopping there could leave the rest of the credential readable.
   */
  private val HeaderAuthorization: Regex = """(?i)(Authorization:\s*)([^\n\r]+)""".r

  /**
   * `Cookie:` or `Set-Cookie:` in a header, anywhere in a line, and the whitespace after it, written or escaped
   * (`\n`, `\r`, `\t`): the value follows, read by `cookieValueEnd`. `Cookie` starts a word, so that a longer name
   * ending in it (`MyCookie`, `my_cookie`) is left to the field passes; it starts one after any JSON escape too - of a
   * line break, a tab, a form feed, a backspace, a quote or a `>` (`\r\nCookie:`, `\fCookie:`, `\bCookie:`,
   * `\u000aCookie:`, `\u0022Cookie:`, `\u003eCookie:`, as HTML-safe serialisers write them) - whose last character is
   * a letter or a digit but which mostly stands for whitespace or punctuation; the rare escaped letter before
   * `Cookie:` costs only a value redacted that need not be. Only JSON's escape letters count, in lower case
   * (`b`, `f`, `n`, `r`, `t`, `u`): a backslash and any other letter (`\N`, `\X`) is no JSON escape, and `Cookie`
   * there continues a word. Possessive, so the whitespace is read once.
   */
  private val CookieHeaderStart: Regex =
    """(?i)(?:(?<![A-Za-z0-9_])|(?<=\\(?-i:[bfnrtu]))|(?<=\\(?-i:u)[0-9a-f]{4}))Cookie:(?:\s|\\++(?-i:[nrt]))*+""".r

  /** A standalone Bearer token. */
  private val BearerToken: Regex = """(?i)\bBearer\s+([a-zA-Z0-9\-_\.]+)""".r

  /**
   * The characters of a Basic auth token: base64, whose `=` padding may be written as its JSON escape (backslashes
   * and `u003d`), as Gson and other HTML-safe serialisers write it, so that the token runs on over it as it does over
   * `=` (#1676). It starts with a character of its own, never with the escape. Only an input that holds such an
   * escape reads differently. Possessive, so it is matched in a loop.
   */
  private val BasicTokenChars: String = """[a-zA-Z0-9+/=](?:[a-zA-Z0-9+/=]|\\++(?-i:u)003[dD])*+"""

  /** A standalone Basic auth token. */
  private val BasicToken: Regex = s"""(?i)\\bBasic\\s+($BasicTokenChars)""".r

  /**
   * What may come before a Bearer or Basic token that `\b` does not find: the JSON escape of `&`, `=` or `?`, which
   * ends in a word character but stands for a separator (#1676), with the lower-case `u` JSON writes.
   */
  private val AfterSeparatorEscape: String = """(?<=\\(?-i:u)00(?:26|3[dD]|3[fF]))"""

  private val EscapedBearerToken: Regex = s"""(?i)${AfterSeparatorEscape}Bearer\\s+([a-zA-Z0-9\\-_\\.]+)""".r
  private val EscapedBasicToken: Regex  = s"""(?i)${AfterSeparatorEscape}Basic\\s+($BasicTokenChars)""".r

  /**
   * `"Authorization": "..."` in JSON, then `Authorization: ...` in headers, then standalone Bearer and Basic tokens,
   * keyword and all (a `Cookie:` header is read by `redactCookieHeaders`, the last pass of all). A token after the escape of a separator is read by a pass of its own, so that a
   * match there cannot take the keyword of a token the plain pattern reads (`Basic`, the escape of `=`,
   * `Basic Basic 9K29`). The placeholder is the caller's and is written as it is: it is appended, never read as a
   * replacement string, where a `$` or `\` would be a group reference or an escape.
   */
  private def authHeaderPasses(placeholder: String): Vector[String => Rewrite] =
    Vector(
      (text: String) =>
        redactQuoted(JsonAuthorizationStart, text, placeholder, ValueEnd.Quote('"'), endsWithSingleQuoted = true),
      (text: String) => regexPass(HeaderAuthorization, text, placeholder, group = 2),
      (text: String) => regexPass(BearerToken, text, placeholder),
      (text: String) => regexPass(EscapedBearerToken, text, placeholder),
      (text: String) => regexPass(BasicToken, text, placeholder),
      (text: String) => regexPass(EscapedBasicToken, text, placeholder)
    )

  /**
   * Replaces `group` of every match of `pattern` (the whole match for group 0) that `replaces` accepts with the
   * placeholder, written between two `wrap`s. The matches are those `replaceAllIn` would find: each search starts
   * where the match before ended.
   */
  private def regexPass(
    pattern: Regex,
    input: String,
    placeholder: String,
    group: Int = 0,
    wrap: String = "",
    replaces: java.util.regex.Matcher => Boolean = _ => true
  ): Rewrite = {
    val matcher = pattern.pattern.matcher(input)
    val out     = new Rewrite(input, placeholder)
    var copied  = 0
    while (matcher.find())
      if (matcher.start(group) >= 0 && replaces(matcher)) {
        out.copy(copied, matcher.start(group))
        out.text(wrap)
        out.mask()
        out.text(wrap)
        copied = matcher.end(group)
      }
    out.finish(copied)
  }

  /**
   * Replaces the value of every `Cookie:` and `Set-Cookie:` header (`CookieHeaderStart`) - the whole value, every
   * name and value of it, since any may be the session token (#1686) - to where `cookieValueEnd` ends it in the
   * header's context. An empty value is left as it is. The matches come in increasing order, so the one forward scan
   * of `EnclosingQuotes` serves them all, and the search goes on after each value: linear in the input.
   *
   * It runs after every other pass (`redactionPasses`), so what it replaces - quotes of a malformed line included -
   * is read by no pass after it, and it writes nothing but the placeholder: a value that runs to the end of a
   * malformed line takes the quotes on it (`msg="Cookie: theme="dark" sid=abc"` becomes `msg="Cookie: [REDACTED]`).
   * The text it reads is the others' output: the default placeholder holds no quote, backslash or line break, so it never
   * moves where a value ends, and a value that holds one is replaced whole.
   */
  private def redactCookieHeaders(input: String, placeholder: String): Rewrite = {
    val matcher   = CookieHeaderStart.pattern.matcher(input)
    val out       = new Rewrite(input, placeholder)
    val enclosing = new EnclosingQuotes(input)

    def contextOf(quote: Char): HeaderContext =
      if (quote == EnclosingQuotes.NoQuote || !enclosing.openedOnItsLine) HeaderContext.Raw
      else if (quote == '\'') HeaderContext.InSingleQuotes
      else if (enclosing.inEscapedString) HeaderContext.InEscapedString
      else HeaderContext.InString

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom >= input.length || !matcher.find(searchFrom)) copiedTo
      else {
        val valueStart = matcher.end
        val valueEnd   = cookieValueEnd(input, valueStart, contextOf(enclosing.enclosingAt(matcher.start)))
        if (valueEnd > valueStart) {
          out.copy(copiedTo, valueStart)
          out.mask()
          loop(valueEnd, valueEnd)
        } else loop(valueStart, copiedTo)
      }

    out.finish(loop(0, 0))
  }

  /**
   * Where a header sits, as `EnclosingQuotes` reads it, which decides where its value ends (`cookieValueEnd`). A
   * string counts only when it opened on the header's own line: a JSON string never holds a line break, and a quote
   * left open on an earlier line is more likely a stray one than the start of a string around the header.
   */
  private enum HeaderContext {

    /** In no string: plain header text. */
    case Raw

    /** In a double-quoted string, JSON's or a log line's. */
    case InString

    /** In a string escaped within a double-quoted string, after an odd number of `\"` in it: JSON inside JSON. */
    case InEscapedString

    /** In a single-quoted string: a Python repr, a shell argument. */
    case InSingleQuotes
  }

  /**
   * The index where the value of a cookie header starting at `from` ends: always at a line break, and, inside a string
   * (`context`), at the escape of one or where the string ends, so that the rest of the string and the fields after it
   * are kept and JSON stays JSON. Plain header text - a request dump, `Cookie: sid="abc"; x=y` - is read to the end
   * of its line, quotes, backslashes and all, as it always was.
   *
   *  - The escape of a line break is a run of backslashes and `n`, `r`, `u000a` or `u000d` of odd length, since an
   *    even run is escaped backslashes and the letter after them is text. In a string escaped within a string, a run
   *    of two (or six), the inner string's escaped escape (`\\n`), ends the value too, but only where a header name
   *    and `:` follow it (`headerNameAfter`): an odd count of `\"` before the header does not prove the string is
   *    JSON inside JSON (`he said \"Cookie: ...`), and where it is not, the run is a backslash and a letter of the
   *    value, so a value ends there only where the text after it reads as the next header. A cookie value holds no
   *    backslash (RFC 6265), so reading one as the end of the value can only end it early where a non-standard cookie
   *    holds `\n` itself.
   *  - Inside a double-quoted string, the first `"` after an even run of backslashes (none, or escaped backslashes)
   *    is where the string ends in well-formed JSON, so the value ends there when what follows it is what follows the
   *    end of a JSON string (`endsJsonStringAt`). An escaped quote (`\"`) is part of the value, so a quoted cookie
   *    value in JSON (`sid=\"abc\"`) is replaced whole; inside a string escaped within a string, only the outer
   *    string's `"` ends the value, which keeps the outer JSON whole. This is a heuristic for well-formed JSON
   *    strings: where that first `"` is followed by anything else, the text is not well-formed JSON (a stray quote
   *    earlier on the line, `size 5" Cookie: a="b"; c=d`, or a quoted cookie value in a log line,
   *    `msg="Cookie: a="x", sid=...`), and the value runs on to the end of its line, as plain header text does; no
   *    later quote is searched for, since a quote in a value that is not JSON ends nothing. A `'` never ends a value:
   *    it is a legal cookie character, and outside a JSON string a value running on to the end of its line can only
   *    hide more, never break the JSON around it.
   *
   * A loop that reads each character a bounded number of times.
   */
  private def cookieValueEnd(input: String, from: Int, context: HeaderContext): Int = {
    val length = input.length
    val inDoubleQuotes =
      context == HeaderContext.InString || context == HeaderContext.InEscapedString
    def isLineBreak(c: Char): Boolean = c == '\n' || c == '\r'
    def lineEnd(at: Int): Int = {
      var i = at
      while (i < length && !isLineBreak(input.charAt(i))) i += 1
      i
    }
    // The index after the line break `n`, `r`, `u000a` or `u000d` that an escape starting at `at` stands for, or -1.
    def afterLineBreakEscape(at: Int): Int =
      if (at >= length) -1
      else if (input.charAt(at) == 'n' || input.charAt(at) == 'r') at + 1
      else if (at + 4 < length && input.startsWith("u000", at) && "aAdD".indexOf(input.charAt(at + 4).toInt) >= 0)
        at + 5
      else -1
    def endsLine(slashes: Int, after: Int): Boolean =
      slashes                                                % 2 == 1 ||
        (context == HeaderContext.InEscapedString && slashes % 4 == 2 && headerNameAfter(input, after))
    var i   = from
    var end = -1
    while (end < 0 && i < length) {
      val c = input.charAt(i)
      if (isLineBreak(c)) end = i
      else if (c == '\\' && context != HeaderContext.Raw) {
        val next    = afterBackslashes(input, i)
        val slashes = next - i
        val after   = afterLineBreakEscape(next)
        if (after >= 0 && endsLine(slashes, after)) end = i
        else if (slashes % 2 == 1 && next < length && !isLineBreak(input.charAt(next))) i = next + 1
        else i = next
      } else if (c == '"' && inDoubleQuotes) end = if (endsJsonStringAt(input, i + 1)) i else lineEnd(i)
      else i += 1
    }
    if (end < 0) length else end
  }

  /**
   * Whether the text at `from`, right after the escape of a line break in a string escaped within a string, starts
   * the next header line: a header name - letters, digits and `-`, at most 64 - and a `:`, after at most one more
   * escaped escape of a line break (`\\r\\n`). At most 70 characters are read.
   */
  private def headerNameAfter(input: String, from: Int): Boolean = {
    val length = input.length
    def isEscapedBreak(at: Int): Boolean =
      at + 2 < length && input.charAt(at) == '\\' && input.charAt(at + 1) == '\\' &&
        (input.charAt(at + 2) == 'n' || input.charAt(at + 2) == 'r') && (at + 3 == length || input.charAt(
          at + 3
        ) != '\\')
    val start = if (isEscapedBreak(from)) from + 3 else from
    var i     = start
    while (i < length && i - start < 64 && (input.charAt(i).isLetterOrDigit || input.charAt(i) == '-')) i += 1
    i > start && i < length && input.charAt(i) == ':'
  }

  /**
   * Whether the text from `from` on is what follows the end of a JSON string: past whitespace, the end of the input, a
   * `,` before the next value - a string, an object, an array, a number, `true`, `false` or `null` - or a run of `}`
   * and `]` (and spaces) that is itself followed by the end of the input, a line break or such a `,`, as the end of a
   * JSON container is. Looser than `endsString`, which wants a key after the `,`, since a header is as often an element
   * of an array (`["Cookie: ...", "Accept: ..."]`, `["Cookie: ...", 1]`); a `,` before any other word (`a="x", b="y"`,
   * a Set-Cookie header folded onto one line) is not taken for one, nor a `}` before a word (`x="}SID`), which is a
   * quote and a brace in a cookie value. A `:` before a value ends a string too: the header is the key of an object
   * (`{"Cookie: sid=...": 1}`). Whitespace, closing brackets and at most six characters after a `,` or `:` are read.
   */
  private def endsJsonStringAt(input: String, from: Int): Boolean = {
    val length = input.length
    def skipSpace(at: Int): Int = {
      var i = at
      while (i < length && Character.isWhitespace(input.charAt(i))) i += 1
      i
    }
    // Whether a JSON value follows the `,` or `:` at `separator`.
    def valueAfter(separator: Int): Boolean = {
      val j = skipSpace(separator + 1)
      def literalAt(word: String): Boolean =
        input.startsWith(word, j) && (j + word.length == length || !input.charAt(j + word.length).isLetterOrDigit)
      j < length && ("\"{[-0123456789".indexOf(input.charAt(j).toInt) >= 0 ||
        literalAt("true") || literalAt("false") || literalAt("null"))
    }
    val i = skipSpace(from)
    i == length || (input.charAt(i) match {
      case '}' | ']' =>
        var j = i
        while (j < length && "}] \t".indexOf(input.charAt(j).toInt) >= 0) j += 1
        j == length || input.charAt(j) == '\n' || input.charAt(j) == '\r' || (input.charAt(j) == ',' && valueAfter(j))
      case ',' | ':' => valueAfter(i)
      case _         => false
    })
  }

  private def isSensitiveQueryKey(key: String): Boolean = {
    val lower = key.toLowerCase(Locale.ROOT)
    SensitiveQueryParams.exists(lower.contains)
  }

  /**
   * Replaces the value of every query parameter whose key holds a sensitive name. The key and the text around the
   * value are kept, and an empty value is left as it is: there is nothing to hide, and a placeholder written before a
   * quote would keep the `key='...'` pass after this one from reading the value. Appended, not passed to
   * `replaceAllIn`, so a `$` or `\` in the input or the placeholder is written as it is.
   *
   * The values are those of two readings of the query, and a character either takes is replaced. The first reads
   * `QueryParamStart`, the one key of each `?` or `&`, as this pass always has. The second also reads the JSON escapes
   * of the separators, which Go's `encoding/json` and HTML-safe serialisers write for `&` (and some for `=` and `?`),
   * so a parameter after an escaped `&` is read as one after `&` is (#1676). Where the input holds no such escape, the
   * two read the same parameters; where it does, the first keeps every value it read before.
   */
  private def redactQueryParams(input: String, placeholder: String): Rewrite = {
    val values = mergeSpans(queryParamValues(input) ++ escapedQueryParamValues(input))
    val out    = new Rewrite(input, placeholder)
    val copiedTo = values.foldLeft(0) { case (from, (start, end)) =>
      out.copy(from, start)
      out.mask()
      end
    }
    out.finish(copiedTo)
  }

  /** The `(start, end)` spans of the sensitive values of the parameters that `QueryParamStart` finds. */
  private def queryParamValues(input: String): Vector[(Int, Int)] = {
    val matcher = QueryParamStart.pattern.matcher(input)

    @tailrec def loop(searchFrom: Int, found: Vector[(Int, Int)]): Vector[(Int, Int)] =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        found
      } else if (isSensitiveQueryKey(matcher.group(2))) {
        val (valueStart, valueLength) = queryValue(input, matcher.end)
        if (valueLength > 0) loop(valueStart + valueLength, found :+ (valueStart -> (valueStart + valueLength)))
        else loop(matcher.end, found)
      } else {
        // The value of a parameter that is kept is searched too: it may be a URL with a query of its own
        // (`?next=/cb?token=...`). It is not scanned, so no character is read twice.
        loop(matcher.end, found)
      }

    loop(0, Vector.empty)
  }

  /**
   * The `(start, end)` spans of the sensitive values of the parameters of a query whose separators may be written as
   * JSON escapes: a key starts after `?`, `&` or the escape of either, and ends at `=` or its escape, where its value
   * starts. Like the key of `QueryParamStart`, it holds no whitespace, quote, `?`, `&` or `=`, nor an escape of the
   * last three, nor the escape of whitespace (`u0020`, `u000a` and the like): a query read through its escapes ends
   * where the text they stand for has whitespace, as the value does (`queryValue`). A loop that reads each character a
   * bounded number of times.
   */
  private def escapedQueryParamValues(input: String): Vector[(Int, Int)] = {
    val length = input.length
    val found  = Vector.newBuilder[(Int, Int)]

    // Reads the parameter whose key starts at `keyStart`, and returns the index to search on from.
    def readParam(keyStart: Int): Int = {
      var k         = keyStart
      var valueFrom = -1
      var stop      = false
      while (!stop && k < length) {
        val c = input.charAt(k)
        if (c == '=') {
          valueFrom = k + 1
          stop = true
        } else if (c == '&' || c == '?' || c == '"' || c == '\'' || isRegexSpace(c)) {
          stop = true
        } else if (c == '\\') {
          val escapeEnd = separatorEscapeEnd(input, k)
          if (escapeEnd < 0 && spaceEscapeEnd(input, k) >= 0) stop = true
          else if (escapeEnd < 0) k = afterBackslashes(input, k)
          else {
            if (escapedSeparator(input, escapeEnd) == '=') valueFrom = escapeEnd
            stop = true
          }
        } else {
          k += 1
        }
      }
      if (valueFrom < 0 || k == keyStart) k
      else if (isSensitiveQueryKey(input.substring(keyStart, k))) {
        val (valueStart, valueLength) = queryValue(input, valueFrom, escapedSpaceEnds = true)
        if (valueLength > 0) found += (valueStart -> (valueStart + valueLength))
        math.max(valueStart + valueLength, valueFrom)
      } else {
        valueFrom // searched, as `queryParamValues` searches the value of a parameter that is kept
      }
    }

    var i = 0
    while (i < length) {
      val c = input.charAt(i)
      if (c == '?' || c == '&') i = readParam(i + 1)
      else if (c == '\\') {
        val escapeEnd = separatorEscapeEnd(input, i)
        i =
          if (escapeEnd < 0) afterBackslashes(input, i)
          else if (escapedSeparator(input, escapeEnd) == '=') escapeEnd
          else readParam(escapeEnd)
      } else {
        i += 1
      }
    }
    found.result()
  }

  /** Spans sorted by their start, with those that overlap joined into one. */
  private def mergeSpans(spans: Vector[(Int, Int)]): Vector[(Int, Int)] =
    spans.sortBy(_._1).foldLeft(Vector.empty[(Int, Int)]) { (merged, span) =>
      merged.lastOption match {
        case Some((start, end)) if span._1 < end => merged.init :+ (start -> math.max(end, span._2))
        case _                                   => merged :+ span
      }
    }

  /** The index after the run of backslashes at `i`. */
  private def afterBackslashes(input: String, i: Int): Int = {
    var j = i
    while (j < input.length && input.charAt(j) == '\\') j += 1
    j
  }

  /**
   * The index after the JSON escape of whitespace - `u0020`, `u0009`, `u000a`, `u000b`, `u000c` or `u000d`, the
   * characters `isRegexSpace` takes, the hex digits in either case - that the run of backslashes at `i` starts, or
   * -1 if it starts none.
   */
  private def spaceEscapeEnd(input: String, i: Int): Int = {
    val u = afterBackslashes(input, i)
    val escapes =
      u > i && u + 5 <= input.length && input.startsWith("u00", u) && {
        val hex = input.substring(u + 3, u + 5).toLowerCase(Locale.ROOT)
        hex == "20" || hex == "09" || hex == "0a" || hex == "0b" || hex == "0c" || hex == "0d"
      }
    if (escapes) u + 5 else -1
  }

  /**
   * The index after the JSON escape of `&`, `=` or `?` that the run of backslashes at `i` starts - `u`, then `0026`,
   * `003d` or `003f`, the hex digits in either case - or -1 if it starts none. The run may hold more than one
   * backslash: JSON that sits inside a string doubles it.
   */
  private def separatorEscapeEnd(input: String, i: Int): Int = {
    val u = afterBackslashes(input, i)
    val escapes =
      u > i && u + 5 <= input.length && input.charAt(u) == 'u' && input.charAt(u + 1) == '0' &&
        input.charAt(u + 2) == '0' && escapedSeparator(input, u + 5) != NotASeparator
    if (escapes) u + 5 else -1
  }

  /** The separator that the escape ending at `end` writes, read from its last two hex digits, or `NotASeparator`. */
  private def escapedSeparator(input: String, end: Int): Char =
    (input.charAt(end - 2), Character.toLowerCase(input.charAt(end - 1))) match {
      case ('2', '6') => '&'
      case ('3', 'd') => '='
      case ('3', 'f') => '?'
      case _          => NotASeparator
    }

  private val NotASeparator: Char = ' '

  /**
   * Where the query value that starts at `from` begins, and its length. A value ends where a query value does, at
   * `&`, its JSON escape (but see below) or whitespace, or at the quote that ends the string the URL sits in, which is kept with the
   * backslashes that escape it in JSON that sits inside a string.
   *
   * RFC 3986 allows `'` unencoded in a query, with `!$()*,;=:@`, and JavaScript's `encodeURIComponent` leaves
   * `'()*!` as they are, so a value may hold a quote. A run of `'` is part of the value when the character after it
   * is a letter, a digit, one of `._~%+/-`, or one of `!$*(@=` (`=` also as its JSON escape): `?key=ab'cd`, `pa'(ss)w0rd`, `Xk9'!mQ2`, `ab''cd`,
   * and `''Xk9` at the start of a value. A `"`, which a query may not hold unencoded, is part of it only before a
   * letter, a digit or one of `._~%+/-`. Any other quote ends the value: the quote that ends a string is followed by
   * `,`, `)`, `;`, `:`, a bracket, `>`, `\`, `"`, whitespace or the end of the input. So does a quote before one of
   * `,);:` inside a value (`ab',cd`): it cannot be told from the end of a string, as in `fetch('...?token=ab')`, and
   * the value is redacted up to it.
   *
   * A value written in quotes (`'abc'`, `"abc"`, or `\"abc\"` in JSON inside a string), as a query string in prose
   * or code may have it, starts after a single opening quote by the same rule, so the quote that ends the enclosing
   * string, as in `"https://x.test/?token="}`, leaves the value empty.
   *
   * The JSON escape of `&` ends a value only while the value has held no quote. The quotes a value holds are not
   * paired, so once one has been read - bare, after backslashes, or as the escape of `'` or `"` (a backslash and
   * `u0027` or `u0022`, which System.Text.Json writes) - an escaped `&` may be inside a quoted credential
   * (`password='p&ss=QZXJ'`, with `'` and `&` escaped), and the value runs over it, as it did before #1676. Once the
   * escape of a quote has been read, a bare `&` is the value's too: a serialiser that escapes quotes escapes `&`, so a
   * bare one there is the credential's (`secret='a&b'` with only its quotes escaped). The value then ends at
   * whitespace or at a quote that ends a string. A loop that reads each character once.
   *
   * With `escapedSpaceEnds`, for a query read through its escapes, the escape of whitespace ends the value as
   * whitespace does: the value may not run over it into the key of a field after it.
   */
  private def queryValue(input: String, from: Int, escapedSpaceEnds: Boolean = false): (Int, Int) = {
    def isQuote(i: Int): Boolean = i < input.length && (input.charAt(i) == '"' || input.charAt(i) == '\'')
    def isUrlChar(i: Int): Boolean =
      i < input.length && (input.charAt(i).isLetterOrDigit || "._~%+/-".indexOf(input.charAt(i).toInt) >= 0)
    // A character that may follow a `'` inside a value: one of a token, or a sub-delimiter a string never ends before,
    // `=` also as its JSON escape.
    def continuesAfterApostrophe(i: Int): Boolean =
      isUrlChar(i) || (i < input.length && "!$*(@=".indexOf(input.charAt(i).toInt) >= 0) || {
        val escapeEnd = if (i < input.length && input.charAt(i) == '\\') separatorEscapeEnd(input, i) else -1
        escapeEnd >= 0 && escapedSeparator(input, escapeEnd) == '='
      }
    def afterSlashes(i: Int): Int = afterBackslashes(input, i)
    // The quote at `q` is part of the value: the index to read on from, or -1 when it ends the value.
    def afterInnerQuote(q: Int): Int =
      if (input.charAt(q) == '"') { if (isUrlChar(q + 1)) q + 1 else -1 }
      else {
        var r = q
        while (r < input.length && input.charAt(r) == '\'') r += 1
        if (continuesAfterApostrophe(r)) r else -1
      }
    val quoteAt = afterSlashes(from)
    val opens =
      isQuote(quoteAt) && (if (input.charAt(quoteAt) == '"') isUrlChar(quoteAt + 1)
                           else continuesAfterApostrophe(quoteAt + 1))
    val start = if (opens) quoteAt + 1 else from
    var end   = start
    var done  = false
    // Whether the value has held a quote, bare or escaped: from there on, the escape of `&` is the value's. And whether
    // it has held the escape of a quote: from there on, `&` is the value's too.
    var quoted        = opens
    var escapedQuoted = false
    while (!done && end < input.length) {
      val c = input.charAt(end)
      if (c == '\\') {
        // A quote after backslashes ends the value as a bare quote does, and the escape of `&` as `&` does, unless a
        // quote came before it; other backslashes are the value's.
        val next      = afterSlashes(end)
        val escapeEnd = separatorEscapeEnd(input, end)
        val quoteEnd  = quoteEscapeEnd(input, end)
        if (escapedSpaceEnds && spaceEscapeEnd(input, end) >= 0) done = true
        else if (escapeEnd >= 0 && escapedSeparator(input, escapeEnd) == '&' && !quoted) done = true
        else if (escapeEnd >= 0) end = escapeEnd
        else if (quoteEnd >= 0) {
          quoted = true
          escapedQuoted = true
          end = quoteEnd
        } else if (!isQuote(next)) end = next
        else {
          val after = afterInnerQuote(next)
          if (after >= 0) {
            quoted = true
            end = after
          } else done = true
        }
      } else if (c == '"' || c == '\'') {
        // A quote inside a token (`ab'cd`) is followed by more of it; the quote that ends a string is not.
        val after = afterInnerQuote(end)
        if (after >= 0) {
          quoted = true
          end = after
        } else done = true
      } else if ((c == '&' && !escapedQuoted) || isRegexSpace(c)) {
        done = true
      } else {
        end += 1
      }
    }
    (start, end - start)
  }

  /** The characters `\s` matches, so that a value ends where the key of `QueryParamStart` would. */
  private def isRegexSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'

  private def jsonFieldPasses(placeholder: String, inputQuotes: Int, escaped: Boolean): Vector[String => Rewrite] = {
    // Arrays and objects first: the strings and numbers under a sensitive key are replaced in one pass, and what is
    // left for the field patterns below is already the placeholder. Then the quoted shapes, then the bare ones, so
    // that a value is never matched by a looser pattern first. The rest of each container - its other leaves, and
    // the containers under a single-quoted key - waits for `containerLeafPasses`, after every field pass.
    //
    // A single-quoted value inside a double-quoted string may end where that string does, but only where no `'` that
    // could close it follows. That is read from the text the passes before have left, so it is trusted only while
    // they have replaced no `'` of the input - one inside a value they redacted, or the closing quote of a credential
    // that a pattern ran over - and the placeholder writes none of its own. Where every pass reads the input itself,
    // the text is the input, and its quotes are all there.
    def quotesKept(text: String): Boolean = !placeholder.contains('\'') && text.count(_ == '\'') >= inputQuotes
    val pairs                             = pairStarts(escaped)
    Vector(
      (text: String) =>
        redactContainers(EscapedJsonContainerStart, text, placeholder, ValueEnd.EscapedQuote, leaves = false),
      (text: String) => redactContainers(JsonContainerStart, text, placeholder, ValueEnd.Quote('"'), leaves = false),
      (text: String) =>
        redactQuoted(EscapedJsonStringStart, text, placeholder, ValueEnd.EscapedQuote, endsWithSingleQuoted = true),
      (text: String) =>
        redactQuoted(JsonStringStart, text, placeholder, ValueEnd.Quote('"'), endsWithSingleQuoted = true),
      (text: String) =>
        redactQuoted(SingleQuotedStart, text, placeholder, ValueEnd.Quote('\''), endsWithString = quotesKept(text)),
      (text: String) =>
        redactQuoted(
          pairs.doubleQuoted,
          text,
          placeholder,
          ValueEnd.Quote('"'),
          endsWithSingleQuoted = true,
          pair = true
        ),
      (text: String) =>
        redactQuoted(
          pairs.singleQuoted,
          text,
          placeholder,
          ValueEnd.Quote('\''),
          endsWithString = quotesKept(text),
          pair = true
        ),
      // A double-quoted value under a single-quoted key: Python's repr of a string that holds a `'` (#1687), escaped
      // when the dict sits inside a JSON string. After the single-quoted passes, so that the quotes `quotesKept`
      // counts are the input's; only where it reads as a value of a dict (`asValue`).
      (text: String) =>
        redactQuoted(SingleQuotedKeyEscapedStringStart, text, placeholder, ValueEnd.EscapedQuote, asValue = true),
      (text: String) =>
        redactQuoted(SingleQuotedKeyStringStart, text, placeholder, ValueEnd.Quote('"'), asValue = true),
      // A number becomes a string, so that the redacted JSON still parses; in the key's own quote, so that a dict
      // inside a JSON string still sits in that string (#1675).
      (text: String) => redactPairs(EscapedJsonNumberField, text, placeholder, wrap = "\\\""),
      (text: String) => redactPairs(JsonNumberField, text, placeholder, wrap = "\""),
      (text: String) => redactPairs(SingleQuotedNumberField, text, placeholder, wrap = "'"),
      (text: String) => redactEqualsPairs(text, placeholder, escaped),
      // A `Cookie:` or `Set-Cookie:` header line is left to `redactCookieHeaders`, which runs after every other pass.
      (text: String) => redactPairs(HeaderLine, text, placeholder, sensitive = isSensitiveHeaderNotCookie),
      (text: String) => redactEscapedQuoted(EscapedQuoteFieldStart, text, placeholder, key = 3, slashes = 1, hex = 2),
      (text: String) =>
        redactEscapedQuoted(pairs.escapedQuote, text, placeholder, key = 1, slashes = 2, hex = 3, pair = true)
    )
  }

  /**
   * A quoted key, `:` and the opening quote of a string value, where each quote is a JSON escape - backslashes, `u00`
   * and `22` for `"` or `27` for `'` - as System.Text.Json writes both quotes and Gson writes `'`: JSON or a dict that
   * sits inside a string. Group 1 is the backslashes of the opening escape, which the other two repeat, group 2 the
   * hex digits of the quote, group 3 the key. A match starts only at the first backslash of a run, so a long run is
   * read once, not once from each of its backslashes.
   */
  private val EscapedQuoteFieldStart: Regex =
    s"""(?<!\\\\)(\\\\++)u00(2[27])($Key)\\1u00\\2\\s*:\\s*\\1u00\\2""".r

  /**
   * Replaces the value of every field or pair whose quotes are JSON escapes (`EscapedQuoteFieldStart`,
   * `PairStarts.escapedQuote`) and whose key is sensitive (#1676), as the passes for `"key": "..."` and `key='...'`
   * replace the value between bare quotes. `start` matches up to the opening quote, with the key, the backslashes of
   * the quote's escape and its hex digits in the groups `key`, `slashes` and `hex`. The value runs to the escape of
   * its quote with as many backslashes as the one that opens it, so the escape of a quote escaped once more inside
   * it is the value's, or to a `"` that no backslash escapes, which ends the string the field sits in, or to the end
   * of the input. Both are left in place. A loop that reads each character of a value once. With `pair`, as for
   * `redactQuoted`, the value of a key that is not sensitive and whose `=` is an escape is not skipped.
   */
  private def redactEscapedQuoted(
    start: Regex,
    input: String,
    placeholder: String,
    key: Int,
    slashes: Int,
    hex: Int,
    pair: Boolean = false
  ): Rewrite = {
    val matcher = start.pattern.matcher(input)
    val out     = new Rewrite(input, placeholder)

    def valueEnd(from: Int, openSlashes: Int, quoteDigit: Char): Int = {
      var i   = from
      var end = -1
      while (end < 0 && i < input.length) {
        val c = input.charAt(i)
        if (c == '\\') {
          val run = afterBackslashes(input, i)
          if (
            run - i == openSlashes && input.startsWith("u002", run) && run + 4 < input.length &&
            input.charAt(run + 4) == quoteDigit
          ) end = i
          else if (run < input.length && input.charAt(run) == '"' && (run - i) % 2 == 1) i = run + 1
          else i = run
        } else if (c == '"') end = i
        else i += 1
      }
      if (end < 0) input.length else end
    }

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom >= input.length || !matcher.find(searchFrom)) copiedTo
      else if (pair && !isSensitiveKey(matcher.group(key)) && afterEscapedEquals(input, matcher.end(key)) >= 0)
        loop(afterEscapedEquals(input, matcher.end(key)), copiedTo)
      else {
        val valueStart = matcher.end
        val end        = valueEnd(valueStart, matcher.group(slashes).length, matcher.group(hex).charAt(1))
        if (isSensitiveKey(matcher.group(key)) && end > valueStart) {
          out.copy(copiedTo, valueStart)
          out.mask()
          loop(end, end)
        } else loop(math.max(end, valueStart), copiedTo)
      }

    out.finish(loop(0, 0))
  }

  /** The backslashes at the end of `input(from until to)` that escape a quote right after it, or "" if none do. */
  private def quoteEscape(input: String, from: Int, to: Int): String =
    if (to >= input.length || (input.charAt(to) != '"' && input.charAt(to) != '\'')) ""
    else {
      var i = to
      while (i > from && input.charAt(i - 1) == '\\') i -= 1
      val slashes = to - i
      "\\" * (slashes & ~(slashes + 1))
    }

  /**
   * Every leaf of a container under a sensitive key that the passes before left: a string in the other quote than
   * the key's, a bare word, and the whole of a container under a single-quoted key (`{'token': ['...']}`, a Python
   * dict in a prompt). It runs last, after the field passes and the API-key patterns, because what it takes for a
   * leaf is a guess - a `'` may be an apostrophe of prose, and a container that a message merely mentions runs on
   * into the text after it - and a guess made before them could swallow the key of a field they would have redacted
   * and leave its value readable. After them it can only replace more.
   */
  private def containerLeafPasses(placeholder: String): Vector[String => Rewrite] =
    Vector(
      (text: String) =>
        redactContainers(EscapedJsonContainerStart, text, placeholder, ValueEnd.EscapedQuote, leaves = true),
      (text: String) => redactContainers(JsonContainerStart, text, placeholder, ValueEnd.Quote('"'), leaves = true),
      (text: String) =>
        redactContainers(SingleQuotedContainerStart, text, placeholder, ValueEnd.Quote('\''), leaves = true)
    )

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
   *
   * With a `singleQuoteDepth` (see `EnclosingQuotes.singleQuoteDepth`), the value's key sits inside a single-quoted
   * string, and a `'` that ends that string (`endsSingleQuotedString`) and is not escaped (`escapesQuote`) ends the
   * value too (#1697). A doubled `''`, a quote escaped as SQL escapes it, ends nothing, nor does an apostrophe
   * between two letters or digits, or a `'` past a line break: a single-quoted string does not run over one. The
   * first other `'` decides: where it does not end the string, no later one does, and the value is read as it is
   * outside a string - a later `'` would sit in another string, or in no Python string at all.
   */
  @tailrec
  private def scanQuotedValue(input: String, from: Int, end: ValueEnd, singleQuoteDepth: Int = 0): Int =
    if (from >= input.length) {
      input.length
    } else {
      val c = input.charAt(from)
      if (singleQuoteDepth > 0 && (c == '\n' || c == '\r')) scanQuotedValue(input, from, end, 0)
      else if (singleQuoteDepth > 0 && c == '\'') {
        if (from + 1 < input.length && input.charAt(from + 1) == '\'')
          scanQuotedValue(input, from + 2, end, singleQuoteDepth)
        else if (isApostrophe(input, from)) scanQuotedValue(input, from + 1, end, singleQuoteDepth)
        else if (endsSingleQuotedString(input, from, singleQuoteDepth)) from
        // The first quote that could end the string does not: the key is in no string that ends the value, which is
        // read as it is outside one.
        else scanQuotedValue(input, from, end, 0)
      } else
        end match {
          case ValueEnd.Quote(quote) =>
            if (c == quote) from
            else scanQuotedValue(input, if (c == '\\') from + 2 else from + 1, end, singleQuoteDepth)
          case ValueEnd.QuoteInString(quote) =>
            if (c == quote || c == '"') from
            else scanQuotedValue(input, if (c == '\\') from + 2 else from + 1, end, singleQuoteDepth)
          case ValueEnd.EscapedQuote =>
            if (c == '"') from
            else if (c == '\\') {
              var next = from
              while (next < input.length && input.charAt(next) == '\\') next += 1
              val slashes = next - from
              if (next < input.length && input.charAt(next) == '"' && slashes % 4 == 1) next - 1
              else if (next < input.length && input.charAt(next) == '"' && slashes % 2 == 0) next
              else if (
                singleQuoteDepth > 0 && next < input.length && input.charAt(next) == '\'' &&
                !escapesQuote(slashes, singleQuoteDepth)
              ) scanQuotedValue(input, next, end, singleQuoteDepth) // a `'` after an escaped backslash
              else scanQuotedValue(input, next + 1, end, singleQuoteDepth)
            } else scanQuotedValue(input, from + 1, end, singleQuoteDepth)
        }
    }

  /**
   * Whether the `'` at `quote` ends a single-quoted string `depth` deep (see `EnclosingQuotes.singleQuoteDepth`) that
   * a double-quoted value sits in (#1697): what follows it is what follows a value in a Python dict, list or tuple.
   * Past spaces and any `}`, `]` or `)`, that is the end of the input - or, inside a JSON string, of that string - or
   * a `,` and then the end of the line or an item - past any `{`, `[` or `(`, a quoted string that closes on its line,
   * a number, `None`, `True` or `False` - followed the same way, or by `:`. Python escapes a `'` inside a
   * single-quoted string, so a credential in one holds no other; but a credential in a JSON field that a stray quote
   * only seems to enclose may (`'Ab3'),9xQ"`, `'x'] y"`), and is read whole, as it is outside any string, unless such
   * a Python container goes on after it.
   *
   * A value asks this of one quote at most (the first decides), and the check reads up to the end of the item after
   * the comma, which ends at the first quote that could close it, so the cost stays linear in the input.
   */
  private def endsSingleQuotedString(input: String, quote: Int, depth: Int): Boolean = {
    val n = input.length
    def blank(from: Int): Int = {
      var i = from
      while (i < n && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) i += 1
      i
    }
    // The end of the line, or, inside a JSON string, a bare `"` that ends it.
    def endsLine(i: Int): Boolean =
      i == n || input.charAt(i) == '\n' || input.charAt(i) == '\r' ||
        (depth > 1 && input.charAt(i) == '"' && input.charAt(i - 1) != '\\')
    // The end of the input, or, inside a JSON string, a bare `"` that ends it. A line break alone is not: a quote
    // before it may as well open a string that a later line closes, as `'t="'` does for the pairs after it.
    def endsInput(i: Int): Boolean = !(i < n && (input.charAt(i) == '\n' || input.charAt(i) == '\r')) && endsLine(i)
    def pastClosers(from: Int): Int = {
      var i = blank(from)
      while (i < n && "}])".indexOf(input.charAt(i).toInt) >= 0) i = blank(i + 1)
      i
    }
    // The index after the quoted item opening at `from`, `'...'`, `"..."` or, inside a JSON string, `\"...\"`, if it
    // closes on its line, or -1.
    def afterItem(from: Int): Int = {
      val quote =
        if (from >= n) EnclosingQuotes.NoQuote
        else if (input.charAt(from) == '\'' || (depth == 1 && input.charAt(from) == '"')) input.charAt(from)
        else if (depth > 1 && input.startsWith("\\\"", from)) '"'
        else EnclosingQuotes.NoQuote
      var i      = if (quote == '"' && depth > 1) from + 2 else from + 1
      var result = if (quote == EnclosingQuotes.NoQuote) n + 1 else -1
      while (result < 0 && i < n) {
        val c = input.charAt(i)
        if (c == '\\') {
          var next = i
          while (next < n && input.charAt(next) == '\\') next += 1
          val slashes = next - i
          if (next < n) {
            val q = input.charAt(next)
            if (q == '"' && depth > 1) {
              // `\"` closes a `\"`-quoted item, `\\\"` is its text, and an even run leaves a bare `"`, which ends the
              // JSON string first.
              if (quote == '"' && (slashes & 3) == 1) result = next + 1
              else if ((slashes & 1) == 0) result = n + 1
            } else if (q == quote && quote == '\'' && !escapesQuote(slashes, depth)) result = next + 1
            else if (q == quote && quote == '"' && (slashes & 1) == 0) result = next + 1
            else if (q == '\n' || q == '\r') result = n + 1
          }
          i = next + 1
        } else if (c == '\n' || c == '\r' || (depth > 1 && c == '"')) result = n + 1
        else if (c == quote) result = i + 1
        else i += 1
      }
      if (result < 0 || result > n) -1 else result
    }
    // The index after a number, `None`, `True` or `False` at `from`, or -1.
    def afterLiteral(from: Int): Int = {
      val word = Seq("None", "True", "False").find(input.startsWith(_, from)).fold(0)(_.length)
      var i    = from + word
      if (word == 0) {
        if (i < n && input.charAt(i) == '-') i += 1
        val digits = i
        while (i < n && (input.charAt(i).isDigit || input.charAt(i) == '.')) i += 1
        if (i == digits) i = -1
      }
      if (i < 0 || (i < n && (input.charAt(i).isLetterOrDigit || input.charAt(i) == '_'))) -1 else i
    }
    val afterQuote = pastClosers(quote + 1)
    if (endsInput(afterQuote)) true
    else if (input.charAt(afterQuote) != ',') false
    else {
      var item = blank(afterQuote + 1)
      if (endsLine(item)) true
      else {
        while (item < n && "{[(".indexOf(input.charAt(item).toInt) >= 0) item = blank(item + 1)
        val itemEnd =
          if (item < n && "}])".indexOf(input.charAt(item).toInt) >= 0) item // an empty container
          else if (item < n && (input.charAt(item) == '\'' || input.charAt(item) == '"' || input.charAt(item) == '\\'))
            afterItem(item)
          else afterLiteral(item)
        itemEnd >= 0 && {
          val after = pastClosers(itemEnd)
          endsInput(after) || ",:".indexOf(input.charAt(after).toInt) >= 0
        }
      }
    }
  }

  /**
   * Whether a run of `slashes` backslashes escapes the `'` after it in a single-quoted string `depth` deep (see
   * `EnclosingQuotes.singleQuoteDepth`): Python's `\'` is an odd run in the string itself, and in a JSON string,
   * which doubles each backslash, a run whose half is odd (`\\'`).
   */
  private def escapesQuote(slashes: Int, depth: Int): Boolean = ((slashes >> (depth - 1)) & 1) == 1

  /**
   * The index of the character that ends a single-quoted value starting at `from` inside a double-quoted string: its
   * closing `'`, or a bare `"` that ends the enclosing string, as `endsString` reads it, and after which no `'` that
   * could close the value follows anywhere in the input (`lastSingleQuote`, the last `'` that is not the apostrophe
   * of a word). Any other `"` is taken for part of the value. A credential may hold a `"` followed by what follows
   * the end of a string (`'Qx"]]9secret'`, `'Qx", "k": 9secret'`), and only its closing `'` tells it from the end of
   * the string: while one may follow, the value runs on to it, as it does outside a string. A value that is closed
   * by neither runs to the end of the input, as `scanQuotedValue` has it.
   */
  @tailrec
  private def scanSingleQuotedInString(input: String, from: Int, lastSingleQuote: Int): Int =
    if (from >= input.length) {
      input.length
    } else {
      val c = input.charAt(from)
      if (c == '\'') from
      else if (c == '"' && lastSingleQuote < from && endsString(input, from + 1)) from
      else scanSingleQuotedInString(input, if (c == '\\') from + 2 else from + 1, lastSingleQuote)
    }

  /**
   * The index of the last `'` in the input that is not the apostrophe of a word - between two letters or digits, as
   * in `it's` - or -1 if there is none. Only a value before it can be closed by its own quote.
   */
  private def lastClosingSingleQuote(input: String): Int = {
    var i = input.length - 1
    while (
      i >= 0 && (input.charAt(i) != '\'' ||
        (i > 0 && i + 1 < input.length && input.charAt(i - 1).isLetterOrDigit && input.charAt(i + 1).isLetterOrDigit))
    ) i -= 1
    i
  }

  /**
   * Whether the text from `from` on is what follows the end of a JSON string value in an object: past whitespace,
   * the end of the input, a `,` before the next `"key":`, or a `}` or `]` followed by the end of the input, another
   * `}` or `]`, or a `,` before the next `"`, `{` or `[`. Only that much is read: whitespace and at most one key, so
   * each character is read a bounded number of times however many quotes ask.
   */
  private def endsString(input: String, from: Int): Boolean = {
    val length = input.length
    def skipSpace(at: Int): Int = {
      var i = at
      while (i < length && Character.isWhitespace(input.charAt(i))) i += 1
      i
    }
    def isKeyChar(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
    // `"key"` and then `:`, the key shaped as `Key` is.
    def keyAt(at: Int): Boolean =
      at + 1 < length && input.charAt(at) == '"' && input.charAt(at + 1).isLetter && input.charAt(at + 1) < 128 && {
        var j = at + 2
        while (j < length && j < at + 65 && isKeyChar(input.charAt(j))) j += 1
        j < length && input.charAt(j) == '"' && {
          val colon = skipSpace(j + 1)
          colon < length && input.charAt(colon) == ':'
        }
      }
    val i = skipSpace(from)
    if (i == length) true
    else
      input.charAt(i) match {
        case ',' => keyAt(skipSpace(i + 1))
        case '}' | ']' =>
          val j = skipSpace(i + 1)
          j == length || input.charAt(j) == '}' || input.charAt(j) == ']' || (input.charAt(j) == ',' && {
            val k = skipSpace(j + 1)
            k < length && "\"{[".indexOf(input.charAt(k).toInt) >= 0
          })
        case _ => false
      }
  }

  /**
   * Replaces the value of every `"key": "value"` whose key is sensitive. `start` matches up to the opening quote; the
   * value runs to the end found by `scanQuotedValue`, and the closing quote is left in place. An empty value is left as it is.
   *
   * With `endsWithString`, a single-quoted value whose key sits inside a double-quoted string (`EnclosingQuotes`)
   * ends at the end of that string if no `'` that could close it follows anywhere in the input
   * (`scanSingleQuotedInString`): a message that mentions `'password': '` without closing it then does not take the
   * rest of the document. Where such a `'` follows, the value runs to the next `'`, as it does outside a string.
   *
   * With `endsWithSingleQuoted`, the other way round: a double-quoted value whose key sits inside a single-quoted
   * string - or, for an escaped key, a single-quoted string inside the double-quoted string that encloses it - ends at
   * its own quote or at a `'` that ends that string (`endsSingleQuotedString`), so that a Python dict whose message
   * mentions `"password": "` keeps the key of its next field for the passes after (#1697).
   *
   * With `asValue`, a match is a value only where it can be one of a dict: closed before `,` and the next key or
   * before `}`, or not closed at all, so that a quote that opens a later key (`'password': "token": [`) or sits
   * inside a later value does not close it, and the key it would run over is left to the passes after; and, for the
   * bare `"`, only where its key does not sit inside a double-quoted string, whose end that `"` would be.
   *
   * With `pair`, `start` is a `key="` or `key='` of `PairStarts`, and the value of a key that is not sensitive and
   * whose `=` is an escape is not skipped: the search goes on right after the escape (see `afterEscapedEquals`).
   */
  private def redactQuoted(
    start: Regex,
    input: String,
    placeholder: String,
    end: ValueEnd,
    endsWithString: Boolean = false,
    endsWithSingleQuoted: Boolean = false,
    asValue: Boolean = false,
    pair: Boolean = false
  ): Rewrite = {
    val matcher        = start.pattern.matcher(input)
    val out            = new Rewrite(input, placeholder)
    val outsideStrings = asValue && end == ValueEnd.Quote('"')
    val enclosing =
      if (endsWithString || endsWithSingleQuoted || outsideStrings) Some(new EnclosingQuotes(input)) else None
    lazy val lastSingleQuote = lastClosingSingleQuote(input)

    // Inside a double-quoted string, a value that is the placeholder followed by a `"` that ends the string - one
    // this pass ended there on an earlier `redact`, with no `'` after it - ends there again, whatever the quotes the
    // placeholder replaced made of the string around it, so that redacting the output a second time changes nothing.
    // Not outside a string, where a value ends only at its own quote, nor where a `'` that could close the value
    // follows: an earlier pass writes the placeholder too (`'Bearer abc"]}secret'` becomes `'[REDACTED]"]}secret'`),
    // and the rest of that value is still to be redacted.
    def alreadyEnded(valueStart: Int): Boolean = {
      val after = valueStart + placeholder.length
      placeholder.nonEmpty && input.startsWith(placeholder, valueStart) && after < input.length &&
      input.charAt(after) == '"' && lastSingleQuote < after && endsString(input, after + 1)
    }

    def valueEndOf(enclosingQuote: Char, valueStart: Int): Int =
      if (endsWithString && enclosingQuote == '"') {
        if (alreadyEnded(valueStart)) valueStart + placeholder.length
        else {
          // A value the end of the string would leave empty (`'password': '", ...`) is no value: the quote after the
          // key may as well be the start of one that runs on, so it is scanned as outside a string.
          val stringEnd = scanSingleQuotedInString(input, valueStart, lastSingleQuote)
          if (stringEnd == valueStart && stringEnd < input.length && input.charAt(stringEnd) == '"')
            scanQuotedValue(input, valueStart, end)
          else stringEnd
        }
      } else if (endsWithSingleQuoted && singleQuoteDepth > 0) {
        // A double-quoted value inside a single-quoted string, which a Python dict whose message mentions
        // `"password": "` holds, ends where that string does (#1697). Python escapes a `'` in such a string, so a
        // value the end of the string leaves empty is empty: read on, it would take the key of the next field.
        scanQuotedValue(input, valueStart, end, singleQuoteDepth)
      } else scanQuotedValue(input, valueStart, end)

    // The single-quoted string the key of the match sits in, if any, read within its line: one that encloses it, or,
    // for an escaped key, one inside the double-quoted string that encloses it - a Python dict inside a JSON string. A
    // bare `"` that the enclosing double-quoted string holds ends it, so a bare key there is in no single-quoted string.
    def singleQuoteDepth: Int = enclosing.fold(0)(_.singleQuoteDepth(escapedKey = end == ValueEnd.EscapedQuote))

    def skipSpace(from: Int): Int = {
      var i = from
      while (i < input.length && Character.isWhitespace(input.charAt(i))) i += 1
      i
    }

    // Whether a quoted key and its `:` start at `at`: a quote, at most `MaxKeyLength` characters that are neither
    // that quote nor a line break, the same quote, and `:`. Bounded, so each value asks a bounded number of reads.
    def keyAt(at: Int): Boolean =
      at < input.length && (input.charAt(at) == '\'' || input.charAt(at) == '"') && {
        val q = input.charAt(at)
        var j = at + 1
        while (j < input.length && j <= at + MaxKeyLength && input.charAt(j) != q && input.charAt(j) != '\n') j += 1
        j < input.length && input.charAt(j) == q && {
          val colon = skipSpace(j + 1)
          colon < input.length && input.charAt(colon) == ':'
        }
      }

    // Whether the value that ends at `valueEnd` is closed where a value of a dict is - before `,` and the next key, or
    // before a `}` that no word runs on from - or not closed at all.
    def endsAsValue(valueEnd: Int): Boolean =
      valueEnd >= input.length || {
        val i = skipSpace(valueEnd + (if (input.charAt(valueEnd) == '\\') 2 else 1))
        i >= input.length || (input.charAt(i) match {
          case ',' => keyAt(skipSpace(i + 1))
          case '}' => i + 1 >= input.length || !input.charAt(i + 1).isLetterOrDigit
          case _   => false
        })
      }

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else if (outsideStrings && enclosing.exists(_.enclosingAt(matcher.start(1)) == '"')) {
        loop(matcher.end, copiedTo)
      } else if (pair && !isSensitiveKey(matcher.group(2)) && afterEscapedEquals(input, matcher.end(2)) >= 0) {
        loop(afterEscapedEquals(input, matcher.end(2)), copiedTo)
      } else {
        val valueStart = matcher.end
        val valueEnd =
          valueEndOf(enclosing.fold(EnclosingQuotes.NoQuote)(_.enclosingAt(matcher.start(1))), valueStart)
        if (asValue && !endsAsValue(valueEnd)) {
          loop(matcher.end, copiedTo)
        } else if (isSensitiveKey(matcher.group(2)) && valueEnd > valueStart) {
          out.copy(copiedTo, valueStart)
          out.mask()
          loop(valueEnd, valueEnd)
        } else {
          loop(valueEnd, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.finish(copiedTo)
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
    private var pos: Int           = 0
    private var open: Char         = EnclosingQuotes.NoQuote
    private var inEscaped: Boolean = false
    private var openedAt: Int      = -1 // where the open string opened
    private var lineStart: Int     = 0  // the index after the last line break passed

    // The same scan within the line only, for `singleQuoteDepth`: a string open at a line break ends there, so a stray
    // quote on an earlier line (`the users' settings`, `'tis`) changes nothing on a later one (#1697).
    private var lineOpen: Char      = EnclosingQuotes.NoQuote
    private var lineOpenedAt: Int   = -1
    private var inSingle: Boolean   = false // inside a single-quoted string within the double-quoted one open
    private var singleAtValue       = false // that single-quoted string, or the one open, opened where a value does
    private var inPyDouble: Boolean = false // inside a `\"`-quoted Python string within the double-quoted one open

    /** The quote of the string that is open just before `index`, or `NoQuote`. */
    def enclosingAt(index: Int): Char = {
      while (pos < index) {
        val c = input.charAt(pos)
        if (c == '\\') {
          if (open == '"' && pos + 1 < input.length && input.charAt(pos + 1) == '"') inEscaped = !inEscaped
          lineBackslash(pos)
          if (pos + 1 < input.length && (input.charAt(pos + 1) == '\n' || input.charAt(pos + 1) == '\r')) {
            lineStart = pos + 2
            endLine()
          }
          pos += 1
        } else if (c == '\n' || c == '\r') {
          lineStart = pos + 1
          endLine()
        } else {
          lineChar(pos, c)
          if (open != EnclosingQuotes.NoQuote) {
            if (c == open && !isApostrophe(pos)) {
              open = EnclosingQuotes.NoQuote
              inEscaped = false
            }
          } else if ((c == '"' || c == '\'') && !isApostrophe(pos)) {
            open = c
            openedAt = pos
          }
        }
        pos += 1
      }
      open
    }

    /**
     * Whether, at the position `enclosingAt` was last asked about, the string open there opened on the same line: no
     * line break character (the escape `\n` is not one) lies between its opening quote and the position.
     */
    def openedOnItsLine: Boolean = open != EnclosingQuotes.NoQuote && openedAt >= lineStart

    /**
     * Whether, at the position `enclosingAt` was last asked about, an odd number of `\"` has passed since the
     * double-quoted string open there opened: the position sits inside a string escaped within that string, whose
     * next `\"` ends it.
     */
    def inEscapedString: Boolean = inEscaped

    /**
     * How deep the single-quoted string open at the position `enclosingAt` was last asked about sits, read within its
     * line: 1 where it is open there itself, 2 where it sits inside the double-quoted string open there - as a Python
     * dict inside a JSON string has its strings - whose escapes double its backslashes, and 0 where there is none or
     * where it did not open where a value does (`opensValue`), and so may be a stray quote (#1697). With
     * `escapedKey` false, a double-quoted key there is bare, and its quote ends the double-quoted string: 0.
     */
    def singleQuoteDepth(escapedKey: Boolean): Int =
      if (!singleAtValue) 0
      else if (lineOpen == '\'') 1
      else if (lineOpen == '"' && inSingle && escapedKey) 2
      else 0

    private def endLine(): Unit = {
      lineOpen = EnclosingQuotes.NoQuote
      inSingle = false
      inPyDouble = false
    }

    // A backslash at `i`, which escapes the character after it: a `\"` that Python reads as a quote (one backslash, or
    // five...) inside a double-quoted string opens or closes a double-quoted Python string within it, whose `'` are
    // its text, outside a single-quoted one, whose text it is.
    private def lineBackslash(i: Int): Unit =
      if (lineOpen == '"' && i + 1 < input.length && input.charAt(i + 1) == '"' && !inSingle) {
        if ((backslashesBefore(i) & 3) == 0) {
          if (inPyDouble) inPyDouble = false
          else if (opensValue(i)) inPyDouble = true
        }
      }

    private def lineChar(i: Int, c: Char): Unit =
      if (lineOpen != EnclosingQuotes.NoQuote) {
        if (c == lineOpen && !isApostrophe(i)) endLine()
        else if (
          lineOpen == '"' && c == '\'' && !inPyDouble && !isApostrophe(i) && !escapesQuote(backslashesBefore(i), 2)
        ) {
          inSingle = !inSingle
          if (inSingle) singleAtValue = opensValue(i)
        }
      } else if ((c == '"' || c == '\'') && !isApostrophe(i)) {
        lineOpen = c
        lineOpenedAt = i
        if (c == '\'') singleAtValue = opensValue(i)
      }

    // Whether the `'` at `i` opens a string where a Python dict, list or tuple has a value or a key: after `{`, `[`,
    // `(`, `,` or `:`, after `=` (`cfg='...'`), at the start of the line or of the double-quoted string open there,
    // spaces aside. A `'` in a word or after other punctuation (`QC(A7'<H`, a stray quote in a credential) opens no
    // string a field's value could end at. Reads the spaces before the quote once.
    private def opensValue(i: Int): Boolean = {
      var j = i - 1
      while (j >= lineStart && Character.isWhitespace(input.charAt(j))) j -= 1
      j < lineStart || "{[(,:=".indexOf(input.charAt(j).toInt) >= 0 || (lineOpen == '"' && j == lineOpenedAt)
    }

    // The backslashes right before `i`. Asked once for each quote, and a run of backslashes ends at one character only.
    private def backslashesBefore(i: Int): Int = {
      var j = i
      while (j > 0 && input.charAt(j - 1) == '\\') j -= 1
      i - j
    }

    private def isApostrophe(i: Int): Boolean = Redaction.isApostrophe(input, i)
  }

  /** Whether the character at `i` is a `'` between two letters or digits: an apostrophe, never a quote. */
  private def isApostrophe(input: String, i: Int): Boolean =
    input.charAt(i) == '\'' && i > 0 && i + 1 < input.length &&
      input.charAt(i - 1).isLetterOrDigit && input.charAt(i + 1).isLetterOrDigit

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
     * and ends the container under a single-quoted key - it may be a double-quoted leaf or the end of a string inside
     * the string - unless it opens a leaf that its own `\"` closes, where a value stands, and the key does not sit in
     * a string escaped within the string (#1687): Python's repr of a value that holds a `'`.
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
  ): Rewrite = {
    val matcher   = start.pattern.matcher(input)
    val out       = new Rewrite(input, placeholder)
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
          out.copy(copiedTo, open)
          val walk = walkAt(matcher.start(1))
          // Leaves in escaped double quotes, only where the key is not itself inside a string escaped within the
          // string, whose closing `\"` would otherwise be read as the opening quote of a leaf.
          val escapedLeaves = leaves && walk == Walk.InDoubleQuotes && !enclosing.inEscapedString
          val valueEnd      = redactLeaves(input, open, out, placeholder, end, walk, leaves, escapedLeaves)
          loop(valueEnd, valueEnd)
        } else {
          loop(open + 1, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.finish(copiedTo)
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
    out: Rewrite,
    placeholder: String,
    end: ValueEnd,
    walk: Walk,
    leaves: Boolean,
    escapedLeaves: Boolean
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
        out.copy(open, next)
      } else {
        // The quotes are the input's: `stringQuote` is what opens the string at `open`, and closes it at `contentEnd`.
        out.copy(open, contentStart)
        if (contentEnd > contentStart) out.mask()
        if (closed) out.copy(contentEnd, next)
      }
      next
    }

    // Whether a leaf scanned inside a double-quoted string stopped at a bare `"`, the end of the enclosing string,
    // and not at its own quote or the end of the input.
    def endedByString(contentEnd: Int): Boolean =
      inDoubleQuotes && contentEnd < length && input.charAt(contentEnd) == '"'

    // Whether the text from `from` to `until` holds a `\"`: JSON escaped inside the enclosing string, whose quotes
    // the walk would pair from the wrong one if it went on past an apostrophe. The text is the inside of one leaf, so
    // each character is read once more at most.
    def holdsEscapedQuote(from: Int, until: Int): Boolean = {
      var j = from
      while (j + 1 < until && !(input.charAt(j) == '\\' && input.charAt(j + 1) == '"')) j += 1
      j + 1 < until
    }

    // Whether the `'` at `open` is the apostrophe of a word of prose, as in `it's` or `O'Brien`: it sits between two
    // letters or digits, and the word before it follows whitespace - not a bracket, a comma or a quote, after which a
    // leaf would start - and is not a Python string prefix (`b'...'`, `rb'...'`: one or two of b, r, u and f).
    def isProseApostrophe(open: Int): Boolean =
      open + 1 < length && input.charAt(open + 1).isLetterOrDigit && {
        var j = open - 1
        while (j >= 0 && input.charAt(j).isLetterOrDigit) j -= 1
        val word = input.substring(j + 1, open)
        word.nonEmpty && j >= 0 && Character.isWhitespace(input.charAt(j)) &&
        !(word.length <= 2 && word.forall(c => "bBrRuUfF".indexOf(c.toInt) >= 0))
      }

    // Whether the quote at `open` opens a string where a value stands: in an object after `:`, in an array after `[`
    // or `,`. Prose has a letter before its apostrophes. Only whitespace is skipped, and the run before a quote is
    // read for that quote alone, so the cost stays linear.
    def precededAsValue(open: Int): Boolean = {
      var j = open - 1
      while (j >= 0 && isSpace(input.charAt(j))) j -= 1
      j >= 0 && (if (inObject) input.charAt(j) == ':' else input.charAt(j) == '[' || input.charAt(j) == ',')
    }

    // Whether what follows a string from `next` on is what follows a value: `,`, `]` or `}`, or, with `orEnd`, the
    // end of the input or, inside a string, that string's end.
    def followedAsValue(next: Int, orEnd: Boolean): Boolean = {
      var k = next
      while (k < length && isSpace(input.charAt(k))) k += 1
      if (k == length) orEnd
      else ",]}".indexOf(input.charAt(k).toInt) >= 0 || (orEnd && inDoubleQuotes && input.charAt(k) == '"')
    }

    // Whether the `\"` whose backslashes start at `from` and whose quote is at `quoteAt` opens a leaf of a container
    // under a single-quoted key inside a double-quoted string: the string it opens is closed by its own `\"` and
    // stands where a value does. Any other `"` ends the container, as the doc of `Walk.InDoubleQuotes` has it.
    def escapedLeaf(from: Int, quoteAt: Int): Boolean = {
      val contentEnd = scanQuotedValue(input, quoteAt + 1, ValueEnd.EscapedQuote)
      contentEnd < length && input.charAt(contentEnd) == '\\' && precededAsValue(from) &&
      followedAsValue(contentEnd + 2, orEnd = false)
    }

    // A string in the other quote than the key's (`'abc'` under `"token"`, `"abc"` under `'token'`), or in the key's
    // own `'`: a key before `:` is kept whole, and a string that cannot be a value - prose, whose `'` is an apostrophe
    // run on by a letter, or the apostrophe of a word of prose that only the end of the enclosing string would close
    // (the `'` of `it's urgent!"`) with no `\"` before that end, or text with a `:` or `=`, which is no leaf but a
    // field the passes before have seen to - is not taken for one: its quote is an ordinary character and the walk
    // goes on inside it. Under a single-quoted key a single-quoted string with a `:` or `=` is a leaf all the same
    // where it stands as a value does (`precededAsValue`, `followedAsValue`): a connection string or a `key=value`
    // token in a Python dict
    // (#1675). A double-quoted one is not: there it is as likely the end of a string around the dict and the start
    // of the next, in a dict pasted into a string without escaping.
    def emitOtherString(open: Int, stringEnd: ValueEnd): Int = {
      val contentStart = open + 1
      val contentEnd   = scanQuotedValue(input, contentStart, stringEnd)
      val closed = contentEnd < length && (stringEnd match {
        case ValueEnd.QuoteInString(q) => input.charAt(contentEnd) == q
        case _                         => true
      })
      val next = if (closed) contentEnd + 1 else contentEnd
      if (closed && keyFollows(next)) {
        out.copy(open, next)
        next
      } else if (
        (closed && inDoubleQuotes && next < length && input.charAt(next).isLetterOrDigit) ||
        (endedByString(contentEnd) && isProseApostrophe(open) && !holdsEscapedQuote(contentStart, contentEnd + 1)) ||
        (!isLeafText(input, contentStart, contentEnd) &&
          !(end == ValueEnd.Quote('\'') && input.charAt(open) == '\'' && precededAsValue(open) &&
            followedAsValue(next, orEnd = true)))
      ) {
        out.copy(open, open + 1)
        open + 1
      } else {
        out.copy(open, open + 1)
        if (contentEnd > contentStart) out.mask()
        if (closed) out.copy(contentEnd, contentEnd + 1)
        next
      }
    }

    // What ends a bare leaf - a number, or a value that is not quoted - and separates leaves: whitespace, `,`, `:`,
    // a bracket, a quote and a backslash.
    def separates(c: Char): Boolean =
      isSpace(c) || c == ',' || c == ':' || c == '[' || c == ']' || c == '{' || c == '}' || c == '"' ||
        c == '\'' || c == '\\'

    // Whether an odd run of backslashes ends just before `at`, so that the character at `at` is escaped. Each run is
    // counted for the one word after it, so the cost stays linear.
    def escapedAt(at: Int): Boolean = {
      var j = at - 1
      while (j >= 0 && input.charAt(j) == '\\') j -= 1
      (at - 1 - j) % 2 == 1
    }

    // The placeholder in the key's quote, in place of a bare leaf.
    def wrapped(): Unit = {
      out.text(quote)
      out.mask()
      out.text(quote)
    }

    // A word as the walk before #1647 wrote it: every character as it is, bar a number, which is replaced.
    def copyWord(start: Int, stop: Int): Unit = {
      var j = start
      while (j < stop) {
        val c = input.charAt(j)
        if (c == '-' || (c >= '0' && c <= '9')) {
          j += 1
          while (j < stop && isNumberChar(input.charAt(j))) j += 1
          wrapped()
        } else {
          out.copy(j, j + 1)
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
        out.copy(i, i + placeholder.length)
        i += placeholder.length
      } else if (c == '[' || c == '{') {
        openers.append(c)
        out.copy(i, i + 1)
        i += 1
      } else if (c == ']' || c == '}') {
        if (openers.length > 0) openers.setLength(openers.length - 1)
        out.copy(i, i + 1)
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
        if (beforeQuote && escapedLeaves && end != ValueEnd.EscapedQuote && slashes % 4 == 1 && escapedLeaf(i, next)) {
          // A double-quoted leaf of a Python dict inside the string, as repr writes a value holding a `'` (#1687).
          out.copy(i, next - 1)
          i = emitString(next - 1, ValueEnd.EscapedQuote)
        } else if (beforeQuote && end != ValueEnd.EscapedQuote) {
          // Any `"` under a single-quoted key ends the container; the backslashes are left with it for the caller.
          done = true
        } else if (beforeQuote && slashes % 4 == 1) {
          out.copy(i, next - 1)
          i = emitString(next - 1, ValueEnd.EscapedQuote)
        } else if (beforeQuote && slashes % 2 == 0) {
          out.copy(i, next)
          i = next
          done = true
        } else {
          // An escape between values (`\n` of a pretty-printed document), copied with the character it escapes.
          val stop = math.min(length, next + 1)
          out.copy(i, stop)
          i = stop
        }
      } else if (c == '\\' && leaves) {
        // A backslash escapes the character after it, as it does for the scan of a string, so that a string whose
        // quote was not taken for a leaf is walked as that scan read it, and each quote opens at most one scan.
        val stop = math.min(length, i + 2)
        out.copy(i, stop)
        i = stop
      } else if (separates(c)) {
        out.copy(i, i + 1)
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
        if (replace && escapedAt(i)) {
          // The word follows a backslash, which escapes its first character: a quote written there would be read as
          // `\"`, and the quotes after it would pair the other way round. The escaped character is kept, as the walk
          // before #1647 kept it, and the rest of the word is replaced.
          out.copy(i, i + 1)
          if (next > i + 1) wrapped()
        } else if (replace) wrapped()
        else if (leaves) out.copy(i, next)
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
   * Replaces the value of every match whose key is sensitive. A value that is already the placeholder is replaced
   * by the placeholder, so this pass leaves its own output as it is; whether the whole of `redact` does is said
   * on [[redact]].
   */
  private def redactPairs(
    pattern: Regex,
    input: String,
    placeholder: String,
    wrap: String = "",
    sensitive: String => Boolean = isSensitiveKey
  ): Rewrite =
    regexPass(pattern, input, placeholder, group = 3, wrap = wrap, replaces = m => sensitive(m.group(2)))

  /**
   * Replaces the value of every `key=value` pair whose key is sensitive. The value, which `PairStarts.equals` finds the
   * start of, runs to whitespace, to one of `&"',;<>`, or to the JSON escape of `&`: a query string or a form body in
   * JSON that Go's `encoding/json` wrote has every `&` escaped (#1676). The text after the escape is read for pairs
   * again, as the text after `&` is. A loop that reads each character of a value once.
   *
   * The value of a sensitive key ends at an escaped `&` only where another pair, `key=` or `key` and the escape of
   * `=`, follows it. A credential may hold an `&` (`password=p&ssW0rd`), which the same serialisers escape, and the
   * value of a sensitive key ran over a literal `&` before: cut at every escape, the rest of the credential was
   * written in the clear. The `=` may be escaped too (`PairEquals`), as Gson writes it.
   *
   * A value that opens with an escaped quote (a backslash and `u0027` or `u0022`, which System.Text.Json and Gson
   * write for `'` and `"`) is one credential up to the matching escaped quote, as `password='p&user=x y'` is between
   * bare quotes: until it closes, the value ends neither at an escaped `&` nor at whitespace, `&`, `,` or `;`, only
   * at a bare quote, which ends the string the pair sits in, at `<`, `>` or at a line break. After the closing escaped
   * quote, the rules above apply again. A key that is not sensitive keeps them throughout, so the pairs inside its
   * quotes are still read, and its value ends at the escape of a quote as at the quote - and, where the input is
   * `escaped` (it holds the escape of a separator or a quote, see [[redact]]), at the escape of `<` or `>` as at the
   * character - so the pair after it is read too: here by this pass, or, where the pair's value opens with an
   * escaped quote, by `redactEscapedQuoted`, as `key='...'` is read by the pass for quoted values.
   *
   * The backslashes that escape the quote the value stops at are kept. Inside JSON that sits in a string,
   * `\"note\": \"token=abc\"`, the value runs up to the `"` of the escaped closing quote and so holds its `\`:
   * written over with the placeholder, the `"` left bare ended the enclosing string there, the document no longer
   * parsed, and the passes after this one paired its quotes the wrong way round (#1677).
   *
   * Of a run of `n` backslashes before the quote, `2^t - 1` are kept, where `t` is the number of trailing one bits
   * of `n`. A quote escaped `d` strings deep follows `2^d - 1` backslashes, and each backslash of the value before it
   * adds `2^(d+1)` more, so what is kept is the escape and what is replaced is the value's: the quote is escaped at
   * every depth as it was, and the document parses at every depth as it did. Only backslashes are kept, never a
   * character of the value that is not one. A run before anything but a quote, and an even run, which escapes
   * nothing, are replaced whole, as they were. A value cut at an escaped `&` keeps nothing: no quote follows it.
   *
   * A sensitive key's value that opens with a quote escaped by backslashes (`\"` or `\'`, at any depth: a logfmt
   * line inside a JSON string, `password=\"hunter2\"`) is a quoted value, as `password="..."` is for the pass for
   * quoted values: its content, up to the same quote escaped as deep (`backslashQuotedValueEnd`), is replaced, and the
   * escaped quotes around it are kept, so the document still parses (#1684). Before, the value stopped at the escaped
   * quote and the content after it was written in the clear.
   */
  private def redactEqualsPairs(input: String, placeholder: String, escaped: Boolean): Rewrite = {
    val matcher   = pairStarts(escaped).equals.pattern.matcher(input)
    val out       = new Rewrite(input, placeholder)
    val enclosing = new EnclosingQuotes(input)

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom >= input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else if (!isSensitiveKey(matcher.group(2)) && afterEscapedEquals(input, matcher.end(2)) >= 0) {
        // A key that is not sensitive and whose `=` is an escape: the text after the escape is read for pairs, as the
        // text the escape stands for would be (see `afterEscapedEquals`).
        loop(matcher.end, copiedTo)
      } else if (isSensitiveKey(matcher.group(2)) && backslashQuoteDepth(input, matcher.end) > 0) {
        // A value in quotes escaped with backslashes (`password=\"hunter2\"`, a logfmt line inside a JSON string):
        // its content is replaced, and the escaped quotes around it are kept (#1684).
        val contentStart = afterBackslashes(input, matcher.end) + 1
        enclosing.enclosingAt(matcher.start(2))
        val (contentEnd, next) =
          backslashQuotedValueEnd(
            input,
            contentStart,
            input.charAt(contentStart - 1),
            backslashQuoteDepth(input, matcher.end),
            enclosing.singleQuoteDepth(escapedKey = true)
          )
        if (contentEnd > contentStart) {
          out.copy(copiedTo, contentStart)
          out.mask()
          loop(next, contentEnd)
        } else loop(next, copiedTo)
      } else {
        val valueStart = matcher.end
        val sensitive  = isSensitiveKey(matcher.group(2))
        var valueEnd   = valueStart
        var escapeEnd  = -1
        // The last hex digit of the escaped quote the value opened with and has not closed yet, or `NotAQuote`.
        var openQuote = NotAQuote
        // The value of a key that is not sensitive ends at the escape of a quote, `<` or `>`, as it ends at the
        // character, so the pair after it is read, by this pass or by the one for values in escaped quotes.
        var stopped = false
        def ends(c: Char): Boolean =
          if (openQuote == NotAQuote || !sensitive) endsEqualsValue(c) else endsEscapedQuotedValue(c)
        while (!stopped && escapeEnd < 0 && valueEnd < input.length && !ends(input.charAt(valueEnd)))
          if (input.charAt(valueEnd) == '\\') {
            val end = separatorEscapeEnd(input, valueEnd)
            if (end >= 0 && escapedSeparator(input, end) == '&') {
              if (!sensitive || (openQuote == NotAQuote && startsPair(input, end))) escapeEnd = end
              else valueEnd = end
            } else {
              val quoteEnd = quoteEscapeEnd(input, valueEnd)
              if (!sensitive && (quoteEnd >= 0 || (escaped && angleEscapeEnd(input, valueEnd) >= 0))) stopped = true
              else if (quoteEnd >= 0) {
                val quote = input.charAt(quoteEnd - 1)
                if (valueEnd == valueStart) openQuote = quote
                else if (quote == openQuote) openQuote = NotAQuote
                valueEnd = quoteEnd
              } else valueEnd = afterBackslashes(input, valueEnd)
            }
          } else {
            valueEnd += 1
          }
        val copied =
          if (sensitive && valueEnd > valueStart) {
            // The backslashes kept are the last before the quote, copied as they are.
            out.copy(copiedTo, valueStart)
            out.mask()
            valueEnd - quoteEscape(input, valueStart, valueEnd).length
          } else copiedTo
        loop(if (escapeEnd >= 0) escapeEnd else valueEnd, copied)
      }

    val copiedTo = loop(0, 0)
    out.finish(copiedTo)
  }

  /** The number of trailing one bits of `n`: the depth of the escape a run of `n` backslashes before a quote writes. */
  private def escapeDepth(n: Int): Int = Integer.numberOfTrailingZeros(~n)

  /**
   * Where a run of backslashes at `at` escapes the `"` or `'` right after it, the depth of that escape (see
   * `escapeDepth`: 1 for `\"`, 2 for `\\\"`, the quote of a string inside a string inside a string), or 0 where no
   * backslash or no quote is there, or the run is even and so escapes nothing.
   */
  private def backslashQuoteDepth(input: String, at: Int): Int = {
    val run = afterBackslashes(input, at)
    if (run == at || run >= input.length || (input.charAt(run) != '"' && input.charAt(run) != '\'')) 0
    else escapeDepth(run - at)
  }

  /**
   * The end of the content of a `key=` value that opens with `quote` escaped `depth` deep, which starts at `from`,
   * and the index to read on from (#1684). The value closes at the same quote escaped as deep: a run of backslashes
   * whose `escapeDepth` is `depth`, so that a backslash of the value escaped before it (`\"ab\\\\\"`, the value
   * `ab\`) is the value's, and a quote escaped deeper (`\"ab\\\"cd\"`, the value `ab"cd`) is too. The same quote
   * escaped less deep, or bare, ends the string the pair sits in, and a line break ends the line: the value is not
   * closed and ends there, and so does an input that ends first. The backslashes that escape the quote it ends at
   * are not content (`quoteEscape`), so they are kept, and the document still parses at every depth. The other quote
   * is content at any depth, but with a `singleQuoteDepth`, where the key sits in a single-quoted string (a Python
   * dict, bare or inside a JSON string, see `EnclosingQuotes.singleQuoteDepth`), a `'` that ends that string
   * (`endsSingleQuotedString`) and is neither escaped (`escapesQuote`) nor doubled (`''`) ends the value too (#1697).
   * A loop that reads each character a bounded number of times.
   */
  private def backslashQuotedValueEnd(
    input: String,
    from: Int,
    quote: Char,
    depth: Int,
    keyQuoteDepth: Int
  ): (Int, Int) = {
    var singleQuoteDepth = keyQuoteDepth
    var i                = from
    var result           = (-1, -1)
    while (result._1 < 0 && i < input.length) {
      val c = input.charAt(i)
      if (c == '\\') {
        val run = afterBackslashes(input, i)
        if (run < input.length && input.charAt(run) == quote) {
          val d = escapeDepth(run - i)
          if (d == depth) result = (run - quoteEscape(input, from, run).length, run + 1)
          else if (d < depth) result = (run - quoteEscape(input, from, run).length, run)
          else i = run + 1
        } else if (
          singleQuoteDepth > 0 && run < input.length && input.charAt(run) == '\'' &&
          escapesQuote(run - i, singleQuoteDepth)
        ) i = run + 1
        else i = run
      } else if (c == quote || c == '\n' || c == '\r') result = (i, i)
      else if (singleQuoteDepth > 0 && c == '\'') {
        if (i + 1 < input.length && input.charAt(i + 1) == '\'') i += 2
        else if (isApostrophe(input, i)) i += 1
        else if (endsSingleQuotedString(input, i, singleQuoteDepth)) result = (i, i)
        else { singleQuoteDepth = 0; i += 1 } // the first quote decides, as in `scanQuotedValue`
      } else i += 1
    }
    if (result._1 < 0) (input.length, input.length) else result
  }

  /**
   * Where the key of a `key=value` pair ends at `keyEnd` in the JSON escape of `=` (backslashes and `u003d`, see
   * `PairEquals`), the index after that escape, or -1 where the `=` is bare. The value of a key that is not sensitive
   * is skipped only after a bare `=`. After an escaped one it is read for pairs: the escape and the text after it may
   * hold the key of a pair that the text the escapes stand for has, and a key that is not sensitive - one that
   * starts at the `u` of the escape of a letter (`u0070assword`, the escape of `p` and `assword`) or any other - would
   * take that pair for its value, and no pass would read the credential (#1676).
   */
  private def afterEscapedEquals(input: String, keyEnd: Int): Int =
    if (keyEnd < input.length && input.charAt(keyEnd) == '\\') afterBackslashes(input, keyEnd) + 5 else -1

  /** `key=`, or `key` and the JSON escape of `=`: the start of the next pair, read where an escaped `&` ends. */
  private val PairAhead: java.util.regex.Pattern = java.util.regex.Pattern.compile(s"""$Key(?:=|\\\\+[u]003[dD])""")

  /** Whether a `key=value` pair starts at `at`. Reads at most a key and an escape, so a bounded number of characters. */
  private def startsPair(input: String, at: Int): Boolean =
    PairAhead.matcher(input).region(at, input.length).lookingAt()

  /**
   * The index after the JSON escape of `'` or `"` that the run of backslashes at `i` starts - `u0027` or `u0022` -
   * or -1 if it starts none. The last character before that index tells which quote it writes.
   */
  private def quoteEscapeEnd(input: String, i: Int): Int = {
    val u = afterBackslashes(input, i)
    val escapes =
      u > i && u + 5 <= input.length && input.charAt(u) == 'u' && input.charAt(u + 1) == '0' &&
        input.charAt(u + 2) == '0' && input.charAt(u + 3) == '2' && "27".indexOf(input.charAt(u + 4).toInt) >= 0
    if (escapes) u + 5 else -1
  }

  private val NotAQuote: Char = ' '

  /** The index after the JSON escape of `<` or `>` (`u003c` or `u003e`) that the backslashes at `i` start, or -1. */
  private def angleEscapeEnd(input: String, i: Int): Int = {
    val u = afterBackslashes(input, i)
    val escapes =
      u > i && u + 5 <= input.length && input.startsWith("u003", u) && "cCeE".indexOf(input.charAt(u + 4).toInt) >= 0
    if (escapes) u + 5 else -1
  }

  /** The characters that end the value of a `key=value` pair: whitespace and `&"',;<>`. */
  private def endsEqualsValue(c: Char): Boolean = isRegexSpace(c) || "&\"',;<>".indexOf(c.toInt) >= 0

  /**
   * The characters that end the value of a `key=value` pair inside escaped quotes that have not closed: a bare quote,
   * which ends the string the pair sits in, `<`, `>` and a line break. Whitespace, `&`, `,` and `;` are the
   * credential's, as they are between bare quotes in `key='...'`.
   */
  private def endsEscapedQuotedValue(c: Char): Boolean = c == '\n' || c == '\r' || "\"'<>".indexOf(c.toInt) >= 0

}
