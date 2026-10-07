package org.llm4s.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.annotation.unused
import scala.util.Try
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
 *  - Sensitive JSON fields (api_key, password, token, etc.)
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
   * Sensitive JSON key names to redact.
   */
  private val SensitiveJsonKeys: Set[String] = Set(
    "api_key",
    "apiKey",
    "apikey",
    "api-key",
    "secret",
    "secretKey",
    "secret_key",
    "password",
    "passwd",
    "token",
    "accessToken",
    "access_token",
    "authorization",
    "credential",
    "credentials",
    "privateKey",
    "private_key",
    // OAuth 2.0 / RFC 8693 token-endpoint fields
    "refresh_token",
    "refreshToken",
    "id_token",
    "idToken",
    "client_secret",
    "clientSecret",
    "assertion",
    "client_assertion",
    "subject_token",
    "actor_token"
  )

  private val SensitiveJsonKeysLower: Set[String] = SensitiveJsonKeys.map(_.toLowerCase)

  // The value may contain escaped quotes (`\"`), which must not end the match early and leave a tail behind.
  private def jsonKeyPattern(key: String): Regex =
    s"""(?i)("${Regex.quote(key)}"\\s*:\\s*")((?:[^"\\\\]|\\\\.)+)(")""".r

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

  /** The string values of sensitive fields, at any depth, when `input` is JSON; otherwise none. */
  private def sensitiveJsonValues(input: String): Seq[String] = {
    def strings(value: ujson.Value): Seq[String] = value match {
      case ujson.Str(s)   => Seq(s)
      case ujson.Obj(obj) => obj.values.toSeq.flatMap(strings)
      case ujson.Arr(arr) => arr.toSeq.flatMap(strings)
      case _              => Nil
    }
    def sensitive(value: ujson.Value): Seq[String] = value match {
      case ujson.Obj(obj) =>
        obj.toSeq.flatMap { (key, v) =>
          if (SensitiveJsonKeysLower.contains(key.toLowerCase)) strings(v) else sensitive(v)
        }
      case ujson.Arr(arr) => arr.toSeq.flatMap(sensitive)
      case _              => Nil
    }
    // A very short value is left to the key pattern: replacing every occurrence of, say, "a" would garble the body.
    Try(ujson.read(input)).toOption.fold(Seq.empty[String])(sensitive).filter(_.trim.length >= MinRepeatedSecretLength)
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

        if (SensitiveQueryParams.exists(s => key.toLowerCase.contains(s.toLowerCase))) {
          Regex.quoteReplacement(s"$separator$key=$placeholder")
        } else {
          Regex.quoteReplacement(m.matched)
        }
      }
    )

  private def redactJsonFields(input: String, placeholder: String): String =
    SensitiveJsonKeys.foldLeft(input) { (acc, key) =>
      val pattern = jsonKeyPattern(key)
      pattern.replaceAllIn(acc, m => Regex.quoteReplacement(s"${m.group(1)}$placeholder${m.group(3)}"))
    }

  private def redactApiKeys(input: String, placeholder: String): String =
    // Delegate to the canonical patterns in SecretPatterns so there is a
    // single source of truth for credential regexes across the codebase.
    SecretPatterns.redactAllWithPlaceholder(input, placeholder)
}
