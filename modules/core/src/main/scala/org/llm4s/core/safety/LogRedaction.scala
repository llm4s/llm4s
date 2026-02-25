package org.llm4s.core.safety

import org.llm4s.util.Redaction

/**
 * Delegates to `org.llm4s.util.Redaction` for all redaction functionality.
 *
 * This object is preserved for backward compatibility. New code should use
 * `org.llm4s.util.Redaction` directly.
 */
@deprecated("Use org.llm4s.util.Redaction instead", "0.2.0")
object LogRedaction {

  val RedactionPlaceholder: String = Redaction.RedactionPlaceholder

  def redact(input: String, placeholder: String = RedactionPlaceholder): String =
    Redaction.redact(input, placeholder)

  def redactForLogging(
    input: String,
    maxLength: Int = 1000,
    placeholder: String = RedactionPlaceholder
  ): String =
    Redaction.redactForLogging(input, maxLength, placeholder)

  def safe(value: Any): String =
    Redaction.safe(value)
}
