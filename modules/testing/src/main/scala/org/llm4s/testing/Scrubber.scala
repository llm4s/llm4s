package org.llm4s.testing

/**
 * Scrubber for removing sensitive data from recordings.
 *
 * Use this to sanitize recorded interactions before saving to disk,
 * preventing accidental exposure of API keys and tokens.
 *
 * @example
 * {{{
 * val scrubber = Scrubber.default
 *   .addPattern("sk-[a-zA-Z0-9]{48}".r, "[OPENAI_KEY]")
 *   .addPattern("Bearer .*".r, "Bearer [REDACTED]")
 *
 * recordingClient.saveWithScrubbing("test.json", scrubber)
 * }}}
 */
trait Scrubber {

  /**
   * Scrub sensitive data from a string.
   *
   * @param content The content to scrub
   * @return Scrubbed content with sensitive data replaced
   */
  def scrub(content: String): String

  /**
   * Add a custom pattern to scrub.
   *
   * @param pattern Regex pattern to match
   * @param replacement Replacement text
   * @return New Scrubber with the additional pattern
   */
  def addPattern(pattern: scala.util.matching.Regex, replacement: String): Scrubber
}

object Scrubber {

  /**
   * Default scrubber with common API key patterns.
   *
   * Includes patterns for:
   * - OpenAI API keys (sk-...)
   * - Anthropic API keys (sk-ant-...)
   * - Bearer tokens
   * - Generic API key headers
   */
  def default: Scrubber = DefaultScrubber(
    List(
      // OpenAI API keys
      "sk-[a-zA-Z0-9]{20,}".r -> "[OPENAI_API_KEY]",
      // Anthropic API keys
      "sk-ant-[a-zA-Z0-9-]{20,}".r -> "[ANTHROPIC_API_KEY]",
      // Google API keys
      "AIza[a-zA-Z0-9_-]{35}".r -> "[GOOGLE_API_KEY]",
      // Bearer tokens in headers
      "Bearer [a-zA-Z0-9._-]+".r -> "Bearer [REDACTED]",
      // Generic api_key parameters
      "api_key=[a-zA-Z0-9_-]+".r -> "api_key=[REDACTED]",
      // Authorization headers
      "Authorization: [^\n]+".r -> "Authorization: [REDACTED]"
    )
  )

  /**
   * Scrubber that performs no scrubbing.
   * Use when you explicitly want to keep all data.
   */
  def none: Scrubber = NoOpScrubber

  /**
   * Create a custom scrubber with specific patterns.
   */
  def custom(patterns: (scala.util.matching.Regex, String)*): Scrubber =
    DefaultScrubber(patterns.toList)
}

private case class DefaultScrubber(
  patterns: List[(scala.util.matching.Regex, String)]
) extends Scrubber {

  override def scrub(content: String): String =
    patterns.foldLeft(content) { case (text, (pattern, replacement)) =>
      pattern.replaceAllIn(text, replacement)
    }

  override def addPattern(pattern: scala.util.matching.Regex, replacement: String): Scrubber =
    copy(patterns = patterns :+ (pattern -> replacement))
}

private object NoOpScrubber extends Scrubber {
  override def scrub(content: String): String = content
  override def addPattern(pattern: scala.util.matching.Regex, replacement: String): Scrubber =
    DefaultScrubber(List(pattern -> replacement))
}
