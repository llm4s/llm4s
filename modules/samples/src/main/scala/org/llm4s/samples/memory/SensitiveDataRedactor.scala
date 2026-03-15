package org.llm4s.samples.memory

/**
 * Utility for redacting sensitive data from text before logging.
 *
 * This prevents accidental exposure of:
 * - API keys and tokens
 * - Passwords and credentials
 * - Email addresses
 * - Credit card numbers
 * - Phone numbers
 * - SSN and other PII
 *
 * Used by memory samples to sanitize context logs.
 */
object SensitiveDataRedactor {

  /**
   * Redact sensitive data patterns from text.
   *
   * @param text The text to redact
   * @return Text with sensitive patterns replaced by placeholders
   */
  def redact(text: String): String =
    text
      // API keys (various formats)
      .replaceAll(
        "(?i)(api[_-]?key|apikey|access[_-]?key|secret[_-]?key)\\s*[:=]\\s*['\"]?[\\w\\-\\.]+",
        "$1=***REDACTED***"
      )
      .replaceAll("(?:sk|pk|rk)-[a-zA-Z0-9]{20,}", "***REDACTED_KEY***")
      .replaceAll("Bearer\\s+[\\w\\-\\.]+", "Bearer ***REDACTED***")
      // Passwords
      .replaceAll(
        "(?i)(password|passwd|pwd)\\s*[:=]\\s*['\"]?[^\\s'\"]+",
        "$1=***REDACTED***"
      )
      // Emails
      .replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "***@***.***")
      // Tokens
      .replaceAll(
        "(?i)(token|auth|credential)\\s*[:=]\\s*['\"]?[\\w\\-\\.]+",
        "$1=***REDACTED***"
      )
      // Credit cards (basic pattern)
      .replaceAll("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b", "****-****-****-****")
      // Phone numbers
      .replaceAll("\\b\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b", "***-***-****")
      // SSN pattern
      .replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "***-**-****")
}
