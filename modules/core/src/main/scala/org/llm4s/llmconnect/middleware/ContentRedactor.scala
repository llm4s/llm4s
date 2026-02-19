package org.llm4s.llmconnect.middleware

/**
 * Strategy for redacting sensitive information from logs.
 */
trait ContentRedactor {
  def redact(content: String): String
}

object ContentRedactor {

  /** A default redactor that masks common patterns like API keys, emails, and credit cards. */
  val default: ContentRedactor = new RegexContentRedactor(
    patterns = Seq(
      // Basic API key patterns (simplified)
      """sk-[a-zA-Z0-9]{20,}""".r -> "sk-...",
      // Email addresses
      """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".r -> "[EMAIL]",
      // Credit card numbers (simplified)
      """\b(?:\d{4}[- ]?){3}\d{4}\b""".r -> "[CARD]"
    )
  )

  class RegexContentRedactor(patterns: Seq[(scala.util.matching.Regex, String)]) extends ContentRedactor {
    override def redact(content: String): String =
      patterns.foldLeft(content) { case (text, (regex, replacement)) =>
        regex.replaceAllIn(text, replacement)
      }
  }
}
