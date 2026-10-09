package org.llm4s.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
   * A URL fit for a `toString` or a log line: scheme, host, port and path are kept; userinfo
   * (`user:password@`), the query and the fragment, which may carry credentials or a signature, are
   * replaced by `***`. A value that does not parse as an absolute URL is `***` altogether.
   */
  def url(value: String): String =
    scala.util
      .Try(new java.net.URI(value.trim))
      .toOption
      .filter(uri => uri.getScheme != null && uri.getRawAuthority != null)
      .map { uri =>
        val userInfo = Option(uri.getRawUserInfo).fold("")(_ => "***@")
        val host     = Option(uri.getHost).getOrElse("***")
        val port     = if uri.getPort >= 0 then s":${uri.getPort}" else ""
        val path     = Option(uri.getRawPath).getOrElse("")
        val query    = Option(uri.getRawQuery).fold("")(_ => "?***")
        val fragment = Option(uri.getRawFragment).fold("")(_ => "#***")
        s"${uri.getScheme}://$userInfo$host$port$path$query$fragment"
      }
      .getOrElse("***")

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
   * so each character is read by at most one attempt.
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
    "bearertoken",
    // OAuth 2.0 / RFC 8693 token-endpoint fields: `subject_token`, `actor_token`, `assertion`, `client_assertion`
    "subjecttoken",
    "actortoken",
    "assertion"
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
      val step1 = redactAuthHeaders(input, placeholder)
      val step2 = redactQueryParams(step1, placeholder)
      val step3 = redactJsonFields(step2, placeholder, input.count(_ == '\''))
      val step4 = redactApiKeys(step3, placeholder)
      redactContainerLeaves(step4, placeholder)
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

  /**
   * A remote reply's body (or an exception message carrying one), fit to put in an `LLMError` or a log
   * line. In order: every exact occurrence of each of `secrets` - the credentials the request carried,
   * such as a subject token, client id or bearer - is replaced, as are its URL-encoded and JSON-escaped
   * forms; so is the string value (of eight characters or more) of every sensitive JSON field
   * (`access_token`, `refresh_token`, `id_token`, `client_secret`, `assertion`, `subject_token`, ...)
   * anywhere in the body, wherever else it is repeated; then [[redact]] applies the general patterns (bearer tokens, JWTs, API keys,
   * sensitive JSON fields and query parameters); and the result is truncated to `maxLength`. Blank
   * secrets are ignored. Truncation comes last, so it never cuts a secret in two before it is matched.
   */
  def remoteBody(body: String, secrets: Iterable[String] = Nil, maxLength: Int = 512): String =
    if (body == null || body.isEmpty) ""
    else redactForLogging(scrubRemote(body, secrets), maxLength)

  /**
   * The exact-match half of [[remoteBody]], untruncated and without the general patterns: `secrets`, and
   * the string values of sensitive JSON fields in `body`, scrubbed. For a body that is parsed afterwards,
   * as `HttpErrorMapper` parses one, which then redacts and truncates the detail it extracts.
   */
  def scrubRemote(body: String, secrets: Iterable[String] = Nil): String =
    if (body == null || body.isEmpty) body
    else scrub(body, secrets ++ sensitiveJsonValues(body))

  /**
   * `input` with every exact occurrence of each non-blank value in `secrets`, and of its URL-encoded and
   * JSON-escaped forms, replaced by `placeholder`. Where occurrences overlap the longest one starting
   * leftmost wins, and the run they cover together becomes one placeholder, so a secret that contains or
   * overlaps another is never left half-masked.
   *
   * All forms are found in one scan ([[ExactMatcher]]), so the cost is linear in the input and the total
   * length of the forms, however many there are: a body echoing thousands of tokens is scrubbed as
   * quickly as one echoing one.
   */
  def scrub(input: String, secrets: Iterable[String], placeholder: String = RedactionPlaceholder): String =
    if (input == null || input.isEmpty) input
    else {
      val forms = secrets.iterator
        .filter(_ != null)
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(secret => Iterator(secret, URLEncoder.encode(secret, StandardCharsets.UTF_8), jsonEscaped(secret)))
        .toSeq
        .distinct
      if (forms.isEmpty) input else ExactMatcher(forms).replaceAll(input, placeholder)
    }

  /**
   * An Aho-Corasick automaton over the reversed `patterns`. Scanning the input backwards gives, for every
   * position, the length of the longest pattern that starts there, in time linear in the input; a forward
   * pass then replaces each maximal run of overlapping occurrences, leftmost first, with the placeholder.
   * Transitions live in one `LongMap` keyed by `(state, char)` rather than a map per state.
   */
  final private class ExactMatcher private (
    transitions: scala.collection.mutable.LongMap[Int],
    fail: Array[Int],
    longestTerminal: Array[Int]
  ) {
    private def key(state: Int, c: Char): Long = (state.toLong << 16) | c.toLong

    private def step(from: Int, c: Char): Int = {
      var state = from
      while (state != 0 && !transitions.contains(key(state, c))) state = fail(state)
      transitions.getOrElse(key(state, c), 0)
    }

    def replaceAll(input: String, placeholder: String): String = {
      val n = input.length
      // longestAt(i): the length of the longest pattern that starts at i, or 0.
      val longestAt = new Array[Int](n)
      var state     = 0
      var j         = n - 1
      while (j >= 0) {
        state = step(state, input.charAt(j))
        longestAt(j) = longestTerminal(state)
        j -= 1
      }
      val out = new java.lang.StringBuilder(n)
      var i   = 0
      while (i < n)
        if (longestAt(i) == 0) {
          out.append(input.charAt(i))
          i += 1
        } else {
          // Extend the run over every occurrence that starts inside it, then mask it once.
          var end = i + longestAt(i)
          var k   = i + 1
          while (k < end) {
            if (longestAt(k) > 0) end = math.max(end, k + longestAt(k))
            k += 1
          }
          out.append(placeholder)
          i = end
        }
      out.toString
    }
  }

  private object ExactMatcher {
    def apply(patterns: Seq[String]): ExactMatcher = {
      val transitions                    = scala.collection.mutable.LongMap.empty[Int]
      val parent                         = scala.collection.mutable.ArrayBuffer(0)
      val viaChar                        = scala.collection.mutable.ArrayBuffer('\u0000')
      val depth                          = scala.collection.mutable.ArrayBuffer(0)
      val terminal                       = scala.collection.mutable.ArrayBuffer(false)
      def key(state: Int, c: Char): Long = (state.toLong << 16) | c.toLong

      patterns.foreach { pattern =>
        var state = 0
        var p     = pattern.length - 1
        while (p >= 0) {
          val c = pattern.charAt(p)
          state = transitions.getOrElseUpdate(
            key(state, c), {
              parent += state; viaChar += c; depth += depth(state) + 1; terminal += false
              parent.length - 1
            }
          )
          p -= 1
        }
        terminal(state) = true
      }

      val size = parent.length
      // Breadth-first order: every state after its parent and after any shallower state its failure link can be.
      val order           = (0 until size).sortBy(depth(_)).toArray
      val fail            = new Array[Int](size)
      val longestTerminal = new Array[Int](size)
      order.foreach { s =>
        if (s != 0) {
          val p = parent(s)
          val c = viaChar(s)
          if (p != 0) {
            var f = fail(p)
            while (f != 0 && !transitions.contains(key(f, c))) f = fail(f)
            fail(s) = transitions.getOrElse(key(f, c), 0)
          }
          // A state's string is a suffix (in the reversed text, a prefix) of its own; its failure chain's first
          // terminal is the longest pattern ending here.
          longestTerminal(s) = if (terminal(s)) depth(s) else longestTerminal(fail(s))
        }
      }
      new ExactMatcher(transitions, fail, longestTerminal)
    }
  }

  private def jsonEscaped(value: String): String = {
    val rendered = ujson.Str(value).render()
    rendered.substring(1, rendered.length - 1)
  }

  private val MinRepeatedSecretLength = 8

  /**
   * Of `ids` - identifiers a request carried that are not themselves credentials, such as a client id, an
   * identity-provider, organization or federation-rule id - those long enough to scrub by exact match: eight
   * characters or more, the floor applied to sensitive JSON values. A short id (`app`, `prod`) would garble every
   * error body it was scrubbed from, so it is left out. Never pass a bearer token, API key, JWT or identity token
   * through this: those go to [[scrub]] / [[remoteBody]] as they are, whatever their length.
   */
  def identifiers(ids: Iterable[String]): Seq[String] =
    ids.iterator.filter(_ != null).map(_.trim).filter(_.length >= MinRepeatedSecretLength).toSeq

  /**
   * The string values of sensitive fields, at any depth, when `input` is JSON; otherwise none. The body is a
   * remote server's, so it is parsed through [[BoundedJson]]: one nested too deeply is treated as no JSON (the
   * configured secrets and the general patterns still apply to its text). The traversal is iterative as well, so
   * it cannot overflow the stack whatever the depth of the value.
   */
  private def sensitiveJsonValues(input: String): Seq[String] =
    // A very short value is left to the key pattern: replacing every occurrence of, say, "a" would garble the body.
    BoundedJson
      .read(input)
      .toOption
      .fold(Seq.empty[String])(sensitiveStrings)
      .filter(_.trim.length >= MinRepeatedSecretLength)

  /** Every string leaf under a sensitive key of `root`, found with an explicit stack rather than recursion. */
  private def sensitiveStrings(root: ujson.Value): Seq[String] = {
    val found = Seq.newBuilder[String]
    // Each entry is a value and whether it sits under a sensitive key.
    val stack = scala.collection.mutable.Stack[(ujson.Value, Boolean)](root -> false)
    while (stack.nonEmpty)
      stack.pop() match {
        case (ujson.Str(s), true)    => found += s
        case (ujson.Obj(obj), under) => obj.foreach((key, v) => stack.push(v -> (under || isSensitiveKey(key))))
        case (ujson.Arr(arr), under) => arr.foreach(v => stack.push(v -> under))
        case _                       => ()
      }
    found.result()
  }

  // ============================================================
  // Private redaction helpers
  // ============================================================

  // Every replacement below is quoted: the placeholder is the caller's, and a `$` or `\` in a replacement string is
  // otherwise read as a group reference or an escape, which throws or writes back the text being redacted.
  private def redactAuthHeaders(input: String, placeholder: String): String = {
    val quoted = Regex.quoteReplacement(placeholder)
    // Handle "Authorization": "..." in JSON
    val step1 = """(?i)("Authorization"\s*:\s*")([^"]+)(")""".r
      .replaceAllIn(input, m => Regex.quoteReplacement(s"${m.group(1)}$placeholder${m.group(3)}"))
    // Handle Authorization: ... in headers
    val step2 = """(?i)(Authorization:\s*)([^\n\r]+)""".r
      .replaceAllIn(step1, m => Regex.quoteReplacement(s"${m.group(1)}$placeholder"))
    // Handle standalone Bearer tokens
    val step3 = """(?i)\bBearer\s+([a-zA-Z0-9\-_\.]+)""".r.replaceAllIn(step2, quoted)
    // Handle standalone Basic auth tokens
    """(?i)\bBasic\s+([a-zA-Z0-9+/=]+)""".r.replaceAllIn(step3, quoted)
  }

  /**
   * Replaces the value of every query parameter whose key holds a sensitive name. The key and the text around the
   * value are kept, and an empty value is left as it is: there is nothing to hide, and a placeholder written before a
   * quote would keep the `key='...'` pass after this one from reading the value. Appended, not passed to
   * `replaceAllIn`, so a `$` or `\` in the input or the placeholder is written as it is.
   */
  private def redactQueryParams(input: String, placeholder: String): String = {
    val matcher = QueryParamStart.pattern.matcher(input)
    val out     = new java.lang.StringBuilder(input.length)

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else {
        val key = matcher.group(2).toLowerCase(Locale.ROOT)
        if (SensitiveQueryParams.exists(key.contains)) {
          val (valueStart, valueLength) = queryValue(input, matcher.end)
          if (valueLength > 0) {
            out.append(input, copiedTo, valueStart).append(placeholder)
            loop(valueStart + valueLength, valueStart + valueLength)
          } else {
            loop(matcher.end, copiedTo)
          }
        } else {
          // The value of a parameter that is kept is searched too: it may be a URL with a query of its own
          // (`?next=/cb?token=...`). It is not scanned, so no character is read twice.
          loop(matcher.end, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.append(input, copiedTo, input.length).toString
  }

  /**
   * Where the query value that starts at `from` begins, and its length. A value ends where a query value does, at
   * `&` or whitespace, or at the quote that ends the string the URL sits in, which is kept with the backslashes
   * that escape it in JSON that sits inside a string.
   *
   * RFC 3986 allows `'` unencoded in a query, with `!$()*,;=:@`, and JavaScript's `encodeURIComponent` leaves
   * `'()*!` as they are, so a value may hold a quote. A run of `'` is part of the value when the character after it
   * is a letter, a digit, one of `._~%+/-`, or one of `!$*(@=`: `?key=ab'cd`, `pa'(ss)w0rd`, `Xk9'!mQ2`, `ab''cd`,
   * and `''Xk9` at the start of a value. A `"`, which a query may not hold unencoded, is part of it only before a
   * letter, a digit or one of `._~%+/-`. Any other quote ends the value: the quote that ends a string is followed by
   * `,`, `)`, `;`, `:`, a bracket, `>`, `\`, `"`, whitespace or the end of the input. So does a quote before one of
   * `,);:` inside a value (`ab',cd`): it cannot be told from the end of a string, as in `fetch('...?token=ab')`, and
   * the value is redacted up to it.
   *
   * A value written in quotes (`'abc'`, `"abc"`, or `\"abc\"` in JSON inside a string), as a query string in prose
   * or code may have it, starts after a single opening quote by the same rule, so the quote that ends the enclosing
   * string, as in `"https://x.test/?token="}`, leaves the value empty. A loop that reads each character once.
   */
  private def queryValue(input: String, from: Int): (Int, Int) = {
    def isQuote(i: Int): Boolean = i < input.length && (input.charAt(i) == '"' || input.charAt(i) == '\'')
    def isUrlChar(i: Int): Boolean =
      i < input.length && (input.charAt(i).isLetterOrDigit || "._~%+/-".indexOf(input.charAt(i).toInt) >= 0)
    // A character that may follow a `'` inside a value: one of a token, or a sub-delimiter a string never ends before.
    def continuesAfterApostrophe(i: Int): Boolean =
      isUrlChar(i) || (i < input.length && "!$*(@=".indexOf(input.charAt(i).toInt) >= 0)
    // The index after the run of backslashes at `i`, if any.
    def afterSlashes(i: Int): Int = {
      var j = i
      while (j < input.length && input.charAt(j) == '\\') j += 1
      j
    }
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
    while (!done && end < input.length) {
      val c = input.charAt(end)
      if (c == '\\') {
        // A quote after backslashes ends the value as a bare quote does; other backslashes are the value's.
        val next = afterSlashes(end)
        if (!isQuote(next)) end = next
        else {
          val after = afterInnerQuote(next)
          if (after >= 0) end = after else done = true
        }
      } else if (c == '"' || c == '\'') {
        // A quote inside a token (`ab'cd`) is followed by more of it; the quote that ends a string is not.
        val after = afterInnerQuote(end)
        if (after >= 0) end = after else done = true
      } else if (c == '&' || isRegexSpace(c)) {
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

  private def redactJsonFields(input: String, placeholder: String, inputQuotes: Int): String = {
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
    // A single-quoted value inside a double-quoted string may end where that string does, but only where no `'` that
    // could close it follows. That is read from the text the passes before have left, so it is trusted only while
    // they have replaced no `'` of the input - one inside a value they redacted, or the closing quote of a credential
    // that a pattern ran over - and the placeholder writes none of its own.
    def quotesKept(text: String): Boolean = !placeholder.contains('\'') && text.count(_ == '\'') >= inputQuotes
    val single =
      redactQuoted(SingleQuotedStart, double, placeholder, ValueEnd.Quote('\''), endsWithString = quotesKept(double))
    // A number becomes a string, so that the redacted JSON still parses.
    val quotedEquals = redactQuoted(DoubleQuotedEqualsStart, single, placeholder, ValueEnd.Quote('"'))
    val allQuoted =
      redactQuoted(
        SingleQuotedEqualsStart,
        quotedEquals,
        placeholder,
        ValueEnd.Quote('\''),
        endsWithString = quotesKept(quotedEquals)
      )
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
   */
  private def redactQuoted(
    start: Regex,
    input: String,
    placeholder: String,
    end: ValueEnd,
    endsWithString: Boolean = false
  ): String = {
    val matcher              = start.pattern.matcher(input)
    val out                  = new java.lang.StringBuilder(input.length)
    val enclosing            = if (endsWithString) Some(new EnclosingQuotes(input)) else None
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

    def valueEndOf(keyStart: Int, valueStart: Int): Int =
      if (enclosing.exists(_.enclosingAt(keyStart) == '"')) {
        if (alreadyEnded(valueStart)) valueStart + placeholder.length
        else {
          // A value the end of the string would leave empty (`'password': '", ...`) is no value: the quote after the
          // key may as well be the start of one that runs on, so it is scanned as outside a string.
          val stringEnd = scanSingleQuotedInString(input, valueStart, lastSingleQuote)
          if (stringEnd == valueStart && stringEnd < input.length && input.charAt(stringEnd) == '"')
            scanQuotedValue(input, valueStart, end)
          else stringEnd
        }
      } else scanQuotedValue(input, valueStart, end)

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else {
        val valueStart = matcher.end
        val valueEnd   = valueEndOf(matcher.start(1), valueStart)
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

    // A string in the other quote than the key's (`'abc'` under `"token"`, `"abc"` under `'token'`), or in the key's
    // own `'`: a key before `:` is kept whole, and a string that cannot be a value - prose, whose `'` is an apostrophe
    // run on by a letter, or the apostrophe of a word of prose that only the end of the enclosing string would close
    // (the `'` of `it's urgent!"`) with no `\"` before that end, or text with a `:` or `=`, which is no leaf but a
    // field the passes before have seen to - is not taken for one: its quote is an ordinary character and the walk
    // goes on inside it.
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
        (endedByString(contentEnd) && isProseApostrophe(open) && !holdsEscapedQuote(contentStart, contentEnd + 1)) ||
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

    // Whether an odd run of backslashes ends just before `at`, so that the character at `at` is escaped. Each run is
    // counted for the one word after it, so the cost stays linear.
    def escapedAt(at: Int): Boolean = {
      var j = at - 1
      while (j >= 0 && input.charAt(j) == '\\') j -= 1
      (at - 1 - j) % 2 == 1
    }

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
        if (replace && escapedAt(i)) {
          // The word follows a backslash, which escapes its first character: a quote written there would be read as
          // `\"`, and the quotes after it would pair the other way round. The escaped character is kept, as the walk
          // before #1647 kept it, and the rest of the word is replaced.
          out.append(input.charAt(i))
          if (next > i + 1) out.append(quote).append(placeholder).append(quote)
        } else if (replace) out.append(quote).append(placeholder).append(quote)
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
   * Replaces the value of every match whose key is sensitive. A value that is already the placeholder is replaced
   * by the placeholder, so this pass leaves its own output as it is; whether the whole of `redact` does is said
   * on [[redact]].
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
