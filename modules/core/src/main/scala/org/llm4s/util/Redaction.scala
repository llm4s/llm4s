package org.llm4s.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.annotation.unused

/**
 * Utilities for redacting sensitive information from strings and log messages.
 *
 * Provides both simple masking (for toString representations) and policy-based redaction (for log messages, provider
 * error bodies, request dumps, MCP transport errors and trace payloads that may carry API keys, auth headers and the
 * like).
 *
 * [[redact]] reads its input in one forward pass over a small tokenizer (`RedactionScanner`), so its cost is linear
 * in the input by construction (#1932). It replaces, with the placeholder:
 *  - the value of every sensitive key, whatever the shape: a JSON or Python field (`"api_key": ...`, `'token': ...`),
 *    a `key=value` pair, a `key: value` header or YAML line, a URL query parameter - a string's content, a number,
 *    a bare word, or every leaf of an array, object, tuple or call under the key;
 *  - an `Authorization`, `Proxy-Authorization`, `Cookie` or `Set-Cookie` header's value, anywhere in a line;
 *  - Bearer and Basic tokens, the password of a URL's userinfo, and the vendor key shapes of
 *    [[SecretPatterns.SecretType.default]];
 *  - all of this inside strings nested in strings (JSON in a JSON string, a Python repr in a JSON string), and in
 *    text written with JSON escapes (`\"`, `&`) whose outer quotes are not there, up to 16 JSON string encodings
 *    deep. Past that, the decoding stops, to keep the cost linear, and it fails closed: a string that still holds an
 *    escape there is replaced whole, as its keys cannot be read.
 *
 * @example
 * {{{
 * import org.llm4s.util.Redaction
 *
 * // Simple masking for toString
 * Redaction.secret("sk-abc123") // "***"
 *
 * // Policy-based redaction for log messages
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
   * redacted, then truncated with `truncateForLog`. Use this, never `truncateForLog` alone, for any text that reaches
   * a log, an `LLMError` message, a trace or the provider exchange sink: cutting first can leave a credential
   * straddling the cut, or strip the key that makes the value after it one.
   *
   * It differs from [[redactForLogging]] only in its defaults and its suffix: 2048 characters, a `null`-safe input
   * and `truncateForLog`'s `(truncated, original length: N)`, the format the error messages and log lines it
   * replaced already used; `redactForLogging` keeps its 1000-character default, `0` for no limit, a caller's
   * placeholder and its `[truncated, N chars omitted]` suffix, which `safe` and the MCP payload log rely on.
   *
   * A body longer than 128 KiB (`SafeBodyWholeLimit`) - an HTML 5xx page from a proxy, a dumped payload - is not
   * redacted whole (#1680). Only a prefix of at most 64 KiB is, cut right after a letter or a digit and right before
   * a separator (whitespace or `"',;<>{}[]()`), so that no key, token or escape is cut in two, and the output says
   * so: `... (truncated, original length: N, redacted from its first K chars)`. Nothing past the cut is ever shown.
   *
   * Redacting the prefix is exact, not a guess: the redaction is told that its input was cut (`truncated`), and
   * nothing still open at the cut is decided in a value's favour. A value, string or container open there is replaced
   * to the end, as at the end of any input; an open bare `assertion` value is never read as prose; an open URL
   * authority has all after its `:` replaced, as its `@` may lie past the cut; and a container whose first element
   * the cut ends is one. Every other decision the redaction makes reads only text before the cut. So what the prefix
   * shows, the whole body would show too.
   *
   * Where no separator lies in the second half of the 64 KiB, no text of the body is shown, only
   * `[response body not shown: ...] (original length: N)`: it fails closed. A `maxLength` of half the prefix limit or
   * more, or a negative one, has the body redacted whole, as before.
   *
   * @param body The text to redact and truncate
   * @param maxLength Maximum length of the redacted text before truncation (default: 2048)
   * @return The redacted text, truncated with metadata when it is longer than `maxLength`
   */
  def safeBody(body: String, maxLength: Int = 2048): String =
    if (body == null) "null"
    else
      safeBodyPrefix(body, maxLength) match {
        case Some(Some(cut)) =>
          redactCut(body.substring(0, cut)).take(maxLength) +
            s"... (truncated, original length: ${body.length}, redacted from its first $cut chars)"
        case Some(None) => safeBodyOmitted(body)
        case None       => truncateForLog(redact(body), maxLength)
      }

  /** The longest body [[safeBody]] redacts whole. */
  private val SafeBodyWholeLimit: Int = 128 * 1024

  /** The longest prefix [[safeBody]] redacts in place of a longer body; the cut is in the second half of it. */
  private val SafeBodyPrefixLimit: Int = 64 * 1024

  /** What [[safeBody]] shows of the prefix it cut, `prefix`: its redaction, told that the text was cut there. */
  private[util] def redactCut(prefix: String): String =
    RedactionScanner.redact(prefix, RedactionPlaceholder, truncated = true)

  /** Where [[safeBody]] cuts `body` and shows a prefix, or `None` where it redacts it whole or shows nothing (#1680). */
  private[util] def safeBodyCut(body: String, maxLength: Int): Option[Int] = safeBodyPrefix(body, maxLength).flatten

  /** What [[safeBody]] writes in place of a body of which it shows nothing. */
  private[util] def safeBodyOmitted(body: String): String =
    "[response body not shown: too long to redact whole, and no place to cut it was found]" +
      s" (original length: ${body.length})"

  /**
   * `None` where [[safeBody]] redacts `body` whole: it is at most `SafeBodyWholeLimit` long, or `maxLength` is
   * negative or too large for a prefix to serve. Otherwise `Some` of the cut, or `Some(None)` where no separator lies
   * in the second half of the prefix limit, and nothing of the body may be shown.
   */
  private def safeBodyPrefix(body: String, maxLength: Int): Option[Option[Int]] =
    if (body.length <= SafeBodyWholeLimit || maxLength < 0 || maxLength >= SafeBodyPrefixLimit / 2) None
    else
      Some {
        val floor = SafeBodyPrefixLimit / 2
        var cut   = SafeBodyPrefixLimit
        while (cut > floor && !(separatesPrefix(body.charAt(cut)) && body.charAt(cut - 1).isLetterOrDigit)) cut -= 1
        if (cut <= floor) None else Some(cut)
      }

  /** The characters [[safeBody]] ends a prefix before: whitespace and `"',;<>{}[]()`. */
  private def separatesPrefix(c: Char): Boolean = Character.isWhitespace(c) || "\"',;<>{}[]()".indexOf(c.toInt) >= 0

  // ============================================================
  // Sensitive names
  // ============================================================

  /**
   * Names a URL query parameter is sensitive by, as a substring of its lower-cased key: `?x_api_key_id=`, `?monkey=`
   * and `?filter[api_key]=` are read as credentials, as they were before.
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
   * Names that mark a key as holding a credential, compared after lower-casing and dropping `_` and `-`, so `api_key`,
   * `apiKey` and `x-api-key` are all `apikey`.
   *
   * A key is sensitive when it is one of these words or ends with one of the suffixes, which covers compound names such
   * as `client_secret`, `refresh_token` and `db_password`. The match is on the whole normalised key, never on a
   * substring: `max_tokens`, `prompt_tokens`, `token_count` and `next_page_token` are not credentials and appear in
   * every provider exchange, so redacting them would destroy the logs.
   *
   * The credential-bearing HTTP headers are keys too, in every shape: `Authorization` and `Proxy-Authorization`
   * (#1686); `Cookie` and `Set-Cookie`, whose whole value is replaced, since a cookie holds a session token;
   * `X-Api-Key`, `Api-Key` and `X-Goog-Api-Key` (suffix `apikey`), `X-Auth-Token` (`authtoken`) and
   * `X-Amz-Security-Token` (`securitytoken`). `cookie` is a whole word, never a suffix or a substring:
   * `cookie_policy`, `cookie_consent` and `max_cookie_age` name settings, not cookies. With `SensitiveKeySuffixes` and
   * the plurals of `SensitivePluralSuffixes`, this is core's one list of sensitive key names; a list of headers to
   * withhold (the HTTP tool's redirect stripping) reads the same names through [[isCredentialName]].
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
    "bearertoken",
    // OAuth 2.0 / RFC 8693 token-endpoint fields: `subject_token`, `actor_token`, `assertion`, `client_assertion`
    "subjecttoken",
    "actortoken",
    "assertion"
  )

  /**
   * The plural of a credential name, as the last word of a key (#1646): `secrets`, `passwords` and `apikeys`, after
   * lower-casing and dropping `_` and `-`, so `api_keys`, `apiKeys`, `x-api-keys`, `client_secrets` and
   * `db_passwords` too. They name nothing but credentials. `tokens` is not one: `max_tokens`, `prompt_tokens` and
   * `usage.tokens` are counts in every provider exchange.
   */
  private val SensitivePluralSuffixes: Seq[String] = Seq("secrets", "passwords", "apikeys")

  /**
   * Query parameters that carry a short-lived credential, by their whole name in any case (#1646): `sig` (an Azure
   * SAS signature), `signature`, `x-amz-signature` (an AWS SigV4 presigned URL), `x-goog-signature` (a Google Cloud
   * Storage signed URL), `code` (an OAuth 2.0 authorization code), `session`, `sessionid` and `jsessionid` (a session
   * id, also as the matrix parameter `;jsessionid=`). Matched whole, not as `SensitiveQueryParams` are, by substring:
   * `sig` is in `signal` and `design`, `code` in `zipcode` and `error_code`. Only as the key of a query parameter, after
   * `?`, `&`, a URL's `#` or an HTML escape of `&` or `?`: a field `"code": 400` is an error code.
   */
  private val SensitiveQueryNames: Set[String] =
    Set("sig", "signature", "x-amz-signature", "x-goog-signature", "code", "session", "sessionid", "jsessionid")

  /**
   * Whether `key` (a field, header or parameter name) names a credential, by `SensitiveKeyWords` and
   * `SensitiveKeySuffixes`. A plural (`api_keys`) is not one of them; `isCredentialName` takes those too.
   */
  private[llm4s] def isSensitiveKey(key: String): Boolean = {
    val normalised = key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit)
    SensitiveKeyWords.contains(normalised) || SensitiveKeySuffixes.exists(normalised.endsWith)
  }

  /**
   * Whether `key` (a field, header or parameter name) names a credential or credentials: `isSensitiveKey`, or a
   * plural of `SensitivePluralSuffixes` (`X-Api-Keys`, `Client-Secrets`, `db_passwords`) (#1646). Redaction reads every
   * key by this. `llm4s-agent-tools`' HTTP tool reads it to withhold such headers on a cross-origin redirect even when
   * they are allowlisted, so what a log redacts and what a redirect withholds stay one list.
   */
  private[llm4s] def isCredentialName(key: String): Boolean =
    isSensitiveKey(key) || SensitivePluralSuffixes.exists(
      key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit).endsWith
    )

  /**
   * Whether the key of a URL query parameter makes its value a credential: [[isCredentialName]], a substring of
   * `SensitiveQueryParams`, or a short-lived credential's name (`SensitiveQueryNames`).
   */
  private[util] def isSensitiveQueryParam(key: String): Boolean = {
    val lower = key.toLowerCase(Locale.ROOT)
    SensitiveQueryParams.exists(lower.contains) || SensitiveQueryNames.contains(lower) || isCredentialName(key)
  }

  private val BareAssertion = "assertion"

  /** Whether `key` is the bare word `assertion`, in any case and spelling `isSensitiveKey` normalises. */
  private[util] def isBareAssertion(key: String): Boolean =
    key.length >= BareAssertion.length && key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit) == BareAssertion

  /** The length of a run of `[A-Za-z0-9+/_=-]` that makes a bare `assertion` value a credential, not prose. */
  private val CredentialRunLength = 20

  private def isCredentialChar(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "+/_=-".indexOf(c.toInt) >= 0

  /**
   * Whether `chars(from until to)` reads as prose: it holds a space (U+0020), holds no `<`, and has no run of
   * [[CredentialRunLength]] or more characters of `[A-Za-z0-9+/_=-]` (#1818). A test framework writes its assertions
   * under the bare key `assertion` (`Assertion: expected status 200`, `{"assertion": "response.status == 200"}`):
   * words and operators with spaces between them. The RFC 7521 / 7523 `assertion` grant fails at least one of the
   * three, however it is wrapped: a JWT, a base64 or base64url SAML assertion, a hex or opaque token is one long run,
   * a raw XML SAML assertion opens with a `<`, and a PEM block holds base64 lines; so does a credential with a word
   * beside it. Only the bare key's value is ever exempt (`isBareAssertion`): a compound key (`client_assertion`) and a
   * container are redacted whatever they hold, and the shapes that read no key (Bearer tokens, JWTs, API keys) still
   * apply inside the value. Reads the value once.
   */
  private[util] def isProseText(chars: Array[Char], from: Int, to: Int): Boolean = {
    var space = false
    var run   = 0
    var i     = from
    while (i < to && run < CredentialRunLength && chars(i) != '<') {
      val c = chars(i)
      if (c == ' ') space = true
      run = if (isCredentialChar(c)) run + 1 else 0
      i += 1
    }
    i == to && space && run < CredentialRunLength
  }

  /** Whether the value `value` of `key` is prose that is left readable: see [[isProseText]]. */
  private def isProseAssertion(key: String, value: String): Boolean =
    isBareAssertion(key) && isProseText(value.toCharArray, 0, value.length)

  // ============================================================
  // Policy-based redaction (for log messages)
  // ============================================================

  /**
   * Redact sensitive information from a string: see the policy described on this object and on `RedactionScanner`.
   *
   * Redacting the output again changes nothing: a value that is already the placeholder is left as it is, and the
   * placeholder in a string, a pair or a container leaf is such a value. (A caller's own placeholder that holds a
   * quote, a backslash or a bracket may read differently the second time.)
   *
   * @param input The input string potentially containing sensitive data
   * @param placeholder The placeholder to use for redacted content
   * @return The input with sensitive data redacted
   */
  def redact(input: String, placeholder: String = RedactionPlaceholder): String =
    if (input == null || input.isEmpty) input
    else RedactionScanner.redact(input, placeholder, truncated = false)

  /**
   * Redact sensitive data and truncate for logging.
   *
   * Applies policy-based redaction first, then truncates if needed. For a provider's response body in an error
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

  /**
   * Every string leaf under a credential key of `root` (`isCredentialName`, so a plural's too: each element of
   * `{"api_keys": [...]}`), found with an explicit stack rather than recursion.
   */
  private def sensitiveStrings(root: ujson.Value): Seq[String] = {
    val found = Seq.newBuilder[String]
    // Each entry is a value and whether it sits under a sensitive key.
    val stack = scala.collection.mutable.Stack[(ujson.Value, Boolean)](root -> false)
    while (stack.nonEmpty)
      stack.pop() match {
        case (ujson.Str(s), true) => found += s
        case (ujson.Obj(obj), under) =>
          obj.foreach { (key, v) =>
            val prose = v match {
              case ujson.Str(s) => isProseAssertion(key, s)
              case _            => false
            }
            stack.push(v -> (under || (isCredentialName(key) && !prose)))
          }
        case (ujson.Arr(arr), under) => arr.foreach(v => stack.push(v -> under))
        case _                       => ()
      }
    found.result()
  }
}
