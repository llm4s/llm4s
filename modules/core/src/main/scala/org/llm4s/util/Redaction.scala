package org.llm4s.util

import java.util.Locale

import scala.annotation.unused
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
 *  - Sensitive fields, whatever the shape: JSON (also inside a string, single-quoted, or with a number value),
 *    `key=value` pairs and `x-api-key: value` header lines (api_key, password, token, client_secret, etc.)
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

  private def isSensitiveKey(key: String): Boolean = {
    val normalised = key.toLowerCase(Locale.ROOT).filter(_.isLetterOrDigit)
    SensitiveKeyWords.contains(normalised) || SensitiveKeySuffixes.exists(normalised.endsWith)
  }

  // A key is a letter followed by at most 63 letters, digits, `_` or `-`. The bound keeps matching linear on long
  // runs of word characters, which a model reply or a base64 body can contain.
  private val Key: String = """[A-Za-z][A-Za-z0-9_-]{0,63}"""

  // Each pattern captures: 1 = text up to the value, 2 = the key, 3 = the value, and where present 4 = the closing
  // delimiter. They are tried one after the other, so a value is redacted by the first shape that fits it.

  /** `"key": "value"`, where the value may contain escaped quotes. */
  private val JsonStringField: Regex =
    s"""("($Key)"\\s*:\\s*")((?:[^"\\\\]|\\\\.)*)(")""".r

  /** `\"key\": \"value\"`: the same JSON inside a string, as a prompt or a response body carries it. */
  private val EscapedJsonStringField: Regex =
    s"""(\\\\"($Key)\\\\"\\s*:\\s*\\\\")((?:[^"\\\\]|\\\\(?!"))*)(\\\\")""".r

  /** `'key': 'value'`. */
  private val SingleQuotedField: Regex =
    s"""('($Key)'\\s*:\\s*')((?:[^'\\\\]|\\\\.)*)(')""".r

  /** `"key": 12345`: a number is a credential too when the key says so (a numeric PIN or passcode). */
  private val JsonNumberField: Regex =
    s"""("($Key)"\\s*:\\s*)(-?\\d+(?:\\.\\d+)?)(?![\\w.-])""".r

  /** `key=value` outside a URL query string: form bodies, log lines, shell-style settings, `a.b.password=...`. */
  private val EqualsPair: Regex =
    s"""((?<![A-Za-z0-9_-])($Key)=)([^\\s&"',;<>]+)""".r

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
    // Quoted shapes first, then the bare ones, so that a value is never matched by a looser pattern first.
    val quoted = Seq(EscapedJsonStringField, JsonStringField, SingleQuotedField).foldLeft(input) { (acc, pattern) =>
      redactPairs(pattern, acc, placeholder, closing = true)
    }
    // A number becomes a string, so that the redacted JSON still parses.
    val numbers = redactPairs(JsonNumberField, quoted, placeholder, closing = false, wrap = "\"")
    Seq(EqualsPair, HeaderLine).foldLeft(numbers) { (acc, pattern) =>
      redactPairs(pattern, acc, placeholder, closing = false)
    }
  }

  /**
   * Replaces the value of every match whose key is sensitive. An empty value, or one that is already the placeholder,
   * is left as it is, so redacting twice gives the same result as redacting once.
   */
  private def redactPairs(
    pattern: Regex,
    input: String,
    placeholder: String,
    closing: Boolean,
    wrap: String = ""
  ): String =
    pattern.replaceAllIn(
      input,
      m => {
        val value = m.group(3)
        val text =
          if (isSensitiveKey(m.group(2)) && value.nonEmpty && value != placeholder) {
            m.group(1) + wrap + placeholder + wrap + (if (closing) m.group(4) else "")
          } else {
            m.matched
          }
        Regex.quoteReplacement(text)
      }
    )

  private def redactApiKeys(input: String, placeholder: String): String =
    // Delegate to the canonical patterns in SecretPatterns so there is a
    // single source of truth for credential regexes across the codebase.
    SecretPatterns.redactAllWithPlaceholder(input, placeholder)
}
