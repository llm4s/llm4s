package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Filters profanity and inappropriate content.
 *
 * This is a basic implementation using a word list.
 * For production, consider integrating with external APIs like:
 * - OpenAI Moderation API
 * - Google Perspective API
 * - Custom ML models
 *
 * Can be used for both input and output validation.
 *
 * @param customBadWords Additional words to filter beyond the default list
 * @param caseSensitive Whether matching should be case-sensitive
 */
class ProfanityFilter(
  customBadWords: Set[String] = Set.empty,
  caseSensitive: Boolean = false
) extends InputGuardrail
    with OutputGuardrail {

  // Default bad words list (basic example - expand for production)
  private val defaultBadWords: Set[String] = Set(
    // This is intentionally minimal for example purposes
    // In production, use a comprehensive profanity list or external API
    "badword",
    "inappropriate"
  )

  private val badWords: Set[String] = {
    val combined = defaultBadWords ++ customBadWords
    if (caseSensitive) combined else combined.map(_.toLowerCase)
  }

  def validate(value: String): Result[String] = {
    val checkValue = if (caseSensitive) value else value.toLowerCase
    val words      = checkValue.split("\\s+")

    val foundBadWords = words.filter(badWords.contains)

    if (foundBadWords.nonEmpty) {
      Left(
        ValidationError.invalid(
          "input",
          "Input contains inappropriate content"
          // Don't reveal the specific words for security/privacy
        )
      )
    } else {
      Right(value)
    }
  }

  val name: String = "ProfanityFilter"

  override val description: Option[String] = Some(
    "Filters profanity and inappropriate content"
  )

  // Resolve conflicting transform methods from both traits
  override def transform(input: String): String = input
}

object ProfanityFilter {

  /**
   * Create a profanity filter with default settings.
   */
  def apply(): ProfanityFilter = new ProfanityFilter()

  /**
   * Create a profanity filter with custom bad words.
   */
  def withCustomWords(customWords: Set[String]): ProfanityFilter =
    new ProfanityFilter(customBadWords = customWords)

  /**
   * Create a case-sensitive profanity filter.
   */
  def caseSensitive(customWords: Set[String] = Set.empty): ProfanityFilter =
    new ProfanityFilter(customBadWords = customWords, caseSensitive = true)
}
