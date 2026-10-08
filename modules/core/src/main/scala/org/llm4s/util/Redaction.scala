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
 *    an array or object value, whose every string and number leaf is replaced), `key=value` pairs and
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
    "bearertoken",
    // OAuth 2.0 / RFC 8693 token-endpoint fields: `subject_token`, `actor_token`, `assertion`, `client_assertion`
    "subjecttoken",
    "actortoken",
    "assertion"
  )

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
      redactApiKeys(step3, placeholder)
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
   * JSON-escaped forms, replaced by `placeholder`. Longer values are replaced first, so a secret that
   * contains another is not left half-masked.
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
        .sortBy(-_.length)
      forms.foldLeft(input)((acc, secret) => acc.replace(secret, placeholder))
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

  private def redactAuthHeaders(input: String, placeholder: String): String = {
    // Handle "Authorization": "..." in JSON
    val step1 = """(?i)("Authorization"\s*:\s*")([^"]+)(")""".r
      .replaceAllIn(input, m => Regex.quoteReplacement(s"${m.group(1)}$placeholder${m.group(3)}"))
    // Handle Authorization: ... in headers
    val step2 = """(?i)(Authorization:\s*)([^\n\r]+)""".r
      .replaceAllIn(step1, m => Regex.quoteReplacement(s"${m.group(1)}$placeholder"))
    // Handle standalone Bearer tokens
    val step3 = """(?i)\bBearer\s+([a-zA-Z0-9\-_\.]+)""".r.replaceAllIn(step2, Regex.quoteReplacement(placeholder))
    // Handle standalone Basic auth tokens
    """(?i)\bBasic\s+([a-zA-Z0-9+/=]+)""".r.replaceAllIn(step3, Regex.quoteReplacement(placeholder))
  }

  private def redactQueryParams(input: String, placeholder: String): String =
    QueryParamPattern.replaceAllIn(
      input,
      m => {
        val separator = m.group(1)
        val key       = m.group(2)

        if (SensitiveQueryParams.exists(s => key.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT)))) {
          Regex.quoteReplacement(s"$separator$key=$placeholder")
        } else {
          Regex.quoteReplacement(m.matched)
        }
      }
    )

  private def redactJsonFields(input: String, placeholder: String): String = {
    // Arrays and objects first: every leaf under a sensitive key is replaced in one pass, and what is left for the
    // field patterns below is already the placeholder. Then the quoted shapes, then the bare ones, so that a value is
    // never matched by a looser pattern first.
    val escapedContainers = redactContainers(EscapedJsonContainerStart, input, placeholder, ValueEnd.EscapedQuote)
    val containers        = redactContainers(JsonContainerStart, escapedContainers, placeholder, ValueEnd.Quote('"'))
    val escaped           = redactQuoted(EscapedJsonStringStart, containers, placeholder, ValueEnd.EscapedQuote)
    val double            = redactQuoted(JsonStringStart, escaped, placeholder, ValueEnd.Quote('"'))
    val single            = redactQuoted(SingleQuotedStart, double, placeholder, ValueEnd.Quote('\''))
    // A number becomes a string, so that the redacted JSON still parses.
    val quotedEquals   = redactQuoted(DoubleQuotedEqualsStart, single, placeholder, ValueEnd.Quote('"'))
    val allQuoted      = redactQuoted(SingleQuotedEqualsStart, quotedEquals, placeholder, ValueEnd.Quote('\''))
    val escapedNumbers = redactPairs(EscapedJsonNumberField, allQuoted, placeholder, wrap = "\\\"")
    val numbers        = redactPairs(JsonNumberField, escapedNumbers, placeholder, wrap = "\"")
    Seq(EqualsPair, HeaderLine).foldLeft(numbers)((acc, pattern) => redactPairs(pattern, acc, placeholder))
  }

  /** How a quoted value ends. */
  private enum ValueEnd {

    /** An unescaped `quote`; a backslash escapes the next character. */
    case Quote(quote: Char)

    /** A backslash followed by `"`, the closing quote of a string inside a string. */
    case EscapedQuote
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
   * Replaces every string and number leaf of each `"key": [...]` or `"key": {...}` whose key is sensitive, and leaves
   * the brackets, the keys of nested objects, `true`, `false` and `null`, so that the redacted JSON still parses and
   * keeps its shape. `start` matches up to and including the opening bracket (group 3). A container whose key is
   * not sensitive is entered, not skipped, so a credential inside it is still found.
   */
  private def redactContainers(start: Regex, input: String, placeholder: String, end: ValueEnd): String = {
    val matcher = start.pattern.matcher(input)
    val out     = new java.lang.StringBuilder(input.length)

    @tailrec def loop(searchFrom: Int, copiedTo: Int): Int =
      if (searchFrom > input.length || !matcher.find(searchFrom)) {
        copiedTo
      } else {
        val open = matcher.start(3)
        if (isSensitiveKey(matcher.group(2))) {
          out.append(input, copiedTo, open)
          val valueEnd = redactLeaves(input, open, out, placeholder, end)
          loop(valueEnd, valueEnd)
        } else {
          loop(open + 1, copiedTo)
        }
      }

    val copiedTo = loop(0, 0)
    out.append(input, copiedTo, input.length).toString
  }

  /**
   * Appends to `out` the array or object that opens at `from`, with every string and number leaf replaced, and
   * returns the index just after it. Brackets are counted on an explicit stack, so the depth of the value does not
   * grow the call stack, and each character is visited once. The value ends at its closing bracket, at the end of
   * the input (a payload cut off in the middle of it) or, for JSON inside a string, at the bare quote that ends the
   * enclosing string, which is left for the caller.
   */
  private def redactLeaves(
    input: String,
    from: Int,
    out: java.lang.StringBuilder,
    placeholder: String,
    end: ValueEnd
  ): Int = {
    val length  = input.length
    val quote   = end match { case ValueEnd.EscapedQuote => "\\\""; case ValueEnd.Quote(q) => q.toString }
    val openers = new java.lang.StringBuilder

    def inObject: Boolean = openers.length > 0 && openers.charAt(openers.length - 1) == '{'

    // A string of an object that is followed by `:` is a key, not a value.
    def isKey(after: Int): Boolean = {
      var i = after
      while (
        i < length && (input.charAt(i) == ' ' || input.charAt(i) == '\t' || input
          .charAt(i) == '\n' || input.charAt(i) == '\r')
      ) i += 1
      inObject && i < length && input.charAt(i) == ':'
    }

    // The string whose opening quote (one character, or `\"`) starts at `open`; returns the index after it.
    def emitString(open: Int): Int = {
      val contentStart = open + quote.length
      val contentEnd   = scanQuotedValue(input, contentStart, end)
      // For JSON inside a string, `scanQuotedValue` may stop at a bare quote: the enclosing string ends there.
      val closed = contentEnd < length && (end match {
        case ValueEnd.Quote(_)     => true
        case ValueEnd.EscapedQuote => input.charAt(contentEnd) == '\\'
      })
      val next = if (closed) contentEnd + quote.length else contentEnd
      if (closed && isKey(next)) {
        out.append(input, open, next)
      } else {
        out.append(quote)
        if (contentEnd > contentStart) out.append(placeholder)
        if (closed) out.append(quote)
      }
      next
    }

    def isNumberChar(c: Char): Boolean =
      (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

    var i    = from
    var done = false
    while (i < length && !done) {
      val c = input.charAt(i)
      if (c == '[' || c == '{') {
        openers.append(c)
        out.append(c)
        i += 1
      } else if (c == ']' || c == '}') {
        if (openers.length > 0) openers.setLength(openers.length - 1)
        out.append(c)
        i += 1
        done = openers.length == 0
      } else if (c == '"') {
        end match {
          case ValueEnd.Quote(_)     => i = emitString(i)
          case ValueEnd.EscapedQuote => done = true // the enclosing string ends, and the container with it
        }
      } else if (c == '\\' && end == ValueEnd.EscapedQuote) {
        var next = i
        while (next < length && input.charAt(next) == '\\') next += 1
        val slashes     = next - i
        val beforeQuote = next < length && input.charAt(next) == '"'
        if (beforeQuote && slashes % 4 == 1) {
          out.append(input, i, next - 1)
          i = emitString(next - 1)
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
      } else if (c == '-' || (c >= '0' && c <= '9')) {
        var next = i + 1
        while (next < length && isNumberChar(input.charAt(next))) next += 1
        out.append(quote).append(placeholder).append(quote)
        i = next
      } else {
        out.append(c)
        i += 1
      }
    }
    i
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
