package org.llm4s.util

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
    "private_key"
  )

  private def jsonKeyPattern(key: String): Regex =
    s"""(?i)("${Regex.quote(key)}"\\s*:\\s*")([^"]+)(")""".r

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

        if (SensitiveQueryParams.exists(s => key.toLowerCase.contains(s.toLowerCase))) {
          s"$separator$key=$placeholder"
        } else {
          m.matched
        }
      }
    )

  private def redactJsonFields(input: String, placeholder: String): String =
    SensitiveJsonKeys.foldLeft(input) { (acc, key) =>
      val pattern = jsonKeyPattern(key)
      pattern.replaceAllIn(acc, m => s"${m.group(1)}$placeholder${m.group(3)}")
    }

  private def redactApiKeys(input: String, placeholder: String): String =
    // Delegate to the canonical patterns in SecretPatterns so there is a
    // single source of truth for credential regexes across the codebase.
    SecretPatterns.redactAllWithPlaceholder(input, placeholder)
}
